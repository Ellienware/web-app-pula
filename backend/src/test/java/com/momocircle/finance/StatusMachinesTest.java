package com.momocircle.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.domain.enums.SettlementStatus;
import org.junit.jupiter.api.Test;

class StatusMachinesTest {

    @Test
    void refundedAndFailedAreTerminal() {
        for (RefundStatus next : RefundStatus.values()) {
            if (next != RefundStatus.REFUNDED) assertFalse(RefundStatus.REFUNDED.canTransitionTo(next), "REFUNDED -> " + next);
            if (next != RefundStatus.FAILED) assertFalse(RefundStatus.FAILED.canTransitionTo(next), "FAILED -> " + next);
        }
        assertTrue(RefundStatus.REFUNDED.isTerminal());
        assertTrue(RefundStatus.FAILED.isTerminal());
    }

    @Test
    void anAcceptedRefundIsProcessingNotRefundedUntilConfirmed() {
        assertTrue(RefundStatus.PENDING.canTransitionTo(RefundStatus.PROCESSING));
        assertTrue(RefundStatus.PROCESSING.canTransitionTo(RefundStatus.REFUNDED));
        assertFalse(RefundStatus.PROCESSING.isTerminal());
        assertFalse(RefundStatus.NEEDS_ATTENTION.isTerminal()); // a later webhook can still complete it
        assertTrue(RefundStatus.NEEDS_ATTENTION.canTransitionTo(RefundStatus.REFUNDED));
    }

    @Test
    void failedRefundsDoNotCountTowardTheRefundedTotal() {
        assertFalse(RefundStatus.FAILED.countsTowardRefundedTotal());
        for (RefundStatus s : RefundStatus.values()) {
            if (s != RefundStatus.FAILED) assertTrue(s.countsTowardRefundedTotal(), s.name());
        }
    }

    @Test
    void settlementCanOnlyReachPaidFromProcessing() {
        for (SettlementStatus from : SettlementStatus.values()) {
            boolean ok = from.canTransitionTo(SettlementStatus.PAID);
            assertEquals(from == SettlementStatus.PROCESSING || from == SettlementStatus.PAID, ok, from + " -> PAID");
        }
    }

    @Test
    void paidAndCancelledSettlementsAreFinal() {
        for (SettlementStatus next : SettlementStatus.values()) {
            if (next != SettlementStatus.PAID) assertFalse(SettlementStatus.PAID.canTransitionTo(next));
            if (next != SettlementStatus.CANCELLED) assertFalse(SettlementStatus.CANCELLED.canTransitionTo(next));
        }
    }

    @Test
    void eligibleSettlementCanBeHeldCancelledOrSent() {
        assertTrue(SettlementStatus.ELIGIBLE.canTransitionTo(SettlementStatus.PROCESSING));
        assertTrue(SettlementStatus.ELIGIBLE.canTransitionTo(SettlementStatus.ON_HOLD));
        assertTrue(SettlementStatus.FAILED.canTransitionTo(SettlementStatus.PROCESSING)); // safe retry
        assertFalse(SettlementStatus.NOT_ELIGIBLE.canTransitionTo(SettlementStatus.PROCESSING)); // never paid early
        assertFalse(SettlementStatus.NOT_ELIGIBLE.canTransitionTo(SettlementStatus.PAID));
    }

    @Test
    void restaurantFaultCausesNeverOweCompensation() {
        assertTrue(CancellationCause.RESTAURANT_REJECTED.isRestaurantFault());
        assertTrue(CancellationCause.RESTAURANT_EXPIRED.isRestaurantFault());
        assertFalse(CancellationCause.RESTAURANT_REJECTED.mayOweCompensation());
        assertFalse(CancellationCause.RESTAURANT_EXPIRED.mayOweCompensation());
        assertFalse(CancellationCause.PAYMENT_FAILURE.mayOweCompensation());
        assertTrue(CancellationCause.PULA_DELIVERY_FAILURE.mayOweCompensation());
        assertFalse(CancellationCause.PULA_DELIVERY_FAILURE.isRestaurantFault());
    }
}
