package com.momocircle.service.ledger;

import com.momocircle.domain.ledger.LedgerAccountEntity;
import com.momocircle.domain.ledger.LedgerPostingEntity;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.ledger.LedgerStore;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import com.momocircle.repository.ledger.LedgerAccountRepository;
import com.momocircle.repository.ledger.LedgerPostingRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * PostgreSQL/H2-backed {@link LedgerStore}. The database is the authority: balances are a projection changed only in the
 * same transaction as the postings, and {@code LedgerReconciliationService} proves they agree.
 *
 * <p><b>Locking.</b> {@link #atomically} locks the named account rows with {@code SELECT ... FOR UPDATE} in ascending id
 * order (a single global order, so opposite-direction transfers cannot deadlock), then runs the work in the SAME
 * transaction. Because every issuance also locks the shared DEMO_FUNDING account, per-user issuance limits are
 * serialised as well.
 *
 * <p><b>Retries.</b> A new top-level transaction is attempted up to {@value #MAX_ATTEMPTS} times when the previous one
 * rolled back for a lock timeout, deadlock, serialization failure (SQLSTATE 40001/40P01), or because a concurrent request
 * with the same idempotency key or the same reversal committed first (unique constraints
 * {@code uq_ledger_idem}, {@code uq_ledger_single_reversal}). Each retry re-runs the whole unit of work from scratch, so
 * it observes the winner's committed row and the engine returns the replay, never a second effect. If the caller is
 * already inside a transaction the work joins it and cannot be retried; call the engine from a non-transactional service.
 *
 * <p><b>Errors.</b> No raw database exception escapes: they become {@link LedgerException}.
 */
@Component
public class JpaLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(JpaLedgerStore.class);
    static final int MAX_ATTEMPTS = 4;

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerPostingRepository postings;
    private final TransactionTemplate template;

    @PersistenceContext
    private EntityManager em;

    public JpaLedgerStore(LedgerAccountRepository accounts, LedgerTransactionRepository transactions,
                          LedgerPostingRepository postings, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
        this.template = new TransactionTemplate(transactionManager);
        this.template.setTimeout(15); // seconds; bounds how long one attempt may wait on locks
    }

    // ------------------------------------------------------------------ LedgerStore

    @Override
    public <T> T atomically(Collection<String> accountIdsToLock, Function<Work, T> work) {
        List<String> ordered = new ArrayList<>(new TreeSet<>(accountIdsToLock));

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                return run(ordered, work);
            } catch (LedgerException e) {
                throw e;
            } catch (RuntimeException e) {
                throw translate(e);
            }
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return template.execute(status -> run(ordered, work));
            } catch (LedgerException e) {
                throw e; // business rule failure: the transaction was rolled back, nothing to retry
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    throw translate(e);
                }
                log.warn("Ledger transaction attempt {}/{} rolled back ({}); retrying", attempt, MAX_ATTEMPTS,
                        e.getClass().getSimpleName());
                backoff(attempt);
            }
        }
        log.error("Ledger transaction abandoned after {} attempts; nothing was applied", MAX_ATTEMPTS);
        throw new LedgerException(Code.CONCURRENCY_FAILURE, "The ledger is busy; nothing was changed. Please retry.");
    }

    @Override
    public Optional<String> systemAccountId(Account.Kind kind, String currency) {
        return accounts.findSystemAccount(kind, currency).map(LedgerAccountEntity::getId);
    }

    // ------------------------------------------------------------------ one attempt

    private <T> T run(List<String> orderedIds, Function<Work, T> work) {
        for (String id : orderedIds) {
            accounts.findByIdForUpdate(id); // absent ids are tolerated here; the engine reports ACCOUNT_NOT_FOUND
        }
        return work.apply(new Tx());
    }

    private final class Tx implements Work {

        @Override
        public Optional<Account> account(String id) {
            return accounts.findById(id).map(LedgerAccountEntity::toDomain);
        }

        @Override
        public long balance(String accountId) {
            return accounts.findBalanceMinor(accountId).orElse(0L);
        }

        @Override
        public Optional<LedgerTransaction> findByIdempotency(String initiatorUserId, String idempotencyKey) {
            return transactions.findByInitiatorAndKey(initiatorUserId, idempotencyKey).map(this::load);
        }

        @Override
        public Optional<LedgerTransaction> find(String txId) {
            return transactions.findById(txId).map(this::load);
        }

        @Override
        public void insertAndApply(LedgerTransaction tx) {
            // Parent first, flushed, so the composite foreign keys and unique constraints are checked here.
            em.persist(LedgerTransactionEntity.from(tx));
            em.flush();
            int line = 1;
            for (Posting p : tx.postings()) {
                em.persist(LedgerPostingEntity.of(UUID.randomUUID().toString(), tx.id(), line++, p, tx.createdAt()));
            }
            em.flush();
            Map<String, Long> deltas = new TreeMap<>();
            for (Posting p : tx.postings()) deltas.merge(p.accountId(), p.delta(), Long::sum);
            for (Map.Entry<String, Long> e : deltas.entrySet()) {
                if (accounts.addToBalance(e.getKey(), e.getValue()) != 1) {
                    throw new LedgerException(Code.ACCOUNT_NOT_FOUND, "Account not found");
                }
            }
        }

        @Override
        public void updateStatus(String txId, TxStatus status, String failureReason) {
            LedgerTransactionEntity e = em.find(LedgerTransactionEntity.class, txId);
            if (e == null) {
                throw new LedgerException(Code.NOT_FOUND, "Transaction not found");
            }
            e.changeStatus(status, failureReason, Instant.now());
            em.flush();
        }

        @Override
        public long issuedTotalMinor(String userId, String currency, Instant since) {
            Long v = transactions.sumIssued(TxType.DEMO_ISSUANCE, TxStatus.POSTED, userId, currency,
                    Posting.Direction.CREDIT, since);
            return v == null ? 0L : v;
        }

        private LedgerTransaction load(LedgerTransactionEntity t) {
            return LedgerMapper.toDomain(t, postings.findByTransaction(t.getId()));
        }
    }

    // ------------------------------------------------------------------ failure classification

    static boolean isRetryable(Throwable e) {
        if (e instanceof ConcurrencyFailureException) return true;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException) {
                String state = ((SQLException) t).getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) return true; // serialization failure / deadlock
            }
            // Type-agnostic on purpose: em.flush() inside this (non-@Repository) bean surfaces raw Hibernate/JPA exceptions
            // that Spring has not translated, so we key on the named constraint, not on the exception class.
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("uq_ledger_idem") || m.contains("uq_ledger_single_reversal")) return true;
        }
        return false;
    }

    static LedgerException translate(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("ck_ledger_balance_floor")) {
                return new LedgerException(Code.INSUFFICIENT_FUNDS, "Insufficient funds");
            }
        }
        if (e instanceof DataAccessException || e instanceof TransactionException
                || e instanceof jakarta.persistence.PersistenceException) {
            log.error("Ledger storage failure ({}); nothing was applied", e.getClass().getSimpleName());
            return new LedgerException(Code.CONCURRENCY_FAILURE, "The ledger could not complete the operation. Nothing was changed.");
        }
        throw e;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 25L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LedgerException(Code.CONCURRENCY_FAILURE, "Interrupted while waiting to retry. Nothing was changed.");
        }
    }
}
