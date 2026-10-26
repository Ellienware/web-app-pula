package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.ledger.TxStatus;
import com.momocircle.payment.WalletPaymentAudit.Issue;
import com.momocircle.payment.WalletPaymentAudit.OrderView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletPaymentAuditTest {

    @Test
    void consistentRecordsHaveNoIssues() {
        assertTrue(WalletPaymentAudit.check(PaymentStatus.SUCCESSFUL, TxStatus.POSTED, OrderView.PAID).isEmpty());
        assertTrue(WalletPaymentAudit.check(PaymentStatus.SUCCESSFUL, TxStatus.POSTED, OrderView.REFUND_IN_PROGRESS).isEmpty());
        assertTrue(WalletPaymentAudit.check(PaymentStatus.PROCESSING, null, OrderView.AWAITING_PAYMENT).isEmpty());
        assertTrue(WalletPaymentAudit.check(PaymentStatus.FAILED, null, OrderView.CANCELLED).isEmpty());
        assertTrue(WalletPaymentAudit.check(PaymentStatus.REFUNDED, TxStatus.REVERSED, OrderView.REFUNDED).isEmpty());
    }

    @Test
    void paidWithoutAPostedLedgerTransactionIsFlagged() {
        assertEquals(List.of(Issue.PAID_WITHOUT_LEDGER_TRANSACTION),
                WalletPaymentAudit.check(PaymentStatus.SUCCESSFUL, null, OrderView.PAID));
        assertEquals(List.of(Issue.PAID_BUT_LEDGER_NOT_POSTED),
                WalletPaymentAudit.check(PaymentStatus.SUCCESSFUL, TxStatus.PENDING, OrderView.PAID));
    }

    @Test
    void debitedButNotPaidIsFlagged() {
        assertEquals(List.of(Issue.DEBITED_BUT_PAYMENT_UNRESOLVED),
                WalletPaymentAudit.check(PaymentStatus.PROCESSING, TxStatus.POSTED, OrderView.AWAITING_PAYMENT));
        assertEquals(List.of(Issue.DEBITED_BUT_PAYMENT_FAILED),
                WalletPaymentAudit.check(PaymentStatus.FAILED, TxStatus.POSTED, OrderView.CANCELLED));
    }

    @Test
    void orderPaidWithoutASuccessfulPaymentIsFlagged() {
        assertEquals(List.of(Issue.ORDER_PAID_WITHOUT_SUCCESSFUL_PAYMENT),
                WalletPaymentAudit.check(PaymentStatus.PROCESSING, null, OrderView.PAID));
        assertEquals(List.of(Issue.ORDER_PAID_WITHOUT_SUCCESSFUL_PAYMENT),
                WalletPaymentAudit.check(PaymentStatus.FAILED, null, OrderView.PAID));
    }

    @Test
    void successfulPaymentOnAnUnpaidOrderIsFlagged() {
        assertEquals(List.of(Issue.PAYMENT_SUCCESSFUL_BUT_ORDER_NOT_PAID),
                WalletPaymentAudit.check(PaymentStatus.SUCCESSFUL, TxStatus.POSTED, OrderView.AWAITING_PAYMENT));
    }

    @Test
    void refundedWithoutAReversalIsFlagged() {
        assertEquals(List.of(Issue.REFUNDED_BUT_LEDGER_NOT_REVERSED),
                WalletPaymentAudit.check(PaymentStatus.REFUNDED, TxStatus.POSTED, OrderView.REFUNDED));
        assertEquals(List.of(Issue.REFUNDED_BUT_LEDGER_NOT_REVERSED),
                WalletPaymentAudit.check(PaymentStatus.REFUNDED, null, OrderView.REFUNDED));
    }
}
