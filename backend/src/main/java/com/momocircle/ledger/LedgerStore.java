package com.momocircle.ledger;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * Persistence contract for the ledger. The engine holds all business rules; a store only has to provide:
 * <ol>
 *   <li>{@link #atomically}: run the work with exclusive access to the named accounts (a database implementation uses
 *       {@code SELECT ... FOR UPDATE} on the account rows, always in sorted id order to avoid deadlocks) and commit or
 *       roll back as one unit;</li>
 *   <li>uniqueness of (initiator, idempotency key), enforced by the database, not only by the lookup below.</li>
 * </ol>
 * Balances held here are projections of the postings; {@link Reconciler} proves they agree.
 *
 * <p>Implementation requirement: if {@code insertAndApply} hits the (initiator, idempotency key) unique constraint
 * because a concurrent request with the same key committed first, the store must roll back and re-read via
 * {@code findByIdempotency} so the caller receives the replay result, never a raw database error.
 */
public interface LedgerStore {

    <T> T atomically(Collection<String> accountIdsToLock, Function<Work, T> work);

    Optional<String> systemAccountId(Account.Kind kind, String currency);

    interface Work {
        Optional<Account> account(String id);

        long balance(String accountId);

        Optional<LedgerTransaction> findByIdempotency(String initiatorUserId, String idempotencyKey);

        Optional<LedgerTransaction> find(String txId);

        /** Insert a new transaction AND apply every posting to the balance projections, atomically with the caller. */
        void insertAndApply(LedgerTransaction tx);

        void updateStatus(String txId, TxStatus status, String failureReason);

        /** Demo value issued to this user (POSTED issuances only) at or after {@code since}, for cap enforcement. */
        long issuedTotalMinor(String userId, String currency, java.time.Instant since);
    }
}
