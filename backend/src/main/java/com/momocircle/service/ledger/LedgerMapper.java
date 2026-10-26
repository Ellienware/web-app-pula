package com.momocircle.service.ledger;

import com.momocircle.domain.ledger.LedgerPostingEntity;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Posting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds framework-free domain objects from entities without losing amounts (integer minor units) or state. */
final class LedgerMapper {

    private LedgerMapper() { }

    static LedgerTransaction toDomain(LedgerTransactionEntity t, List<LedgerPostingEntity> postingEntities) {
        List<Posting> postings = new ArrayList<>(postingEntities.size());
        for (LedgerPostingEntity p : postingEntities) postings.add(p.toDomain());
        return new LedgerTransaction(t.getId(), t.getType(), t.getStatus(), t.getInitiatorUserId(), t.getIdempotencyKey(),
                t.getFingerprint(), t.getCurrency(), postings, t.getReversesTxId(), t.getProviderReference(),
                t.getFailureReason(), t.getCreatedAt());
    }

    /** Groups a batch of posting rows by transaction id; rows must already be ordered by line number. */
    static Map<String, List<LedgerPostingEntity>> groupByTransaction(Collection<LedgerPostingEntity> rows) {
        Map<String, List<LedgerPostingEntity>> out = new HashMap<>();
        for (LedgerPostingEntity p : rows) out.computeIfAbsent(p.getTransactionId(), k -> new ArrayList<>()).add(p);
        return out;
    }
}
