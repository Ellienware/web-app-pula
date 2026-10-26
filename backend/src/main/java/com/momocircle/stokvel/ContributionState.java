package com.momocircle.stokvel;

import java.math.BigDecimal;

/** Status of one member's contribution for one period. Computed from confirmed payments, never stored. */
public enum ContributionState {
    /** Nothing owed yet / nothing expected. */
    DUE,
    PARTIAL,
    PAID,
    /** The period ended and the expected amount was not (fully) paid. */
    MISSED;

    public static ContributionState of(BigDecimal expected, BigDecimal paid, boolean periodEnded) {
        if (expected == null || expected.signum() <= 0) {
            return DUE;
        }
        if (paid.compareTo(expected) >= 0) {
            return PAID;
        }
        if (periodEnded) {
            return MISSED;
        }
        return paid.signum() > 0 ? PARTIAL : DUE;
    }
}
