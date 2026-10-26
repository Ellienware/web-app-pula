package com.momocircle.service;

import com.momocircle.domain.BranchItemAvailability;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.CanonicalFoodAttributes;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.MenuCategory;
import com.momocircle.domain.Modifier;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.ProductModifierGroup;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.FoodDiscoveryDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchItemAvailabilityRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.ModifierRepository;
import com.momocircle.repository.ProductFoodDetailsRepository;
import com.momocircle.repository.ProductModifierGroupRepository;
import com.momocircle.repository.ProductRepository;
import com.momocircle.service.FoodMatchingService.FoodProfile;
import com.momocircle.service.FoodMatchingService.MatchLevel;
import com.momocircle.service.FoodMatchingService.MatchResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — public discovery: restaurants, menus, food search and food comparison.
 *
 * Principles: results carry comparable facts and the reasons two meals are (or are not) the same;
 * nothing is ranked as a "winner"; ordering is chosen by the customer; the customer's coordinates
 * are used for distance only (never stored, never returned); when a distance filter is applied,
 * offers whose location is unknown are excluded and counted, not silently included.
 *
 * Scale note: candidate items are loaded as a capped set (MAX_CANDIDATES) and filtered in memory
 * because price, availability and distance depend on per-branch data. That is fine for the
 * current volume; a real search index is the next step if the food catalogue grows large.
 */
@Service
public class FoodDiscoveryService {

    static final int MAX_CANDIDATES = 1000;
    static final int MAX_PAGE_SIZE = 50;
    private static final Set<BusinessType> FOOD_TYPES = Arrays.stream(BusinessType.values())
            .filter(BusinessType::isFood).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    static final String NO_WINNER_NOTE = "PULA shows comparable details and does not pick a winner. "
            + "Check the differences listed on each result and choose what suits you.";

    private final ProductRepository productRepository;
    private final BusinessRepository businessRepository;
    private final BusinessBranchRepository branchRepository;
    private final BranchItemAvailabilityRepository availabilityRepository;
    private final ProductFoodDetailsRepository detailsRepository;
    private final ProductModifierGroupRepository productGroupRepository;
    private final ModifierRepository modifierRepository;
    private final FoodMatchingService matching;
    private final CanonicalProductService canonicalProductService;

    public FoodDiscoveryService(ProductRepository productRepository, BusinessRepository businessRepository,
                                BusinessBranchRepository branchRepository,
                                BranchItemAvailabilityRepository availabilityRepository,
                                ProductFoodDetailsRepository detailsRepository,
                                ProductModifierGroupRepository productGroupRepository,
                                ModifierRepository modifierRepository, FoodMatchingService matching,
                                CanonicalProductService canonicalProductService) {
        this.productRepository = productRepository;
        this.businessRepository = businessRepository;
        this.branchRepository = branchRepository;
        this.availabilityRepository = availabilityRepository;
        this.detailsRepository = detailsRepository;
        this.productGroupRepository = productGroupRepository;
        this.modifierRepository = modifierRepository;
        this.matching = matching;
        this.canonicalProductService = canonicalProductService;
    }

    // ------------------------------------------------------------------ request-scoped caches

    private final class Ctx {
        final Map<String, List<BusinessBranch>> branches = new HashMap<>();
        final Map<String, Map<String, BranchItemAvailability>> availability = new HashMap<>();
        Map<String, ProductFoodDetails> details = new HashMap<>();
        Set<String> productsWithExtras = new HashSet<>();
        int unknownDistance = 0;

        List<BusinessBranch> branchesOf(String businessId) {
            return branches.computeIfAbsent(businessId, branchRepository::findByBusinessIdOrderByCreatedAtAsc);
        }

        BranchItemAvailability availabilityOf(String branchId, String productId) {
            return availability.computeIfAbsent(branchId, id -> availabilityRepository.findByBranchId(id).stream()
                    .collect(Collectors.toMap(a -> a.getProduct().getId(), a -> a, (a, b) -> a)))
                    .get(productId);
        }

        void load(List<Product> products) {
            List<String> ids = products.stream().map(Product::getId).toList();
            if (ids.isEmpty()) return;
            details = detailsRepository.findByProductIdIn(ids).stream()
                    .collect(Collectors.toMap(d -> d.getProduct().getId(), d -> d, (a, b) -> a));
            productsWithExtras = productGroupRepository.findByProductIdIn(ids).stream()
                    .map(l -> l.getProduct().getId()).collect(Collectors.toSet());
        }
    }

