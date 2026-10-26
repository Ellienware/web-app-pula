package com.momocircle.payment;

import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.ledger.TxStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletPaymentAuditPartialRefundTest {

    @Test
    void refundedByCompensatingTransfersLeavesOriginalPostedWithoutDiscrepancy() {
        assertTrue(WalletPaymentAudit.check(PaymentStatus.REFUNDED, TxStatus.POSTED, WalletPaymentAudit.OrderView.REFUNDED, true).isEmpty());
    }

    @Test
    void refundedWithOriginalStillPostedAndNoTransfersIsStillFlagged() {
        assertEquals(java.util.List.of(WalletPaymentAudit.Issue.REFUNDED_BUT_LEDGER_NOT_REVERSED),
                WalletPaymentAudit.check(PaymentStatus.REFUNDED, TxStatus.POSTED, WalletPaymentAudit.OrderView.REFUNDED));
    }

    @Test
    void reversedOriginalStillPasses() {
        assertTrue(WalletPaymentAudit.check(PaymentStatus.REFUNDED, TxStatus.REVERSED, WalletPaymentAudit.OrderView.REFUNDED).isEmpty());
    }
}
