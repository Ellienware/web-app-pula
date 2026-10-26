package com.momocircle.ledger;

/** Explicit transaction states. PENDING holds funds; POSTED is final value movement; the rest are terminal. */
public enum TxStatus {
    PENDING, POSTED, FAILED, REVERSED, CANCELLED;

    public boolean canTransitionTo(TxStatus next) {
        return switch (this) {
            case PENDING -> next == POSTED || next == FAILED || next == CANCELLED;
            case POSTED -> next == REVERSED;
            case FAILED, REVERSED, CANCELLED -> false;
        };
    }
}
