package com.momocircle.domain.enums;

/**
 * Lifecycle of a restaurant settlement (PULA -> restaurant). PAID is reached only when the payout
 * provider confirms success; creating or sending a payout request is never enough.
 */
public enum SettlementStatus {
    NOT_ELIGIBLE, ELIGIBLE, PROCESSING, PAID, FAILED, ON_HOLD, CANCELLED;

    public boolean isOpen() { return this != PAID && this != CANCELLED; }

    public boolean canTransitionTo(SettlementStatus next) {
        if (next == this) return true;
        return switch (this) {
            case NOT_ELIGIBLE -> next == ELIGIBLE || next == CANCELLED || next == ON_HOLD;
            case ELIGIBLE -> next == PROCESSING || next == ON_HOLD || next == CANCELLED;
            case PROCESSING -> next == PAID || next == FAILED;
            case FAILED -> next == PROCESSING || next == ON_HOLD || next == CANCELLED;
            case ON_HOLD -> next == ELIGIBLE || next == NOT_ELIGIBLE || next == CANCELLED;
            case PAID, CANCELLED -> false;
        };
    }
}
