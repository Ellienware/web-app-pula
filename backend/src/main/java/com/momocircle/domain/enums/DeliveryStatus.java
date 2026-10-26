package com.momocircle.domain.enums;

/**
 * Lifecycle of a MoMoRide delivery job for a MoMoMarket order. Deliberately
 * a separate state machine from {@link OrderStatus} — a delivery has its
 * own driver-facing lifecycle (accept/pick up/deliver) that isn't the same
 * shape as the seller-facing order lifecycle, even though DELIVERED
 * eventually triggers the order into COMPLETED (see
 * OrderService#markCompletedByDelivery). Keeping them separate is what lets
 * MoMoRide stay a distinct module rather than MoMoMarket's order state
 * machine growing driver-specific states it doesn't otherwise need.
 */
public enum DeliveryStatus {
    /** Seller has requested MoMoRide delivery; no driver has accepted yet. */
    REQUESTED,
    /** A driver has claimed this delivery. */
    ACCEPTED,
    /** Driver has collected the order from the seller. */
    PICKED_UP,
    /** Delivered to the buyer — terminal, successful state. */
    DELIVERED,
    /** Cancelled before delivery — terminal. */
    CANCELLED;

    /** Whitelist of legal forward transitions — mirrors OrderStatus's own pattern. */
    public boolean canTransitionTo(DeliveryStatus next) {
        return switch (this) {
            case REQUESTED -> next == ACCEPTED || next == CANCELLED;
            case ACCEPTED -> next == PICKED_UP || next == CANCELLED;
            case PICKED_UP -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
