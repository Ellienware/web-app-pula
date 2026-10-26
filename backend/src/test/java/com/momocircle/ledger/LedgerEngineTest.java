package com.momocircle.ledger;

import com.momocircle.ledger.LedgerException.Code;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerEngineTest {

    private static final String ZAR = "ZAR";

    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));

    private final Actor alice = Actor.ofUser("alice");
    private final Actor bob = Actor.ofUser("bob");
    private final Actor admin = Actor.asAdmin("ops");

    LedgerEngineTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, ZAR, true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, ZAR, true));
        store.add(new Account("w-alice", Account.Kind.USER_WALLET, "alice", ZAR, false));
        store.add(new Account("w-bob", Account.Kind.USER_WALLET, "bob", ZAR, false));
        store.add(new Account("w-usd", Account.Kind.USER_WALLET, "alice", "USD", false));
    }

    private static Money zar(long minor) { return new Money(minor, ZAR); }

    private long bal(String id) { return store.balances.get(id); }

    private void assertReconciled() {
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    private void fund(Actor who, String wallet, long minor) {
        engine.issueDemoValue(who, wallet, zar(minor), "seed-" + who.userId() + "-" + seq.incrementAndGet());
    }

    private static Code codeOf(Runnable r) {
        return assertThrows(LedgerException.class, r::run).code();
    }

    // ------------------------------------------------------------ money

    @Test
    void legacyDecimalsConvertExactlyAndExtraPrecisionIsRejectedNotRounded() {
        assertEquals(1234L, Money.ofDecimal(new BigDecimal("12.34"), ZAR).minor());
        assertEquals(1050L, Money.ofDecimal(new BigDecimal("10.5"), ZAR).minor());
        assertEquals(new BigDecimal("12.34"), new Money(1234, ZAR).toDecimal());
        assertEquals(Code.INVALID_AMOUNT, codeOf(() -> Money.ofDecimal(new BigDecimal("12.345"), ZAR)));
        assertEquals(Code.INVALID_AMOUNT, codeOf(() -> new Money(-1, ZAR)));
        assertEquals(Code.INVALID_CURRENCY, codeOf(() -> new Money(1, "zar")));
        assertEquals(Code.INVALID_CURRENCY, codeOf(() -> Money.ofDecimal(BigDecimal.ONE, "ZZZ")));
    }

    // ------------------------------------------------------------ issuance

    @Test
    void demoIssuanceIsLabelledBalancedAndClassifiedSeparately() {
        LedgerEngine.Result r = engine.issueDemoValue(alice, "w-alice", zar(10_000), "k1");
        assertEquals(TxType.DEMO_ISSUANCE, r.tx().type());
        assertEquals(TxStatus.POSTED, r.tx().status());
        assertTrue(r.tx().simulated());
        assertTrue(r.tx().providerReference().startsWith("SIM-"));
        assertEquals(10_000L, bal("w-alice"));
        assertEquals(-10_000L, bal("fund"));
        assertReconciled();
    }

    @Test
    void issuanceCapsAndOwnershipAreEnforced() {
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> engine.issueDemoValue(alice, "w-alice", zar(500_001), "big")));
        for (int i = 0; i < 4; i++) engine.issueDemoValue(alice, "w-alice", zar(500_000), "cap" + i);
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> engine.issueDemoValue(alice, "w-alice", zar(1), "over-total")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.issueDemoValue(bob, "w-alice", zar(100), "steal")));
        assertEquals(Code.INVALID_AMOUNT, codeOf(() -> engine.issueDemoValue(bob, "w-bob", zar(0), "zero")));
        assertReconciled();
    }

    // ------------------------------------------------------------ transfers

    @Test
    void transferMovesValueAndRejectsInsufficientBalance() {
        fund(alice, "w-alice", 1_000);
        engine.transfer(alice, "w-alice", "w-bob", zar(400), "t1");
        assertEquals(600L, bal("w-alice"));
        assertEquals(400L, bal("w-bob"));
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.transfer(alice, "w-alice", "w-bob", zar(601), "t2")));
        assertEquals(600L, bal("w-alice"));
        assertReconciled();
    }

    @Test
    void cannotSpendFromAnotherUsersWalletAndInvalidTargetsAreRejected() {
        fund(alice, "w-alice", 1_000);
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(bob, "w-alice", "w-bob", zar(100), "x1")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(admin, "w-alice", "w-bob", zar(100), "x2"))); // admins cannot move user funds
        assertEquals(Code.SELF_TRANSFER, codeOf(() -> engine.transfer(alice, "w-alice", "w-alice", zar(1), "x3")));
        assertEquals(Code.ACCOUNT_NOT_FOUND, codeOf(() -> engine.transfer(alice, "w-alice", "nope", zar(1), "x4")));
        assertEquals(Code.ACCOUNT_NOT_FOUND, codeOf(() -> engine.transfer(alice, "w-alice", "fund", zar(1), "x5"))); // cannot target a system account
        assertEquals(Code.CURRENCY_MISMATCH, codeOf(() -> engine.transfer(alice, "w-alice", "w-usd", zar(1), "x6")));
        assertEquals(Code.INVALID_AMOUNT, codeOf(() -> engine.transfer(alice, "w-alice", "w-bob", zar(0), "x7")));
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> engine.transfer(alice, "w-alice", "w-bob", zar(1_000_001), "x8")));
        assertEquals(1_000L, bal("w-alice"));
        assertReconciled();
    }

    // ------------------------------------------------------------ idempotency

    @Test
    void replayedKeyHasASingleEffectAndMismatchedReuseIsRejected() {
        fund(alice, "w-alice", 1_000);
        LedgerEngine.Result first = engine.transfer(alice, "w-alice", "w-bob", zar(100), "same");
        LedgerEngine.Result again = engine.transfer(alice, "w-alice", "w-bob", zar(100), "same");
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.tx().id(), again.tx().id());
        assertEquals(100L, bal("w-bob"));
        assertEquals(Code.IDEMPOTENCY_CONFLICT, codeOf(() -> engine.transfer(alice, "w-alice", "w-bob", zar(200), "same")));
        assertEquals(Code.IDEMPOTENCY_CONFLICT, codeOf(() -> engine.transfer(alice, "w-alice", "w-bob", zar(1), " ")));
        assertEquals(100L, bal("w-bob"));
    }

    @Test
    void anotherUsersKeyIsNeverReplayedToYou() {
        fund(alice, "w-alice", 1_000);
        fund(bob, "w-bob", 1_000);
        LedgerEngine.Result a = engine.transfer(alice, "w-alice", "w-bob", zar(100), "shared-key");
        LedgerEngine.Result b = engine.transfer(bob, "w-bob", "w-alice", zar(50), "shared-key");
        assertFalse(b.replayed());
        assertTrue(!a.tx().id().equals(b.tx().id()));
        assertReconciled();
    }

    // ------------------------------------------------------------ concurrency

    private <T> List<Object> runConcurrently(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<T> j : jobs) {
            fs.add(pool.submit(() -> {
                go.await();
                try { return j.call(); } catch (LedgerException e) { return e.code(); }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get());
        pool.shutdown();
        return out;
    }

    @Test
    void concurrentTransfersCannotOverspend() throws Exception {
        store.readDelayNanos = 300_000; // simulate DB latency so a missing lock is detectable
        fund(alice, "w-alice", 1_000);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String key = "c" + i;
            jobs.add(() -> engine.transfer(alice, "w-alice", "w-bob", zar(100), key));
        }
        List<Object> results = runConcurrently(jobs);
        long ok = results.stream().filter(r -> r instanceof LedgerEngine.Result).count();
        long insufficient = results.stream().filter(r -> r == Code.INSUFFICIENT_FUNDS).count();
        assertEquals(10L, ok);
        assertEquals(40L, insufficient);
        assertEquals(0L, bal("w-alice"));
        assertEquals(1_000L, bal("w-bob"));
        assertReconciled();
    }

    @Test
    void concurrentDuplicateRequestsHaveExactlyOneEffect() throws Exception {
        store.readDelayNanos = 300_000; // simulate DB latency so a missing lock is detectable
        fund(alice, "w-alice", 1_000);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) jobs.add(() -> engine.transfer(alice, "w-alice", "w-bob", zar(100), "dup"));
        List<Object> results = runConcurrently(jobs);
        long fresh = results.stream().filter(r -> r instanceof LedgerEngine.Result && !((LedgerEngine.Result) r).replayed()).count();
        assertEquals(1L, fresh);
        assertEquals(900L, bal("w-alice"));
        assertEquals(100L, bal("w-bob"));
        assertReconciled();
    }

    @Test
    void crossTransfersInOppositeDirectionsDoNotDeadlockAndConserveValue() throws Exception {
        store.readDelayNanos = 300_000; // simulate DB latency so a missing lock is detectable
        fund(alice, "w-alice", 10_000);
        fund(bob, "w-bob", 10_000);
        List<Callable<LedgerEngine.Result>> jobs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String key = "x" + i;
            if (i % 2 == 0) jobs.add(() -> engine.transfer(alice, "w-alice", "w-bob", zar(10), key));
            else jobs.add(() -> engine.transfer(bob, "w-bob", "w-alice", zar(10), key));
        }
        runConcurrently(jobs);
        assertEquals(20_000L, bal("w-alice") + bal("w-bob"));
        assertReconciled();
    }

    // ------------------------------------------------------------ reversal / lifecycle

    @Test
    void reversalRestoresBalancesViaCompensatingEntriesAndIsIdempotent() {
        fund(alice, "w-alice", 1_000);
        LedgerTransaction t = engine.transfer(alice, "w-alice", "w-bob", zar(300), "r1").tx();
        LedgerTransaction rev = engine.reverse(admin, t.id(), "support request");
        assertEquals(TxStatus.REVERSED, rev.status());
        assertEquals(1_000L, bal("w-alice"));
        assertEquals(0L, bal("w-bob"));
        assertEquals(TxStatus.REVERSED, engine.reverse(admin, t.id(), "again").status());
        long reversalCount = store.allTransactions().stream().filter(x -> x.type() == TxType.REVERSAL).count();
        assertEquals(1L, reversalCount);
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.reverse(alice, t.id(), "me")));
        assertReconciled();
    }

    @Test
    void reversalFailsClosedWhenTheRecipientAlreadySpentTheFunds() {
        fund(alice, "w-alice", 1_000);
        LedgerTransaction t = engine.transfer(alice, "w-alice", "w-bob", zar(300), "s1").tx();
        engine.transfer(bob, "w-bob", "w-alice", zar(250), "s2");
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.reverse(admin, t.id(), "too late")));
        assertEquals(TxStatus.POSTED, store.txs.get(t.id()).status());
        assertReconciled();
    }

    @Test
    void aReversalCannotBeReversed() {
        fund(alice, "w-alice", 1_000);
        LedgerTransaction t = engine.transfer(alice, "w-alice", "w-bob", zar(100), "rr").tx();
        engine.reverse(admin, t.id(), "x");
        String reversalId = store.allTransactions().stream().filter(x -> x.type() == TxType.REVERSAL).findFirst().get().id();
        assertEquals(Code.ILLEGAL_STATE, codeOf(() -> engine.reverse(admin, reversalId, "loop")));
    }

    @Test
    void withdrawalHoldsFundsThenCompletesFailsOrCancels() {
        fund(alice, "w-alice", 1_000);
        LedgerTransaction w1 = engine.requestWithdrawal(alice, "w-alice", zar(400), "w1").tx();
        assertEquals(TxStatus.PENDING, w1.status());
        assertEquals(600L, bal("w-alice"));
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.requestWithdrawal(alice, "w-alice", zar(700), "w2")));

        assertEquals(TxStatus.POSTED, engine.completeWithdrawal(Actor.asSystem(), w1.id()).status());
        assertEquals(TxStatus.POSTED, engine.completeWithdrawal(Actor.asSystem(), w1.id()).status()); // idempotent
        assertEquals(600L, bal("w-alice"));

        LedgerTransaction w3 = engine.requestWithdrawal(alice, "w-alice", zar(100), "w3").tx();
        assertEquals(TxStatus.FAILED, engine.failWithdrawal(admin, w3.id(), "simulated bank decline").status());
        assertEquals(600L, bal("w-alice")); // funds returned

        LedgerTransaction w4 = engine.requestWithdrawal(alice, "w-alice", zar(50), "w4").tx();
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.cancelWithdrawal(bob, w4.id())));
        assertEquals(TxStatus.CANCELLED, engine.cancelWithdrawal(alice, w4.id()).status());
        assertEquals(600L, bal("w-alice"));

        assertEquals(Code.ILLEGAL_STATE, codeOf(() -> engine.completeWithdrawal(Actor.asSystem(), w3.id())));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.completeWithdrawal(alice, w1.id())));
        assertReconciled();
    }

    // ------------------------------------------------------------ reconciliation + state machine

    @Test
    void reconcilerDetectsTamperedProjectionsAndUnbalancedTransactions() {
        fund(alice, "w-alice", 1_000);
        store.balances.put("w-alice", 999L);
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.stream().anyMatch(x -> x.kind().equals("BALANCE_MISMATCH") && x.subject().equals("w-alice")));
        store.balances.put("w-alice", 1_000L);

        LedgerTransaction bad = new LedgerTransaction("bad", TxType.INTERNAL_TRANSFER, TxStatus.POSTED, "alice", "bad", "f", ZAR,
                List.of(new Posting("w-alice", Posting.Direction.DEBIT, 5, ZAR), new Posting("w-bob", Posting.Direction.CREDIT, 4, ZAR)),
                null, "SIM-BAD", null, Instant.EPOCH);
        List<LedgerTransaction> all = store.allTransactions();
        all.add(bad);
        List<Reconciler.Discrepancy> d2 = Reconciler.reconcile(store.accounts.values(), store.balances, all);
        assertTrue(d2.stream().anyMatch(x -> x.kind().equals("UNBALANCED_TX")));
        assertTrue(d2.stream().anyMatch(x -> x.kind().equals("VALUE_NOT_CONSERVED")));
    }

    @Test
    void stateMachineAllowsOnlyDefinedTransitions() {
        assertTrue(TxStatus.PENDING.canTransitionTo(TxStatus.POSTED));
        assertTrue(TxStatus.PENDING.canTransitionTo(TxStatus.FAILED));
        assertTrue(TxStatus.PENDING.canTransitionTo(TxStatus.CANCELLED));
        assertTrue(TxStatus.POSTED.canTransitionTo(TxStatus.REVERSED));
        assertFalse(TxStatus.POSTED.canTransitionTo(TxStatus.FAILED));
        assertFalse(TxStatus.POSTED.canTransitionTo(TxStatus.CANCELLED));
        for (TxStatus terminal : new TxStatus[]{TxStatus.FAILED, TxStatus.REVERSED, TxStatus.CANCELLED}) {
            for (TxStatus next : TxStatus.values()) assertFalse(terminal.canTransitionTo(next));
        }
    }

    @Test
    void aTransactionThatDoesNotBalanceIsRejectedBeforeAnythingIsStored() {
        List<Posting> unbalanced = List.of(new Posting("w-alice", Posting.Direction.DEBIT, 5, ZAR),
                new Posting("w-bob", Posting.Direction.CREDIT, 4, ZAR));
        assertEquals(Code.UNBALANCED, codeOf(() -> LedgerEngine.assertBalanced(unbalanced, ZAR)));
        assertEquals(Code.UNBALANCED, codeOf(() -> LedgerEngine.assertBalanced(unbalanced.subList(0, 1), ZAR)));
    }

    // ------------------------------------------------------------ rolling issuance window (Phase 9A completion)

    private static final class StepClock extends Clock {
        private Instant now = Instant.parse("2026-10-13T00:00:00Z");
        void advance(java.time.Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void issuanceLimitIsARollingWindowNotALifetimeCap() {
        StepClock clock = new StepClock();
        AtomicLong ids = new AtomicLong();
        LedgerEngine windowed = new LedgerEngine(store, LedgerLimits.demoDefaults(), () -> "w-" + ids.incrementAndGet(), clock);
        for (int i = 0; i < 4; i++) windowed.issueDemoValue(alice, "w-alice", zar(500_000), "win" + i); // R20,000 issued today
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> windowed.issueDemoValue(alice, "w-alice", zar(1), "blocked")));
        clock.advance(java.time.Duration.ofDays(29));
        assertEquals(Code.LIMIT_EXCEEDED, codeOf(() -> windowed.issueDemoValue(alice, "w-alice", zar(1), "still-blocked")));
        clock.advance(java.time.Duration.ofDays(2)); // first issuances are now older than 30 days
        windowed.issueDemoValue(alice, "w-alice", zar(500_000), "reopened");
        assertEquals(2_500_000L, bal("w-alice"));
        assertReconciled();
    }

    @Test
    void aReversedIssuanceNoLongerCountsAgainstTheLimit() {
        for (int i = 0; i < 4; i++) engine.issueDemoValue(alice, "w-alice", zar(500_000), "rv" + i);
        LedgerTransaction first = store.allTransactions().stream()
                .filter(x -> x.type() == TxType.DEMO_ISSUANCE).findFirst().get();
        engine.reverse(admin, first.id(), "issued in error");
        engine.issueDemoValue(alice, "w-alice", zar(500_000), "after-reversal");
        assertReconciled();
    }

    @Test
    void limitsRejectNonPositiveWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerLimits(1, 1, 1, 1, java.time.Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerLimits(1, 1, 1, 1, java.time.Duration.ofDays(-1)));
    }

    // ------------------------------------------------------------ reversal audit in the reconciler

    private List<Reconciler.Discrepancy> reconcileWith(List<LedgerTransaction> txs) {
        return Reconciler.reconcile(store.accounts.values(), store.balances, txs);
    }

    @Test
    void reconcilerDetectsMissingDuplicateAndMalformedReversals() {
        fund(alice, "w-alice", 1_000);
        LedgerTransaction t = engine.transfer(alice, "w-alice", "w-bob", zar(300), "audit").tx();
        engine.reverse(admin, t.id(), "audit");
        List<LedgerTransaction> all = store.allTransactions();
        assertTrue(reconcileWith(all).isEmpty());

        LedgerTransaction reversal = all.stream().filter(x -> x.type() == TxType.REVERSAL).findFirst().get();

        List<LedgerTransaction> withoutReversal = new ArrayList<>(all);
        withoutReversal.remove(reversal);
        assertTrue(reconcileWith(withoutReversal).stream().anyMatch(d -> d.kind().equals("REVERSAL_MISSING")));

        List<LedgerTransaction> duplicated = new ArrayList<>(all);
        duplicated.add(new LedgerTransaction("dup", TxType.REVERSAL, TxStatus.POSTED, "ops", "reversal:dup", "f", ZAR,
                reversal.postings(), t.id(), "SIM-DUP", null, Instant.EPOCH));
        assertTrue(reconcileWith(duplicated).stream().anyMatch(d -> d.kind().equals("DUPLICATE_REVERSAL")));

        List<LedgerTransaction> malformed = new ArrayList<>(all);
        malformed.remove(reversal);
        malformed.add(new LedgerTransaction(reversal.id(), TxType.REVERSAL, TxStatus.POSTED, "ops", "reversal:x", "f", ZAR,
                t.postings(), t.id(), "SIM-BAD", null, Instant.EPOCH)); // same direction as the original: not a mirror
        assertTrue(reconcileWith(malformed).stream().anyMatch(d -> d.kind().equals("REVERSAL_INVALID")));

        List<LedgerTransaction> orphan = new ArrayList<>(all);
        orphan.removeIf(x -> x.id().equals(t.id()));
        assertTrue(reconcileWith(orphan).stream().anyMatch(d -> d.kind().equals("REVERSAL_INVALID")));
    }
}
