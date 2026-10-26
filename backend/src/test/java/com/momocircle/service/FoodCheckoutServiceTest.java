package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.config.FoodProperties;
import com.momocircle.domain.BranchItemAvailability;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.CartItemModifier;
import com.momocircle.domain.Modifier;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Order;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.ProductModifierGroup;
import com.momocircle.domain.enums.BusinessType;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers PULA Food backend pricing:
 * modifiers, branch rules, fulfilment, delivery fee, minimum order, availability and capacity.
 */
class FoodCheckoutServiceTest {

    private ProductFoodDetailsRepository details;
    private ProductModifierGroupRepository productGroups;
    private ModifierRepository modifiers;
    private BusinessBranchRepository branches;
    private BranchItemAvailabilityRepository availability;
    private FoodProperties props;
    private FoodCheckoutService service;

    private Business business;
    private Product stew;
    private ModifierGroup extrasGroup;
    private Modifier egg;
    private Modifier atchar;

    @BeforeEach
    void setUp() {
        details = mock(ProductFoodDetailsRepository.class);
        productGroups = mock(ProductModifierGroupRepository.class);
        modifiers = mock(ModifierRepository.class);
        branches = mock(BusinessBranchRepository.class);
        availability = mock(BranchItemAvailabilityRepository.class);
        props = new FoodProperties();
        CurrencyProperties currency = new CurrencyProperties();
        currency.setDefaultCurrency("ZAR");
        currency.setSupported(List.of("ZAR"));
        service = new FoodCheckoutService(details, productGroups, modifiers, branches, availability,
                new FinancialValidationService(currency, new BigDecimal("10000000")), props);

        business = new Business();
        business.setId("biz-1");
        business.setName("Gogo's Kitchen");
        business.setBusinessType(BusinessType.HOME_KITCHEN);
        business.setFulfilmentOptions(new java.util.LinkedHashSet<>(
                Set.of(FulfilmentOption.CUSTOMER_PICKUP, FulfilmentOption.MOMORIDE_DELIVERY)));
        business.setDeliveryFee(new BigDecimal("20.00"));
        business.setLocation("12 Vilakazi St, Soweto");

        stew = product("p-stew", "Beef Stew + Pap", "65.00");
        extrasGroup = group("g-extras", "Extras", 0, 3);
        egg = modifier("m-egg", extrasGroup, "Egg", "8.00", true);
        atchar = modifier("m-atchar", extrasGroup, "Atchar", "5.00", true);

        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of());
        link(stew, extrasGroup);
        when(modifiers.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            if (ids == null) return List.of(); // Mockito calls the previous answer with null while a test re-stubs any()
            return List.of(egg, atchar).stream().filter(m -> ids.contains(m.getId())).toList();
        });
    }

    private Product product(String id, String name, String price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setCurrency("ZAR");
        p.setStatus(ProductStatus.ACTIVE);
        p.setBusiness(business);
        return p;
    }

    private ModifierGroup group(String id, String name, int min, int max) {
        ModifierGroup g = new ModifierGroup();
        g.setId(id);
        g.setName(name);
        g.setMinSelect(min);
        g.setMaxSelect(max);
        g.setBusiness(business);
        return g;
    }

    private Modifier modifier(String id, ModifierGroup g, String name, String delta, boolean available) {
        Modifier m = new Modifier();
        m.setId(id);
        m.setGroup(g);
        m.setName(name);
        m.setPriceDelta(new BigDecimal(delta));
        m.setAvailable(available);
        return m;
    }

    private void link(Product p, ModifierGroup g) {
        ProductModifierGroup l = new ProductModifierGroup();
        l.setProduct(p);
        l.setGroup(g);
        when(productGroups.findByProductId(p.getId())).thenReturn(List.of(l));
    }

    private CartItem line(Product p, int qty, Modifier... mods) {
        CartItem ci = new CartItem();
        ci.setProduct(p);
        ci.setQuantity(qty);
        for (Modifier m : mods) {
            CartItemModifier cm = new CartItemModifier();
            cm.setCartItem(ci);
            cm.setModifier(m);
            ci.getModifiers().add(cm);
        }
        return ci;
    }

    private CheckoutRequest pickup() {
        return new CheckoutRequest("biz-1", null, FulfilmentOption.CUSTOMER_PICKUP, null, null);
    }

    private CheckoutRequest delivery() {
        return new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "12 Vilakazi St", "no onions");
    }

    // ------------------------------------------------------------------ pricing

    @Test
    void modifiersAreAddedToEachPortionAndPricedByTheBackend() {
        var priced = service.price(business, List.of(line(stew, 2, egg, atchar)), pickup());
        assertEquals(new BigDecimal("156.00"), priced.subtotal()); // (65 + 8 + 5) * 2
        assertEquals(BigDecimal.ZERO, priced.deliveryFee());
        assertEquals(new BigDecimal("156.00"), priced.total());
    }

    @Test
    void deliveryAddsTheRestaurantsFlatFee() {
        var priced = service.price(business, List.of(line(stew, 1)), delivery());
        assertEquals(new BigDecimal("65.00"), priced.subtotal());
        assertEquals(new BigDecimal("20.00"), priced.deliveryFee());
        assertEquals(new BigDecimal("85.00"), priced.total());
        assertEquals("12 Vilakazi St", priced.deliveryAddress());
        assertEquals("no onions", priced.note());
    }

    @Test
    void deliveryNeedsAnAddressAndAConfiguredFee() {
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, " ", null)));
        business.setDeliveryFee(null);
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), delivery()));
    }

    @Test
    void rideDeliveryNeedsAPickupAddress() {
        business.setLocation(" ");
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), delivery()));
        // a branch address is enough on its own
        BusinessBranch soweto = branch("br-1", "Soweto");
        soweto.setAddress("5 Main Rd");
        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of(soweto));
        var req = new CheckoutRequest("biz-1", "br-1", FulfilmentOption.MOMORIDE_DELIVERY, "addr", null);
        assertEquals(new BigDecimal("85.00"), service.price(business, List.of(line(stew, 1)), req).total());
    }

    @Test
    void rideDeliveryBelowThePlatformFloorIsRefused() {
        props.setMinPulaRideDeliveryFee(new BigDecimal("25"));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), delivery()));
        // collection is unaffected by the delivery floor
        assertEquals(new BigDecimal("65.00"), service.price(business, List.of(line(stew, 1)), pickup()).total());
    }

    @Test
    void fulfilmentMustBeChosenAndOffered() {
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", null, null, null, null)));
        business.setFulfilmentOptions(new java.util.LinkedHashSet<>(Set.of(FulfilmentOption.CUSTOMER_PICKUP)));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), delivery()));
    }

    @Test
    void closedOrInactiveBusinessCannotTakeOrders() {
        business.setTemporarilyClosed(true);
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), pickup()));
        business.setTemporarilyClosed(false);
        business.setActive(false);
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), pickup()));
    }

    @Test
    void minimumOrderIsEnforcedOnFoodSubtotalOnly() {
        business.setMinimumOrderAmount(new BigDecimal("70.00"));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), delivery()));
        assertEquals(new BigDecimal("73.00"),
                service.price(business, List.of(line(stew, 1, egg)), pickup()).subtotal());
    }

    @Test
    void inactiveItemIsRefused() {
        stew.setStatus(ProductStatus.OUT_OF_STOCK);
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), pickup()));
    }

    private CheckoutRequest deliveryExpecting(String expected) {
        return new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "12 Vilakazi St", null,
                expected == null ? null : new BigDecimal(expected));
    }

    @Test
    void foodCheckoutWithMissingExpectedTotalIsRejected() {
        var priced = service.price(business, List.of(line(stew, 1)), delivery()); // R85.00, no expectedTotal sent
        ApiException ex = assertThrows(ApiException.class,
                () -> service.requireExpectedTotal(priced, deliveryExpecting(null)));
        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void foodCheckoutWithMatchingExpectedTotalSucceeds() {
        var priced = service.price(business, List.of(line(stew, 1)), delivery()); // R85.00
        service.requireExpectedTotal(priced, deliveryExpecting("85.00")); // no exception
    }

    @Test
    void foodCheckoutWithDifferentExpectedTotalIsRejectedAsConflict() {
        var priced = service.price(business, List.of(line(stew, 1)), delivery()); // R85.00
        ApiException ex = assertThrows(ApiException.class,
                () -> service.requireExpectedTotal(priced, deliveryExpecting("70.00")));
        assertEquals(409, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("85.00"));
    }

    @Test
    void scaleOnlyDifferenceInExpectedTotalSucceeds() {
        var priced = service.price(business, List.of(line(stew, 1)), delivery()); // 85.00
        service.requireExpectedTotal(priced, deliveryExpecting("85")); // 85 vs 85.00 is the same amount
    }

    // ------------------------------------------------------------------ modifier rules

    @Test
    void modifierFromAnotherItemsGroupIsRejected() {
        ModifierGroup other = group("g-other", "Drinks", 0, 1);
        Modifier cola = modifier("m-cola", other, "Cola", "15.00", true);
        when(modifiers.findAllById(any())).thenReturn(List.of(cola));
        assertThrows(ApiException.class, () -> service.resolveModifiers(stew, List.of("m-cola")));
    }

    @Test
    void unavailableAndDuplicateModifiersAreRejected() {
        egg.setAvailable(false);
        assertThrows(ApiException.class, () -> service.resolveModifiers(stew, List.of("m-egg")));
        egg.setAvailable(true);
        assertThrows(ApiException.class, () -> service.resolveModifiers(stew, List.of("m-egg", "m-egg")));
    }

    @Test
    void groupMinimumAndMaximumAreEnforced() {
        extrasGroup.setMinSelect(1);
        assertThrows(ApiException.class, () -> service.resolveModifiers(stew, List.of())); // required, none chosen
        extrasGroup.setMinSelect(0);
        extrasGroup.setMaxSelect(1);
        assertThrows(ApiException.class, () -> service.resolveModifiers(stew, List.of("m-egg", "m-atchar")));
        assertEquals(1, service.resolveModifiers(stew, List.of("m-egg")).size());
    }

    @Test
    void modifierPriceChangesAfterAddToCartAreRepricedAtCheckout() {
        CartItem ci = line(stew, 1, egg);
        egg.setPriceDelta(new BigDecimal("10.00")); // merchant raised the price after the item was added
        assertEquals(new BigDecimal("75.00"), service.price(business, List.of(ci), pickup()).subtotal());
    }

    @Test
    void signatureIgnoresOrderAndIsEmptyForNoModifiers() {
        assertEquals("", FoodCheckoutService.signature(List.of()));
        assertEquals("", FoodCheckoutService.signature(null));
        assertEquals(FoodCheckoutService.signature(List.of("a", "b")), FoodCheckoutService.signature(List.of("b", "a")));
        assertEquals(64, FoodCheckoutService.signature(List.of("a")).length());
    }

    // ------------------------------------------------------------------ branches

    private BusinessBranch branch(String id, String name) {
        BusinessBranch b = new BusinessBranch();
        b.setId(id);
        b.setName(name);
        b.setBusiness(business);
        return b;
    }

    @Test
    void businessWithBranchesMustPickOneThatBelongsToIt() {
        BusinessBranch soweto = branch("br-1", "Soweto");
        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of(soweto));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), pickup()));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", "br-other", FulfilmentOption.CUSTOMER_PICKUP, null, null)));
        var priced = service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", "br-1", FulfilmentOption.CUSTOMER_PICKUP, null, null));
        assertEquals("br-1", priced.branch().getId());
    }

    @Test
    void singleLocationBusinessRejectsABranchId() {
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", "br-1", FulfilmentOption.CUSTOMER_PICKUP, null, null)));
    }

    @Test
    void closedBranchIsRefusedAndBranchOverridesApply() {
        BusinessBranch soweto = branch("br-1", "Soweto");
        soweto.setDeliveryFee(new BigDecimal("12.00"));
        soweto.setFulfilmentOptions(new java.util.LinkedHashSet<>(Set.of(FulfilmentOption.MOMORIDE_DELIVERY)));
        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of(soweto));
        BranchItemAvailability a = new BranchItemAvailability();
        a.setBranch(soweto);
        a.setProduct(stew);
        a.setPriceOverride(new BigDecimal("60.00"));
        when(availability.findByBranchIdAndProductId("br-1", "p-stew")).thenReturn(Optional.of(a));

        var req = new CheckoutRequest("biz-1", "br-1", FulfilmentOption.MOMORIDE_DELIVERY, "addr", null);
        var priced = service.price(business, List.of(line(stew, 1)), req);
        assertEquals(new BigDecimal("60.00"), priced.subtotal());   // branch price override
        assertEquals(new BigDecimal("12.00"), priced.deliveryFee()); // branch fee wins over business fee
        // the branch offers only delivery, so collection is refused even though the business offers it
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", "br-1", FulfilmentOption.CUSTOMER_PICKUP, null, null)));

        soweto.setTemporarilyClosed(true);
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)), req));
    }

    @Test
    void itemUnavailableAtTheChosenBranchIsRefused() {
        BusinessBranch soweto = branch("br-1", "Soweto");
        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of(soweto));
        BranchItemAvailability a = new BranchItemAvailability();
        a.setAvailable(false);
        when(availability.findByBranchIdAndProductId("br-1", "p-stew")).thenReturn(Optional.of(a));
        assertThrows(ApiException.class, () -> service.price(business, List.of(line(stew, 1)),
                new CheckoutRequest("biz-1", "br-1", FulfilmentOption.CUSTOMER_PICKUP, null, null)));
    }

    // ------------------------------------------------------------------ capacity

    @Test
    void cappedItemsAreClaimedAtomicallyAndRefusedWhenShort() {
        ProductFoodDetails d = new ProductFoodDetails();
        d.setDailyQuantity(5);
        when(details.findByProductId("p-stew")).thenReturn(Optional.of(d));
        var priced = service.price(business, List.of(line(stew, 2, egg), line(stew, 1)), pickup());

        when(details.claimPortions("p-stew", 3)).thenReturn(1);
        var claims = service.claimCapacity(priced);
        assertEquals(1, claims.size());
        assertEquals(3, claims.get(0).quantity()); // both lines of the same dish are claimed together

        when(details.claimPortions("p-stew", 3)).thenReturn(0);
        assertThrows(ApiException.class, () -> service.claimCapacity(priced));
    }

    @Test
    void uncappedItemsAreNeverClaimed() {
        when(details.findByProductId("p-stew")).thenReturn(Optional.empty());
        var priced = service.price(business, List.of(line(stew, 4)), pickup());
        assertTrue(service.claimCapacity(priced).isEmpty());
        verify(details, never()).claimPortions(anyString(), anyInt());
    }

    @Test
    void releasingClaimsGivesPortionsBack() {
        service.releaseClaims(List.of(new FoodCheckoutService.Claim("p-stew", 3)));
        verify(details).releasePortions("p-stew", 3);
    }

    // ------------------------------------------------------------------ order assembly

    @Test
    void orderIsBuiltWithModifierSnapshotsAndAwaitsTheRestaurant() {
        var priced = service.price(business, List.of(line(stew, 2, egg)), delivery());
        Order order = new Order();
        service.applyToOrder(order, priced);

        assertEquals(OrderKind.FOOD, order.getKind());
        assertEquals(RestaurantDecision.PENDING, order.getRestaurantDecision());
        assertEquals(FulfilmentOption.MOMORIDE_DELIVERY, order.getFulfilmentMethod());
        assertEquals(new BigDecimal("20.00"), order.getDeliveryFeeSnapshot());
        assertEquals(new BigDecimal("166.00"), order.getTotal()); // 2 * (65 + 8) + 20
        assertEquals(1, order.getItems().size());
        assertEquals(new BigDecimal("65.00"), order.getItems().get(0).getUnitPriceSnapshot());
        assertEquals(new BigDecimal("146.00"), order.getItems().get(0).getLineTotal());
        assertEquals("Egg", order.getItems().get(0).getModifiers().get(0).getNameSnapshot());
        assertEquals(new BigDecimal("8.00"), order.getItems().get(0).getModifiers().get(0).getPriceDeltaSnapshot());
    }

    @Test
    void collectionOrdersHaveNoDeliveryFeeSnapshot() {
        Order order = new Order();
        service.applyToOrder(order, service.price(business, List.of(line(stew, 1)), pickup()));
        assertNull(order.getDeliveryFeeSnapshot());
        assertNull(order.getDeliveryAddress());
    }
}
