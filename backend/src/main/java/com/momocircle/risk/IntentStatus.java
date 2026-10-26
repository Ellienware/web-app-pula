package com.momocircle.risk;

public enum IntentStatus {
    /** Assessed; no provider reference bound yet. */
    ASSESSED,
    /** Bound to a provider reference; the provider may now be called for exactly this amount/currency. */
    AUTHORISED,
    /** The provider accepted the initiation. */
    INITIATED,
    /** Initiation was attempted and failed. */
    FAILED
}
