package com.momocircle.domain.enums;

/**
 * Commitment state of ONE business's share. Payment and fulfilment are NOT stored here: they are read from the linked
 * StockOrder / Payment so there is exactly one source of truth for them.
 */
public enum BulkAllocationStatus {
    /** Business asked for a quantity; nothing is agreed and no obligation exists. */
    JOINED,
    /** Business explicitly accepted a quantity and a maximum price per pack. */
    CONFIRMED,
    /** A change (own quantity or someone else's) means the agreed price/quantity no longer holds; must be re-confirmed. */
    PENDING_RECONFIRMATION,
    WITHDRAWN,
    /** Never confirmed before finalisation; dropped without any obligation. */
    LAPSED,
    /** A stock order exists; waiting for this business's own payment. */
    ORDERED,
    /** Payment confirmed by the payment infrastructure (observed from the order). */
    PAID,
    CANCELLED,
    /** Paid, but the group/supplier cancelled or short-shipped: a refund or credit is owed. Not executed automatically. */
    REFUND_DUE;

    public boolean isActiveInterest() {
        return this == JOINED || this == CONFIRMED || this == PENDING_RECONFIRMATION;
    }

    public boolean hasOrder() {
        return this == ORDERED || this == PAID || this == REFUND_DUE;
    }
}