    // ------------------------------------------------------------------ search

    @Transactional(readOnly = true)
    public FoodSearchResponse search(FoodSearchCriteria c) {
        Ctx ctx = new Ctx();
        List<Product> candidates = productRepository.findFoodCandidates(FOOD_TYPES, PageRequest.of(0, MAX_CANDIDATES));
        ctx.load(candidates);

        boolean hasQuery = c.q() != null && !c.q().isBlank();
        Set<String> queryTokens = FoodMatchingService.tokens(c.q());
        List<CanonicalProduct> resolved = hasQuery ? matching.resolveCanonicalFoods(c.q()) : List.of();
        List<FoodProfile> references = new ArrayList<>();
        for (CanonicalProduct cp : resolved) references.add(matching.profileOf(cp, matching.attributesOf(cp.getId())));

        List<FoodOfferResponse> offers = new ArrayList<>();
        for (Product p : candidates) {
            ProductFoodDetails d = ctx.details.get(p.getId());
            if (c.foodType() != null && !c.foodType().isBlank()
                    && (d == null || !FoodMatchingService.token(c.foodType()).equals(d.getFoodType()))) continue;
            FoodProfile profile = matching.profileOf(p, d);
            MatchResult best = null;
            for (FoodProfile ref : references) {
                MatchResult r = matching.match(ref, profile);
                if (r.level() != null && (best == null || r.level().ordinal() < best.level().ordinal())) best = r;
            }
            boolean textual = hasQuery && itemTokens(p, d).containsAll(queryTokens);
            if (hasQuery && !textual && best == null) continue;
            offers.addAll(offersFor(p, d, best, ctx, c));
        }
        offers.sort(comparator(c.sort(), true));
        int size = pageSize(c.size());
        int page = Math.max(0, c.page());
        int from = Math.min(offers.size(), page * size);
        List<FoodOfferResponse> slice = offers.subList(from, Math.min(offers.size(), from + size));
        return new FoodSearchResponse(slice, offers.size(), page, size,
                resolved.stream().map(CanonicalProduct::getName).toList(), ctx.unknownDistance);
    }

    private static Set<String> itemTokens(Product p, ProductFoodDetails d) {
        StringBuilder sb = new StringBuilder(p.getName());
        if (p.getDescription() != null) sb.append(' ').append(p.getDescription());
        if (p.getMenuCategory() != null) sb.append(' ').append(p.getMenuCategory().getName());
        if (d != null) {
            for (String s : new String[] {d.getFoodType(), d.getProtein(), d.getStarch()}) if (s != null) sb.append(' ').append(s);
            for (String s : d.getSides()) sb.append(' ').append(s);
        }
        return FoodMatchingService.tokens(sb.toString());
    }

    // ------------------------------------------------------------------ comparison

    @Transactional(readOnly = true)
    public FoodComparisonResponse compareCanonical(String canonicalId, FoodSearchCriteria c) {
        CanonicalProduct cp = canonicalProductService.requireById(canonicalId);
        CanonicalFoodAttributes attrs = matching.attributesOf(cp.getId());
        return compareAgainst(matching.profileOf(cp, attrs), cp.getName(), cp.getId(), null, c);
    }

    @Transactional(readOnly = true)
    public FoodComparisonResponse compareItem(String productId, FoodSearchCriteria c) {
        Product p = productRepository.findById(productId)
                .filter(x -> x.getStatus() == ProductStatus.ACTIVE && FoodCheckoutService.isFoodBusiness(x.getBusiness()))
                .orElseThrow(() -> ApiException.notFound("Menu item not found"));
        ProductFoodDetails d = detailsRepository.findByProductId(p.getId()).orElse(null);
        String canonicalId = p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId();
        return compareAgainst(matching.profileOf(p, d), p.getName(), canonicalId, p.getId(), c);
    }

