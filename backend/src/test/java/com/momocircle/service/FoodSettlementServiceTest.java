package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.momocircle.domain.Order;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.domain.enums.SettlementType;
import com.momocircle.exception.ApiException;
import com.momocircle.payout.PayoutProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FoodSettlementServiceTest {

    private FoodFinanceFixture f;

    @BeforeEach
    void setUp() { f = new FoodFinanceFixture(); }

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private Order completed(String id) {
        Order o = f.paidFoodOrder(id, true);
        f.settlements.openForPaidOrder(o);
        f.accepted(o);
        f.ready(o);
        o.setStatus(OrderStatus.COMPLETED);
        f.settlements.onOrderCompleted(o);
        return o;
    }

    // ------------------------------------------------------------------ settlement lifecycle

    @Test
    void aPaidOrderHasANotEligibleSettlementAndNothingIsPayableYet() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementStatus.NOT_ELIGIBLE, s.getStatus());
        assertEquals(SettlementType.SALE, s.getType());
        assertTrue(f.settlements.dueForPayout().isEmpty());
        assertFalse(f.settlements.pay(s.getId(), false), "cannot be paid before the order completes");
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void notPaidWhileTheOrderIsStillPreparingReadyOrOutForDelivery() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        for (OrderStatus st : new OrderStatus[] {OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.READY}) {
            o.setStatus(st);
            assertTrue(f.settlements.dueForPayout().isEmpty(), st.name());
            assertFalse(f.settlements.pay(f.settlementOf("o1").getId(), false), st.name());
        }
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void aCompletedOrderCreatesExactlyOneEligibleSettlementWithTheRightMoney() {
        Order o = completed("o1");
        f.settlements.onOrderCompleted(o); // completing again changes nothing
        f.settlements.openForPaidOrder(o);

        assertEquals(1, f.settlementsByOrder.size());
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertEquals("settlement:o1", s.getIdempotencyKey());
        assertEquals("biz-1", s.getBusiness().getId());
        assertEquals("pay-o1", s.getPayment().getId());
        assertEquals(d("100.00"), s.getFoodSubtotal());
        assertEquals(d("20.00"), s.getDeliveryAllocation());
        assertEquals(d("100.00"), s.getEligibleRevenue());
        assertEquals(d("100.00"), s.getNetAmount(), "the R20 delivery fee is not restaurant revenue");
        assertEquals("ZAR", s.getCurrency());
        assertNotNull(s.getEligibleAt());
    }

    @Test
    void commissionComesFromConfigurationNotFromCode() {
        f.props.setCommissionRate(d("0.10"));
        f.props.setFixedFee(d("2.00"));
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(0, d("0.10").compareTo(s.getCommissionRate()));
        assertEquals(d("10.00"), s.getCommissionAmount());
        assertEquals(d("2.00"), s.getOtherFees());
        assertEquals(d("88.00"), s.getNetAmount());

        f.props.setCommissionRate(d("0.25"));
        completed("o2");
        assertEquals(d("73.00"), f.settlementOf("o2").getNetAmount(), "a new rate applies to new settlements");
        assertEquals(d("88.00"), f.settlementOf("o1").getNetAmount(), "and never rewrites old ones");
    }

    @Test
    void theSettlementDelayKeepsItUnpayableUntilTheDisputeWindowPasses() {
        f.props.setDelayMinutes(120);
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertTrue(s.getEligibleAt().isAfter(Instant.now().plusSeconds(60 * 119)));
        assertTrue(f.settlements.dueForPayout().isEmpty(), "inside the window nothing is due");
        s.setEligibleAt(Instant.now().minusSeconds(1)); // window passed
        assertEquals(1, f.settlements.dueForPayout().size());
    }

    // ------------------------------------------------------------------ no settlement for failed orders

    @Test
    void restaurantRejectionCreatesNoSuccessfulSettlement() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.RESTAURANT_REJECTED);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertEquals(d("0"), f.settlementOf("o1").getNetAmount().stripTrailingZeros().max(BigDecimal.ZERO).setScale(0));
        assertTrue(f.settlements.dueForPayout().isEmpty());
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void anExpiredOrderCreatesNoSuccessfulSettlement() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.RESTAURANT_EXPIRED);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertEquals(CancellationCause.RESTAURANT_EXPIRED, f.settlementOf("o1").getCause());
    }

    @Test
    void aCustomerCancellationBeforePreparationCancelsTheSettlementWithNoCompensation() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.CUSTOMER_CANCELLED); // readyAt == null
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertEquals(SettlementType.SALE, f.settlementOf("o1").getType());
    }

    // ------------------------------------------------------------------ payout: provider-confirmed PAID, idempotent, retry-safe

    @Test
    void aSettlementIsPaidOnlyWhenTheProviderConfirmsSuccess() {
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertTrue(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertNotNull(s.getPaidAt());
        assertEquals("sandbox", s.getPayoutProvider());
        assertNotNull(s.getPayoutReference());
        assertEquals(1, f.payoutRequests.size());
        PayoutProvider.PayoutRequest req = f.payoutRequests.get(0);
        assertEquals("payout:settlement:" + s.getId(), req.idempotencyKey());
        assertEquals(d("100.00"), req.amount());
        assertEquals("ZAR", req.currency());
        assertEquals("0821234567", req.recipientMsisdn());
    }

    @Test
    void aPayoutRequestThatTheProviderHasNotConfirmedIsNeverPaid() {
        when(f.payoutProvider.pay(any())).thenReturn(new PayoutProvider.PayoutResult(PayoutStatus.PROCESSING, "PEND-1", null));
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertFalse(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PROCESSING, s.getStatus());
        assertNull(s.getPaidAt());
    }

    @Test
    void retryingAnAlreadyPaidSettlementNeverSendsASecondPayout() {
        completed("o1");
        String id = f.settlementOf("o1").getId();
        assertTrue(f.settlements.pay(id, false));
        assertFalse(f.settlements.pay(id, false));
        assertFalse(f.settlements.pay(id, true));
        assertTrue(f.settlements.dueForPayout().isEmpty());
        assertEquals(1, f.payoutRequests.size());
    }

    @Test
    void aFailedPayoutIsRecordedAndCanRetrySafelyWithTheSameIdempotencyKey() {
        when(f.payoutProvider.pay(any()))
                .thenReturn(new PayoutProvider.PayoutResult(PayoutStatus.FAILED, null, "Wallet unavailable"))
                .thenReturn(new PayoutProvider.PayoutResult(PayoutStatus.SUCCESSFUL, "REF-OK", null));
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");

        assertFalse(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.FAILED, s.getStatus());
        assertEquals("Wallet unavailable", s.getFailureReason());
        assertNull(s.getPaidAt());
        assertEquals(1, f.settlements.dueForPayout().size(), "FAILED settlements are picked up for retry");

        assertTrue(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        assertEquals("REF-OK", s.getPayoutReference());
        assertNull(s.getFailureReason());
        assertEquals(2, s.getPayoutAttempts());
        // Both provider calls (the failed one and the retry) carried the SAME idempotency key: one payout, ever.
        org.mockito.ArgumentCaptor<PayoutProvider.PayoutRequest> cap = org.mockito.ArgumentCaptor.forClass(PayoutProvider.PayoutRequest.class);
        verify(f.payoutProvider, times(2)).pay(cap.capture());
        assertEquals(cap.getAllValues().get(0).idempotencyKey(), cap.getAllValues().get(1).idempotencyKey());
        assertEquals("payout:settlement:" + s.getId(), cap.getAllValues().get(0).idempotencyKey());
    }

    @Test
    void failedPayoutsStopRetryingAutomaticallyAtTheAttemptCeiling() {
        f.props.setMaxPayoutAttempts(2);
        when(f.payoutProvider.pay(any())).thenReturn(new PayoutProvider.PayoutResult(PayoutStatus.FAILED, null, "down"));
        completed("o1");
        String id = f.settlementOf("o1").getId();
        f.settlements.pay(id, false);
        assertEquals(1, f.settlements.dueForPayout().size());
        f.settlements.pay(id, false);
        assertTrue(f.settlements.dueForPayout().isEmpty(), "left FAILED for an admin to see, not retried forever");
        assertEquals(SettlementStatus.FAILED, f.settlementOf("o1").getStatus());
    }

    @Test
    void aProviderTimeoutNeverMarksItFailedAndTheStuckRetryRepeatsTheSameIdempotentCall() {
        when(f.payoutProvider.pay(any()))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(new PayoutProvider.PayoutResult(PayoutStatus.SUCCESSFUL, "REF-LATE", null));
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");

        assertFalse(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PROCESSING, s.getStatus(), "outcome unknown: not FAILED, not PAID");
        assertEquals(1, f.settlements.dueForPayout().size());
        assertTrue(f.settlements.dueForPayout().get(0).stuck());

        assertTrue(f.settlements.pay(s.getId(), true));
        assertEquals(SettlementStatus.PAID, s.getStatus());
    }

    @Test
    void twoWorkersRacingToPayTheSameSettlementOnlyOneSendsIt() throws Exception {
        completed("o1");
        String id = f.settlementOf("o1").getId();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> { start.await(); return f.settlements.pay(id, false); }));
        }
        start.countDown();
        int paid = 0;
        for (var r : results) if (r.get()) paid++;
        pool.shutdown();
        assertEquals(1, paid);
        assertEquals(1, f.payoutRequests.size());
    }

    @Test
    void aSettlementWithNothingLeftToPayClosesWithoutCallingTheProvider() {
        f.props.setCommissionRate(d("1.00")); // PULA keeps everything
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(d("0.00"), s.getNetAmount());
        assertTrue(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        verify(f.payoutProvider, never()).pay(any());
    }

    // ------------------------------------------------------------------ merchant protection

    @Test
    void preparedFoodThatPULACouldNotDeliverEarnsCompensationAndNoDriverIsPaid() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.accepted(o);
        f.ready(o);

        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);

        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementType.MERCHANT_PROTECTION, s.getType());
        assertEquals(SettlementStatus.ELIGIBLE, s.getStatus());
        assertEquals(CancellationCause.PULA_DELIVERY_FAILURE, s.getCause());
        assertEquals(d("100.00"), s.getNetAmount(), "the prepared food, never the undelivered R20 delivery fee");
        assertEquals(d("100.00"), s.getPulaFulfilmentLoss(), "PULA absorbs the loss");
        assertTrue(f.settlements.pay(s.getId(), false));
        assertEquals(SettlementStatus.PAID, s.getStatus());
        // Only the restaurant is paid. No driver earnings payout is produced: there is no delivery to complete.
        assertEquals(1, f.payoutRequests.size());
        assertTrue(f.payoutRequests.get(0).idempotencyKey().startsWith("payout:settlement:"));
        assertEquals("PULA merchant protection", f.payoutRequests.get(0).narrative());
        assertTrue(f.payoutRequests.stream().noneMatch(r -> r.idempotencyKey().startsWith("payout:delivery:")));
    }

    @Test
    void theCompensationPolicyIsConfigurable() {
        f.props.setCompensationCommissionRate(d("0.10"));
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.ready(f.accepted(o));
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(d("10.00"), s.getCommissionAmount());
        assertEquals(d("90.00"), s.getNetAmount());
        assertEquals(d("90.00"), s.getPulaFulfilmentLoss());
    }

    @Test
    void compensationCanBeSwitchedOffForPulaDeliveryFailures() {
        f.props.setCompensatePulaDeliveryFailure(false);
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.ready(f.accepted(o));
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
    }

    @Test
    void restaurantRejectionNeverTriggersMerchantProtectionEvenIfSomehowMarkedReady() {
        for (CancellationCause c : new CancellationCause[] {CancellationCause.RESTAURANT_REJECTED, CancellationCause.RESTAURANT_EXPIRED}) {
            Order o = f.paidFoodOrder("o-" + c, true);
            f.settlements.openForPaidOrder(o);
            f.ready(f.accepted(o));
            f.settlements.onOrderEndedWithRefund(o, c);
            assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o-" + c).getStatus(), c.name());
            assertEquals(SettlementType.SALE, f.settlementOf("o-" + c).getType(), c.name());
        }
    }

    @Test
    void aPulaFailureBeforeTheFoodWasPreparedOwesNothing() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.accepted(o); // accepted but never marked READY
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
    }

    @Test
    void aCustomerCancellationAfterPreparationFollowsTheConfiguredPolicyAndIsNeverSilentlyAPulaFailure() {
        Order a = f.paidFoodOrder("a", true);
        f.settlements.openForPaidOrder(a);
        f.ready(f.accepted(a));
        f.settlements.onOrderEndedWithRefund(a, CancellationCause.CUSTOMER_CANCELLED); // default policy: no compensation
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("a").getStatus());

        f.props.setCompensateCustomerCancelledAfterPrep(true);
        Order b = f.paidFoodOrder("b", true);
        f.settlements.openForPaidOrder(b);
        f.ready(f.accepted(b));
        f.settlements.onOrderEndedWithRefund(b, CancellationCause.CUSTOMER_CANCELLED);
        assertEquals(SettlementType.MERCHANT_PROTECTION, f.settlementOf("b").getType());
        assertEquals(CancellationCause.CUSTOMER_CANCELLED, f.settlementOf("b").getCause(), "the cause stays what it really was");
    }

    @Test
    void anAdminCancellationAfterPreparationFollowsItsOwnPolicy() {
        Order a = f.paidFoodOrder("a", true);
        f.settlements.openForPaidOrder(a);
        f.ready(f.accepted(a));
        f.settlements.onOrderEndedWithRefund(a, CancellationCause.ADMIN_CANCELLED);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("a").getStatus());
    }

    @Test
    void compensationCanNeverBeCreatedOrPaidTwice() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.ready(f.accepted(o));
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        f.settlements.onOrderEndedWithRefund(o, CancellationCause.PULA_DELIVERY_FAILURE);
        assertEquals(1, f.settlementsByOrder.size());
        String id = f.settlementOf("o1").getId();
        f.settlements.pay(id, false);
        f.settlements.pay(id, false);
        f.settlements.pay(id, true);
        assertEquals(1, f.payoutRequests.size());
    }

    @Test
    void aRefundedCustomersMoneyDoesNotReduceTheCompensation() {
        Order o = f.paidFoodOrder("o1", true);
        f.settlements.openForPaidOrder(o);
        f.ready(f.accepted(o));
        o.setStatus(OrderStatus.REFUND_PENDING);
        o.setCancellationCause(CancellationCause.PULA_DELIVERY_FAILURE);
        f.refundService.attemptRefund(o); // the customer gets all R120 back (R100 food + R20 delivery)
        f.refundService.attemptRefund(o);
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(SettlementType.MERCHANT_PROTECTION, s.getType());
        assertEquals(d("100.00"), s.getNetAmount());
    }

    // ------------------------------------------------------------------ refund interaction and admin controls

    @Test
    void aPartialRefundReducesAnUnpaidSettlementButNotAPaidOne() {
        Order o = completed("o1");
        PaymentRefundHelper.partial(f, o, "30", "k1");
        RestaurantSettlement s = f.settlementOf("o1");
        assertEquals(d("30.00"), s.getRefundAmount());
        assertEquals(d("70.00"), s.getNetAmount());

        f.settlements.pay(s.getId(), false);
        assertEquals(SettlementStatus.PAID, s.getStatus());
        PaymentRefundHelper.partial(f, o, "10", "k2");
        assertEquals(d("70.00"), s.getNetAmount(), "paid amounts are frozen");
    }

    @Test
    void anAdminCanHoldAndReleaseAnUnpaidSettlementAndAHeldOneIsNeverPaid() {
        completed("o1");
        String id = f.settlementOf("o1").getId();
        f.settlements.hold(id, "fraud review");
        assertEquals(SettlementStatus.ON_HOLD, f.settlementOf("o1").getStatus());
        assertEquals("fraud review", f.settlementOf("o1").getHoldReason());
        assertTrue(f.settlements.dueForPayout().isEmpty());
        assertFalse(f.settlements.pay(id, false));
        assertTrue(f.payoutRequests.isEmpty());

        f.settlements.release(id);
        assertEquals(SettlementStatus.ELIGIBLE, f.settlementOf("o1").getStatus());
        assertTrue(f.settlements.pay(id, false));
    }

    @Test
    void aPaidSettlementCannotBeHeldAndThereIsNoWayToForcePaid() {
        completed("o1");
        RestaurantSettlement s = f.settlementOf("o1");
        f.settlements.pay(s.getId(), false);
        assertThrows(ApiException.class, () -> f.settlements.hold(s.getId(), "too late"));
        assertThrows(IllegalStateException.class, () -> f.settlementOf("o1").transitionTo(SettlementStatus.FAILED));
        RestaurantSettlement np = new RestaurantSettlement();
        assertThrows(IllegalStateException.class, () -> np.transitionTo(SettlementStatus.PAID), "NOT_ELIGIBLE -> PAID is illegal");
    }

    @Test
    void releasingASettlementThatIsNotOnHoldIsRefused() {
        completed("o1");
        assertThrows(ApiException.class, () -> f.settlements.release(f.settlementOf("o1").getId()));
    }

    @Test
    void nonFoodOrdersNeverGetASettlement() {
        Order retail = f.paidFoodOrder("r1", true);
        retail.setKind(com.momocircle.domain.enums.OrderKind.RETAIL);
        assertNull(f.settlements.openForPaidOrder(retail));
        f.settlements.onOrderCompleted(retail);
        f.settlements.onOrderEndedWithRefund(retail, CancellationCause.ADMIN_CANCELLED);
        assertTrue(f.settlementsByOrder.isEmpty());
    }

    // small helper kept local so the assertions above read cleanly
    static final class PaymentRefundHelper {
        static void partial(FoodFinanceFixture f, Order o, String amount, String key) {
            var r = f.refundService.requestPartialRefund(o, new BigDecimal(amount), CancellationCause.ADMIN_CANCELLED, null, null, key);
            f.refundService.advanceById(r.getId());
        }
    }
}
