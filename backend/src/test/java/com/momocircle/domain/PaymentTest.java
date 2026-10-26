package com.momocircle.domain;

import com.momocircle.domain.enums.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the financial invariants from the Phase B.1 brief directly against
 * the domain object, independent of any service/HTTP layer:
 *   - a successful payment cannot become FAILED or PROCESSING again
 *   - a failed payment cannot become SUCCESSFUL
 *   - a refunded payment cannot become SUCCESSFUL again
 *   - only the documented forward transitions are legal
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not actually be run here. This compiles against nothing
 * beyond what SandboxPaymentProviderTest already relies on (junit-jupiter),
 * so it should run cleanly in a normal dev environment, but that has not
 * been confirmed.
 */
class PaymentTest {

    private Payment newPending() {
        Payment payment = new Payment();
        payment.setAmount(new BigDecimal("1000"));
        payment.setCurrency("XAF");
        payment.setProvider("sandbox");
        return payment; // status defaults to PENDING
    }

    @Test
    void pendingCanMoveToProcessing() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
    }

    @Test
    void pendingCanMoveToFailed() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.FAILED);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void processingCanMoveToSuccessful() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        assertEquals(PaymentStatus.SUCCESSFUL, payment.getStatus());
    }

    @Test
    void processingCanMoveToFailed() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.FAILED);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void successfulCanMoveToRefunded() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        payment.transitionTo(PaymentStatus.REFUNDED);
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void sameStatusReportIsANoOpNotAnError() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.PROCESSING); // provider re-reports same state
        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
    }

    @Test
    void successfulCannotBecomeFailed() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.FAILED));
    }

    @Test
    void successfulCannotBecomeProcessingAgain() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.PROCESSING));
    }

    @Test
    void failedCannotBecomeSuccessful() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.FAILED);
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.SUCCESSFUL));
    }

    @Test
    void refundedCannotBecomeSuccessfulAgain() {
        Payment payment = newPending();
        payment.transitionTo(PaymentStatus.PROCESSING);
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        payment.transitionTo(PaymentStatus.REFUNDED);
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.SUCCESSFUL));
    }

    @Test
    void pendingCannotJumpStraightToSuccessful() {
        Payment payment = newPending();
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.SUCCESSFUL));
    }

    @Test
    void pendingCannotJumpStraightToRefunded() {
        Payment payment = newPending();
        assertThrows(IllegalStateException.class, () -> payment.transitionTo(PaymentStatus.REFUNDED));
    }
}
