package com.momocircle.ledger;

import java.time.Duration;

/**
 * Per-operation ceilings, in minor units. PROVISIONAL demo values, configurable by the application (see
 * {@code LedgerConfig}); enforced server-side. The per-user issuance ceiling applies to a ROLLING window.
 */
public record LedgerLimits(long maxTransferMinor, long maxIssuancePerRequestMinor, long maxIssuanceTotalPerUserMinor,
                           long maxWithdrawalMinor, Duration issuanceWindow) {

    public LedgerLimits {
        if (issuanceWindow == null || issuanceWindow.isZero() || issuanceWindow.isNegative()) {
            throw new IllegalArgumentException("issuanceWindow must be a positive duration");
        }
    }

    /** R10,000 per transfer; R5,000 per issuance request; R20,000 issued per user per rolling 30 days; R10,000 per withdrawal. */
    public static LedgerLimits demoDefaults() {
        return new LedgerLimits(1_000_000L, 500_000L, 2_000_000L, 1_000_000L, Duration.ofDays(30));
    }
}
