package com.momocircle.domain.wallet;

/**
 * Lifecycle of a payment REQUEST (not of a ledger transaction). Only PAID means value moved, and only because a ledger
 * transaction was posted. PROCESSING is a short claim held by exactly one payer while the ledger posting runs.
 */
public enum PaymentRequestStatus {
    OPEN, PROCESSING, PAID, REJECTED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this == PAID || this == REJECTED || this == CANCELLED || this == EXPIRED;
    }
}
