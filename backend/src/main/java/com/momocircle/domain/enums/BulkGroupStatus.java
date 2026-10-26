package com.momocircle.domain.enums;

/**
 * Lifecycle of a bulk-purchase group. Money never moves in this state machine: each business pays its own allocation
 * through the existing stock checkout, and the group only observes the result.
 *
 * DRAFT -> OPEN -> TARGET_REACHED -> CONFIRMATION -> PAYMENT_PENDING -> ORDER_PLACED -> SUPPLIER_CONFIRMED
 *       -> FULFILMENT -> COMPLETED | PARTIALLY_FULFILLED, with CANCELLED / FAILED as terminal exits.
 */
public enum BulkGroupStatus {
    DRAFT,
    OPEN,
    TARGET_REACHED,
    CONFIRMATION,
    /** Per-business stock orders exist and are awaiting each business's own payment. */
    PAYMENT_PENDING,
    /** Every participating allocation is paid; the combined demand has been placed with the supplier. */
    ORDER_PLACED,
    SUPPLIER_CONFIRMED,
    FULFILMENT,
    COMPLETED,
    /** Finished, but with a shortage or reported damaged/missing items that need follow-up. */
    PARTIALLY_FULFILLED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_FULFILLED || this == CANCELLED || this == FAILED;
    }

    /** Joining, leaving and changing a requested quantity are only possible before orders exist. */
    public boolean acceptsParticipationChanges() {
        return this == OPEN || this == TARGET_REACHED || this == CONFIRMATION;
    }

    public boolean canTransitionTo(BulkGroupStatus next) {
        return switch (this) {
            case DRAFT -> next == OPEN || next == CANCELLED;
            case OPEN -> next == TARGET_REACHED || next == CONFIRMATION || next == CANCELLED;
            case TARGET_REACHED -> next == OPEN || next == CONFIRMATION || next == CANCELLED;
            case CONFIRMATION -> next == PAYMENT_PENDING || next == OPEN || next == CANCELLED;
            case PAYMENT_PENDING -> next == ORDER_PLACED || next == CANCELLED || next == FAILED;
            case ORDER_PLACED -> next == SUPPLIER_CONFIRMED || next == CANCELLED || next == FAILED;
            case SUPPLIER_CONFIRMED -> next == FULFILMENT || next == COMPLETED || next == PARTIALLY_FULFILLED;
            case FULFILMENT -> next == COMPLETED || next == PARTIALLY_FULFILLED;
            case COMPLETED, PARTIALLY_FULFILLED, CANCELLED, FAILED -> false;
        };
    }
}
