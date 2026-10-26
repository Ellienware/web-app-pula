package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.CartItem;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.dto.MarketDtos.CheckoutRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.MockPaystackGateway;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentOrchestrator;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves BOTH food checkout paths (legacy/synchronous {@link OrderService#checkout} and
 * {@link ConsumerPaystackCheckoutService#start}) enforce the mandatory expected total BEFORE any
 * portions are claimed, any payment is reserved or charged, or any order is saved. The rule itself
 * is unit-tested in FoodCheckoutServiceTest; this class guards the wiring so a path cannot skip it.
 *
 * NOT VERIFIED IN THIS SANDBOX (no Maven Central access) — run locally with ./mvnw test.
 */
class FoodExpectedTotalCheckoutWiringTest {

    private OrderRepository orders;
    private PaymentRepository payments;
    private CartService cart;
    private BusinessService businessService;
    private FoodCheckoutService foodCheckout;
    private PaymentIdempotencyService idem;
    private PaymentProvider provider;
    private OrderService orderService;
    private ConsumerPaystackCheckoutService paystackService;

    private User buyer;
    private Business business;
    private CartItem item;
    private FoodCheckoutService.PricedFoodOrder priced;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        cart = mock(CartService.class);
        businessService = mock(BusinessService.class);
        foodCheckout = mock(FoodCheckoutService.class);
        idem = mock(PaymentIdempotencyService.class);
        provider = mock(PaymentProvider.class);

        orderService = new OrderService(orders, payments, cart, businessService, mock(ProductService.class),
                provider, idem, mock(NotificationService.class), mock(ReceiptService.class), foodCheckout,
                new FoodProperties(), mock(OrderRefundService.class), mock(FoodDeliveryService.class),
                mock(FoodSettlementService.class));
        paystackService = new ConsumerPaystackCheckoutService(orders, payments, cart, businessService,
                mock(ProductService.class), idem, new PaymentOrchestrator(new MockPaystackGateway(), provider),
                foodCheckout, "");

        buyer = new User();
        buyer.setId("buyer-1");
        business = new Business();
        business.setId("biz-1");
        business.setName("Gogo's Kitchen");
        business.setOwner(new User());
        business.setBusinessType(BusinessType.HOME_KITCHEN);
        when(businessService.requireById("biz-1")).thenReturn(business);

        Product p = new Product();
        p.setId("p1");
        p.setName("Beef Stew + Pap");
        p.setPrice(new BigDecimal("65.00"));
        p.setCurrency("ZAR");
        item = new CartItem();
        item.setProduct(p);
        item.setQuantity(1);
        when(cart.itemsForBusiness(buyer, "biz-1")).thenReturn(List.of(item));

        priced = new FoodCheckoutService.PricedFoodOrder(List.of(), new BigDecimal("65.00"), new BigDecimal("20.00"),
                new BigDecimal("85.00"), null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd", null, "ZAR");
        when(foodCheckout.price(any(), any(), any())).thenReturn(priced);
    }

    private CheckoutRequest requestWithoutExpectedTotal() {
        return new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd", null, null);
    }

    private void assertNothingWasClaimedReservedChargedOrSaved() {
        verify(foodCheckout, never()).claimCapacity(any());
        verify(idem, never()).reserve(anyString(), any(), anyString(), anyString());
        verify(provider, never()).charge(any());
        verify(orders, never()).save(any());
    }

    @Test
    void legacyCheckoutRefusesFoodWithoutExpectedTotal() {
        CheckoutRequest req = requestWithoutExpectedTotal();
        doThrow(ApiException.badRequest("Please review the order total before paying."))
                .when(foodCheckout).requireExpectedTotal(priced, req);

        ApiException ex = assertThrows(ApiException.class, () -> orderService.checkout(buyer, req, "key-1"));

        assertEquals(400, ex.getStatus().value());
        verify(foodCheckout).requireExpectedTotal(priced, req);
        assertNothingWasClaimedReservedChargedOrSaved();
    }

    @Test
    void legacyCheckoutRefusesFoodWhenTheReviewedTotalDiffers() {
        CheckoutRequest req = new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd",
                null, new BigDecimal("70.00"));
        doThrow(ApiException.conflict("The total has changed")).when(foodCheckout).requireExpectedTotal(priced, req);

        ApiException ex = assertThrows(ApiException.class, () -> orderService.checkout(buyer, req, "key-1"));

        assertEquals(409, ex.getStatus().value());
        assertNothingWasClaimedReservedChargedOrSaved();
    }

    @Test
    void paystackCheckoutRefusesFoodWithoutExpectedTotal() {
        CheckoutRequest req = requestWithoutExpectedTotal();
        doThrow(ApiException.badRequest("Please review the order total before paying."))
                .when(foodCheckout).requireExpectedTotal(priced, req);

        ApiException ex = assertThrows(ApiException.class, () -> paystackService.start(buyer, req, "key-1"));

        assertEquals(400, ex.getStatus().value());
        verify(foodCheckout).requireExpectedTotal(priced, req);
        assertNothingWasClaimedReservedChargedOrSaved();
    }

    @Test
    void paystackCheckoutRefusesFoodWhenTheReviewedTotalDiffers() {
        CheckoutRequest req = new CheckoutRequest("biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd",
                null, new BigDecimal("70.00"));
        doThrow(ApiException.conflict("The total has changed")).when(foodCheckout).requireExpectedTotal(priced, req);

        ApiException ex = assertThrows(ApiException.class, () -> paystackService.start(buyer, req, "key-1"));

        assertEquals(409, ex.getStatus().value());
        assertNothingWasClaimedReservedChargedOrSaved();
    }

    @Test
    void nonFoodCheckoutNeverConsultsTheFoodExpectedTotalRule() {
        business.setBusinessType(BusinessType.FORMAL_RETAILER);
        CheckoutRequest retail = new CheckoutRequest("biz-1"); // no expectedTotal, no food fields
        // The collaborators are mocks, so each path may fail later on an unstubbed call. That is
        // irrelevant here: the assertion is that the food rule was never reached for a retail order.
        try { orderService.checkout(buyer, retail, "key-1"); } catch (RuntimeException ignored) { }
        try { paystackService.start(buyer, retail, "key-1"); } catch (RuntimeException ignored) { }
        verify(foodCheckout, never()).price(any(), any(), any());
        verify(foodCheckout, never()).requireExpectedTotal(any(), any());
    }
}
