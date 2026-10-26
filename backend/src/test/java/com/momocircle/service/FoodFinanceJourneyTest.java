package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.momocircle.config.FoodProperties;
import com.momocircle.domain.Order;
import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.MarketDtos.UpdateOrderStatusRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.PaymentIdempotencyService;
import com.momocircle.payment.PaymentProvider;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end money journeys through the REAL OrderService, OrderRefundService and FoodSettlementService
 * (only Paystack, the payout provider, delivery dispatch, cart and notifications are faked).
 */
class FoodFinanceJourneyTest {

    private FoodFinanceFixture f;
    private OrderService orders;
    private BusinessService businessService;
    private FoodDeliveryService foodDelivery;

    @BeforeEach
    void setUp() {
        f = new FoodFinanceFixture();
        businessService = mock(BusinessService.class);
        foodDelivery = mock(FoodDeliveryService.class);
        orders = new OrderService(f.orderRepo, f.paymentRepo, mock(CartService.class), businessService, mock(ProductService.class),
                mock(PaymentProvider.class), mock(PaymentIdempotencyService.class), f.notifications, mock(ReceiptService.class),
                mock(FoodCheckoutService.class), new FoodProperties(), f.refundService, foodDelivery, f.settlements);
    }

    /** The customer's Paystack payment has just succeeded (the same hook checkout / reconciliation call). */
    private Order paid(String id) {
        Order o = f.paidFoodOrder(id, true);
        o.setStatus(OrderStatus.PAYMENT_PROCESSING);
        orders.applyResolvedSuccess(o, o.getPayment());
        return o;
    }

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private void runMaintenancePass() {
        for (String id : f.refundService.orderIdsAwaitingRefund()) f.refundService.attemptRefundById(id);
        for (String id : f.refundService.inFlightRefundIds()) f.refundService.advanceById(id);
        for (FoodSettlementService.DuePayout due : f.settlements.dueForPayout()) f.settlements.pay(due.settlementId(), due.stuck());
    }

    // ------------------------------------------------------------------ 1. payment -> restaurant -> delivery -> completion -> settlement -> payout

    @Test
    void successfulDeliveryJourneyEndsInOneRestaurantPayoutAndNoRefund() {
        Order o = paid("o1");
        assertEquals(SettlementStatus.NOT_ELIGIBLE, f.settlementOf("o1").getStatus(), "payment alone pays nobody");

        orders.acceptFoodOrder(f.owner, "o1");
        assertEquals(SettlementStatus.NOT_ELIGIBLE, f.settlementOf("o1").getStatus());

        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        assertNotNull(o.getReadyAt(), "the time the food was prepared is recorded");
        verify(foodDelivery).dispatchIfEligible(o);
        runMaintenancePass();
        assertTrue(f.payoutRequests.isEmpty(), "nothing is paid while READY / out for delivery");

        orders.markCompletedByDelivery(o);

        assertEquals(OrderStatus.COMPLETED, o.getStatus());
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());

