package com.momocircle.service;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.BranchItemAvailability;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.CartItemModifier;
import com.momocircle.domain.Modifier;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.OrderItemModifier;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.ProductModifierGroup;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchItemAvailabilityRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.ModifierRepository;
import com.momocircle.repository.ProductFoodDetailsRepository;
import com.momocircle.repository.ProductModifierGroupRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * PULA Food — the single place that prices and validates a food cart. Both checkout paths
 * (synchronous and Paystack) call this, so the rules cannot drift apart. Everything here is
 * computed from database state; nothing the client sends is trusted for price, availability,
 * modifier eligibility or fees.
 *
 * Food items are NOT stock-counted (Product.stock stays unused); availability is the product
 * status, the per-branch availability row, and an optional portion cap on ProductFoodDetails
 * that is claimed atomically.
 */
@Service
public class FoodCheckoutService {

    public record PricedLine(CartItem cartItem, Product product, BigDecimal unitPrice, List<Modifier> modifiers,
                             BigDecimal modifierTotal, int quantity, BigDecimal lineTotal) {}

    public record PricedFoodOrder(List<PricedLine> lines, BigDecimal subtotal, BigDecimal deliveryFee,
                                  BigDecimal total, BusinessBranch branch, FulfilmentOption fulfilment,
                                  String deliveryAddress, String note, String currency) {}

    /** Portions claimed from a capped item, kept so they can be given back if the checkout doesn't proceed. */
    public record Claim(String productId, int quantity) {}

    private final ProductFoodDetailsRepository detailsRepository;
    private final ProductModifierGroupRepository productGroupRepository;
    private final ModifierRepository modifierRepository;
    private final BusinessBranchRepository branchRepository;
    private final BranchItemAvailabilityRepository availabilityRepository;
    private final FinancialValidationService validation;
    private final FoodProperties properties;

    public FoodCheckoutService(ProductFoodDetailsRepository detailsRepository,
                               ProductModifierGroupRepository productGroupRepository,
                               ModifierRepository modifierRepository,
                               BusinessBranchRepository branchRepository,
                               BranchItemAvailabilityRepository availabilityRepository,
                               FinancialValidationService validation, FoodProperties properties) {
        this.detailsRepository = detailsRepository;
        this.productGroupRepository = productGroupRepository;
        this.modifierRepository = modifierRepository;
        this.branchRepository = branchRepository;
        this.availabilityRepository = availabilityRepository;
        this.validation = validation;
        this.properties = properties;
    }

    public static boolean isFoodBusiness(Business b) {
        return b.getBusinessType() != null && b.getBusinessType().isFood();
    }

    // ------------------------------------------------------------------ modifiers

