package com.momocircle.domain.enums;

/**
 * Lifecycle of one refund operation (PULA -> customer). Mirrors the asynchronous nature of Paystack
 * refunds: a refund the provider has merely accepted is PROCESSING, never REFUNDED.
 *
 *   PENDING          created internally, not yet accepted by the provider
 *   PROCESSING       provider accepted it (Paystack pending/processing); money not yet returned
 *   REFUNDED         provider confirmed the refund processed
 *   FAILED           provider reported failure (Paystack credits the amount back to PULA); needs a human
 *   NEEDS_ATTENTION  provider needs manual action, an amount/currency mismatch, or retries ran out
 */
public enum RefundStatus {
    PENDING, PROCESSING, REFUNDED, FAILED, NEEDS_ATTENTION;

    public boolean isTerminal() { return this == REFUNDED || this == FAILED; }

    /** Counts toward the cumulative-refund ceiling: money that has been, or is still being, returned. */
    public boolean countsTowardRefundedTotal() { return this != FAILED; }

    public boolean canTransitionTo(RefundStatus next) {
        if (next == this) return true;
        return switch (this) {
            case PENDING -> next == PROCESSING || next == REFUNDED || next == FAILED || next == NEEDS_ATTENTION;
            case PROCESSING -> next == REFUNDED || next == FAILED || next == NEEDS_ATTENTION;
            case NEEDS_ATTENTION -> next == PROCESSING || next == REFUNDED || next == FAILED;
            case REFUNDED, FAILED -> false;
        };
    }
}
