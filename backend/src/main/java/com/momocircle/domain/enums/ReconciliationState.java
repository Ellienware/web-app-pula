package com.momocircle.domain.enums;

/** Outcome of checking one marketplace sale against its payment, settlement and payout. */
public enum ReconciliationState {
    MATCHED,
    /** Catch-all when two records disagree in a way that is not one of the specific states below. */
    MISMATCH,
    /** A settlement/sale exists but the payment is missing or never succeeded. */
    MISSING_PAYMENT,
    /** The sale was paid (and earned) but no settlement exists. */
    MISSING_SETTLEMENT,
    /** The settlement says PAID but there is no payout reference (or nothing was owed and a payout exists). */
    MISSING_PAYOUT,
    /** Source total, payment amount and settlement gross do not agree. */
    AMOUNT_MISMATCH,
    /** Currency or provider reference disagree between the records. */
    REFERENCE_MISMATCH
}