    /** Stable identity of a modifier set: SHA-256 hex of the sorted ids, "" when empty. */
    public static String signature(Collection<String> modifierIds) {
        if (modifierIds == null || modifierIds.isEmpty()) return "";
        try {
            String joined = String.join(",", new TreeSet<>(modifierIds));
            byte[] d = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Resolves and validates a modifier selection for a product: each id must exist, be
     * available, belong to a group attached to THIS product, appear once, and every attached
     * group's min/max must be respected. Used when adding to the cart (early feedback) and
     * again at checkout (authoritative).
     */
    public List<Modifier> resolveModifiers(Product product, Collection<String> ids) {
        List<String> requested = ids == null ? List.of() : new ArrayList<>(ids);
        Set<String> distinct = new HashSet<>(requested);
        if (distinct.size() != requested.size()) {
            throw ApiException.badRequest("Each option can only be chosen once");
        }
        if (distinct.size() > properties.getMaxModifiersPerLine()) {
            throw ApiException.badRequest("Too many options chosen for \"" + product.getName() + "\"");
        }
        List<ProductModifierGroup> attached = productGroupRepository.findByProductId(product.getId());
        Map<String, ModifierGroup> groups = new LinkedHashMap<>();
        for (ProductModifierGroup l : attached) groups.put(l.getGroup().getId(), l.getGroup());

        List<Modifier> chosen = new ArrayList<>();
        if (!distinct.isEmpty()) {
            List<Modifier> found = modifierRepository.findAllById(distinct);
            if (found.size() != distinct.size()) {
                throw ApiException.badRequest("One of the chosen options does not exist");
            }
            chosen.addAll(found);
        }
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        for (Modifier m : chosen) {
            String gid = m.getGroup().getId();
            if (!groups.containsKey(gid)) {
                throw ApiException.badRequest("\"" + m.getName() + "\" is not an option for \"" + product.getName() + "\"");
            }
            if (!m.isAvailable()) {
                throw ApiException.badRequest("\"" + m.getName() + "\" is not available right now");
            }
            perGroup.merge(gid, 1, Integer::sum);
        }
        for (ModifierGroup g : groups.values()) {
            int n = perGroup.getOrDefault(g.getId(), 0);
            if (n < g.getMinSelect()) {
                throw ApiException.badRequest("Choose at least " + g.getMinSelect() + " from \"" + g.getName()
                        + "\" for \"" + product.getName() + "\"");
            }
            if (n > g.getMaxSelect()) {
                throw ApiException.badRequest("Choose at most " + g.getMaxSelect() + " from \"" + g.getName()
                        + "\" for \"" + product.getName() + "\"");
            }
        }
        chosen.sort(java.util.Comparator.comparing(Modifier::getId));
        return chosen;
    }

    // ------------------------------------------------------------------ pricing

    /** Validates and prices a food cart. Reads only; claims and writes happen in the caller's transaction. */
    public PricedFoodOrder price(Business business, List<CartItem> items, CheckoutRequest req) {
        if (!business.isActive() || business.isTemporarilyClosed()) {
            throw ApiException.badRequest(business.getName() + " is not taking orders right now");
        }
        List<BusinessBranch> branches = branchRepository.findByBusinessIdOrderByCreatedAtAsc(business.getId());
        BusinessBranch branch = null;
        if (!branches.isEmpty()) {
            if (req.branchId() == null || req.branchId().isBlank()) {
                throw ApiException.badRequest("Choose which branch to order from");
            }
            branch = branches.stream().filter(b -> b.getId().equals(req.branchId())).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("That branch does not belong to this restaurant"));
            if (!branch.isActive() || branch.isTemporarilyClosed()) {
                throw ApiException.badRequest(branch.getName() + " is not taking orders right now");
            }
        } else if (req.branchId() != null && !req.branchId().isBlank()) {
            throw ApiException.badRequest("This restaurant has no branches to choose from");
        }

        FulfilmentOption fulfilment = req.fulfilmentMethod();
        if (fulfilment == null) throw ApiException.badRequest("Choose collection or delivery");
        Set<FulfilmentOption> offered = (branch != null && !branch.getFulfilmentOptions().isEmpty())
                ? branch.getFulfilmentOptions() : business.getFulfilmentOptions();
        if (!offered.contains(fulfilment)) {
            throw ApiException.badRequest("This restaurant does not offer that fulfilment method");
        }

        BigDecimal fee = BigDecimal.ZERO;
        String address = null;
        if (fulfilment != FulfilmentOption.CUSTOMER_PICKUP) {
            if (req.deliveryAddress() == null || req.deliveryAddress().isBlank()) {
                throw ApiException.badRequest("A delivery address is required");
            }
            address = req.deliveryAddress().trim();
            if (fulfilment == FulfilmentOption.MOMORIDE_DELIVERY) {
                // A driver needs somewhere to collect from, so refuse now rather than fail when the order is marked ready.
                String pickup = (branch != null && branch.getAddress() != null && !branch.getAddress().isBlank())
                        ? branch.getAddress() : business.getLocation();
                if (pickup == null || pickup.isBlank()) {
                    throw ApiException.badRequest("Delivery is not available yet: this restaurant has not set its address");
                }
            }
            BigDecimal configured = (branch != null && branch.getDeliveryFee() != null)
                    ? branch.getDeliveryFee() : business.getDeliveryFee();
            if (configured == null) {
                throw ApiException.badRequest("This restaurant has not set a delivery fee yet");
            }
            validation.requireValidNonNegativeAmount(configured, "deliveryFee");
            if (fulfilment == FulfilmentOption.MOMORIDE_DELIVERY
                    && configured.compareTo(properties.getMinPulaRideDeliveryFee()) < 0) {
                throw ApiException.badRequest("Delivery is not available from this restaurant at the moment");
            }
            fee = configured;
        }

        String currency = items.get(0).getProduct().getCurrency();
        List<PricedLine> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem ci : items) {
            Product p = ci.getProduct();
            if (!p.getCurrency().equals(currency)) {
                throw ApiException.badRequest("All items in one order must share a currency");
            }
            if (p.getStatus() != ProductStatus.ACTIVE) {
                throw ApiException.badRequest("\"" + p.getName() + "\" is no longer available");
            }
            BigDecimal unit = p.getPrice();
            if (branch != null) {
                BranchItemAvailability a = availabilityRepository.findByBranchIdAndProductId(branch.getId(), p.getId()).orElse(null);
                if (a != null) {
                    if (!a.isAvailable()) {
                        throw ApiException.badRequest("\"" + p.getName() + "\" is not available at " + branch.getName());
                    }
                    if (a.getPriceOverride() != null) unit = a.getPriceOverride();
                }
            }
            List<String> ids = ci.getModifiers().stream().map(m -> m.getModifier().getId()).toList();
            List<Modifier> mods = resolveModifiers(p, ids);
            BigDecimal modTotal = mods.stream().map(Modifier::getPriceDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal lineTotal = unit.add(modTotal).multiply(BigDecimal.valueOf(ci.getQuantity()));
            lines.add(new PricedLine(ci, p, unit, mods, modTotal, ci.getQuantity(), lineTotal));
            subtotal = subtotal.add(lineTotal);
        }

        BigDecimal min = (branch != null && branch.getMinimumOrderAmount() != null)
                ? branch.getMinimumOrderAmount() : business.getMinimumOrderAmount();
        if (min != null && subtotal.compareTo(min) < 0) {
            throw ApiException.badRequest("The minimum order is " + currency + " " + min.toPlainString());
        }
        return new PricedFoodOrder(lines, subtotal, fee, subtotal.add(fee), branch, fulfilment, address,
                req.customerNote() == null || req.customerNote().isBlank() ? null : req.customerNote().trim(), currency);
    }

    /**
     * Food checkout requires the total the customer reviewed in the quote. A missing total is
     * refused (400) and a total that differs from the backend's authoritative price is refused
     * (409), so nobody is ever charged an amount they haven't seen. Comparison is numeric, so
     * 85 and 85.00 are the same amount. The backend price is always recalculated from database
     * state; this value is only ever compared against it, never used as the charge.
     */
    public void requireExpectedTotal(PricedFoodOrder priced, CheckoutRequest req) {
        if (req.expectedTotal() == null) {
            throw ApiException.badRequest("Please review the order total before paying.");
        }
        if (priced.total().compareTo(req.expectedTotal()) != 0) {
            throw ApiException.conflict("The total has changed to " + priced.currency() + " "
                    + priced.total().toPlainString() + ". Please review your order again.");
        }
    }

    // ------------------------------------------------------------------ capacity

    /**
     * Atomically claims portions for capped items. Throws if any cap can't cover the order; the
     * caller's transaction then rolls back every earlier claim made in it. Returns the claims so
     * the caller can give them back explicitly when it returns an existing order instead.
     */
    public List<Claim> claimCapacity(PricedFoodOrder priced) {
        Map<String, Integer> perProduct = new LinkedHashMap<>();
        for (PricedLine l : priced.lines()) perProduct.merge(l.product().getId(), l.quantity(), Integer::sum);
        List<Claim> claims = new ArrayList<>();
        for (Map.Entry<String, Integer> e : perProduct.entrySet()) {
            ProductFoodDetails d = detailsRepository.findByProductId(e.getKey()).orElse(null);
            if (d == null || d.getDailyQuantity() == null) continue; // not capacity-limited
            if (detailsRepository.claimPortions(e.getKey(), e.getValue()) == 0) {
                String name = priced.lines().stream().filter(l -> l.product().getId().equals(e.getKey()))
                        .findFirst().map(l -> l.product().getName()).orElse("This item");
                throw ApiException.conflict("\"" + name + "\" is sold out or does not have enough portions left");
            }
            claims.add(new Claim(e.getKey(), e.getValue()));
        }
        return claims;
    }

    public void releaseClaims(List<Claim> claims) {
        for (Claim c : claims) detailsRepository.releasePortions(c.productId(), c.quantity());
    }

    /** Gives back the portions a food order held (failed payment, rejection, timeout). */
    public void releaseCapacity(Order order) {
        Map<String, Integer> perProduct = new LinkedHashMap<>();
        for (OrderItem i : order.getItems()) perProduct.merge(i.getProduct().getId(), i.getQuantity(), Integer::sum);
        perProduct.forEach(detailsRepository::releasePortions);
    }

    // ------------------------------------------------------------------ order assembly

    /** Fills a new Order from the priced cart, including modifier snapshots. */
    public void applyToOrder(Order order, PricedFoodOrder p) {
        order.setKind(OrderKind.FOOD);
        order.setRestaurantDecision(RestaurantDecision.PENDING);
        order.setBranch(p.branch());
        order.setFulfilmentMethod(p.fulfilment());
        order.setDeliveryAddress(p.deliveryAddress());
        order.setDeliveryFeeSnapshot(p.fulfilment() == FulfilmentOption.CUSTOMER_PICKUP ? null : p.deliveryFee());
        order.setCustomerNote(p.note());
        order.setCurrency(p.currency());
        order.setSubtotal(p.subtotal());
        order.setTotal(p.total());
        for (PricedLine l : p.lines()) {
            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            oi.setProduct(l.product());
            oi.setProductNameSnapshot(l.product().getName());
            oi.setUnitPriceSnapshot(l.unitPrice());
            oi.setQuantity(l.quantity());
            oi.setLineTotal(l.lineTotal());
            for (Modifier m : l.modifiers()) {
                OrderItemModifier om = new OrderItemModifier();
                om.setOrderItem(oi);
                om.setModifier(m);
                om.setNameSnapshot(m.getName());
                om.setPriceDeltaSnapshot(m.getPriceDelta());
                oi.getModifiers().add(om);
            }
            order.getItems().add(oi);
        }
    }

    /** Builds the cart-line modifier rows for an already-validated selection. */
    public List<CartItemModifier> toCartModifiers(CartItem item, List<Modifier> mods) {
        List<CartItemModifier> out = new ArrayList<>();
        for (Modifier m : mods) {
            CartItemModifier c = new CartItemModifier();
            c.setCartItem(item);
            c.setModifier(m);
            out.add(c);
        }
        return out;
    }
}
