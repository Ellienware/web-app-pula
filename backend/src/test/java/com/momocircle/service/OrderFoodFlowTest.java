package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.RestaurantDecision;
import com.momocircle.dto.MarketDtos.OrderResponse;
import com.momocircle.dto.MarketDtos.SetOrderFulfilmentRequest;
import com.momocircle.dto.MarketDtos.UpdateOrderStatusRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentProvider;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers the PULA Food restaurant decision
 * flow in OrderService (accept / reject / timeout) and the guards that stop the retail endpoints
 * from bypassing it, plus a retail regression check.
 */
class OrderFoodFlowTest {

    private OrderRepository orders;
    private BusinessService businessService;
    private FoodCheckoutService foodCheckout;
    private OrderRefundService refunds;
    private FoodDeliveryService foodDelivery;
    private FoodSettlementService settlements;
    private NotificationService notifications;
    private CartService cartService;
    private OrderService service;

    private User seller;
    private Business business;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        businessService = mock(BusinessService.class);
        foodCheckout = mock(FoodCheckoutService.class);
        refunds = mock(OrderRefundService.class);
        foodDelivery = mock(FoodDeliveryService.class);
        settlements = mock(FoodSettlementService.class);
        notifications = mock(NotificationService.class);
        cartService = mock(CartService.class);
        service = new OrderService(orders, mock(PaymentRepository.class), cartService, businessService,
                mock(ProductService.class), mock(PaymentProvider.class), mock(PaymentIdempotencyService.class),
                notifications, mock(ReceiptService.class), foodCheckout, new FoodProperties(), refunds, foodDelivery, settlements);

        seller = new User();
        seller.setId("seller-1");
        business = new Business();
        business.setId("biz-1");
        business.setName("Gogo's Kitchen");
        business.setOwner(seller);
        business.setBusinessType(BusinessType.HOME_KITCHEN);
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Order order(String id, OrderKind kind, OrderStatus status, RestaurantDecision decision) {
        Order o = new Order();
        o.setId(id);
        User buyer = new User();
        buyer.setId("buyer-1");
        buyer.setFullName("Thandi");
        o.setBuyer(buyer);
        o.setBusiness(business);
        o.setKind(kind);
        o.setStatus(status);
        o.setRestaurantDecision(decision);
        o.setSubtotal(new BigDecimal("65.00"));
        o.setTotal(new BigDecimal("85.00"));
        o.setCurrency("ZAR");
        o.setFulfilmentMethod(FulfilmentOption.MOMORIDE_DELIVERY);
        return o;
    }

    private Order foodPaid(String id) {
        Order o = order(id, OrderKind.FOOD, OrderStatus.PAID, RestaurantDecision.PENDING);
        o.setAcceptDeadlineAt(Instant.now().plus(Duration.ofMinutes(5)));
        return o;
    }

    private void found(Order o) {
        when(orders.findByIdForUpdate(o.getId())).thenReturn(Optional.of(o));
        when(orders.findById(o.getId())).thenReturn(Optional.of(o));
    }

    // ------------------------------------------------------------------ accept

    @Test
    void acceptMovesAPaidFoodOrderToPreparing() {
        Order o = foodPaid("o1");
        found(o);
        OrderResponse r = service.acceptFoodOrder(seller, "o1");
        assertEquals(OrderStatus.PROCESSING, r.status());
        assertEquals(RestaurantDecision.ACCEPTED, r.restaurantDecision());
        verify(notifications).notifyOrderStatusChanged(o);
    }

    @Test
    void acceptingTwiceIsIdempotent() {
        Order o = foodPaid("o1");
        found(o);
        service.acceptFoodOrder(seller, "o1");
        service.acceptFoodOrder(seller, "o1");
        verify(notifications, times(1)).notifyOrderStatusChanged(o);
    }

    @Test
    void onlyTheOwnerCanDecide() {
        Order o = foodPaid("o1");
        found(o);
        doThrow(ApiException.forbidden("not yours")).when(businessService).requireOwnership(business, seller);
        assertThrows(ApiException.class, () -> service.acceptFoodOrder(seller, "o1"));
        assertThrows(ApiException.class, () -> service.rejectFoodOrder(seller, "o1", null));
        assertEquals(OrderStatus.PAID, o.getStatus());
        verify(orders, never()).save(any());
    }

