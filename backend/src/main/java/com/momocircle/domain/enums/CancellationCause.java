package com.momocircle.domain.enums;

/**
 * Why a food order ended without being completed. Drives the refund and settlement rules, so it is
 * recorded explicitly and never inferred: a customer cancellation is never silently a PULA failure.
 * Stored as VARCHAR(32) on orders.cancellation_cause.
 */
public enum CancellationCause {
    /** Restaurant declined the order (fault: restaurant). */
    RESTAURANT_REJECTED(true, false),
    /** Restaurant never answered before the accept deadline (fault: restaurant). */
    RESTAURANT_EXPIRED(true, false),
    /** Customer withdrew the order. Compensation after preparation follows configured policy. */
    CUSTOMER_CANCELLED(false, true),
    /** Food was prepared but PULA Ride found no driver in the delivery window (fault: PULA). */
    PULA_DELIVERY_FAILURE(false, true),
    /** The customer's payment never succeeded. No order money exists to refund or settle. */
    PAYMENT_FAILURE(false, false),
    /** Cancelled by PULA support. Compensation after preparation follows configured policy. */
    ADMIN_CANCELLED(false, true);

    private final boolean restaurantFault;
    private final boolean mayOweCompensation;

    CancellationCause(boolean restaurantFault, boolean mayOweCompensation) {
        this.restaurantFault = restaurantFault;
        this.mayOweCompensation = mayOweCompensation;
    }

    /** Restaurant-fault causes never earn compensation and never earn a settlement. */
    public boolean isRestaurantFault() { return restaurantFault; }

    /** Whether prepared food could earn merchant-protection compensation for this cause (still policy-gated). */
    public boolean mayOweCompensation() { return mayOweCompensation; }
}
