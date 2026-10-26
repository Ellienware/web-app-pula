package com.momocircle.domain.enums;

/**
 * Lifecycle of one participant's share, which is also the "split-bill payment request" shown to that participant.
 * Only {@link #PAID} with settlement method LEDGER means simulated demo-wallet value moved, and only because a shared-ledger
 * transaction was committed. None of these states ever refers to real-world money.
 */
public enum ShareStatus {
    /** Created by the expense creator; the participant has not responded. Nothing is debited. */
    PENDING,
    /** The participant agrees they owe the amount. Still nothing is debited. */
    ACCEPTED,
    /** The participant disagrees with the amount; the creator must resolve it. */
    DISPUTED,
    /** The participant says they are not part of this expense. */
    DECLINED,
    /** The participant confirmed payment and a ledger transfer is being posted (held by exactly one claimant). */
    PROCESSING,
    /** Settled: by a committed ledger transfer, by the payer's own share, or recorded as received outside PULA. */
    PAID,
    /** A settled ledger transfer was returned to the participant by an explicit, confirmed compensating transfer. */
    REFUNDED,
    /** A settled ledger transfer was reversed by an operator through the ledger's reversal. */
    REVERSED,
    /** The expense was cancelled before this share was settled. */
    CANCELLED;

    /** Debt still owed (unsettled and not cancelled). */
    public boolean isOutstanding() {
        return this == PENDING || this == ACCEPTED || this == DISPUTED || this == DECLINED || this == PROCESSING;
    }

    /** Statuses from which the participant may pay (payment is itself the explicit confirmation of the amount). */
    public boolean isPayable() {
        return this == PENDING || this == ACCEPTED;
    }
}
