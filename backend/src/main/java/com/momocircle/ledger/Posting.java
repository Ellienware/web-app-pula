package com.momocircle.ledger;

/** One immutable ledger line. */
public record Posting(String accountId, Direction direction, long minor, String currency) {
    public enum Direction { DEBIT, CREDIT }

    public Posting {
        if (minor <= 0) {
            throw new LedgerException(LedgerException.Code.INVALID_AMOUNT, "A posting amount must be positive");
        }
    }

    /** Signed effect on {@code credits - debits}. */
    public long delta() {
        return direction == Direction.CREDIT ? minor : -minor;
    }

    public Posting opposite() {
        return new Posting(accountId, direction == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT, minor, currency);
    }
}
