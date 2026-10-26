package com.momocircle.ledger;

/** Business-rule failure in the shared ledger. Carries a stable code so callers can map it to an HTTP status. */
public class LedgerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_AMOUNT, INVALID_CURRENCY, CURRENCY_MISMATCH, UNBALANCED, INSUFFICIENT_FUNDS, NOT_OWNER,
        ACCOUNT_NOT_FOUND, ILLEGAL_STATE, IDEMPOTENCY_CONFLICT, LIMIT_EXCEEDED, SELF_TRANSFER, NOT_FOUND,
        /** Persistence could not complete after bounded retries (lock timeout, deadlock, storage fault). Nothing was applied. */
        CONCURRENCY_FAILURE
    }

    private final Code code;

    public LedgerException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
