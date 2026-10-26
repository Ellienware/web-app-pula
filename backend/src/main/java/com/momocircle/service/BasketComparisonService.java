package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.Product;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.BasketComparisonResponse;
import com.momocircle.dto.MarketDtos.BasketItemRequest;
import com.momocircle.dto.MarketDtos.CompareBasketRequest;
import com.momocircle.dto.MarketDtos.FewestDeliveriesStop;
import com.momocircle.dto.MarketDtos.StoreBasketTotal;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implements the ecosystem brief's "Compare My Basket" (§9) AND a
 * deliberately-scoped slice of "Smart Basket Optimization" (§10) — real,
 * computable factors only:
 *
 * <ul>
 *   <li>Delivery fee: Business.deliveryFee, a real seller-configured flat
 *       fee — folded into estimatedTotal alongside estimatedSubtotal.</li>
 *   <li>Minimum order requirements: Business.minimumOrderAmount, real
 *       seller-configured data — surfaced as meetsMinimumOrder rather than
 *       silently excluding a store that's close but under the minimum.</li>
 *   <li>Fewest deliveries: a genuine multi-store combination (greedy
 *       set-cover, see #computeFewestDeliveriesPlan) that covers the whole
 *       basket using as few stores as possible.</li>
 * </ul>
 *
 * <p>Deliberately NOT implemented: "service fees" and "promotions" (no
 * such data model exists anywhere in this app — adding one that's never
 * populated would be a hollow schema change, not a real feature), "FASTEST"
 * (no delivery-time data exists anywhere in this app), and "BEST_BALANCE"
 * (a composite score across cost/speed/convenience needs the speed and
 * convenience inputs FASTEST would need, which don't exist). Building any
 * of these now would mean fabricating the data behind them, which this
 * codebase has consistently avoided everywhere else — see
 * DeliveryRequest.estimatedFee's own javadoc for the same principle
 * applied to MoMoRide delivery fees specifically.
 *
 * <p>Only counts EXACT_MATCH listings — same reasoning as
 * PriceComparisonService: a basket total built on a guessed product match
 * is worse than no total. Requires ACTIVE status but, unlike
 * PriceComparisonService's "available" flag, does not require stock >=
 * requested quantity.
 */
@Service
public class BasketComparisonService {

    private static final int MAX_ITEMS = 50;

    private final ProductRepository productRepository;
    private final CanonicalProductService canonicalProductService;

    public BasketComparisonService(ProductRepository productRepository,
                                    CanonicalProductService canonicalProductService) {
        this.productRepository = productRepository;
        this.canonicalProductService = canonicalProductService;
    }

    @Transactional(readOnly = true)
    public BasketComparisonResponse compare(CompareBasketRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("Basket must contain at least one item");
        }
        if (request.items().size() > MAX_ITEMS) {
            throw ApiException.badRequest("Basket cannot contain more than " + MAX_ITEMS + " items");
        }

        for (BasketItemRequest item : request.items()) {
            canonicalProductService.requireById(item.canonicalProductId());
        }

        int itemsRequested = request.items().size();

        // Kept simple: businessId -> running subtotal/itemsFound/missing, plus the Business
        // entity itself (captured once) so its deliveryFee/minimumOrderAmount are available
        // without a second query pass.
        Map<String, Business> businessById = new LinkedHashMap<>();
        Map<String, String> currencyByBusinessId = new LinkedHashMap<>();
        Map<String, BigDecimal> subtotalByBusinessId = new LinkedHashMap<>();
        Map<String, Integer> itemsFoundByBusinessId = new LinkedHashMap<>();
        Map<String, List<String>> missingByBusinessId = new LinkedHashMap<>();
        // businessId -> canonicalProductId -> line total, needed for the fewest-deliveries set cover.
        Map<String, Map<String, BigDecimal>> lineTotalsByBusinessThenItem = new LinkedHashMap<>();
        Map<String, List<String>> businessesPerItem = new LinkedHashMap<>();

        for (BasketItemRequest item : request.items()) {
            List<Product> matches = productRepository.findExactMatchesForCanonicalProduct(item.canonicalProductId())
                    .stream().filter(p -> p.getStatus() == ProductStatus.ACTIVE).toList();
            businessesPerItem.put(item.canonicalProductId(), matches.stream().map(p -> p.getBusiness().getId()).toList());

            for (Product product : matches) {
                Business business = product.getBusiness();
                String businessId = business.getId();
                BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(item.quantity()));

                businessById.putIfAbsent(businessId, business);
                currencyByBusinessId.putIfAbsent(businessId, product.getCurrency());
                subtotalByBusinessId.merge(businessId, lineTotal, BigDecimal::add);
                itemsFoundByBusinessId.merge(businessId, 1, Integer::sum);
                missingByBusinessId.computeIfAbsent(businessId, k -> new ArrayList<>());
                lineTotalsByBusinessThenItem
                        .computeIfAbsent(businessId, k -> new LinkedHashMap<>())
                        .put(item.canonicalProductId(), lineTotal);
            }
        }

        for (BasketItemRequest item : request.items()) {
            List<String> businessesWithThisItem = businessesPerItem.get(item.canonicalProductId());
            for (String businessId : businessById.keySet()) {
                if (!businessesWithThisItem.contains(businessId)) {
                    missingByBusinessId.get(businessId).add(item.canonicalProductId());
                }
            }
        }

        List<StoreBasketTotal> storeTotals = businessById.keySet().stream()
                .map(businessId -> {
                    Business business = businessById.get(businessId);
                    BigDecimal subtotal = subtotalByBusinessId.get(businessId);
                    int itemsFound = itemsFoundByBusinessId.get(businessId);
                    BigDecimal deliveryFee = business.getDeliveryFee();
                    BigDecimal total = deliveryFee == null ? subtotal : subtotal.add(deliveryFee);
                    BigDecimal minimum = business.getMinimumOrderAmount();
                    boolean meetsMinimum = minimum == null || subtotal.compareTo(minimum) >= 0;
                    return new StoreBasketTotal(
                            businessId, business.getName(), currencyByBusinessId.get(businessId), subtotal,
                            itemsFound, itemsRequested, itemsFound == itemsRequested,
                            missingByBusinessId.get(businessId), deliveryFee, total, minimum, meetsMinimum);
                })
                .sorted(Comparator.comparing(StoreBasketTotal::estimatedTotal))
                .toList();

        List<StoreBasketTotal> completeStores = storeTotals.stream()
                .filter(StoreBasketTotal::completeBasket)
                .toList();

        StoreBasketTotal cheapestComplete = completeStores.isEmpty() ? null : completeStores.get(0);
        BigDecimal potentialSavings = completeStores.size() < 2 ? null
                : completeStores.get(completeStores.size() - 1).estimatedTotal()
                        .subtract(completeStores.get(0).estimatedTotal());

        String currency = storeTotals.isEmpty() ? null : storeTotals.get(0).currency();

        List<FewestDeliveriesStop> plan = computeFewestDeliveriesPlan(
                request, businessById, lineTotalsByBusinessThenItem, businessesPerItem);
        BigDecimal planTotal = plan == null ? null : plan.stream()
                .map(stop -> stop.deliveryFee() == null ? stop.subtotal() : stop.subtotal().add(stop.deliveryFee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BasketComparisonResponse(storeTotals, cheapestComplete, potentialSavings, currency, plan, planTotal);
    }

    /**
     * Greedy set-cover: repeatedly pick whichever remaining store covers the
     * most still-uncovered requested items (ties broken by cheapest subtotal
     * for the items it would cover), until every item is covered or no
     * remaining store covers anything new. This is a well-known, simple
     * approximation to an NP-hard problem — not guaranteed to find the
     * mathematically fewest possible stores in every case, and this method's
     * own name/javadoc says so rather than presenting it as provably optimal.
     */
    private List<FewestDeliveriesStop> computeFewestDeliveriesPlan(
            CompareBasketRequest request, Map<String, Business> businessById,
            Map<String, Map<String, BigDecimal>> lineTotalsByBusinessThenItem,
            Map<String, List<String>> businessesPerItem) {

        Set<String> uncovered = new LinkedHashSet<>();
        request.items().forEach(i -> uncovered.add(i.canonicalProductId()));

        List<FewestDeliveriesStop> plan = new ArrayList<>();
        Set<String> usedBusinesses = new LinkedHashSet<>();

        while (!uncovered.isEmpty()) {
            String bestBusinessId = null;
            List<String> bestCovers = null;
            BigDecimal bestCost = null;

            for (String businessId : businessById.keySet()) {
                if (usedBusinesses.contains(businessId)) continue;
                Map<String, BigDecimal> lines = lineTotalsByBusinessThenItem.get(businessId);
                List<String> covers = uncovered.stream().filter(lines::containsKey).toList();
                if (covers.isEmpty()) continue;
                BigDecimal cost = covers.stream().map(lines::get).reduce(BigDecimal.ZERO, BigDecimal::add);

                boolean better = bestBusinessId == null
                        || covers.size() > bestCovers.size()
                        || (covers.size() == bestCovers.size() && cost.compareTo(bestCost) < 0);
                if (better) {
                    bestBusinessId = businessId;
                    bestCovers = covers;
                    bestCost = cost;
                }
            }

            if (bestBusinessId == null) {
                // No remaining store covers anything new — the basket can't be fully
                // completed by any combination of currently-listed sellers.
                return null;
            }

            Business business = businessById.get(bestBusinessId);
            plan.add(new FewestDeliveriesStop(bestBusinessId, business.getName(), bestCovers, bestCost, business.getDeliveryFee()));
            usedBusinesses.add(bestBusinessId);
            uncovered.removeAll(bestCovers);
        }

        return plan;
    }
}
