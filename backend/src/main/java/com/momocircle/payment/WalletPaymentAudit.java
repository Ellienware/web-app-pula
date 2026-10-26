package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.ledger.TxStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciliation rules for wallet-funded orders: payment record, ledger transaction and business order must agree.
 * Pure logic; the caller supplies the three facts and reports whatever this returns. It never repairs anything.
 */
public final class WalletPaymentAudit {

    /** The order's state reduced to what matters for money. */
    public enum OrderView { AWAITING_PAYMENT, PAID, REFUND_IN_PROGRESS, REFUNDED, CANCELLED }

    public enum Issue {
        PAID_WITHOUT_LEDGER_TRANSACTION,
        PAID_BUT_LEDGER_NOT_POSTED,
        DEBITED_BUT_PAYMENT_UNRESOLVED,
        DEBITED_BUT_PAYMENT_FAILED,
        REFUNDED_BUT_LEDGER_NOT_REVERSED,
        ORDER_PAID_WITHOUT_SUCCESSFUL_PAYMENT,
        PAYMENT_SUCCESSFUL_BUT_ORDER_NOT_PAID
    }

    private WalletPaymentAudit() { }

    /** @param ledgerStatus null when the payment has no linked ledger transaction (or it cannot be found) */
    public static List<Issue> check(PaymentStatus payment, TxStatus ledgerStatus, OrderView order) {
        return check(payment, ledgerStatus, order, false);
    }

    /**
     * @param refundedByCompensatingTransfers true when the refunds that returned the money did so with their own
     *        compensating ledger transfers (partial / cumulative refunds), in which case the ORIGINAL ledger transaction
     *        correctly stays POSTED and is not a discrepancy.
     */
    public static List<Issue> check(PaymentStatus payment, TxStatus ledgerStatus, OrderView order,
                                    boolean refundedByCompensatingTransfers) {
        List<Issue> out = new ArrayList<>();
        switch (payment) {
            case SUCCESSFUL -> {
                if (ledgerStatus == null) out.add(Issue.PAID_WITHOUT_LEDGER_TRANSACTION);
                else if (ledgerStatus != TxStatus.POSTED) out.add(Issue.PAID_BUT_LEDGER_NOT_POSTED);
                if (order == OrderView.AWAITING_PAYMENT || order == OrderView.CANCELLED) {
                    out.add(Issue.PAYMENT_SUCCESSFUL_BUT_ORDER_NOT_PAID);
                }
            }
            case REFUNDED -> {
                boolean ok = ledgerStatus == TxStatus.REVERSED
                        || (ledgerStatus == TxStatus.POSTED && refundedByCompensatingTransfers);
                if (!ok) out.add(Issue.REFUNDED_BUT_LEDGER_NOT_REVERSED);
            }
            case PROCESSING, PENDING -> {
                if (ledgerStatus == TxStatus.POSTED) out.add(Issue.DEBITED_BUT_PAYMENT_UNRESOLVED);
                if (order == OrderView.PAID) out.add(Issue.ORDER_PAID_WITHOUT_SUCCESSFUL_PAYMENT);
            }
            case FAILED -> {
                if (ledgerStatus == TxStatus.POSTED) out.add(Issue.DEBITED_BUT_PAYMENT_FAILED);
                if (order == OrderView.PAID) out.add(Issue.ORDER_PAID_WITHOUT_SUCCESSFUL_PAYMENT);
            }
        }
        return out;
    }
}
