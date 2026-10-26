package com.momocircle.finance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the records that describe ONE commercial wallet sale: the payment, its refunds, its merchant settlements and the
 * ledger transaction. Pure, read-only logic over integer minor units: it reports discrepancies with the relevant ids,
 * amounts and a recommended next action and never repairs, deletes or creates anything, so running it repeatedly is safe.
 *
 * <p>The records are different REPRESENTATIONS of one economic event, never separate events to add up:
 * the payment is the authoritative capture, the ledger transaction is the (simulated) movement, the settlement is the
 * merchant-payable accounting record, and a refund returns part of the capture. All of it is SIMULATED value.
 */
public final class FinancialRecordReconciler {

    public enum Kind {
        WALLET_PAYMENT_WITHOUT_LEDGER_TX,
        LEDGER_TX_SHARED_BY_PAYMENTS,
        REFUNDS_EXCEED_PAYMENT,
        PAYMENT_REFUND_STATUS_MISMATCH,
        REFUND_LEDGER_TX_SHARED,
        WALLET_REFUND_WITHOUT_LEDGER_EVIDENCE,
        DOUBLE_CREDIT_SETTLEMENT_PAID_BY_PAYOUT,
        DUPLICATE_SALE_SETTLEMENT,
        SETTLEMENT_REFUND_MISMATCH,
        SETTLEMENT_GROSS_EXCEEDS_PAYMENT
    }

    public record Refund(String id, long amountMinor, boolean completed, String ledgerTxId, boolean originalReversed) { }

    public record Settlement(String id, boolean saleType, String status, String payoutProvider, long grossMinor,
                             long refundMinor) { }

    public record Sale(String paymentId, String orderId, String merchantId, boolean walletRail, String paymentStatus,
                       long paymentMinor, String ledgerTxId, List<Refund> refunds, List<Settlement> settlements) { }

    public record Finding(Kind kind, String paymentId, String orderId, String relatedIds, long amountMinor,
                          String recommendedAction) { }

    /** payout_provider marker of a settlement closed against the sale's existing simulated wallet credit. */
    public static final String WALLET_LEDGER_PROVIDER = "wallet-ledger-simulated";

    private FinancialRecordReconciler() { }

