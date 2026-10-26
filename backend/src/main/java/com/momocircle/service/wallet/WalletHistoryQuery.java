package com.momocircle.service.wallet;

import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Read-only history of ledger transactions that touch ONE wallet account. The JPQL is assembled from fixed fragments; every
 * value is a bound parameter. Optional filters are only added when present, which avoids the typed-null parameter problem
 * some PostgreSQL drivers have with {@code (:p is null or ...)}.
 */
@Repository
public class WalletHistoryQuery {

    @PersistenceContext
    private EntityManager em;

    public record Filter(TxType type, TxStatus status, Posting.Direction direction, Instant from, Instant to) {
        public static Filter none() { return new Filter(null, null, null, null, null); }
    }

    @Transactional(readOnly = true)
    public Page<LedgerTransactionEntity> history(String walletAccountId, Filter f, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where exists (select 1 from LedgerPostingEntity p where p.transactionId = t.id and p.accountId = :wallet");
        if (f.direction() != null) where.append(" and p.direction = :direction");
        where.append(")");
        if (f.type() != null) where.append(" and t.txType = :type");
        if (f.status() != null) where.append(" and t.status = :status");
        if (f.from() != null) where.append(" and t.createdAt >= :from");
        if (f.to() != null) where.append(" and t.createdAt < :to");

        TypedQuery<LedgerTransactionEntity> q = em.createQuery(
                "select t from LedgerTransactionEntity t" + where + " order by t.createdAt desc, t.id desc", LedgerTransactionEntity.class);
        TypedQuery<Long> c = em.createQuery("select count(t) from LedgerTransactionEntity t" + where, Long.class);
        for (TypedQuery<?> query : List.of(q, c)) {
            query.setParameter("wallet", walletAccountId);
            if (f.direction() != null) query.setParameter("direction", f.direction());
            if (f.type() != null) query.setParameter("type", f.type());
            if (f.status() != null) query.setParameter("status", f.status());
            if (f.from() != null) query.setParameter("from", f.from());
            if (f.to() != null) query.setParameter("to", f.to());
        }
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(q.getResultList(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()), c.getSingleResult());
    }

    /** True if the transaction has at least one posting on this wallet account. */
    @Transactional(readOnly = true)
    public boolean touches(String walletAccountId, String txId) {
        Long n = em.createQuery("select count(p) from LedgerPostingEntity p where p.transactionId = :tx and p.accountId = :wallet", Long.class)
                .setParameter("tx", txId).setParameter("wallet", walletAccountId).getSingleResult();
        return n != null && n > 0;
    }

    /** Operator queue: all simulated withdrawals still PENDING, oldest first. Admin use only (the caller enforces the role). */
    @Transactional(readOnly = true)
    public Page<LedgerTransactionEntity> pendingWithdrawals(Pageable pageable) {
        TypedQuery<LedgerTransactionEntity> q = em.createQuery(
                "select t from LedgerTransactionEntity t where t.txType = :type and t.status = :status order by t.createdAt asc, t.id asc",
                LedgerTransactionEntity.class);
        TypedQuery<Long> c = em.createQuery(
                "select count(t) from LedgerTransactionEntity t where t.txType = :type and t.status = :status", Long.class);
        for (TypedQuery<?> query : List.of(q, c)) {
            query.setParameter("type", TxType.SIMULATED_WITHDRAWAL);
            query.setParameter("status", TxStatus.PENDING);
        }
        q.setFirstResult((int) pageable.getOffset());
        q.setMaxResults(pageable.getPageSize());
        return new PageImpl<>(q.getResultList(), PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()), c.getSingleResult());
    }
}
