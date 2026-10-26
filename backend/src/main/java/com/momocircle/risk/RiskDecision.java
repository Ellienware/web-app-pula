package com.momocircle.risk;

public enum RiskDecision {
    /** May proceed to the existing payment flow (which still applies all of its own validation). */
    ALLOW,
    /** The authenticated user must re-confirm this exact transaction (amount and currency) before it can proceed. */
    STEP_UP,
    /** Held for an authorised administrator. */
    REVIEW,
    /** Rejected by a hard rule. */
    BLOCK
}
