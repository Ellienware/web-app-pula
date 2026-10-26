package com.momocircle.ledger;

import com.momocircle.ledger.LedgerException.Code;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SIMULATED partial / cumulative wallet refunds posted as compensating transfers; the original is never edited. */
class LedgerRefundTransferTest {

    private static final String ZAR = "ZAR";
    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC));
    private final Actor buyer = Actor.ofUser("buyer");
    private final Actor system = Actor.asSystem();
    private String paymentTx;

    LedgerRefundTransferTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, ZAR, true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, ZAR, true));
        store.add(new Account("w-buyer", Account.Kind.USER_WALLET, "buyer", ZAR, false));
        store.add(new Account("w-seller", Account.Kind.USER_WALLET, "seller", ZAR, false));
        engine.issueDemoValue(buyer, "w-buyer", new Money(100_000, ZAR), "seed");
        paymentTx = engine.transfer(buyer, "w-buyer", "w-seller", new Money(50_000, ZAR), "pay-1").tx().id();
    }

    private static Money zar(long m) { return new Money(m, ZAR); }
    private long bal(String id) { return store.balances.get(id); }

    private void assertReconciled() {
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    @Test
    void partialRefundThenSecondPartialLeavesRemainder() {
        engine.refundTransfer(system, paymentTx, zar(10_000), "refund:a");
        engine.refundTransfer(system, paymentTx, zar(15_000), "refund:b");
        assertEquals(50_000 + 25_000, bal("w-buyer"));      // R500 paid, R250 returned
        assertEquals(25_000, bal("w-seller"));
        assertEquals(TxStatus.POSTED, store.allTransactions().stream().filter(t -> t.id().equals(paymentTx)).findFirst().get().status()); // original never edited
        assertReconciled();
    }

    @Test
    void sameKeyReplaysAndNeverRefundsTwice() {
        var first = engine.refundTransfer(system, paymentTx, zar(10_000), "refund:a");
        var again = engine.refundTransfer(system, paymentTx, zar(10_000), "refund:a");
        assertTrue(again.replayed());
        assertEquals(first.tx().id(), again.tx().id());
        assertEquals(60_000, bal("w-buyer"));
    }

    @Test
    void sameKeyDifferentAmountIsRejected() {
        engine.refundTransfer(system, paymentTx, zar(10_000), "refund:a");
        assertEquals(Code.IDEMPOTENCY_CONFLICT,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, zar(20_000), "refund:a")).code());
    }

    @Test
    void refundLargerThanThePaymentIsRejected() {
        assertEquals(Code.INVALID_AMOUNT,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, zar(50_001), "refund:x")).code());
        assertEquals(50_000, bal("w-buyer"));
    }

    @Test
    void zeroAndWrongCurrencyAreRejected() {
        assertEquals(Code.INVALID_AMOUNT,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, zar(0), "refund:z")).code());
        assertEquals(Code.CURRENCY_MISMATCH,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, new Money(100, "USD"), "refund:u")).code());
    }

    @Test
    void ordinaryUsersCannotRefund() {
        assertEquals(Code.NOT_OWNER,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(buyer, paymentTx, zar(100), "refund:u")).code());
        assertEquals(Code.NOT_OWNER,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(Actor.ofUser("seller"), paymentTx, zar(100), "refund:s")).code());
    }

    @Test
    void sellerWalletNeverOverdrawsWhenFundsAlreadySpent() {
        engine.transfer(Actor.ofUser("seller"), "w-seller", "w-buyer", zar(45_000), "seller-spends");
        assertEquals(Code.INSUFFICIENT_FUNDS,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, zar(10_000), "refund:o")).code());
        assertEquals(5_000, bal("w-seller"));
        assertReconciled();
    }

    @Test
    void cannotRefundAReversedOrNonPaymentTransaction() {
        engine.reverse(system, paymentTx, "test");
        assertEquals(Code.ILLEGAL_STATE,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, paymentTx, zar(100), "refund:r")).code());
        String issuance = store.allTransactions().stream().filter(t -> t.type() == TxType.DEMO_ISSUANCE).findFirst().get().id();
        assertEquals(Code.ILLEGAL_STATE,
                assertThrows(LedgerException.class, () -> engine.refundTransfer(system, issuance, zar(100), "refund:i")).code());
    }

    @Test
    void refundsAreRecordedAsSimulated() {
        var r = engine.refundTransfer(system, paymentTx, zar(10_000), "refund:a");
        assertTrue(r.tx().simulated());
        assertFalse(r.replayed());
    }
}