    public static List<Finding> reconcile(List<Sale> sales) {
        List<Finding> out = new ArrayList<>();
        Map<String, List<String>> paymentsByLedgerTx = new HashMap<>();
        Map<String, List<String>> refundsByLedgerTx = new HashMap<>();

        for (Sale s : sales) {
            if (s.walletRail() && s.ledgerTxId() != null) {
                paymentsByLedgerTx.computeIfAbsent(s.ledgerTxId(), k -> new ArrayList<>()).add(s.paymentId());
            }
            for (Refund r : s.refunds()) {
                if (r.ledgerTxId() != null) {
                    refundsByLedgerTx.computeIfAbsent(r.ledgerTxId(), k -> new ArrayList<>()).add(r.id());
                }
            }
        }

        for (Sale s : sales) {
            boolean captured = "SUCCESSFUL".equals(s.paymentStatus()) || "REFUNDED".equals(s.paymentStatus());
            if (s.walletRail() && captured && s.ledgerTxId() == null) {
                out.add(new Finding(Kind.WALLET_PAYMENT_WITHOUT_LEDGER_TX, s.paymentId(), s.orderId(), "", s.paymentMinor(),
                        "Investigate manually: a wallet payment must have a ledger transaction. Do not mark it paid or refund it until traced."));
            }

            long refunded = 0;
            for (Refund r : s.refunds()) if (r.completed()) refunded += r.amountMinor();
            if (refunded > s.paymentMinor()) {
                out.add(new Finding(Kind.REFUNDS_EXCEED_PAYMENT, s.paymentId(), s.orderId(), ids(s.refunds()),
                        refunded - s.paymentMinor(), "Freeze further refunds on this payment and review the refund rows; do not auto-correct."));
            }
            boolean fully = refunded == s.paymentMinor() && s.paymentMinor() > 0;
            if (fully && !"REFUNDED".equals(s.paymentStatus())) {
                out.add(new Finding(Kind.PAYMENT_REFUND_STATUS_MISMATCH, s.paymentId(), s.orderId(), ids(s.refunds()), refunded,
                        "Refunds cover the whole payment but the payment is " + s.paymentStatus() + ": confirm the order state, then fix the status through the refund service."));
            } else if ("REFUNDED".equals(s.paymentStatus()) && refunded < s.paymentMinor()) {
                out.add(new Finding(Kind.PAYMENT_REFUND_STATUS_MISMATCH, s.paymentId(), s.orderId(), ids(s.refunds()),
                        s.paymentMinor() - refunded, "Payment says REFUNDED but completed refunds are short: find the missing refund before releasing anything."));
            }

            if (s.walletRail()) {
                for (Refund r : s.refunds()) {
                    if (r.completed() && r.ledgerTxId() == null && !r.originalReversed()) {
                        out.add(new Finding(Kind.WALLET_REFUND_WITHOUT_LEDGER_EVIDENCE, s.paymentId(), s.orderId(), r.id(),
                                r.amountMinor(), "A completed wallet refund has neither its own compensating ledger entry nor a reversal of the payment: trace it manually."));
                    }
                }
            }

            int saleSettlements = 0;
            for (Settlement st : s.settlements()) {
                if (st.saleType()) saleSettlements++;
                boolean paidByPayout = "PAID".equals(st.status()) && st.payoutProvider() != null
                        && !WALLET_LEDGER_PROVIDER.equals(st.payoutProvider()) && !"none".equals(st.payoutProvider());
                if (s.walletRail() && paidByPayout) {
                    out.add(new Finding(Kind.DOUBLE_CREDIT_SETTLEMENT_PAID_BY_PAYOUT, s.paymentId(), s.orderId(), st.id(),
                            st.grossMinor(), "The seller already received the SIMULATED wallet credit at payment time and a payout was also recorded: "
                            + "treat the payout as a duplicate presentation of the same sale; do not add it to income."));
                }
                if (st.grossMinor() > s.paymentMinor()) {
                    out.add(new Finding(Kind.SETTLEMENT_GROSS_EXCEEDS_PAYMENT, s.paymentId(), s.orderId(), st.id(),
                            st.grossMinor() - s.paymentMinor(), "Settlement gross is larger than the captured payment: review the settlement inputs."));
                }
                if (st.saleType() && st.refundMinor() != refunded) {
                    out.add(new Finding(Kind.SETTLEMENT_REFUND_MISMATCH, s.paymentId(), s.orderId(), st.id(),
                            Math.abs(st.refundMinor() - refunded), "Settlement refund total (" + st.refundMinor() + ") differs from completed refunds ("
                            + refunded + "): re-run the settlement refund sync; if it persists, flag for manual adjustment."));
                }
            }
            if (saleSettlements > 1) {
                out.add(new Finding(Kind.DUPLICATE_SALE_SETTLEMENT, s.paymentId(), s.orderId(), settlementIds(s.settlements()), 0,
                        "More than one SALE settlement exists for one payment: keep the original, cancel the duplicate by an authorised adjustment."));
            }
        }

        for (Map.Entry<String, List<String>> e : paymentsByLedgerTx.entrySet()) {
            if (e.getValue().size() > 1) {
                out.add(new Finding(Kind.LEDGER_TX_SHARED_BY_PAYMENTS, e.getValue().get(0), null, String.join(",", e.getValue()), 0,
                        "One ledger transaction backs several payments: only one can be right. Investigate before any refund."));
            }
        }
        for (Map.Entry<String, List<String>> e : refundsByLedgerTx.entrySet()) {
            if (e.getValue().size() > 1) {
                out.add(new Finding(Kind.REFUND_LEDGER_TX_SHARED, null, null, String.join(",", e.getValue()), 0,
                        "One compensating ledger entry backs several refunds: only one can be right."));
            }
        }
        return out;
    }

    private static String ids(List<Refund> refunds) {
        StringBuilder sb = new StringBuilder();
        for (Refund r : refunds) { if (sb.length() > 0) sb.append(','); sb.append(r.id()); }
        return sb.toString();
    }

    private static String settlementIds(List<Settlement> list) {
        StringBuilder sb = new StringBuilder();
        for (Settlement s : list) { if (sb.length() > 0) sb.append(','); sb.append(s.id()); }
        return sb.toString();
    }
}