    @Test
    void retailOrdersCannotBeAcceptedOrRejected() {
        Order o = order("o1", OrderKind.RETAIL, OrderStatus.PAID, null);
        found(o);
        assertThrows(ApiException.class, () -> service.acceptFoodOrder(seller, "o1"));
        assertThrows(ApiException.class, () -> service.rejectFoodOrder(seller, "o1", null));
    }

    @Test
    void unpaidFoodOrderCannotBeAccepted() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PAYMENT_PROCESSING, RestaurantDecision.PENDING);
        found(o);
        assertThrows(ApiException.class, () -> service.acceptFoodOrder(seller, "o1"));
    }

    // ------------------------------------------------------------------ reject

    @Test
    void rejectReleasesPortionsStartsTheRefundAndKeepsTheReason() {
        Order o = foodPaid("o1");
        found(o);
        OrderResponse r = service.rejectFoodOrder(seller, "o1", "  Out of beef  ");
        assertEquals(OrderStatus.REFUND_PENDING, r.status());
        assertEquals(RestaurantDecision.REJECTED, r.restaurantDecision());
        assertEquals("Out of beef", r.rejectionReason());
        verify(foodCheckout).releaseCapacity(o);
        verify(refunds).attemptRefund(o);
    }

    @Test
    void rejectWithoutAReasonUsesADefaultAndIsIdempotent() {
        Order o = foodPaid("o1");
        found(o);
        service.rejectFoodOrder(seller, "o1", null);
        assertEquals("The restaurant could not accept this order", o.getRejectionReason());
        service.rejectFoodOrder(seller, "o1", "again");
        verify(foodCheckout, times(1)).releaseCapacity(o);
        verify(refunds, times(1)).attemptRefund(o);
    }

    @Test
    void cannotRejectAnOrderAlreadyAccepted() {
        Order o = foodPaid("o1");
        found(o);
        service.acceptFoodOrder(seller, "o1");
        assertThrows(ApiException.class, () -> service.rejectFoodOrder(seller, "o1", null));
        verify(refunds, never()).attemptRefund(any());
    }

    // ------------------------------------------------------------------ timeout

    @Test
    void expiredUnansweredOrderIsRejectedAndRefunded() {
        Order o = foodPaid("o1");
        o.setAcceptDeadlineAt(Instant.now().minusSeconds(5));
        found(o);
        service.autoRejectIfExpired("o1");
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
        assertEquals(RestaurantDecision.REJECTED, o.getRestaurantDecision());
        assertEquals("The restaurant did not respond in time", o.getRejectionReason());
        verify(foodCheckout).releaseCapacity(o);
        verify(refunds).attemptRefund(o);
    }

    @Test
    void timeoutDoesNothingWhenNotYetDueOrAlreadyDecided() {
        Order notDue = foodPaid("o1");
        found(notDue);
        service.autoRejectIfExpired("o1");
        assertEquals(OrderStatus.PAID, notDue.getStatus());

        Order accepted = foodPaid("o2");
        accepted.setAcceptDeadlineAt(Instant.now().minusSeconds(5));
        found(accepted);
        service.acceptFoodOrder(seller, "o2"); // accept wins the race
        service.autoRejectIfExpired("o2");
        assertEquals(OrderStatus.PROCESSING, accepted.getStatus());
        verify(refunds, never()).attemptRefund(any());
    }

    @Test
    void timeoutIgnoresUnknownOrders() {
        when(orders.findByIdForUpdate("missing")).thenReturn(Optional.empty());
        service.autoRejectIfExpired("missing"); // must not throw
        verify(orders, never()).save(any());
    }

    // ------------------------------------------------------------------ guards on the retail endpoints

    @Test
    void foodOrderCannotSkipTheDecisionViaStatusPatch() {
        Order o = foodPaid("o1");
        found(o);
        assertThrows(ApiException.class,
                () -> service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.PROCESSING)));
        assertEquals(OrderStatus.PAID, o.getStatus());
    }

    @Test
    void foodOrderCannotBeRefundedByStatusPatch() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PROCESSING, RestaurantDecision.ACCEPTED);
        found(o);
        assertThrows(ApiException.class,
                () -> service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.REFUND_PENDING)));
    }

    @Test
    void sellerCannotCompleteAPulaRideDeliveryByHand() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.READY, RestaurantDecision.ACCEPTED);
        found(o);
        assertThrows(ApiException.class,
                () -> service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.COMPLETED)));
        assertEquals(OrderStatus.READY, o.getStatus());
    }

    @Test
    void sellerCanPrepareAndCompleteACollectionOrder() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PROCESSING, RestaurantDecision.ACCEPTED);
        o.setFulfilmentMethod(FulfilmentOption.CUSTOMER_PICKUP);
        found(o);
        service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.COMPLETED));
        assertEquals(OrderStatus.COMPLETED, o.getStatus());
    }

    @Test
    void customersFulfilmentChoiceCannotBeChangedBySeller() {
        Order o = foodPaid("o1");
        found(o);
        assertThrows(ApiException.class, () -> service.setFulfilment(seller, "o1",
                new SetOrderFulfilmentRequest(FulfilmentOption.CUSTOMER_PICKUP, null)));
        assertEquals(FulfilmentOption.MOMORIDE_DELIVERY, o.getFulfilmentMethod());
    }

    // ------------------------------------------------------------------ retail regression

    @Test
    void retailOrdersStillFollowTheOriginalStatusFlow() {
        Order o = order("r1", OrderKind.RETAIL, OrderStatus.PAID, null);
        found(o);
        service.updateStatus(seller, "r1", new UpdateOrderStatusRequest(OrderStatus.PROCESSING));
        service.updateStatus(seller, "r1", new UpdateOrderStatusRequest(OrderStatus.READY));
        service.updateStatus(seller, "r1", new UpdateOrderStatusRequest(OrderStatus.COMPLETED));
        assertEquals(OrderStatus.COMPLETED, o.getStatus());
    }

    @Test
    void retailSellerCanStillSetFulfilment() {
        business.setFulfilmentOptions(new java.util.LinkedHashSet<>(java.util.Set.of(FulfilmentOption.CUSTOMER_PICKUP)));
        Order o = order("r1", OrderKind.RETAIL, OrderStatus.PAID, null);
        found(o);
        service.setFulfilment(seller, "r1", new SetOrderFulfilmentRequest(FulfilmentOption.CUSTOMER_PICKUP, null));
        assertEquals(FulfilmentOption.CUSTOMER_PICKUP, o.getFulfilmentMethod());
    }

    // ------------------------------------------------------------------ automatic dispatch hook

    @Test
    void markingAFoodOrderReadyStartsDispatchInTheSameCall() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PROCESSING, RestaurantDecision.ACCEPTED);
        found(o);
        service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        assertEquals(OrderStatus.READY, o.getStatus());
        verify(foodDelivery).dispatchIfEligible(o);
    }

    @Test
    void otherStatusChangesDoNotDispatch() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.READY, RestaurantDecision.ACCEPTED);
        o.setFulfilmentMethod(FulfilmentOption.CUSTOMER_PICKUP);
        found(o);
        service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.COMPLETED));
        verify(foodDelivery, never()).dispatchIfEligible(any());
    }

    @Test
    void ifDispatchFailsTheReadyChangeFailsWithIt() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PROCESSING, RestaurantDecision.ACCEPTED);
        found(o);
        when(foodDelivery.dispatchIfEligible(o)).thenThrow(ApiException.badRequest("no pickup address"));
        assertThrows(ApiException.class,
                () -> service.updateStatus(seller, "o1", new UpdateOrderStatusRequest(OrderStatus.READY)));
        verify(notifications, never()).notifyOrderStatusChanged(any());
    }

    // ------------------------------------------------------------------ customer cancel

    private User buyerUser() {
        User b = new User();
        b.setId("buyer-1");
        return b;
    }

    @Test
    void customerCanCancelBeforeTheRestaurantDecidesAndGetsAFullRefund() {
        Order o = foodPaid("o1");
        found(o);
        OrderResponse r = service.cancelFoodOrderByCustomer(buyerUser(), "o1");
        assertEquals(OrderStatus.REFUND_PENDING, r.status());
        assertEquals(RestaurantDecision.WITHDRAWN, r.restaurantDecision());
        verify(foodCheckout).releaseCapacity(o);
        verify(refunds).attemptRefund(o);
    }

    @Test
    void customerCancelIsIdempotent() {
        Order o = foodPaid("o1");
        found(o);
        service.cancelFoodOrderByCustomer(buyerUser(), "o1");
        service.cancelFoodOrderByCustomer(buyerUser(), "o1");
        verify(refunds, times(1)).attemptRefund(o);
    }

    @Test
    void anotherCustomerCannotCancelMyOrder() {
        Order o = foodPaid("o1");
        found(o);
        User stranger = new User();
        stranger.setId("someone-else");
        assertThrows(ApiException.class, () -> service.cancelFoodOrderByCustomer(stranger, "o1"));
        assertEquals(OrderStatus.PAID, o.getStatus());
    }

    @Test
    void customerCannotCancelWhileTheKitchenIsPreparing() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.PROCESSING, RestaurantDecision.ACCEPTED);
        found(o);
        assertThrows(ApiException.class, () -> service.cancelFoodOrderByCustomer(buyerUser(), "o1"));
        verify(refunds, never()).attemptRefund(any());
    }

    @Test
    void readyDeliveryWithNoDriverCanBeCancelledAfterTheTimeoutAndPortionsStayUsed() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.READY, RestaurantDecision.ACCEPTED);
        found(o);
        OrderResponse r = service.cancelFoodOrderByCustomer(buyerUser(), "o1");
        assertEquals(OrderStatus.REFUND_PENDING, r.status());
        verify(foodDelivery).cancelUnclaimedDeliveryForCustomer(o);
        verify(foodCheckout, never()).releaseCapacity(any()); // the food was already made
        verify(refunds).attemptRefund(o);
    }

    @Test
    void readyDeliveryCancelIsRefusedWhileTheDeliveryRuleSaysNo() {
        Order o = order("o1", OrderKind.FOOD, OrderStatus.READY, RestaurantDecision.ACCEPTED);
        found(o);
        org.mockito.Mockito.doThrow(ApiException.badRequest("still looking")).when(foodDelivery).cancelUnclaimedDeliveryForCustomer(o);
        assertThrows(ApiException.class, () -> service.cancelFoodOrderByCustomer(buyerUser(), "o1"));
        assertEquals(OrderStatus.READY, o.getStatus());
        verify(refunds, never()).attemptRefund(any());
    }

    @Test
    void retailOrdersCannotUseTheFoodCancel() {
        Order o = order("r1", OrderKind.RETAIL, OrderStatus.PAID, null);
        found(o);
        assertThrows(ApiException.class, () -> service.cancelFoodOrderByCustomer(buyerUser(), "r1"));
    }

    // ------------------------------------------------------------------ checkout quote

    @Test
    void quoteReturnsTheBackendPricingWithoutChargingOrClaimingAnything() {
        User me = buyerUser(); // User has no equals(), so stubs and calls must share one instance
        com.momocircle.dto.MarketDtos.CheckoutRequest req = new com.momocircle.dto.MarketDtos.CheckoutRequest(
                "biz-1", null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd", null);
        when(businessService.requireById("biz-1")).thenReturn(business);
        com.momocircle.domain.CartItem ci = new com.momocircle.domain.CartItem();
        when(cartService.itemsForBusiness(me, "biz-1")).thenReturn(java.util.List.of(ci));
        com.momocircle.domain.Product p = new com.momocircle.domain.Product();
        p.setId("p1");
        p.setName("Beef Stew + Pap");
        var line = new FoodCheckoutService.PricedLine(ci, p, new BigDecimal("65.00"), java.util.List.of(),
                BigDecimal.ZERO, 2, new BigDecimal("130.00"));
        when(foodCheckout.price(business, java.util.List.of(ci), req)).thenReturn(new FoodCheckoutService.PricedFoodOrder(
                java.util.List.of(line), new BigDecimal("130.00"), new BigDecimal("20.00"), new BigDecimal("150.00"),
                null, FulfilmentOption.MOMORIDE_DELIVERY, "5 Main Rd", null, "ZAR"));

        var q = service.quoteFoodCheckout(me, req);

        assertEquals(new BigDecimal("150.00"), q.total());
        assertEquals(new BigDecimal("20.00"), q.deliveryFee());
        assertEquals(1, q.lines().size());
        assertEquals("Beef Stew + Pap", q.lines().get(0).name());
        verify(foodCheckout, never()).claimCapacity(any());
        verify(orders, never()).save(any());
    }

    @Test
    void quoteIsRefusedForRetailBusinessesAndEmptyCarts() {
        User me = buyerUser();
        business.setBusinessType(BusinessType.FORMAL_RETAILER);
        when(businessService.requireById("biz-1")).thenReturn(business);
        var req = new com.momocircle.dto.MarketDtos.CheckoutRequest("biz-1");
        assertThrows(ApiException.class, () -> service.quoteFoodCheckout(me, req));

        business.setBusinessType(BusinessType.HOME_KITCHEN);
        when(cartService.itemsForBusiness(me, "biz-1")).thenReturn(java.util.List.of());
        assertThrows(ApiException.class, () -> service.quoteFoodCheckout(me, req));
    }
}
