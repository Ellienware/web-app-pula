package com.momocircle.ledger;

import com.momocircle.ledger.LedgerException.Code;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure ledger-engine rules for BUSINESS wallets (no Spring, no database): who may debit a business wallet, that a business
 * context can never touch a personal wallet, anyone may pay INTO a business wallet, and the ledger stays balanced.
 */
class BusinessWalletEngineTest {

    private static final String ZAR = "ZAR";

    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));

    private final Actor alice = Actor.ofUser("alice");                     // personal
    private final Actor aliceForShop = Actor.forBusiness("alice", "shop"); // alice acting FOR business "shop"
    private final Actor bobForShop = Actor.forBusiness("bob", "shop");     // another member of the same business
    private final Actor carolForCafe = Actor.forBusiness("carol", "cafe"); // a member of a DIFFERENT business
    private final Actor admin = Actor.asAdmin("ops");

    BusinessWalletEngineTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, ZAR, true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, ZAR, true));
        store.add(new Account("w-alice", Account.Kind.USER_WALLET, "alice", ZAR, false));
        store.add(new Account("w-bob", Account.Kind.USER_WALLET, "bob", ZAR, false));
        store.add(new Account("b-shop", Account.Kind.BUSINESS_WALLET, null, "shop", ZAR, false));
        store.add(new Account("b-cafe", Account.Kind.BUSINESS_WALLET, null, "cafe", ZAR, false));
    }

    private static Money zar(long minor) { return new Money(minor, ZAR); }
    private long bal(String id) { return store.balances.get(id); }
    private static Code codeOf(Runnable r) { return assertThrows(LedgerException.class, r::run).code(); }

    private void assertReconciled() {
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    @Test
    void accountInvariantsAreEnforcedByTheDomainType() {
        assertThrows(IllegalArgumentException.class, () -> new Account("x", Account.Kind.BUSINESS_WALLET, null, null, ZAR, false), "needs a business owner");
        assertThrows(IllegalArgumentException.class, () -> new Account("x", Account.Kind.BUSINESS_WALLET, "alice", "shop", ZAR, false), "never owned by a user id");
        assertThrows(IllegalArgumentException.class, () -> new Account("x", Account.Kind.BUSINESS_WALLET, null, "shop", ZAR, true), "never negative");
        assertThrows(IllegalArgumentException.class, () -> new Account("x", Account.Kind.USER_WALLET, "alice", "shop", ZAR, false), "user wallet has no business owner");
        assertThrows(IllegalArgumentException.class, () -> new Account("x", Account.Kind.DEMO_FUNDING, null, "shop", ZAR, true), "system account has no business owner");
    }

    @Test
    void aMemberActingForTheBusinessCanFundTransferAndWithdrawFromItsWallet() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(10_000), "k1");
        assertEquals(10_000, bal("b-shop"));
        engine.transfer(aliceForShop, "b-shop", "w-bob", zar(2_500), "k2");
        assertEquals(7_500, bal("b-shop"));
        assertEquals(2_500, bal("w-bob"));
        engine.requestWithdrawal(bobForShop, "b-shop", zar(1_000), "k3");
        assertEquals(6_500, bal("b-shop"));
        assertReconciled();
    }

    @Test
    void aPlainUserEvenTheBusinessOwnerAsAPersonCannotDebitTheBusinessWallet() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(10_000), "k1");
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(alice, "b-shop", "w-alice", zar(100), "k2")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.requestWithdrawal(alice, "b-shop", zar(100), "k3")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.issueDemoValue(alice, "b-shop", zar(100), "k4")));
        assertEquals(10_000, bal("b-shop"));
    }

    @Test
    void anActorForAnotherBusinessCannotTouchThisBusinessWallet() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(10_000), "k1");
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(carolForCafe, "b-shop", "b-cafe", zar(100), "k2")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.requestWithdrawal(carolForCafe, "b-shop", zar(100), "k3")));
        assertEquals(10_000, bal("b-shop"));
        assertEquals(0, bal("b-cafe"));
    }

    @Test
    void aBusinessContextCanNeverDebitAPersonalWallet() {
        engine.issueDemoValue(alice, "w-alice", zar(5_000), "k1");
        // alice acting for her shop must not spend her personal money through the business context
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(aliceForShop, "w-alice", "w-bob", zar(100), "k2")));
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.requestWithdrawal(aliceForShop, "w-alice", zar(100), "k3")));
        assertEquals(5_000, bal("w-alice"));
    }

    @Test
    void anyoneCanPayIntoABusinessWalletAndAdminsDoNotGainOwnership() {
        engine.issueDemoValue(alice, "w-alice", zar(5_000), "k1");
        engine.transfer(alice, "w-alice", "b-shop", zar(1_200), "k2");
        assertEquals(1_200, bal("b-shop"));
        assertEquals(3_800, bal("w-alice"));
        // being an operator does not let you spend from a wallet you do not own
        assertEquals(Code.NOT_OWNER, codeOf(() -> engine.transfer(admin, "b-shop", "w-bob", zar(100), "k3")));
        assertReconciled();
    }

    @Test
    void businessWalletsCannotOverdrawAndTransfersToSelfAreRefused() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(1_000), "k1");
        assertEquals(Code.INSUFFICIENT_FUNDS, codeOf(() -> engine.transfer(aliceForShop, "b-shop", "w-bob", zar(1_001), "k2")));
        assertEquals(Code.SELF_TRANSFER, codeOf(() -> engine.transfer(aliceForShop, "b-shop", "b-shop", zar(1), "k3")));
        assertEquals(1_000, bal("b-shop"));
    }

    @Test
    void idempotencyForABusinessActionReplaysWithoutADoublePosting() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(10_000), "k1");
        var first = engine.transfer(aliceForShop, "b-shop", "w-bob", zar(100), "same");
        var replay = engine.transfer(aliceForShop, "b-shop", "w-bob", zar(100), "same");
        assertEquals(first.tx().id(), replay.tx().id());
        assertEquals(9_900, bal("b-shop"));
        assertEquals(Code.IDEMPOTENCY_CONFLICT, codeOf(() -> engine.transfer(aliceForShop, "b-shop", "w-bob", zar(101), "same")));
    }

    @Test
    void aFailedBusinessWithdrawalReturnsTheFundsToTheBusinessWallet() {
        engine.issueDemoValue(aliceForShop, "b-shop", zar(10_000), "k1");
        var w = engine.requestWithdrawal(aliceForShop, "b-shop", zar(4_000), "k2");
        assertEquals(6_000, bal("b-shop"));
        engine.failWithdrawal(admin, w.tx().id(), "simulated failure");
        assertEquals(10_000, bal("b-shop"));
        assertReconciled();
    }
}
