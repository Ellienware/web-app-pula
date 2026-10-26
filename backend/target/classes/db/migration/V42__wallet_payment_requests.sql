-- V42: PULA Money demo wallet (Phase 9B). Forward-only; adds two NEW tables and touches no existing row or table.
--
-- Wallet BALANCES are not stored here: the only authority is the Phase 9A shared ledger (ledger_accounts / ledger_postings).
-- This migration adds only
--   * wallet_profiles: a stable, opaque public identifier per user so that other users can be addressed without exposing
--     internal user ids, emails or phone numbers;
--   * wallet_payment_requests: a REQUEST for payment. A request is not a transfer and never holds value. It points at the
--     ledger transaction (ledger_transaction_id) only after the payer has explicitly confirmed and the ledger has posted.
-- Everything is SIMULATED demo value. Portable PostgreSQL / H2 subset.
--
-- NOTE ON FLYWAY ORDER: db/postgresql-guards/V41__ledger_postgresql_guards.sql is an OPT-IN location holding version 41.
-- If it is enabled AFTER this V42 has been applied, Flyway reports V41 as "ignored/out of order" unless
-- spring.flyway.out-of-order=true. Enable the guards (after verifying them on a disposable database) BEFORE applying V42,
-- or set out-of-order. See docs/PHASE_9B_REPORT.md.

CREATE TABLE wallet_profiles (
    user_id     VARCHAR(36) PRIMARY KEY,
    public_id   VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_wallet_profile_user  FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uq_wallet_profile_public UNIQUE (public_id)
);

CREATE TABLE wallet_payment_requests (
    id                     VARCHAR(36)  PRIMARY KEY,
    public_ref             VARCHAR(32)  NOT NULL,           -- opaque random reference; the QR/share code wraps it with a signature
    requester_user_id      VARCHAR(36)  NOT NULL,           -- the user who will RECEIVE the demo value
    target_payer_user_id   VARCHAR(36),                     -- optional: only this user may view/pay/reject
    payer_user_id          VARCHAR(36),                     -- set while a payment is being processed, and when paid
    amount_minor           BIGINT       NOT NULL,
    currency               VARCHAR(3)   NOT NULL,
    purpose                VARCHAR(140) NOT NULL,
    status                 VARCHAR(16)  NOT NULL,
    expires_at             TIMESTAMP    NOT NULL,
    ledger_transaction_id  VARCHAR(36),
    resolved_at            TIMESTAMP,
    created_at             TIMESTAMP    NOT NULL,
    updated_at             TIMESTAMP    NOT NULL,
    CONSTRAINT ck_wpr_status   CHECK (status IN ('OPEN', 'PROCESSING', 'PAID', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_wpr_amount   CHECK (amount_minor > 0),
    CONSTRAINT ck_wpr_currency CHECK (currency = UPPER(currency) AND LENGTH(currency) = 3),
    CONSTRAINT ck_wpr_not_self_target CHECK (target_payer_user_id IS NULL OR target_payer_user_id <> requester_user_id),
    CONSTRAINT ck_wpr_not_self_payer  CHECK (payer_user_id IS NULL OR payer_user_id <> requester_user_id),
    -- A PAID request always names the payer and the ledger transaction that moved the value.
    CONSTRAINT ck_wpr_paid_has_tx CHECK (status <> 'PAID' OR (ledger_transaction_id IS NOT NULL AND payer_user_id IS NOT NULL)),
    CONSTRAINT ck_wpr_processing_has_payer CHECK (status <> 'PROCESSING' OR payer_user_id IS NOT NULL),
    CONSTRAINT uq_wpr_public_ref UNIQUE (public_ref),
    -- One ledger transaction can settle at most one request.
    CONSTRAINT uq_wpr_ledger_tx UNIQUE (ledger_transaction_id),
    CONSTRAINT fk_wpr_requester FOREIGN KEY (requester_user_id)    REFERENCES users(id),
    CONSTRAINT fk_wpr_target    FOREIGN KEY (target_payer_user_id) REFERENCES users(id),
    CONSTRAINT fk_wpr_payer     FOREIGN KEY (payer_user_id)        REFERENCES users(id),
    CONSTRAINT fk_wpr_ledger_tx FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions(id)
);
CREATE INDEX idx_wpr_requester ON wallet_payment_requests (requester_user_id, created_at);
CREATE INDEX idx_wpr_target    ON wallet_payment_requests (target_payer_user_id, created_at);
CREATE INDEX idx_wpr_status_expiry ON wallet_payment_requests (status, expires_at);