    private FoodComparisonResponse compareAgainst(FoodProfile ref, String refName, String canonicalId,
                                                  String refProductId, FoodSearchCriteria c) {
        Ctx ctx = new Ctx();
        List<Product> candidates = productRepository.findFoodCandidates(FOOD_TYPES, PageRequest.of(0, MAX_CANDIDATES));
        ctx.load(candidates);
        List<FoodOfferResponse> exact = new ArrayList<>(), similar = new ArrayList<>(), alternatives = new ArrayList<>();
        for (Product p : candidates) {
            if (p.getId().equals(refProductId)) continue;
            ProductFoodDetails d = ctx.details.get(p.getId());
            MatchResult m = matching.match(ref, matching.profileOf(p, d));
            if (m.level() == null) continue;
            List<FoodOfferResponse> offers = offersFor(p, d, m, ctx, c);
            switch (m.level()) {
                case EXACT_MATCH -> exact.addAll(offers);
                case SIMILAR_MEAL -> similar.addAll(offers);
                case ALTERNATIVE -> alternatives.addAll(offers);
            }
        }
        Comparator<FoodOfferResponse> cmp = comparator(c.sort(), false);
        exact.sort(cmp);
        similar.sort(cmp);
        alternatives.sort(cmp);
        return new FoodComparisonResponse(refName, canonicalId, refProductId, exact, similar, alternatives,
                ctx.unknownDistance, NO_WINNER_NOTE);
    }

    // ------------------------------------------------------------------ offers

    private List<FoodOfferResponse> offersFor(Product p, ProductFoodDetails d, MatchResult match, Ctx ctx,
                                              FoodSearchCriteria c) {
        Business b = p.getBusiness();
        if (c.businessType() != null && b.getBusinessType() != c.businessType()) return List.of();
        List<BusinessBranch> branches = ctx.branchesOf(b.getId()).stream().filter(BusinessBranch::isActive).toList();
        List<FoodOfferResponse> out = new ArrayList<>();
        if (branches.isEmpty()) {
            addIfPasses(out, build(p, d, b, null, match, ctx, c), c, ctx);
        } else {
            for (BusinessBranch br : branches) addIfPasses(out, build(p, d, b, br, match, ctx, c), c, ctx);
        }
        return out;
    }

    private FoodOfferResponse build(Product p, ProductFoodDetails d, Business b, BusinessBranch br,
                                    MatchResult match, Ctx ctx, FoodSearchCriteria c) {
        BigDecimal price = p.getPrice();
        String unavailable = null;
        if (b.isTemporarilyClosed()) unavailable = "Temporarily closed";
        if (br != null) {
            if (br.isTemporarilyClosed()) unavailable = "Temporarily closed";
            BranchItemAvailability row = ctx.availabilityOf(br.getId(), p.getId());
            if (row != null) {
                if (!row.isAvailable()) unavailable = "Not available at this branch";
                if (row.getPriceOverride() != null) price = row.getPriceOverride();
            }
        }
        if (unavailable == null && d != null && d.getDailyQuantity() != null && d.getDailyQuantity() <= 0) {
            unavailable = "Sold out";
        }
        Set<FulfilmentOption> options = (br != null && !br.getFulfilmentOptions().isEmpty())
                ? br.getFulfilmentOptions() : b.getFulfilmentOptions();
        boolean collection = options.contains(FulfilmentOption.CUSTOMER_PICKUP);
        BigDecimal fee = (br != null && br.getDeliveryFee() != null) ? br.getDeliveryFee() : b.getDeliveryFee();
        boolean offersDelivery = options.contains(FulfilmentOption.MOMORIDE_DELIVERY)
                || options.contains(FulfilmentOption.SELLER_DELIVERY)
                || options.contains(FulfilmentOption.SUPPORTED_PARTNER_DELIVERY);
        boolean delivery = offersDelivery && fee != null; // checkout refuses delivery with no fee set
        Integer prep = (br != null && br.getPrepTimeMinutes() != null) ? br.getPrepTimeMinutes()
                : d != null ? d.getPrepTimeMinutes() : null;
        List<String> hours = (br != null && !br.getOperatingHours().isEmpty()) ? br.getOperatingHours() : b.getOperatingHours();
        Double distance = (c.hasLocation() && br != null && br.getLatitude() != null && br.getLongitude() != null)
                ? round1(haversineKm(c.lat(), c.lng(), br.getLatitude().doubleValue(), br.getLongitude().doubleValue()))
                : null;
        return new FoodOfferResponse(
                p.getId(), p.getName(), p.getDescription(), p.getImageUrls().isEmpty() ? null : p.getImageUrls().get(0),
                b.getId(), b.getName(), b.getBusinessType(),
                br == null ? null : br.getId(), br == null ? null : br.getName(), br == null ? null : br.getArea(),
                br != null && br.getAddress() != null ? br.getAddress() : b.getLocation(),
                price, p.getCurrency(),
                d == null ? null : d.getFoodType(), d == null ? null : d.getProtein(), d == null ? null : d.getStarch(),
                d == null ? null : d.getPortionSize(), d == null ? null : d.getPortionDescription(),
                d == null ? Set.of() : new LinkedHashSet<>(d.getSides()),
                d == null ? Set.of() : new LinkedHashSet<>(d.getAllergens()),
                d == null ? Set.of() : new LinkedHashSet<>(d.getDietaryTags()),
                ctx.productsWithExtras.contains(p.getId()),
                p.getRatingAverage(), p.getRatingCount(), b.getRatingAverage(), b.getRatingCount(),
                collection, delivery, delivery ? fee : null, prep, distance, unavailable == null, unavailable,
                new ArrayList<>(hours), p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId(),
                match == null ? null : match.level().name(), match == null ? List.of() : match.reasons(),
                match != null && match.uncertain());
    }

