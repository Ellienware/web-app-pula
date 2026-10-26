package com.momocircle.domain.enums;

public enum SaveToBuyStatus {
    /** Created, not yet confirmed/submitted. Nothing is committed. */
    DRAFT,
    /** Stokvel only: waiting for the stokvel's configured approvals. */
    PENDING_APPROVAL,
    /** Confirmed by the owner (personal) or approved by the group (stokvel). The named buyer may now check out. */
    APPROVED,
    /** An order has been created through the normal marketplace checkout and is awaiting payment. */
    CHECKOUT_STARTED,
    /** The linked order was paid. */
    PURCHASED,
    CANCELLED,
    EXPIRED;

    public boolean isOpen() {
        return this == DRAFT || this == PENDING_APPROVAL || this == APPROVED;
    }
}
