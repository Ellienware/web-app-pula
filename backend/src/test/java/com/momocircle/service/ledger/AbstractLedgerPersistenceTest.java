package com.momocircle.service.ledger;

import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.Posting;
import com.momocircle.ledger.Posting.Direction;
import com.momocircle.ledger.Reconciler;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;

/**
 * Real-database scenarios for the ledger persistence layer. Subclasses choose the database (H2 in PostgreSQL mode, or
 * PostgreSQL via Testcontainers). Tests run WITHOUT a surrounding test transaction on purpose: every operation commits for
 * real, which is what concurrency, idempotency races and constraint behaviour require. Each test uses brand-new users so
 * tests do not interfere; the shared system accounts are asserted by delta.
 *
 * <p>Concurrency is coordinated with a start latch (all workers released together) and asserted on exact outcomes; there
 * are no sleeps used as proof of safety.
 */

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaLedgerStore.class, LedgerAccountService.class, LedgerReconciliationService.class, LedgerConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractLedgerPersistenceTest {

    static final String ZAR = "ZAR";
    static final int THREADS = 16;

    @Autowired LedgerEngine engine;
    @Autowired JpaLedgerStore store;
    @Autowired LedgerAccountService accountService;
    @Autowired LedgerReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    /** Tracks raw SQL transaction fixtures that must be cleaned up after each test. */
    private final Set<String> schemaFixtureTransactionIds = new HashSet<>();
    /** PostgreSQL-with-guards variants return true: commit-time triggers then also reject drift. */
    protected boolean databaseGuardsEnabled() {
        return false;
    }

    // ------------------------------------------------------------------ fixtures

    String newUser() {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, now, now, id + "@test.local", "Ledger Test", "0000000000", "x", "USER", true);
        return id;
    }

    String wallet(String userId) {
        return accountService.getOrCreateWallet(userId, ZAR).id();
    }

    private String fundingId() {
        return store.systemAccountId(Account.Kind.DEMO_FUNDING, ZAR).orElseThrow();
    }

    private String clearingId() {
        return store.systemAccountId(Account.Kind.WITHDRAWAL_CLEARING, ZAR).orElseThrow();
    }

    long balance(String accountId) {
        return jdbc.queryForObject("select balance_minor from ledger_accounts where id = ?", Long.class, accountId);
    }

    static Money zar(long minor) {
        return new Money(minor, ZAR);
    }

    void fund(String userId, String walletId, long minor) {
        engine.issueDemoValue(Actor.ofUser(userId), walletId, zar(minor), "seed-" + UUID.randomUUID());
    }

    void assertReconciled() {
        List<Reconciler.Discrepancy> d = reconciliation.reconcile();
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    static Code codeOf(Runnable r) {
        return assertThrows(LedgerException.class, r::run).code();
    }

    /** Runs all jobs at the same instant; each element is the job's result or the LedgerException code it raised. */
    <T> List<Object> concurrently(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return job.call();
                } catch (LedgerException e) {
                    return e.code();
                }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    static long count(List<Object> results, Object marker) {
        return results.stream().filter(r -> r == marker).count();
    }

    static long successes(List<Object> results) {
        return results.stream().filter(r -> r instanceof LedgerEngine.Result).count();
    }

    // ------------------------------------------------------------------ persistence

    @Test
    void issuanceIsPersistedAndReloadedWithExactAmountsAndState() {
        String u = newUser();
        String w = wallet(u);
        long fundingBefore = balance(fundingId());

        LedgerEngine.Result r = engine.issueDemoValue(Actor.ofUser(u), w, zar(12_345), "issue-" + UUID.randomUUID());

        LedgerTransaction loaded = store.atomically(List.of(), work -> work.find(r.tx().id()).orElseThrow());
        assertEquals(TxType.DEMO_ISSUANCE, loaded.type());
        assertEquals(TxStatus.POSTED, loaded.status());
        assertEquals(u, loaded.initiatorUserId());
        assertEquals(ZAR, loaded.currency());
        assertTrue(loaded.simulated());
        assertTrue(loaded.providerReference().startsWith("SIM-"));
        assertEquals(r.tx().postings(), loaded.postings());
        assertEquals(12_345L, loaded.postings().get(0).minor());
        assertNotNull(loaded.createdAt());
        assertEquals(12_345L, balance(w));
        assertEquals(fundingBefore - 12_345L, balance(fundingId()));
        assertReconciled();
    }

    @Test
    void aFailureAfterTheTransactionRowIsInsertedRollsBackEverything() {
        String u = newUser();
        String w = wallet(u);
        String txId = UUID.randomUUID().toString();
        LedgerTransaction tx = new LedgerTransaction(txId, TxType.INTERNAL_TRANSFER, TxStatus.POSTED, u, "half-" + txId,
                "f", ZAR, List.of(new Posting(w, Direction.DEBIT, 5, ZAR), new Posting("no-such-account", Direction.CREDIT, 5, ZAR)),
                null, "SIM-" + txId.substring(0, 12).toUpperCase(), null, Instant.now());

        LedgerException e = assertThrows(LedgerException.class,
                () -> store.atomically(List.of(w), work -> { work.insertAndApply(tx); return null; }));
        assertFalse(e.getMessage().toLowerCase().contains("ledger_postings"), "no raw SQL detail may leak");
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions where id = ?", Long.class, txId));
        assertEquals(0L, balance(w));
        assertReconciled();
    }

    @Test
    void anInjectedBusinessFailureAfterInsertRollsBackTheTransactionAndBalances() {
        String u = newUser();
        String w = wallet(u);
        fund(u, w, 1_000);
        String txId = UUID.randomUUID().toString();
        LedgerTransaction tx = new LedgerTransaction(txId, TxType.INTERNAL_TRANSFER, TxStatus.POSTED, u, "inj-" + txId, "f", ZAR,
                List.of(new Posting(w, Direction.DEBIT, 100, ZAR), new Posting(fundingId(), Direction.CREDIT, 100, ZAR)),
                null, "SIM-" + txId.substring(0, 12).toUpperCase(), null, Instant.now());
        assertThrows(LedgerException.class, () -> store.atomically(List.of(w, fundingId()), work -> {
            work.insertAndApply(tx);
            throw new LedgerException(Code.ILLEGAL_STATE, "injected");
        }));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions where id = ?", Long.class, txId));
        assertEquals(1_000L, balance(w));
        assertReconciled();
    }

    // ------------------------------------------------------------------ rules through the real store

    @Test
    void transfersEnforceOverdraftOwnershipLimitsAndCurrency() {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        engine.transfer(Actor.ofUser(a), wa, wb, zar(400), "t-" + UUID.randomUUID());
        assertEquals(600L, balance(wa));
        assertEquals(400L, balance(wb));
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(601), "o-" + UUID.randomUUID())));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(Actor.ofUser(b), wa, wb, zar(1), "n-" + UUID.randomUUID())));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(Actor.asAdmin("ops"), wa, wb, zar(1), "ad-" + UUID.randomUUID())));
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(1_000_001), "l-" + UUID.randomUUID())));
        assertEquals(Code.ACCOUNT_NOT_FOUND, codeOf(() -> engine.transfer(Actor.ofUser(a), wa, "ghost", zar(1), "g-" + UUID.randomUUID())));
        assertEquals(Code.ACCOUNT_NOT_FOUND, codeOf(() -> engine.transfer(Actor.ofUser(a), wa, fundingId(), zar(1), "s-" + UUID.randomUUID())));
        assertEquals(600L, balance(wa));
        assertReconciled();
    }

    @Test
    void idempotencyIsEnforcedPerInitiatorByTheDatabase() {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        fund(b, wb, 1_000);
        String key = "shared-" + UUID.randomUUID();

        LedgerEngine.Result first = engine.transfer(Actor.ofUser(a), wa, wb, zar(100), key);
        LedgerEngine.Result again = engine.transfer(Actor.ofUser(a), wa, wb, zar(100), key);
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.tx().id(), again.tx().id());
        assertEquals(900L, balance(wa));

        assertEquals(Code.IDEMPOTENCY_CONFLICT, codeOf(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(200), key)));

        LedgerEngine.Result other = engine.transfer(Actor.ofUser(b), wb, wa, zar(50), key); // same key, different actor
        assertFalse(other.replayed());
        assertTrue(!other.tx().id().equals(first.tx().id()));
        assertReconciled();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentTransfersCannotOverspend() throws Exception {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS + 8; i++) {
            String key = "ov-" + UUID.randomUUID();
            jobs.add(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(100), key));
        }
        List<Object> results = concurrently(jobs);
        assertEquals(10L, successes(results));
        assertEquals((long) jobs.size() - 10, count(results, Code.INSUFFICIENT_FUNDS));
        assertEquals(0L, balance(wa));
        assertEquals(1_000L, balance(wb));
        assertReconciled();
    }

    @Test
    void concurrentRequestsWithTheSameIdempotencyKeyHaveExactlyOneEffect() throws Exception {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        String key = "dup-" + UUID.randomUUID();
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) jobs.add(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(100), key));
        List<Object> results = concurrently(jobs);

        assertEquals((long) THREADS, successes(results), "every duplicate must succeed as a replay, none may fail: " + results);
        Set<String> ids = new HashSet<>();
        long fresh = 0;
        for (Object r : results) {
            LedgerEngine.Result res = (LedgerEngine.Result) r;
            ids.add(res.tx().id());
            if (!res.replayed()) fresh++;
        }
        assertEquals(1, ids.size());
        assertEquals(1L, fresh);
        assertEquals(900L, balance(wa));
        assertEquals(100L, balance(wb));
        assertReconciled();
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlockAndConserveValue() throws Exception {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 10_000);
        fund(b, wb, 10_000);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS * 2; i++) {
            String key = "x-" + UUID.randomUUID();
            if (i % 2 == 0) jobs.add(() -> engine.transfer(Actor.ofUser(a), wa, wb, zar(10), key));
            else jobs.add(() -> engine.transfer(Actor.ofUser(b), wb, wa, zar(10), key));
        }
        List<Object> results = concurrently(jobs);
        assertEquals((long) jobs.size(), successes(results), "no transfer should fail or time out: " + results);
        assertEquals(20_000L, balance(wa) + balance(wb));
        assertReconciled();
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawTheWallet() throws Exception {
        String u = newUser();
        String w = wallet(u);
        fund(u, w, 1_000);
        long clearingBefore = balance(clearingId());
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "wd-" + UUID.randomUUID();
            jobs.add(() -> engine.requestWithdrawal(Actor.ofUser(u), w, zar(100), key));
        }
        List<Object> results = concurrently(jobs);
        assertEquals(10L, successes(results));
        assertEquals((long) THREADS - 10, count(results, Code.INSUFFICIENT_FUNDS));
        assertEquals(0L, balance(w));
        assertEquals(clearingBefore + 1_000L, balance(clearingId()));
        assertReconciled();
    }

    @Test
    void concurrentReversalsOfOneTransactionProduceExactlyOneReversal() throws Exception {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        LedgerTransaction t = engine.transfer(Actor.ofUser(a), wa, wb, zar(300), "rev-" + UUID.randomUUID()).tx();
        List<Callable<LedgerTransaction>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) jobs.add(() -> engine.reverse(Actor.asAdmin("ops"), t.id(), "race"));
        List<Object> results = concurrently(jobs);
        for (Object r : results) {
            assertTrue(r instanceof LedgerTransaction, "unexpected result: " + r);
            assertEquals(TxStatus.REVERSED, ((LedgerTransaction) r).status());
        }
        assertEquals(1L, jdbc.queryForObject("select count(*) from ledger_transactions where reverses_tx_id = ?", Long.class, t.id()));
        assertEquals(1_000L, balance(wa));
        assertEquals(0L, balance(wb));
        assertReconciled();
    }

    @Test
    void concurrentIssuanceCannotExceedThePerUserWindowLimit() throws Exception {
        String u = newUser();
        String w = wallet(u);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "iss-" + UUID.randomUUID();
            jobs.add(() -> engine.issueDemoValue(Actor.ofUser(u), w, zar(500_000), key)); // R5,000 each; cap is R20,000
        }
        List<Object> results = concurrently(jobs);
        assertEquals(4L, successes(results));
        assertEquals(4L, count(results, Code.LIMIT_EXCEEDED));
        assertEquals(2_000_000L, balance(w));
        assertReconciled();
    }

    @Test
    void concurrentWalletCreationYieldsOneWallet() throws Exception {
        String u = newUser();
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) jobs.add(() -> accountService.getOrCreateWallet(u, ZAR).id());
        List<Object> results = concurrently(jobs);
        Set<Object> distinct = new HashSet<>(results);
        assertEquals(1, distinct.size(), "all callers must see the same wallet: " + results);
        assertEquals(1L, jdbc.queryForObject("select count(*) from ledger_accounts where owner_user_id = ?", Long.class, u));
    }

    // ------------------------------------------------------------------ lifecycle + reconciliation

    @Test
    void aMixtureOfTransfersFailuresWithdrawalsAndReversalsStillReconciles() {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 5_000);
        fund(b, wb, 2_000);
        LedgerTransaction t1 = engine.transfer(Actor.ofUser(a), wa, wb, zar(700), "m1-" + UUID.randomUUID()).tx();
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.transfer(Actor.ofUser(b), wb, wa, zar(99_999), "m2-" + UUID.randomUUID())));
        LedgerTransaction w1 = engine.requestWithdrawal(Actor.ofUser(a), wa, zar(500), "m3-" + UUID.randomUUID()).tx();
        engine.completeWithdrawal(Actor.asSystem(), w1.id());
        LedgerTransaction w2 = engine.requestWithdrawal(Actor.ofUser(a), wa, zar(200), "m4-" + UUID.randomUUID()).tx();
        engine.failWithdrawal(Actor.asAdmin("ops"), w2.id(), "simulated decline");
        LedgerTransaction w3 = engine.requestWithdrawal(Actor.ofUser(b), wb, zar(100), "m5-" + UUID.randomUUID()).tx();
        engine.cancelWithdrawal(Actor.ofUser(b), w3.id());
        engine.reverse(Actor.asAdmin("ops"), t1.id(), "support");
        assertEquals(4_500L, balance(wa));
        assertEquals(2_000L, balance(wb));
        assertReconciled();
    }

    @Test
    void reversalFailsClosedWhenTheRecipientAlreadySpentTheFunds() {
        String a = newUser(), b = newUser();
        String wa = wallet(a), wb = wallet(b);
        fund(a, wa, 1_000);
        LedgerTransaction t = engine.transfer(Actor.ofUser(a), wa, wb, zar(300), "fc1-" + UUID.randomUUID()).tx();
        engine.transfer(Actor.ofUser(b), wb, wa, zar(250), "fc2-" + UUID.randomUUID());
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.reverse(Actor.asAdmin("ops"), t.id(), "too late")));
        assertEquals("POSTED", jdbc.queryForObject("select status from ledger_transactions where id = ?", String.class, t.id()));
        assertReconciled();
    }

    // ------------------------------------------------------------------ schema constraints (raw SQL)

    private void insertTx(String id, String type, String key, boolean simulated, String reverses, String ccy) {
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
                "insert into ledger_transactions (id, type, status, initiator_user_id, idempotency_key, fingerprint, currency, "
                        + "reverses_tx_id, provider_reference, simulated, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, type, "POSTED", "raw", key, "fp", ccy, reverses,
                "SIM-" + id.substring(0, 12).toUpperCase(), simulated, now, now
        );

        // Track only successful inserts; failed constraint checks must not be recorded.
        schemaFixtureTransactionIds.add(id);
    }

    @AfterEach
    void cleanSchemaFixtureTransactions() {
        if (schemaFixtureTransactionIds.isEmpty()) {
            return;
        }

        List<String> ids = new ArrayList<>(schemaFixtureTransactionIds);
        String placeholders = String.join(
                ",",
                java.util.Collections.nCopies(ids.size(), "?")
        );
        Object[] args = ids.toArray();

        jdbc.update(
                "delete from ledger_postings where transaction_id in (" + placeholders + ")",
                args
        );

        // Remove reversal children before their original transactions.
        jdbc.update(
                "delete from ledger_transactions where type = 'REVERSAL' and id in ("
                        + placeholders + ")",
                args
        );

        jdbc.update(
                "delete from ledger_transactions where id in (" + placeholders + ")",
                args
        );

        schemaFixtureTransactionIds.clear();
    }

    @Test
    void theSchemaRejectsNonSimulatedTransactions() {
        String id = UUID.randomUUID().toString();
        assertThrows(DataAccessException.class, () -> insertTx(id, "INTERNAL_TRANSFER", "ns-" + id, false, null, ZAR));
    }

    @Test
    void theSchemaRejectsDuplicateIdempotencyKeysPerInitiatorButAllowsOtherInitiators() {
        String k = "dupkey-" + UUID.randomUUID();
        insertTx(UUID.randomUUID().toString(), "INTERNAL_TRANSFER", k, true, null, ZAR);
        assertThrows(DataAccessException.class, () -> insertTx(UUID.randomUUID().toString(), "INTERNAL_TRANSFER", k, true, null, ZAR));
    }

    @Test
    void theSchemaRejectsMismatchedCurrenciesAndBadPostings() {
        String u = newUser();
        String w = wallet(u);
        String txId = UUID.randomUUID().toString();
        insertTx(txId, "INTERNAL_TRANSFER", "cur-" + txId, true, null, ZAR);
        Timestamp now = Timestamp.from(Instant.now());
        String sql = "insert into ledger_postings (id, transaction_id, line_no, account_id, direction, amount_minor, currency, created_at) values (?,?,?,?,?,?,?,?)";
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, UUID.randomUUID().toString(), txId, 1, w, "CREDIT", 5, "USD", now)); // wallet is ZAR
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, UUID.randomUUID().toString(), txId, 2, w, "CREDIT", 0, ZAR, now));   // zero amount
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, UUID.randomUUID().toString(), txId, 3, "ghost", "CREDIT", 5, ZAR, now)); // unknown account
        assertThrows(DataAccessException.class, () -> jdbc.update(sql, UUID.randomUUID().toString(), "ghost", 4, w, "CREDIT", 5, ZAR, now));   // unknown tx
    }

    @Test
    void theSchemaRejectsANegativeWalletBalanceAndAWalletWithoutAnOwner() {
        String u = newUser();
        String w = wallet(u);
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_accounts set balance_minor = -1 where id = ?", w));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into ledger_accounts (id, kind, owner_user_id, system_key, currency, allow_negative, balance_minor, created_at) "
                        + "values (?, 'USER_WALLET', null, null, 'ZAR', false, 0, ?)", UUID.randomUUID().toString(), Timestamp.from(Instant.now())));
    }

    @Test
    void theSchemaAllowsAtMostOneReversalPerTransaction() {
        String orig = UUID.randomUUID().toString();
        insertTx(orig, "INTERNAL_TRANSFER", "o-" + orig, true, null, ZAR);
        insertTx(UUID.randomUUID().toString(), "REVERSAL", "r1-" + orig, true, orig, ZAR);
        assertThrows(DataAccessException.class, () -> insertTx(UUID.randomUUID().toString(), "REVERSAL", "r2-" + orig, true, orig, ZAR));
        assertThrows(DataAccessException.class, () -> insertTx(UUID.randomUUID().toString(), "REVERSAL", "r3-" + UUID.randomUUID(), true, null, ZAR)); // reversal without original
    }

    // ------------------------------------------------------------------ reconciliation detects drift

    @Test
    void reconciliationDetectsABalanceProjectionThatDriftedFromThePostings() {
        String u = newUser();
        String w = wallet(u);
        fund(u, w, 1_000);
        assertReconciled();
        if (databaseGuardsEnabled()) {
            // With the PostgreSQL guards the database itself refuses the drift at commit.
            assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_accounts set balance_minor = balance_minor + 1 where id = ?", w));
            assertReconciled();
            return;
        }
        jdbc.update("update ledger_accounts set balance_minor = balance_minor + 1 where id = ?", w);
        try {
            List<Reconciler.Discrepancy> d = reconciliation.reconcile();
            assertTrue(d.stream().anyMatch(x -> x.kind().equals("BALANCE_MISMATCH") && x.subject().equals(w)), "drift not detected: " + d);
        } finally {
            jdbc.update("update ledger_accounts set balance_minor = balance_minor - 1 where id = ?", w);
        }
        assertReconciled();
    }
}