        runMaintenancePass();
        runMaintenancePass(); // a second pass must not pay again

        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(1, f.payoutRequests.size());
        assertEquals(d("100.00"), f.payoutRequests.get(0).amount(), "R100 food; the R20 delivery fee belongs to the driver/PULA split");
        assertTrue(f.refunds.isEmpty());
        assertEquals(1, f.settlementsByOrder.size());
    }

    @Test
    void aCollectionOrderBecomesEligibleWhenTheRestaurantMarksItCompleted() {
        Order o = paid("o1");
        o.setFulfilmentMethod(FulfilmentOption.CUSTOMER_PICKUP);
        orders.acceptFoodOrder(f.owner, "o1");
        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.COMPLETED));
        assertEquals(SettlementStatus.ELIGIBLE, f.settlementOf("o1").getStatus());
        runMaintenancePass();
        assertEquals(SettlementStatus.PAID, f.settlementOf("o1").getStatus());
    }

    // ------------------------------------------------------------------ 2. rejection / expiry -> Paystack refund -> settlement cancelled

    @Test
    void restaurantRejectionStartsAPaystackRefundCancelsTheSettlementAndPaysNobody() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = paid("o1");

        orders.rejectFoodOrder(f.owner, "o1", "Out of stock");

        assertEquals(CancellationCause.RESTAURANT_REJECTED, o.getCancellationCause());
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        PaymentRefund r = f.refunds.values().iterator().next();
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus(), "not REFUNDED until Paystack confirms");
        assertEquals(PaymentStatus.SUCCESSFUL, o.getPayment().getStatus());

        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());
        runMaintenancePass();

        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(PaymentStatus.REFUNDED, o.getPayment().getStatus());
        assertTrue(f.payoutRequests.isEmpty());
        assertEquals(d("120.00"), r.getAmount(), "the customer gets everything back, delivery fee included");
    }

    @Test
    void restaurantTimeoutRefundsTheCustomerAndCancelsTheSettlement() {
        Order o = paid("o1");
        o.setAcceptDeadlineAt(Instant.now().minus(Duration.ofMinutes(1)));

        orders.autoRejectIfExpired("o1");
        runMaintenancePass();

        assertEquals(CancellationCause.RESTAURANT_EXPIRED, o.getCancellationCause());
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void aCustomerCancellingBeforeTheRestaurantDecidesIsRefundedWithoutAnyCompensation() {
        Order o = paid("o1");
        orders.cancelFoodOrderByCustomer(f.buyer, "o1");
        runMaintenancePass();
        assertEquals(CancellationCause.CUSTOMER_CANCELLED, o.getCancellationCause());
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertEquals(SettlementType.SALE, f.settlementOf("o1").getType());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertTrue(f.payoutRequests.isEmpty());
    }

    // ------------------------------------------------------------------ 3. prepared, no driver -> refund + merchant protection

    @Test
    void preparedFoodWithNoDriverRefundsTheCustomerAndCompensatesTheRestaurantAndNoDriverIsPaid() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = paid("o1");
        orders.acceptFoodOrder(f.owner, "o1");
        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));

        orders.cancelFoodOrderByCustomer(f.buyer, "o1"); // allowed only after the no-driver timeout (verified by FoodDeliveryService)

        verify(foodDelivery).cancelUnclaimedDeliveryForCustomer(o);
        assertEquals(CancellationCause.PULA_DELIVERY_FAILURE, o.getCancellationCause());
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementType.MERCHANT_PROTECTION, s.getType());
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertEquals(d("100.00"), s.getNetAmount());
        assertEquals(d("100.00"), s.getPulaFulfilmentLoss());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus(), "refund still processing at Paystack");

        PaymentRefund r = f.refunds.values().iterator().next();
        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());
        runMaintenancePass();
        runMaintenancePass();

        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(d("120.00"), r.getAmount());
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals(1, f.payoutRequests.size());
        assertEquals(d("100.00"), f.payoutRequests.get(0).amount());
        assertTrue(f.payoutRequests.get(0).idempotencyKey().startsWith("payout:settlement:"));
        assertTrue(f.payoutRequests.stream().noneMatch(p -> p.idempotencyKey().startsWith("payout:delivery:")),
                "driver payout is zero: no delivery was completed");
    }

    @Test
    void theCompensationSurvivesAFailedCustomerRefundAndIsStillPaidExactlyOnce() {
        com.momocircle.payment.PaystackGateway g = mock(com.momocircle.payment.PaystackGateway.class);
        when(g.createRefund(any())).thenThrow(new com.momocircle.payment.PaystackGateway.RefundRejectedException("refused"));
        f.gateway = g; f.build();
        orders = new OrderService(f.orderRepo, f.paymentRepo, mock(CartService.class), businessService, mock(ProductService.class),
                mock(PaymentProvider.class), mock(PaymentIdempotencyService.class), f.notifications, mock(ReceiptService.class),
                mock(FoodCheckoutService.class), new FoodProperties(), f.refundService, foodDelivery, f.settlements);
        Order o = paid("o1");
        orders.acceptFoodOrder(f.owner, "o1");
        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        orders.cancelFoodOrderByCustomer(f.buyer, "o1");
        runMaintenancePass();
        runMaintenancePass();

        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus(), "customer refund failed: an admin must retry it");
        assertEquals(RefundStatus.FAILED, f.refunds.values().iterator().next().getStatus());
        assertEquals(SettlementStatus.PAID, f.settlementOf("o1").getStatus());
        assertEquals(1, f.payoutRequests.size());
    }

    // ------------------------------------------------------------------ payment failure / retries / limits

    @Test
    void aFailedPaymentCreatesNoPaidOrderAndNoSettlement() {
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.PAYMENT_PROCESSING);
        orders.applyResolvedFailure(o);
        assertEquals(OrderStatus.CANCELLED, o.getStatus());
        assertEquals(CancellationCause.PAYMENT_FAILURE, o.getCancellationCause());
        assertTrue(f.settlementsByOrder.isEmpty());
        assertTrue(f.refunds.isEmpty());
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void paymentSuccessReportedTwiceStillMakesOneSettlement() {
        Order o = paid("o1");
        orders.applyResolvedSuccess(o, o.getPayment());
        orders.applyResolvedSuccess(o, o.getPayment());
        assertEquals(1, f.settlementsByOrder.size());
    }

    @Test
    void aRestaurantAlreadyPaidCannotBeSilentlyRefundedByAnAdmin() {
        Order o = paid("o1");
        orders.acceptFoodOrder(f.owner, "o1");
        orders.updateStatus(f.owner, "o1", new UpdateOrderStatusRequest(OrderStatus.READY));
        orders.markCompletedByDelivery(o);
        runMaintenancePass();
        assertEquals(SettlementStatus.PAID, f.settlementOf("o1").getStatus());
        assertThrows(ApiException.class, () -> f.refundService.adminFullRefund(o, "chargeback"));
        assertEquals(OrderStatus.COMPLETED, o.getStatus(), "the refused refund changed nothing");
        assertTrue(f.refunds.isEmpty());
    }
}
