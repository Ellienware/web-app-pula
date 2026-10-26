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

/** The ledger rules the wholesale wallet checkout relies on: business wallet to business wallet, SIMULATED value. */
class WholesaleBusinessToBusinessPaymentTest {

    private static final String ZAR = "ZAR";
    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC));
    private final Actor buyerOwner = Actor.forBusiness("owner-b", "buyer-biz");
    private final Actor otherBizMember = Actor.forBusiness("owner-c", "other-biz");
    private final Actor buyerAsPerson = Actor.ofUser("owner-b");

    WholesaleBusinessToBusinessPaymentTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, ZAR, true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, ZAR, true));
        store.add(new Account("b-buyer", Account.Kind.BUSINESS_WALLET, null, "buyer-biz", ZAR, false));
        store.add(new Account("b-supplier", Account.Kind.BUSINESS_WALLET, null, "supplier-biz", ZAR, false));
        store.add(new Account("b-other", Account.Kind.BUSINESS_WALLET, null, "other-biz", ZAR, false));
        engine.issueDemoValue(buyerOwner, "b-buyer", new Money(100_000, ZAR), "seed");
    }

    private static Money zar(long m) { return new Money(m, ZAR); }
    private long bal(String id) { return store.balances.get(id); }

    private void assertReconciled() {
        List<Reconciler.Discrepancy> d = Reconciler.reconcile(store.accounts.values(), store.balances, store.allTransactions());
        assertTrue(d.isEmpty(), "reconciliation discrepancies: " + d);
    }

    @Test
    void businessPaysSupplierOnceEvenIfRetriedWithTheSameKey() {
        var first = engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(40_000), "mkt:pay-1");
        var again = engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(40_000), "mkt:pay-1");
        assertTrue(again.replayed());
        assertEquals(first.tx().id(), again.tx().id());
        assertEquals(60_000, bal("b-buyer"));
        assertEquals(40_000, bal("b-supplier"));
        assertReconciled();
    }

    @Test
    void anotherBusinessCannotChargeTheBuyingBusinessWallet() {
        assertEquals(Code.NOT_OWNER, assertThrows(LedgerException.class,
                () -> engine.transfer(otherBizMember, "b-buyer", "b-supplier", zar(1_000), "mkt:evil")).code());
        assertEquals(100_000, bal("b-buyer"));
    }

    @Test
    void theOwnerAsAPersonCannotDebitTheBusinessWallet() {
        assertEquals(Code.NOT_OWNER, assertThrows(LedgerException.class,
                () -> engine.transfer(buyerAsPerson, "b-buyer", "b-supplier", zar(1_000), "mkt:person")).code());
    }

    @Test
    void insufficientBusinessBalanceCreatesNoDebit() {
        assertEquals(Code.INSUFFICIENT_FUNDS, assertThrows(LedgerException.class,
                () -> engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(100_001), "mkt:big")).code());
        assertEquals(100_000, bal("b-buyer"));
        assertEquals(0, bal("b-supplier"));
    }

    @Test
    void sameKeyWithADifferentAmountIsRejectedNotSilentlyReplayed() {
        engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(40_000), "mkt:pay-2");
        assertEquals(Code.IDEMPOTENCY_CONFLICT, assertThrows(LedgerException.class,
                () -> engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(50_000), "mkt:pay-2")).code());
    }

    @Test
    void sellerCreditCanBeRefundedInPartsWithoutExceedingThePayment() {
        String pay = engine.transfer(buyerOwner, "b-buyer", "b-supplier", zar(40_000), "mkt:pay-3").tx().id();
        engine.refundTransfer(Actor.asSystem(), pay, zar(10_000), "refund:1");
        engine.refundTransfer(Actor.asSystem(), pay, zar(30_000), "refund:2");
        assertEquals(100_000, bal("b-buyer"));
        assertEquals(0, bal("b-supplier"));
        assertEquals(Code.INSUFFICIENT_FUNDS, assertThrows(LedgerException.class,
                () -> engine.refundTransfer(Actor.asSystem(), pay, zar(1), "refund:3")).code());
        assertReconciled();
    }
}
