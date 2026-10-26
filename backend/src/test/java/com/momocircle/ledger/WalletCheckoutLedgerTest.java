package com.momocircle.ledger;

import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.payment.PaymentRails;
import com.momocircle.payment.WalletPaymentVerification;
import com.momocircle.payment.WalletPaymentVerification.Outcome;
import org.junit.jupiter.api.Test;

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

/**
 * The money rules behind paying an order from the SIMULATED wallet, run against the real {@link LedgerEngine} (no Spring, no
 * database): the buyer's personal wallet pays the seller's business wallet under the per-payment key from
 * {@link PaymentRails#ledgerKey}, exactly as {@code WalletCheckoutService} does. Everything is simulated demo value.
 */
class WalletCheckoutLedgerTest {

    private static final String ZAR = "ZAR";

    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC));

    private final Actor buyer = Actor.ofUser("buyer");
    private final Actor mallory = Actor.ofUser("mallory");
    private final Actor sellerStaff = Actor.forBusiness("owner", "shop");
    private final Actor system = Actor.asSystem();

    WalletCheckoutLedgerTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, ZAR, true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, ZAR, true));
        store.add(new Account("w-buyer", Account.Kind.USER_WALLET, "buyer", ZAR, false));
        store.add(new Account("w-mallory", Account.Kind.USER_WALLET, "mallory", ZAR, false));
        store.add(new Account("w-other", Account.Kind.USER_WALLET, "other", ZAR, false));
        store.add(new Account("b-shop", Account.Kind.BUSINESS_WALLET, null, "shop", ZAR, false));
        engine.issueDemoValue(buyer, "w-buyer", zar(10_000), "fund-1"); // R100.00 of demo value
    }

    private static Money zar(long minor) { return new Money(minor, ZAR); }
    private long bal(String id) { return store.balances.get(id); }
    private static Code codeOf(Runnable r) { return assertThrows(LedgerException.class, r::run).code(); }

    private LedgerEngine.Result pay(Actor who, String paymentId, long minor) {
        return engine.transfer(who, "w-buyer", "b-shop", zar(minor), PaymentRails.ledgerKey(paymentId));
    }

    private void assertReconciled() {
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    private static Outcome verify(LedgerTransaction tx, long expectedMinor) {
        return WalletPaymentVerification.evaluate(tx.status(), tx.type(), tx.initiatorUserId(), "buyer", tx.currency(), ZAR,
                tx.postings(), expectedMinor);
    }

    @Test
    void anOrderPaymentDebitsTheBuyerCreditsTheSellersSimulatedWalletAndVerifiesAsPaid() {
        LedgerEngine.Result r = pay(buyer, "pay-1", 2_550);
        assertFalse(r.replayed());
        assertEquals(7_450, bal("w-buyer"));
        assertEquals(2_550, bal("b-shop"));
        assertEquals(TxType.INTERNAL_TRANSFER, r.tx().type());
        assertEquals(Outcome.PAID, verify(r.tx(), 2_550));
        assertReconciled();
    }

    @Test
    void retriesAndDoubleTapsReplayTheSameDebitAndNeverChargeTwice() {
        LedgerEngine.Result first = pay(buyer, "pay-1", 2_550);
        LedgerEngine.Result again = pay(buyer, "pay-1", 2_550);
        assertTrue(again.replayed());
        assertEquals(first.tx().id(), again.tx().id());
        assertEquals(7_450, bal("w-buyer"));
        assertEquals(2_550, bal("b-shop"));
        assertReconciled();
    }

    @Test
    void theSameKeyWithATamperedAmountIsRejectedAndMovesNothing() {
        pay(buyer, "pay-1", 2_550);
        assertEquals(Code.IDEMPOTENCY_CONFLICT, codeOf(() -> pay(buyer, "pay-1", 100)));
        assertEquals(7_450, bal("w-buyer"));
        assertEquals(2_550, bal("b-shop"));
    }

    @Test
    void insufficientBalanceMovesNothingAndTheOrderStaysUnpaid() {
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> pay(buyer, "pay-big", 10_001)));
        assertEquals(10_000, bal("w-buyer"));
        assertEquals(0, bal("b-shop"));
        // Funding the wallet afterwards lets the very same payment succeed under the very same key.
        engine.issueDemoValue(buyer, "w-buyer", zar(5_000), "fund-2");
        assertEquals(Outcome.PAID, verify(pay(buyer, "pay-big", 10_001).tx(), 10_001));
        assertReconciled();
    }

    @Test
    void nobodyCanSpendAnotherUsersWalletToPayAnOrder() {
        assertEquals(Code.NOT_OWNER, codeOf(() -> pay(mallory, "pay-evil", 100)));
        assertEquals(10_000, bal("w-buyer"));
        assertEquals(0, bal("b-shop"));
    }

    @Test
    void aBusinessContextCannotPayAnOrderOutOfAPersonalWallet() {
        assertEquals(Code.NOT_OWNER, codeOf(() -> pay(sellerStaff, "pay-x", 100)));
        assertEquals(10_000, bal("w-buyer"));
    }

    @Test
    void invalidAmountsAndCurrenciesAreRefused() {
        assertEquals(Code.INVALID_AMOUNT, codeOf(() -> pay(buyer, "pay-0", 0)));
        assertThrows(LedgerException.class, () -> new Money(1, "zar"), "lower-case currency is not a currency code");
        assertEquals(Code.CURRENCY_MISMATCH, codeOf(() -> engine.transfer(buyer, "w-buyer", "b-shop", new Money(100, "USD"), "k-usd")));
        assertEquals(10_000, bal("w-buyer"));
    }

    @Test
    void concurrentRepeatsOfTheSamePaymentDebitExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LedgerEngine.Result>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<LedgerEngine.Result> c = () -> { start.await(); return pay(buyer, "pay-race", 2_000); };
            futures.add(pool.submit(c));
        }
        start.countDown();
        long replays = 0;
        String txId = null;
        for (Future<LedgerEngine.Result> f : futures) {
            LedgerEngine.Result r = f.get();
            if (r.replayed()) replays++;
            if (txId == null) txId = r.tx().id();
            assertEquals(txId, r.tx().id());
        }
        pool.shutdown();
        assertEquals(threads - 1, replays);
        assertEquals(8_000, bal("w-buyer"));
        assertEquals(2_000, bal("b-shop"));
        assertReconciled();
    }

    @Test
    void concurrentDifferentOrdersCannotOverspendTheWallet() throws Exception {
        int threads = 12; // 12 x R30.00 against R100.00: at most 3 can succeed
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try { pay(buyer, "pay-" + n, 3_000); return true; }
                catch (LedgerException e) { return e.code() == Code.INSUFFICIENT_FUNDS ? false : null; }
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : futures) {
            Boolean r = f.get();
            assertTrue(r != null, "only INSUFFICIENT_FUNDS is an acceptable refusal");
            if (r) ok++;
        }
        pool.shutdown();
        assertEquals(3, ok);
        assertEquals(1_000, bal("w-buyer"));
        assertEquals(9_000, bal("b-shop"));
        assertReconciled();
    }

    @Test
    void aRefundIsACompensatingReversalNeverAnEditAndIsIdempotent() {
        LedgerEngine.Result paid = pay(buyer, "pay-1", 2_550);
        LedgerTransaction reversed = engine.reverse(system, paid.tx().id(), "Order refund o-1");
        assertEquals(TxStatus.REVERSED, reversed.status());
        assertEquals(10_000, bal("w-buyer"));
        assertEquals(0, bal("b-shop"));
        long txCount = store.allTransactions().size();
        // Repeating the refund is a no-op: no second reversal, no second credit.
        assertEquals(TxStatus.REVERSED, engine.reverse(system, paid.tx().id(), "Order refund o-1").status());
        assertEquals(txCount, store.allTransactions().size());
        assertEquals(10_000, bal("w-buyer"));
        // A reversed debit never verifies as a paid order.
        assertEquals(Outcome.FAILED, WalletPaymentVerification.evaluate(TxStatus.REVERSED, TxType.INTERNAL_TRANSFER, "buyer", "buyer",
                ZAR, ZAR, paid.tx().postings(), 2_550));
        assertReconciled();
    }

    @Test
    void aRefundFailsClosedWhenTheSellerHasAlreadySpentTheFunds() {
        LedgerEngine.Result paid = pay(buyer, "pay-1", 2_550);
        engine.transfer(sellerStaff, "b-shop", "w-other", zar(2_000), "seller-spend-1"); // seller moved the demo funds on
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.reverse(system, paid.tx().id(), "Order refund o-1")));
        assertEquals(7_450, bal("w-buyer"));
        assertEquals(550, bal("b-shop"));
        assertEquals(TxStatus.POSTED, store.allTransactions().stream().filter(t -> t.id().equals(paid.tx().id())).findFirst().get().status());
        assertReconciled();
    }

    @Test
    void onlyAnOperatorOrTheSystemCanReverseAPayment() {
        LedgerEngine.Result paid = pay(buyer, "pay-1", 2_550);
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.reverse(buyer, paid.tx().id(), "I want my money back")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.reverse(sellerStaff, paid.tx().id(), "no")));
        assertEquals(7_450, bal("w-buyer"));
    }
}
