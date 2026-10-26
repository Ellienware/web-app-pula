package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.momocircle.domain.Order;
import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.payment.ChargeResult;
import com.momocircle.payment.PaystackGateway;
import com.momocircle.payment.PaystackGateway.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Paystack-capable refunds: real OrderRefundService + real FoodSettlementService over in-memory repositories. */
class OrderRefundServiceTest {

    private FoodFinanceFixture f;

    @BeforeEach
    void setUp() { f = new FoodFinanceFixture(); }

    private Order rejectedOrder(String id, boolean paystack) {
        Order o = f.paidFoodOrder(id, paystack);
        f.settlements.openForPaidOrder(o);
        o.setCancellationCause(CancellationCause.RESTAURANT_REJECTED);
        o.setStatus(OrderStatus.REFUND_PENDING);
        return o;
    }

    private PaymentRefund theRefund(String orderId) {
        List<PaymentRefund> all = f.refunds.values().stream().filter(r -> r.getOrder().getId().equals(orderId)).toList();
        assertEquals(1, all.size(), "exactly one refund operation expected");
        return all.get(0);
    }

    // ------------------------------------------------------------------ initiation and asynchronous completion

    @Test
    void paystackRefundIsInitiatedAndTheOrderStaysRefundPendingUntilThePaystackConfirms() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);

        boolean done = f.refundService.attemptRefund(o);

        assertFalse(done, "Paystack accepting the refund is not completion");
        PaymentRefund r = theRefund("o1");
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertEquals("paystack", r.getProvider());
        assertEquals("TX-o1", r.getOriginalReference());
        assertNotNull(r.getProviderRefundReference(), "Paystack's refund id is stored");
        assertEquals(new BigDecimal("120.00"), r.getAmount());
        assertEquals("ZAR", r.getCurrency());
        assertEquals(CancellationCause.RESTAURANT_REJECTED, r.getReason());
        assertEquals("refund:o1", r.getIdempotencyKey());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
        assertEquals(PaymentStatus.SUCCESSFUL, o.getPayment().getStatus());
        assertEquals(1, f.mockGateway.listRefunds("TX-o1").size(), "one provider refund was created");
        verify(f.notifications, never()).notifyOrderStatusChanged(o);
    }

    @Test
    void theOrderBecomesRefundedOnlyAfterTheProviderConfirmsProcessed() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");

        f.refundService.advanceById(r.getId()); // still processing at Paystack
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());

        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());
        f.refundService.advanceById(r.getId());

        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        assertNotNull(r.getProcessedAt());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(PaymentStatus.REFUNDED, o.getPayment().getStatus());
        verify(f.notifications).notifyOrderStatusChanged(o);
    }

    @Test
    void aSecondPassAfterAutoProcessingCompletesTheRefund() {
        Order o = rejectedOrder("o1", true);
        assertFalse(f.refundService.attemptRefund(o)); // first pass: accepted, PROCESSING
        assertTrue(f.refundService.attemptRefund(o));  // maintenance pass: Paystack now says processed
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(1, f.refunds.size());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void duplicateRefundRequestsNeverCreateASecondRefundOrASecondProviderCall() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        f.refundService.attemptRefund(o);
        f.refundService.attemptRefund(o);
        f.refundService.adminFullRefund(o, "admin clicked again");
        assertEquals(1, f.refunds.size());
        assertEquals(1, f.mockGateway.listRefunds("TX-o1").size());
    }

    @Test
    void aRestartedApplicationContinuesTheSameRefundInsteadOfStartingAnother() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        f.build(); // brand-new service instances over the same database
        f.refundService.attemptRefund(o);
        assertEquals(1, f.refunds.size());
        assertEquals(1, f.mockGateway.listRefunds("TX-o1").size());
    }

    @Test
    void aNetworkTimeoutAfterPaystackAcceptedTheRequestIsRecoveredNotRepeated() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.mode()).thenReturn("PAYSTACK");
        when(g.createRefund(any())).thenThrow(new RefundTransportException("timeout"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);

        assertFalse(f.refundService.attemptRefund(o));
        PaymentRefund r = theRefund("o1");
        assertEquals(RefundStatus.PENDING, r.getStatus());
        assertNull(r.getProviderRefundReference());

        // Paystack had in fact accepted it. The next pass finds it by transaction reference and adopts it.
        when(g.listRefunds("TX-o1")).thenReturn(List.of(new RefundResult(RefundRemoteStatus.PROCESSING, "9001", "TX-o1", 12000, "ZAR", "processing")));
        when(g.fetchRefund("9001")).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSED, "9001", "TX-o1", 12000, "ZAR", "processed"));
        assertFalse(f.refundService.attemptRefund(o)); // adopted as PROCESSING, exactly as the list reported it
        assertEquals("9001", r.getProviderRefundReference());
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertTrue(f.refundService.attemptRefund(o));  // next pass re-fetches it: processed
        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        verify(g, times(1)).createRefund(any()); // never a second refund
    }

    @Test
    void aTimeoutWithNothingOnTheProviderSideIsRetriedAndThenSucceeds() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any()))
                .thenThrow(new RefundTransportException("timeout"))
                .thenReturn(new RefundResult(RefundRemoteStatus.PROCESSING, "9002", "TX-o1", 12000, "ZAR", "processing"));
        when(g.listRefunds("TX-o1")).thenReturn(List.of()); // look first: nothing there
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);

        f.refundService.attemptRefund(o);
        f.refundService.attemptRefund(o);

        assertEquals(RefundStatus.PROCESSING, theRefund("o1").getStatus());
        verify(g, times(2)).createRefund(any());
    }

    @Test
    void anUnreachableProviderNeverTriggersABlindSecondRefund() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenThrow(new RefundTransportException("timeout"));
        when(g.listRefunds(anyString())).thenThrow(new RefundTransportException("down")); // cannot even look
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);

        f.refundService.attemptRefund(o); // 1st: create fails
        f.refundService.attemptRefund(o); // 2nd: cannot check, so it must not create
        f.refundService.attemptRefund(o);

        verify(g, times(1)).createRefund(any());
        assertEquals(RefundStatus.PENDING, theRefund("o1").getStatus());
    }

    @Test
    void afterTheMaximumAttemptsTheRefundNeedsAttentionInsteadOfRetryingForever() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenThrow(new RefundTransportException("timeout"));
        when(g.listRefunds("TX-o1")).thenReturn(List.of());
        f.gateway = g; f.maxRefundAttempts = 3; f.build();
        Order o = rejectedOrder("o1", true);
        for (int i = 0; i < 6; i++) f.refundService.attemptRefund(o);
        assertEquals(RefundStatus.NEEDS_ATTENTION, theRefund("o1").getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
        verify(g, times(3)).createRefund(any());
    }

    // ------------------------------------------------------------------ provider failure

    @Test
    void aProviderRefusalMarksTheRefundFailedAndLeavesTheOrderRefundPending() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenThrow(new RefundRejectedException("Transaction has been fully reversed"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);

        assertFalse(f.refundService.attemptRefund(o));

        PaymentRefund r = theRefund("o1");
        assertEquals(RefundStatus.FAILED, r.getStatus());
        assertEquals("Transaction has been fully reversed", r.getFailureReason());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
        assertNotEquals(PaymentStatus.REFUNDED, o.getPayment().getStatus());

        f.refundService.attemptRefund(o); // maintenance pass must not silently resend a failed refund
        verify(g, times(1)).createRefund(any());
    }

    @Test
    void anAdminCanRetryAFailedRefundAsANewOperationAndTheFailedOneStaysAsHistory() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any()))
                .thenThrow(new RefundRejectedException("bank returned the funds"))
                .thenReturn(new RefundResult(RefundRemoteStatus.PROCESSING, "9100", "TX-o1", 12000, "ZAR", "processing"));
        when(g.fetchRefund("9100")).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSED, "9100", "TX-o1", 12000, "ZAR", "processed"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund failed = theRefund("o1");

        PaymentRefund retry = f.refundService.retryFailed(failed.getId());
        f.refundService.advanceById(retry.getId());

        assertEquals(RefundStatus.FAILED, failed.getStatus(), "history is kept");
        assertEquals(RefundStatus.REFUNDED, retry.getStatus());
        assertNotEquals(failed.getIdempotencyKey(), retry.getIdempotencyKey());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        // The ceiling counts the failed one out, so the customer is refunded exactly once.
        assertEquals(new BigDecimal("120.00"), f.refundRepo.sumRefundedByOrder("o1"));
    }

    @Test
    void onlyAFailedRefundCanBeRetried() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        assertThrows(ApiException.class, () -> f.refundService.retryFailed(theRefund("o1").getId()));
    }

    // ------------------------------------------------------------------ partial refunds and amount validation

    @Test
    void aPartialRefundSendsOnlyThatAmountAndLeavesTheOrderAndPaymentAlone() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);

        PaymentRefund r = f.refundService.requestPartialRefund(o, new BigDecimal("20"), CancellationCause.ADMIN_CANCELLED,
                "Delivery fee refunded", "late delivery", "op-1");

        assertEquals(new BigDecimal("20.00"), r.getAmount());
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertEquals(2000, f.mockGateway.fetchRefund(r.getProviderRefundReference()).amountMinor());
        assertEquals(OrderStatus.COMPLETED, o.getStatus());

        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());
        f.refundService.advanceById(r.getId());
        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        assertEquals(OrderStatus.COMPLETED, o.getStatus(), "a partial refund does not refund the order");
        assertEquals(PaymentStatus.SUCCESSFUL, o.getPayment().getStatus(), "the payment is only REFUNDED once fully covered");
    }

    @Test
    void theSamePartialRefundOperationKeyIsAlwaysTheSameRefund() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);
        PaymentRefund a = f.refundService.requestPartialRefund(o, new BigDecimal("20"), null, null, null, "op-1");
        PaymentRefund b = f.refundService.requestPartialRefund(o, new BigDecimal("20"), null, null, null, "op-1");
        assertSame(a, b);
        assertEquals(1, f.mockGateway.listRefunds("TX-o1").size());
    }

    @Test
    void cumulativeRefundsCanNeverExceedTheOriginalPayment() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);
        f.refundService.requestPartialRefund(o, new BigDecimal("100"), null, null, null, "a");
        ApiException e = assertThrows(ApiException.class,
                () -> f.refundService.requestPartialRefund(o, new BigDecimal("30"), null, null, null, "b")); // 100 + 30 > 120
        assertEquals(400, e.getStatus().value());
        PaymentRefund last = f.refundService.requestPartialRefund(o, new BigDecimal("20"), null, null, null, "c"); // exactly 120 total
        assertEquals(new BigDecimal("20.00"), last.getAmount());
        assertEquals(2, f.refunds.size());
    }

    @Test
    void zeroNegativeAndOversizedRefundAmountsAreRefusedBeforeAnyProviderCall() {
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);
        for (String bad : new String[] {"0", "-5", "120.01", "1000"}) {
            ApiException e = assertThrows(ApiException.class,
                    () -> f.refundService.requestPartialRefund(o, new BigDecimal(bad), null, null, null, "k" + bad));
            assertEquals(400, e.getStatus().value(), bad);
        }
        assertThrows(ApiException.class, () -> f.refundService.requestPartialRefund(o, new BigDecimal("5"), null, null, null, " "));
        assertTrue(f.refunds.isEmpty());
        assertTrue(f.mockGateway.listRefunds("TX-o1").isEmpty());
    }

    @Test
    void aPartialRefundThatUsesUpTheBalanceBehavesLikeAFullRefund() {
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);
        PaymentRefund r = f.refundService.requestPartialRefund(o, new BigDecimal("120.00"), CancellationCause.ADMIN_CANCELLED, null, null, "all");
        f.refundService.advanceById(r.getId());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(PaymentStatus.REFUNDED, o.getPayment().getStatus());
    }

    // ------------------------------------------------------------------ webhooks

    @Test
    void aVerifiedRefundProcessedWebhookCompletesTheRefund() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");
        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());

        assertTrue(f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1"));

        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
    }

    @Test
    void aDuplicateRefundWebhookChangesNothing() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");
        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());

        f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1");
        assertTrue(f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1"));
        assertTrue(f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1"));

        assertEquals(1, f.refunds.size());
        assertEquals(RefundStatus.REFUNDED, r.getStatus());
        verify(f.notifications, times(1)).notifyOrderStatusChanged(o); // the customer is told once
    }

    @Test
    void aWebhookForARefundPULADoesNotKnowIsIgnored() {
        assertFalse(f.refundService.onProviderRefundNotification("424242", "TX-unknown"));
        assertTrue(f.refunds.isEmpty());
    }

    @Test
    void aWebhookIsOnlyAHintTheStateComesFromARefetchFromThePaystack() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");
        // A (hypothetical) forged "processed" webhook while Paystack still says processing: nothing may change.
        assertTrue(f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1"));
        assertEquals(RefundStatus.PROCESSING, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
    }

    @Test
    void aFailedRefundWebhookMarksTheRefundFailedAndTheOrderStaysRefundPending() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");
        f.mockGateway.simulateRefundFailed(r.getProviderRefundReference());

        f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "TX-o1");

        assertEquals(RefundStatus.FAILED, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
    }

    @Test
    void aProviderAmountThatDoesNotMatchIsNeverAcceptedAsASuccessfulRefund() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSING, "9200", "TX-o1", 12000, "ZAR", "processing"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");

        when(g.fetchRefund("9200")).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSED, "9200", "TX-o1", 5000, "ZAR", "processed")); // R50, not R120
        f.refundService.onProviderRefundNotification("9200", "TX-o1");

        assertEquals(RefundStatus.NEEDS_ATTENTION, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
    }

    @Test
    void aProviderCurrencyThatDoesNotMatchNeedsAttention() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSING, "9201", "TX-o1", 12000, "ZAR", "processing"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        when(g.fetchRefund("9201")).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSED, "9201", "TX-o1", 12000, "NGN", "processed"));
        f.refundService.onProviderRefundNotification("9201", "TX-o1");
        assertEquals(RefundStatus.NEEDS_ATTENTION, theRefund("o1").getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
    }

    @Test
    void aWebhookNamingADifferentTransactionIsNeverApplied() {
        f.mockGateway.setAutoProcessRefunds(false);
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        PaymentRefund r = theRefund("o1");
        f.mockGateway.simulateRefundProcessed(r.getProviderRefundReference());
        f.refundService.onProviderRefundNotification(r.getProviderRefundReference(), "SOME-OTHER-TX");
        assertEquals(RefundStatus.NEEDS_ATTENTION, r.getStatus());
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
    }

    @Test
    void aRefundNeedingAttentionCanStillCompleteWhenPaystackLaterProcessesIt() {
        PaystackGateway g = mock(PaystackGateway.class);
        when(g.createRefund(any())).thenReturn(new RefundResult(RefundRemoteStatus.NEEDS_ATTENTION, "9300", "TX-o1", 12000, "ZAR", "needs-attention"));
        f.gateway = g; f.build();
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        assertEquals(RefundStatus.NEEDS_ATTENTION, theRefund("o1").getStatus());
        when(g.fetchRefund("9300")).thenReturn(new RefundResult(RefundRemoteStatus.PROCESSED, "9300", "TX-o1", 12000, "ZAR", "processed"));
        f.refundService.onProviderRefundNotification("9300", "TX-o1");
        assertEquals(RefundStatus.REFUNDED, theRefund("o1").getStatus());
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
    }

    // ------------------------------------------------------------------ legacy provider and settlement interaction

    @Test
    void legacyProviderPaymentsKeepTheirSynchronousRefundAndTheirOriginalIdempotencyKey() {
        when(f.legacy.refund(anyString(), anyString())).thenReturn(new ChargeResult(PaymentStatus.REFUNDED, "TX-o1", null));
        Order o = rejectedOrder("o1", false);

        assertTrue(f.refundService.attemptRefund(o));

        verify(f.legacy).refund("TX-o1", "refund_o1");
        assertEquals(OrderStatus.REFUNDED, o.getStatus());
        assertEquals(PaymentStatus.REFUNDED, o.getPayment().getStatus());
        assertEquals("sandbox", theRefund("o1").getProvider());
    }

    @Test
    void aLegacyProviderThatDoesNotCompleteLeavesTheOrderRefundPendingForARetry() {
        when(f.legacy.refund(anyString(), anyString())).thenReturn(new ChargeResult(PaymentStatus.PROCESSING, "TX-o1", null));
        Order o = rejectedOrder("o1", false);
        assertFalse(f.refundService.attemptRefund(o));
        assertEquals(OrderStatus.REFUND_PENDING, o.getStatus());
        assertEquals(RefundStatus.PENDING, theRefund("o1").getStatus());
    }

    @Test
    void partialRefundsAreRefusedForLegacyPayments() {
        Order o = f.paidFoodOrder("o1", false);
        o.setStatus(OrderStatus.COMPLETED);
        assertThrows(ApiException.class, () -> f.refundService.requestPartialRefund(o, new BigDecimal("10"), null, null, null, "x"));
        verify(f.legacy, never()).refund(anyString(), anyString());
    }

    @Test
    void refundingAnOrderDoesNotPayTheRestaurantAndCancelsItsSettlement() {
        Order o = rejectedOrder("o1", true);
        f.refundService.attemptRefund(o);
        assertEquals(SettlementStatus.CANCELLED, f.settlementOf("o1").getStatus());
        assertTrue(f.payoutRequests.isEmpty());
    }

    @Test
    void anAdminCannotRefundAnOrderWhoseRestaurantHasAlreadyBeenPaid() {
        Order o = f.paidFoodOrder("o1", true);
        o.setStatus(OrderStatus.COMPLETED);
        f.settlements.onOrderCompleted(o);
        f.settlements.pay(f.settlementOf("o1").getId(), false);
        assertEquals(SettlementStatus.PAID, f.settlementOf("o1").getStatus());

        ApiException e = assertThrows(ApiException.class, () -> f.refundService.adminFullRefund(o, "dispute"));
        assertEquals(409, e.getStatus().value());
        assertTrue(f.mockGateway.listRefunds("TX-o1").isEmpty(), "no provider refund was started");
    }
}
