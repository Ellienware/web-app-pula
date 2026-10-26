package com.momocircle.domain.enums;

/** How a PAID share was settled. Only LEDGER corresponds to simulated funds moving through the shared ledger. */
public enum SettlementMethod {
    /** A committed Phase 9B internal wallet transfer; the share names its ledger transaction. */
    LEDGER,
    /** The payer's own portion of the expense: nothing to transfer. */
    SELF,
    /** The payer confirmed they were repaid outside PULA. PULA moved nothing and does not verify it. */
    EXTERNAL,
    /** Marked settled by hand before split-bill settlement existed; no ledger transaction exists. */
    LEGACY,
    /** A zero-amount share: nothing is owed. */
    NONE
}