    private void addIfPasses(List<FoodOfferResponse> out, FoodOfferResponse o, FoodSearchCriteria c, Ctx ctx) {
        if (!c.includeUnavailable() && !o.available()) return;
        if (c.maxPrice() != null && o.price().compareTo(c.maxPrice()) > 0) return;
        if (c.minRating() != null) {
            BigDecimal r = ratingBasis(o);
            if (r == null || r.compareTo(c.minRating()) < 0) return;
        }
        if (c.fulfilment() == FulfilmentFilter.COLLECTION && !o.collectionAvailable()) return;
        if (c.fulfilment() == FulfilmentFilter.DELIVERY && !o.deliveryAvailable()) return;
        if (c.maxDeliveryFee() != null && (!o.deliveryAvailable() || o.deliveryFee().compareTo(c.maxDeliveryFee()) > 0)) return;
        if (c.maxPrepMinutes() != null && (o.prepMinutes() == null || o.prepMinutes() > c.maxPrepMinutes())) return;
        if (c.area() != null && !c.area().isBlank() && !inArea(o, c.area())) return;
        if (c.maxDistanceKm() != null && c.hasLocation()) {
            if (o.distanceKm() == null) { ctx.unknownDistance++; return; }
            if (o.distanceKm() > c.maxDistanceKm()) return;
        }
        out.add(o);
    }

    private static boolean inArea(FoodOfferResponse o, String area) {
        String a = area.trim().toLowerCase(Locale.ROOT);
        return (o.area() != null && o.area().toLowerCase(Locale.ROOT).contains(a))
                || (o.address() != null && o.address().toLowerCase(Locale.ROOT).contains(a));
    }

    /** The item's own rating when it has reviews, otherwise the restaurant's; null when neither has any. */
    private static BigDecimal ratingBasis(FoodOfferResponse o) {
        if (o.itemRatingCount() > 0) return o.itemRating();
        if (o.restaurantRatingCount() > 0) return o.restaurantRating();
        return null;
    }

    private static Comparator<FoodOfferResponse> comparator(FoodSort sort, boolean levelFirst) {
        Comparator<Double> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<FoodOfferResponse> byDistance = Comparator.comparing(FoodOfferResponse::distanceKm, nullsLast);
        Comparator<FoodOfferResponse> byName = Comparator.comparing(o -> o.name().toLowerCase(Locale.ROOT));
        Comparator<FoodOfferResponse> tail = byName.thenComparing(FoodOfferResponse::businessName)
                .thenComparing(o -> o.branchId() == null ? "" : o.branchId());
        FoodSort s = sort == null ? FoodSort.RELEVANCE : sort;
        return switch (s) {
            case DISTANCE -> byDistance.thenComparing(tail);
            case PRICE -> Comparator.comparing(FoodOfferResponse::price).thenComparing(tail);
            case RATING -> Comparator.comparing((FoodOfferResponse o) -> {
                BigDecimal r = ratingBasis(o);
                return r == null ? BigDecimal.valueOf(-1) : r;
            }).reversed().thenComparing(tail);
            case NAME -> tail;
            case RELEVANCE -> {
                Comparator<FoodOfferResponse> level = Comparator.comparingInt(o -> o.matchLevel() == null ? 3
                        : MatchLevel.valueOf(o.matchLevel()).ordinal());
                yield (levelFirst ? level.thenComparing(byDistance) : byDistance).thenComparing(tail);
            }
        };
    }

