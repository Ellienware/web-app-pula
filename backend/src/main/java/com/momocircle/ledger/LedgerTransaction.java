package com.momocircle.ledger;

import java.time.Instant;
import java.util.List;

/**
 * One ledger transaction. Postings are immutable once created; only {@code status}/{@code failureReason} may change, and
 * only along {@link TxStatus#canTransitionTo}. Every transaction here is SIMULATED; there is no field that could mark one
 * as real money, by design.
 */
public record LedgerTransaction(
        String id,
        TxType type,
        TxStatus status,
        String initiatorUserId,
        String idempotencyKey,
        String fingerprint,
        String currency,
        List<Posting> postings,
        String reversesTxId,
        String providerReference,
        String failureReason,
        Instant createdAt) {

    public LedgerTransaction {
        postings = List.copyOf(postings);
    }

    /** Always true. Surfaced in every API response, log line and history row. */
    public boolean simulated() {
        return true;
    }

    public LedgerTransaction withStatus(TxStatus next, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new LedgerException(LedgerException.Code.ILLEGAL_STATE, "Cannot move " + status + " to " + next);
        }
        return new LedgerTransaction(id, type, next, initiatorUserId, idempotencyKey, fingerprint, currency, postings,
                reversesTxId, providerReference, reason == null ? failureReason : reason, createdAt);
    }
}
