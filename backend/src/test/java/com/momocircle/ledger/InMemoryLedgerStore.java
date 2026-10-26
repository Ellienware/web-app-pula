package com.momocircle.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * TEST-ONLY reference store. It models what a database store must guarantee: exclusive per-account locking acquired in
 * sorted order, and a uniqueness constraint on (initiator, idempotency key) that rejects a duplicate insert outright.
 * It proves the engine's rules; it does NOT prove any SQL, JPA mapping, transaction isolation or Flyway migration.
 */
final class InMemoryLedgerStore implements LedgerStore {

    /** Simulated read latency; widens check-then-act race windows so tests can detect missing locking. */
    volatile long readDelayNanos = 0;

    final Map<String, Account> accounts = new ConcurrentHashMap<>();
    final Map<String, Long> balances = new ConcurrentHashMap<>();
    final Map<String, LedgerTransaction> txs = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    void add(Account a) {
        accounts.put(a.id(), a);
        balances.putIfAbsent(a.id(), 0L);
    }

    @Override
    public <T> T atomically(Collection<String> ids, Function<Work, T> work) {
        List<ReentrantLock> held = new ArrayList<>();
        try {
            for (String id : new TreeSet<>(ids)) {
                ReentrantLock l = locks.computeIfAbsent(id, k -> new ReentrantLock());
                l.lock();
                held.add(l);
            }
            return work.apply(new W());
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        }
    }

    @Override
    public Optional<String> systemAccountId(Account.Kind kind, String currency) {
        return accounts.values().stream().filter(a -> a.kind() == kind && a.currency().equals(currency))
                .map(Account::id).findFirst();
    }

    List<LedgerTransaction> allTransactions() {
        return new ArrayList<>(txs.values());
    }

    private final class W implements Work {
        @Override public Optional<Account> account(String id) { return Optional.ofNullable(accounts.get(id)); }
        @Override
        public long balance(String id) {
            long v = balances.getOrDefault(id, 0L);
            if (readDelayNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(readDelayNanos);
            return v;
        }

        @Override
        public Optional<LedgerTransaction> findByIdempotency(String user, String key) {
            String id = idempotencyIndex.get(user + "\u0000" + key);
            return id == null ? Optional.empty() : Optional.of(txs.get(id));
        }

        @Override public Optional<LedgerTransaction> find(String id) { return Optional.ofNullable(txs.get(id)); }

        @Override
        public void insertAndApply(LedgerTransaction tx) {
            String idemKey = tx.initiatorUserId() + "\u0000" + tx.idempotencyKey();
            if (idempotencyIndex.putIfAbsent(idemKey, tx.id()) != null) {
                throw new IllegalStateException("UNIQUE violation on (initiator, idempotency_key)");
            }
            txs.put(tx.id(), tx);
            Map<String, Long> d = new HashMap<>();
            for (Posting p : tx.postings()) d.merge(p.accountId(), p.delta(), Long::sum);
            d.forEach((acc, delta) -> balances.merge(acc, delta, Long::sum));
        }

        @Override
        public void updateStatus(String id, TxStatus status, String reason) {
            txs.computeIfPresent(id, (k, v) -> v.withStatus(status, reason));
        }

        @Override
        public long issuedTotalMinor(String userId, String currency, java.time.Instant since) {
            return txs.values().stream()
                    .filter(t -> t.type() == TxType.DEMO_ISSUANCE && t.status() == TxStatus.POSTED
                            && t.initiatorUserId().equals(userId) && t.currency().equals(currency)
                            && !t.createdAt().isBefore(since))
                    .mapToLong(t -> t.postings().get(0).minor()).sum();
        }
    }
}