    // ------------------------------------------------------------------ restaurants

    @Transactional(readOnly = true)
    public RestaurantListResponse restaurants(RestaurantQuery q) {
        Ctx ctx = new Ctx();
        List<Business> businesses = businessRepository.findByActiveTrueAndBusinessTypeIn(FOOD_TYPES,
                PageRequest.of(0, 500, Sort.by("name"))).getContent();
        Set<String> qTokens = FoodMatchingService.tokens(q.q());
        boolean located = q.lat() != null && q.lng() != null;
        List<RestaurantSummary> out = new ArrayList<>();
        int unknown = 0;
        for (Business b : businesses) {
            if (q.businessType() != null && b.getBusinessType() != q.businessType()) continue;
            if (!qTokens.isEmpty()) {
                Set<String> t = FoodMatchingService.tokens(b.getName() + " " + (b.getDescription() == null ? "" : b.getDescription())
                        + " " + (b.getCategory() == null ? "" : b.getCategory()));
                if (!t.containsAll(qTokens)) continue;
            }
            List<BusinessBranch> branches = ctx.branchesOf(b.getId()).stream().filter(BusinessBranch::isActive).toList();
            RestaurantSummary s = summarise(b, branches, q.lat(), q.lng());
            if (q.area() != null && !q.area().isBlank() && !restaurantInArea(b, branches, q.area())) continue;
            if (q.fulfilment() == FulfilmentFilter.COLLECTION && !s.collectionAvailable()) continue;
            if (q.fulfilment() == FulfilmentFilter.DELIVERY && !s.deliveryAvailable()) continue;
            if (q.minRating() != null && (s.ratingCount() == 0 || s.rating().compareTo(q.minRating()) < 0)) continue;
            if (q.maxDistanceKm() != null && located) {
                if (s.nearestDistanceKm() == null) { unknown++; continue; }
                if (s.nearestDistanceKm() > q.maxDistanceKm()) continue;
            }
            out.add(s);
        }
        Comparator<RestaurantSummary> byName = Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT));
        FoodSort sort = q.sort() == null ? FoodSort.NAME : q.sort();
        out.sort(switch (sort) {
            case DISTANCE -> Comparator.comparing(RestaurantSummary::nearestDistanceKm,
                    Comparator.nullsLast(Comparator.<Double>naturalOrder())).thenComparing(byName);
            case RATING -> Comparator.comparing((RestaurantSummary r) -> r.ratingCount() == 0 ? BigDecimal.valueOf(-1) : r.rating())
                    .reversed().thenComparing(byName);
            default -> byName;
        });
        int size = pageSize(q.size());
        int page = Math.max(0, q.page());
        int from = Math.min(out.size(), page * size);
        return new RestaurantListResponse(out.subList(from, Math.min(out.size(), from + size)), out.size(), page, size, unknown);
    }

    @Transactional(readOnly = true)
    public RestaurantDetail restaurant(String businessId, Double lat, Double lng) {
        Business b = requireFoodBusiness(businessId);
        List<BusinessBranch> branches = branchRepository.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream()
                .filter(BusinessBranch::isActive).toList();
        List<BranchView> views = new ArrayList<>();
        for (BusinessBranch br : branches) {
            Double dist = (lat != null && lng != null && br.getLatitude() != null && br.getLongitude() != null)
                    ? round1(haversineKm(lat, lng, br.getLatitude().doubleValue(), br.getLongitude().doubleValue())) : null;
            String closed = b.isTemporarilyClosed() || br.isTemporarilyClosed() ? "Temporarily closed" : null;
            views.add(new BranchView(br.getId(), br.getName(), br.getAddress(), br.getArea(), br.getLatitude(),
                    br.getLongitude(), new ArrayList<>(br.getOperatingHours()), new LinkedHashSet<>(br.getFulfilmentOptions()),
                    new LinkedHashSet<>(br.getDeliveryAreas()), br.getDeliveryFee(), br.getMinimumOrderAmount(),
                    br.getPrepTimeMinutes(), closed == null, closed, dist));
        }
        String closedReason = closedReason(b, branches);
        return new RestaurantDetail(b.getId(), b.getName(), b.getBusinessType(), b.getDescription(), b.getLogoUrl(),
                b.getLocation(), b.getContactPhone(), b.getContactEmail(), new ArrayList<>(b.getOperatingHours()),
                new LinkedHashSet<>(b.getFulfilmentOptions()), b.getDeliveryFee(), b.getMinimumOrderAmount(),
                b.getRatingAverage(), b.getRatingCount(), closedReason == null, closedReason, views);
    }

    private RestaurantSummary summarise(Business b, List<BusinessBranch> branches, Double lat, Double lng) {
        boolean collection = false, delivery = false;
        BigDecimal feeFrom = null;
        Integer prepFrom = null;
        Double nearest = null;
        List<BusinessBranch> locations = branches.isEmpty() ? java.util.Collections.singletonList((BusinessBranch) null) : branches;
        for (BusinessBranch br : locations) {
            Set<FulfilmentOption> opts = (br != null && !br.getFulfilmentOptions().isEmpty()) ? br.getFulfilmentOptions() : b.getFulfilmentOptions();
            BigDecimal fee = (br != null && br.getDeliveryFee() != null) ? br.getDeliveryFee() : b.getDeliveryFee();
            collection |= opts.contains(FulfilmentOption.CUSTOMER_PICKUP);
            boolean d = (opts.contains(FulfilmentOption.MOMORIDE_DELIVERY) || opts.contains(FulfilmentOption.SELLER_DELIVERY)
                    || opts.contains(FulfilmentOption.SUPPORTED_PARTNER_DELIVERY)) && fee != null;
            delivery |= d;
            if (d && (feeFrom == null || fee.compareTo(feeFrom) < 0)) feeFrom = fee;
            if (br != null && br.getPrepTimeMinutes() != null && (prepFrom == null || br.getPrepTimeMinutes() < prepFrom)) prepFrom = br.getPrepTimeMinutes();
            if (br != null && lat != null && lng != null && br.getLatitude() != null && br.getLongitude() != null) {
                double dist = round1(haversineKm(lat, lng, br.getLatitude().doubleValue(), br.getLongitude().doubleValue()));
                if (nearest == null || dist < nearest) nearest = dist;
            }
        }
        String area = !branches.isEmpty() && branches.get(0).getArea() != null ? branches.get(0).getArea() : b.getLocation();
        String closed = closedReason(b, branches);
        return new RestaurantSummary(b.getId(), b.getName(), b.getBusinessType(), b.getDescription(), b.getLogoUrl(), area,
                branches.size(), b.getRatingAverage(), b.getRatingCount(), collection, delivery, feeFrom,
                b.getMinimumOrderAmount(), prepFrom, nearest, closed == null, closed);
    }

    private static String closedReason(Business b, List<BusinessBranch> activeBranches) {
        if (b.isTemporarilyClosed()) return "Temporarily closed";
        if (!activeBranches.isEmpty() && activeBranches.stream().allMatch(BusinessBranch::isTemporarilyClosed)) {
            return "All branches are closed right now";
        }
        return null;
    }

    private static boolean restaurantInArea(Business b, List<BusinessBranch> branches, String area) {
        String a = area.trim().toLowerCase(Locale.ROOT);
        if (b.getLocation() != null && b.getLocation().toLowerCase(Locale.ROOT).contains(a)) return true;
        for (BusinessBranch br : branches) {
            if (br.getArea() != null && br.getArea().toLowerCase(Locale.ROOT).contains(a)) return true;
            if (br.getAddress() != null && br.getAddress().toLowerCase(Locale.ROOT).contains(a)) return true;
            if (br.getDeliveryAreas().stream().anyMatch(x -> x.toLowerCase(Locale.ROOT).contains(a))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ menu

    @Transactional(readOnly = true)
    public MenuResponse menu(String businessId, String branchId) {
        Business b = requireFoodBusiness(businessId);
        BusinessBranch branch = null;
        if (branchId != null && !branchId.isBlank()) {
            branch = branchRepository.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream()
                    .filter(x -> x.getId().equals(branchId) && x.isActive()).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Branch not found"));
        }
        List<Product> items = productRepository.findByBusinessIdAndStatusOrderByNameAsc(b.getId(), ProductStatus.ACTIVE);
        Ctx ctx = new Ctx();
        ctx.load(items);
        Map<String, List<ModifierGroupView>> groupsByProduct = modifierViews(items);
        Map<String, MenuCategory> categories = new LinkedHashMap<>();
        Map<String, List<MenuItemView>> byCategory = new LinkedHashMap<>();
        for (Product p : items) {
            MenuCategory mc = p.getMenuCategory();
            String key = mc == null ? "" : mc.getId();
            if (mc != null) categories.putIfAbsent(key, mc);
            byCategory.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(menuItem(p, ctx.details.get(p.getId()), b, branch, ctx, groupsByProduct.getOrDefault(p.getId(), List.of())));
        }
        List<MenuCategoryView> views = new ArrayList<>();
        categories.values().stream()
                .sorted(Comparator.comparingInt(MenuCategory::getSortOrder).thenComparing(MenuCategory::getName))
                .forEach(mc -> views.add(new MenuCategoryView(mc.getId(), mc.getName(), byCategory.get(mc.getId()))));
        if (byCategory.containsKey("")) views.add(new MenuCategoryView(null, "Other", byCategory.get("")));
        return new MenuResponse(b.getId(), b.getName(), branch == null ? null : branch.getId(),
                branch == null ? null : branch.getName(), views);
    }

    private MenuItemView menuItem(Product p, ProductFoodDetails d, Business b, BusinessBranch br, Ctx ctx,
                                  List<ModifierGroupView> groups) {
        BigDecimal price = p.getPrice();
        String unavailable = b.isTemporarilyClosed() ? "Temporarily closed" : null;
        if (br != null) {
            if (br.isTemporarilyClosed()) unavailable = "Temporarily closed";
            BranchItemAvailability row = ctx.availabilityOf(br.getId(), p.getId());
            if (row != null) {
                if (!row.isAvailable()) unavailable = "Not available at this branch";
                if (row.getPriceOverride() != null) price = row.getPriceOverride();
            }
        }
        if (unavailable == null && d != null && d.getDailyQuantity() != null && d.getDailyQuantity() <= 0) unavailable = "Sold out";
        return new MenuItemView(p.getId(), p.getName(), p.getDescription(),
                p.getImageUrls().isEmpty() ? null : p.getImageUrls().get(0), price, p.getCurrency(),
                d == null ? null : d.getFoodType(), d == null ? null : d.getProtein(), d == null ? null : d.getStarch(),
                d == null ? null : d.getPortionSize(), d == null ? null : d.getPortionDescription(),
                d == null ? Set.of() : new LinkedHashSet<>(d.getSides()),
                d == null ? Set.of() : new LinkedHashSet<>(d.getAllergens()),
                d == null ? Set.of() : new LinkedHashSet<>(d.getDietaryTags()),
                d == null ? null : d.getSpiceLevel(),
                (br != null && br.getPrepTimeMinutes() != null) ? br.getPrepTimeMinutes() : d == null ? null : d.getPrepTimeMinutes(),
                p.getRatingAverage(), p.getRatingCount(), unavailable == null, unavailable,
                p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId(), groups);
    }

    private Map<String, List<ModifierGroupView>> modifierViews(List<Product> items) {
        List<String> ids = items.stream().map(Product::getId).toList();
        Map<String, List<ModifierGroupView>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        Map<String, ModifierGroupView> groupCache = new HashMap<>();
        for (ProductModifierGroup link : productGroupRepository.findByProductIdIn(ids)) {
            ModifierGroup g = link.getGroup();
            ModifierGroupView view = groupCache.computeIfAbsent(g.getId(), gid -> {
                List<ModifierView> mods = modifierRepository.findByGroupIdOrderByNameAsc(gid).stream()
                        .map(m -> new ModifierView(m.getId(), m.getName(), m.getPriceDelta(), m.isAvailable())).toList();
                return new ModifierGroupView(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(), g.isRequired(), mods);
            });
            out.computeIfAbsent(link.getProduct().getId(), k -> new ArrayList<>()).add(view);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Business requireFoodBusiness(String id) {
        return businessRepository.findById(id)
                .filter(x -> x.isActive() && FoodCheckoutService.isFoodBusiness(x))
                .orElseThrow(() -> ApiException.notFound("Restaurant not found"));
    }

    private static int pageSize(int requested) {
        return requested <= 0 ? 20 : Math.min(requested, MAX_PAGE_SIZE);
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
