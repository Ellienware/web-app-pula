package com.momocircle.domain.enums;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    PAID,
    PROCESSING,
    READY,
    COMPLETED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    /** Whitelist of legal forward transitions — mirrors the transaction state machine. */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PAYMENT_PROCESSING || next == CANCELLED;
            case PAYMENT_PROCESSING -> next == PAID || next == CANCELLED;
            case PAID -> next == PROCESSING || next == REFUND_PENDING;
            case PROCESSING -> next == READY || next == REFUND_PENDING;
            case READY -> next == COMPLETED || next == REFUND_PENDING;
            case COMPLETED -> next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
    }
}
