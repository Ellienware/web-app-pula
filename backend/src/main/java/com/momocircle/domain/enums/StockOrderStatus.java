package com.momocircle.domain.enums;

/**
 * State machine for a {@link com.momocircle.domain.StockOrder} (brief §15).
 * Mirrors the structure of {@link OrderStatus} but with B2B-specific states
 * (e.g. AWAITING_SUPPLIER_CONFIRMATION) and kept deliberately separate so
 * changes to one flow can't accidentally break the other.
 */
public enum StockOrderStatus {
    /** Payment not yet made; order is a draft until the buyer pays. */
    PENDING_PAYMENT,
    PAYMENT_PROCESSING,
    /** Payment received — waiting for the supplier to acknowledge. */
    PAID,
    /** Supplier confirmed they have the stock and can fulfil. */
    CONFIRMED,
    /** Supplier is picking/packing the stock. */
    PREPARING,
    /** Stock is ready: for SELF_COLLECTION = ready to collect; for delivery = handed to driver. */
    READY_FOR_COLLECTION,
    /** In transit (only meaningful for WHOLESALER_DELIVERY / PULA_RIDE_DELIVERY). */
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    public boolean canTransitionTo(StockOrderStatus next) {
        return switch (this) {
            case PENDING_PAYMENT -> next == PAYMENT_PROCESSING || next == CANCELLED;
            case PAYMENT_PROCESSING -> next == PAID || next == CANCELLED;
            case PAID -> next == CONFIRMED || next == REFUND_PENDING;
            case CONFIRMED -> next == PREPARING || next == REFUND_PENDING;
            case PREPARING -> next == READY_FOR_COLLECTION || next == REFUND_PENDING;
            case READY_FOR_COLLECTION -> next == DELIVERED || next == OUT_FOR_DELIVERY || next == REFUND_PENDING;
            case OUT_FOR_DELIVERY -> next == DELIVERED || next == REFUND_PENDING;
            case DELIVERED -> next == REFUND_PENDING;
            case REFUND_PENDING -> next == REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
    }
}
