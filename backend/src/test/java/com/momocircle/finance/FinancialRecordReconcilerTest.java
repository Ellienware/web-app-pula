package com.momocircle.finance;

import com.momocircle.finance.FinancialRecordReconciler.Finding;
import com.momocircle.finance.FinancialRecordReconciler.Kind;
import com.momocircle.finance.FinancialRecordReconciler.Refund;
import com.momocircle.finance.FinancialRecordReconciler.Sale;
import com.momocircle.finance.FinancialRecordReconciler.Settlement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialRecordReconcilerTest {

    private static final String W = FinancialRecordReconciler.WALLET_LEDGER_PROVIDER;

    private static Sale sale(String status, long pay, String ledger, List<Refund> r, List<Settlement> s) {
        return new Sale("p1", "o1", "m1", true, status, pay, ledger, r, s);
    }

    private static boolean has(List<Finding> f, Kind k) { return f.stream().anyMatch(x -> x.kind() == k); }

    @Test
    void cleanSaleWithPartialRefundsAndWalletClosedSettlementHasNoFindings() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("SUCCESSFUL", 50_000, "L1",
                List.of(new Refund("r1", 10_000, true, "L2", false), new Refund("r2", 15_000, true, "L3", false)),
                List.of(new Settlement("s1", true, "PAID", W, 50_000, 25_000)))));
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void settlementPaidByPayoutOnAWalletSaleIsFlaggedAsDoubleCredit() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("SUCCESSFUL", 50_000, "L1", List.of(),
                List.of(new Settlement("s1", true, "PAID", "sandbox", 50_000, 0)))));
        assertTrue(has(f, Kind.DOUBLE_CREDIT_SETTLEMENT_PAID_BY_PAYOUT));
    }

    @Test
    void refundsExceedingPaymentAreFlagged() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("REFUNDED", 10_000, "L1",
                List.of(new Refund("r1", 6_000, true, "L2", false), new Refund("r2", 6_000, true, "L3", false)), List.of())));
        assertTrue(has(f, Kind.REFUNDS_EXCEED_PAYMENT));
    }

    @Test
    void fullyRefundedButPaymentStillSuccessfulIsFlagged() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("SUCCESSFUL", 10_000, "L1",
                List.of(new Refund("r1", 4_000, true, "L2", false), new Refund("r2", 6_000, true, "L3", false)), List.of())));
        assertTrue(has(f, Kind.PAYMENT_REFUND_STATUS_MISMATCH));
    }

    @Test
    void pendingOrFailedRefundsDoNotCountAsRefunded() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("SUCCESSFUL", 10_000, "L1",
                List.of(new Refund("r1", 10_000, false, null, false)), List.of())));
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void completedWalletRefundWithoutLedgerEvidenceIsFlagged() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("SUCCESSFUL", 10_000, "L1",
                List.of(new Refund("r1", 3_000, true, null, false)), List.of())));
        assertTrue(has(f, Kind.WALLET_REFUND_WITHOUT_LEDGER_EVIDENCE));
    }

    @Test
    void soleFullRefundByReversalNeedsNoOwnLedgerEntry() {
        var f = FinancialRecordReconciler.reconcile(List.of(sale("REFUNDED", 10_000, "L1",
                List.of(new Refund("r1", 10_000, true, null, true)), List.of())));
        assertTrue(f.isEmpty(), f.toString());
    }

    @Test
    void duplicateSaleSettlementsAndSharedLedgerLinksAreFlagged() {
        Sale a = new Sale("p1", "o1", "m1", true, "SUCCESSFUL", 100, "L1", List.of(),
                List.of(new Settlement("s1", true, "NOT_ELIGIBLE", null, 100, 0), new Settlement("s2", true, "NOT_ELIGIBLE", null, 100, 0)));
        Sale b = new Sale("p2", "o2", "m1", true, "SUCCESSFUL", 100, "L1", List.of(), List.of());
        var f = FinancialRecordReconciler.reconcile(List.of(a, b));
        assertTrue(has(f, Kind.DUPLICATE_SALE_SETTLEMENT));
        assertTrue(has(f, Kind.LEDGER_TX_SHARED_BY_PAYMENTS));
    }

    @Test
    void walletPaymentWithoutLedgerTxAndSettlementRefundMismatchAreFlagged() {
        var f = FinancialRecordReconciler.reconcile(List.of(
                sale("SUCCESSFUL", 100, null, List.of(), List.of()),
                new Sale("p9", "o9", "m1", true, "SUCCESSFUL", 100, "L9",
                        List.of(new Refund("r1", 40, true, "L8", false)), List.of(new Settlement("s1", true, "NOT_ELIGIBLE", null, 100, 0)))));
        assertTrue(has(f, Kind.WALLET_PAYMENT_WITHOUT_LEDGER_TX));
        assertTrue(has(f, Kind.SETTLEMENT_REFUND_MISMATCH));
    }

    @Test
    void runningTwiceGivesTheSameResultAndChangesNothing() {
        List<Sale> in = List.of(sale("SUCCESSFUL", 100, null, List.of(), List.of()));
        assertEquals(FinancialRecordReconciler.reconcile(in), FinancialRecordReconciler.reconcile(in));
    }
}
