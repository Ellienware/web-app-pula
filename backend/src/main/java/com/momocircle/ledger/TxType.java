package com.momocircle.ledger;

/** Distinct business events. They are deliberately separate and must never be conflated. */
public enum TxType {
    /** Clearly-labelled demo value created for a user. Not a deposit, not a bank transfer. */
    DEMO_ISSUANCE,
    /** Simulated movement between two PULA Money wallets. */
    INTERNAL_TRANSFER,
    /** Simulated withdrawal request. No real cash or bank transfer ever occurs. */
    SIMULATED_WITHDRAWAL,
    /** Compensating entries for a failed, cancelled or reversed transaction. */
    REVERSAL
}
