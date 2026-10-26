package com.momocircle.payment;

import com.momocircle.ledger.Posting;
import com.momocircle.ledger.Posting.Direction;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import com.momocircle.payment.WalletPaymentVerification.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalletPaymentVerificationTest {

    private static List<Posting> transfer(long minor) {
        return List.of(new Posting("payer", Direction.DEBIT, minor, "ZAR"), new Posting("payee", Direction.CREDIT, minor, "ZAR"));
    }

    private static Outcome eval(TxStatus s, TxType t, String initiator, String cur, List<Posting> p, long expected) {
        return WalletPaymentVerification.evaluate(s, t, initiator, "buyer-1", cur, "ZAR", p, expected);
    }

    @Test
    void exactPostedTransferByTheBuyerIsPaid() {
        assertEquals(Outcome.PAID, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(12_550), 12_550));
    }

    @Test
    void amountMismatchIsReviewNeverPaid() {
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(12_549), 12_550));
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(12_551), 12_550));
    }

    @Test
    void currencyMismatchIsReview() {
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "USD", transfer(100), 100));
    }

    @Test
    void transferStartedBySomeoneElseIsReview() {
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "someone-else", "ZAR", transfer(100), 100));
    }

    @Test
    void wrongTransactionTypeIsReview() {
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.DEMO_ISSUANCE, "buyer-1", "ZAR", transfer(100), 100));
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.SIMULATED_WITHDRAWAL, "buyer-1", "ZAR", transfer(100), 100));
    }

    @Test
    void unexpectedPostingShapeIsReview() {
        List<Posting> three = List.of(new Posting("a", Direction.DEBIT, 100, "ZAR"), new Posting("b", Direction.CREDIT, 60, "ZAR"),
                new Posting("c", Direction.CREDIT, 40, "ZAR"));
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", three, 100));
        List<Posting> twoDebits = List.of(new Posting("a", Direction.DEBIT, 100, "ZAR"), new Posting("b", Direction.DEBIT, 100, "ZAR"));
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", twoDebits, 100));
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", null, 100));
    }

    @Test
    void zeroExpectedAmountIsNeverPaid() {
        assertEquals(Outcome.REVIEW, eval(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(100), 0));
    }

    @Test
    void inFlightIsPendingAndEndedWithoutValueIsFailed() {
        assertEquals(Outcome.PENDING, eval(TxStatus.PENDING, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(100), 100));
        assertEquals(Outcome.PENDING, eval(null, null, null, null, null, 100));
        assertEquals(Outcome.FAILED, eval(TxStatus.FAILED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(100), 100));
        assertEquals(Outcome.FAILED, eval(TxStatus.CANCELLED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(100), 100));
        assertEquals(Outcome.FAILED, eval(TxStatus.REVERSED, TxType.INTERNAL_TRANSFER, "buyer-1", "ZAR", transfer(100), 100));
    }

    @Test
    void unknownBuyerSkipsOnlyTheInitiatorCheck() {
        assertEquals(Outcome.PAID, WalletPaymentVerification.evaluate(TxStatus.POSTED, TxType.INTERNAL_TRANSFER, "anyone", null,
                "ZAR", "ZAR", transfer(100), 100));
    }
}
