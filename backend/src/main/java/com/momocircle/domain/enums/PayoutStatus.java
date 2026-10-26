package com.momocircle.domain.enums;

/** Lifecycle of a driver payout (PULA -> driver). Separate from PaymentStatus by design. */
public enum PayoutStatus {
    PENDING, PROCESSING, SUCCESSFUL, FAILED, REVERSED;

    public boolean canTransitionTo(PayoutStatus next) {
        return switch (this) {
            case PENDING -> next == PROCESSING || next == FAILED;
            case PROCESSING -> next == SUCCESSFUL || next == FAILED;
            case SUCCESSFUL -> next == REVERSED;
            case FAILED -> next == PROCESSING; // bounded retry, see PayoutService.retryFailedPayouts
            case REVERSED -> false;
        };
    }
}
