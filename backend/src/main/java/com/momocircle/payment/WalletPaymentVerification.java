package com.momocircle.payment;

import com.momocircle.ledger.Posting;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;

import java.util.List;

/**
 * Decides, from ledger facts only, whether a wallet payment has really been paid. Pure logic with no framework or
 * database dependency so it can be tested exhaustively.
 *
 * <p>An order may be marked paid only when the linked ledger transaction is POSTED, is a plain internal transfer
 * started by the buyer, is in the order's currency, and consists of exactly one debit and one credit of exactly the
 * authoritative amount. Anything that looks wrong is REVIEW (never paid, never silently failed); a transaction that
 * ended without moving value is FAILED; a transaction still in flight is PENDING.
 */
public final class WalletPaymentVerification {

    public enum Outcome { PAID, FAILED, PENDING, REVIEW }

    private WalletPaymentVerification() { }

    /**
     * @param expectedInitiatorUserId the buyer who must have initiated the transfer; null skips that check (callers that
     *                                do not know the buyer)
     */
    public static Outcome evaluate(TxStatus status, TxType type, String initiatorUserId, String expectedInitiatorUserId,
                                   String txCurrency, String expectedCurrency, List<Posting> postings, long expectedMinor) {
        if (status == null) return Outcome.PENDING;
        switch (status) {
            case PENDING -> { return Outcome.PENDING; }
            case FAILED, CANCELLED, REVERSED -> { return Outcome.FAILED; }
            case POSTED -> { /* checked below */ }
        }
        if (type != TxType.INTERNAL_TRANSFER) return Outcome.REVIEW;
        if (expectedInitiatorUserId != null && !expectedInitiatorUserId.equals(initiatorUserId)) return Outcome.REVIEW;
        if (txCurrency == null || expectedCurrency == null || !txCurrency.equalsIgnoreCase(expectedCurrency)) return Outcome.REVIEW;
        if (expectedMinor <= 0 || postings == null || postings.size() != 2) return Outcome.REVIEW;
        long debit = 0, credit = 0;
        int debits = 0, credits = 0;
        for (Posting p : postings) {
            if (!p.currency().equalsIgnoreCase(expectedCurrency)) return Outcome.REVIEW;
            if (p.direction() == Posting.Direction.DEBIT) { debit += p.minor(); debits++; }
            else { credit += p.minor(); credits++; }
        }
        if (debits != 1 || credits != 1) return Outcome.REVIEW;
        return debit == expectedMinor && credit == expectedMinor ? Outcome.PAID : Outcome.REVIEW;
    }
}
