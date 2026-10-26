-- V40: simulated shared ledger (Phase 9A). Forward-only; adds new tables and ONE nullable column on `payments`.
-- No existing row is rewritten. Portable subset (PostgreSQL and H2 in PostgreSQL mode).
--
-- Every ledger row is SIMULATED value: ledger_transactions.simulated can only ever be TRUE. Historical real or mock
-- provider data stays in payments / payment_refunds / payment_webhook_events, untouched.
--
-- What this migration enforces in the database (portable):  primary/foreign keys, valid kinds/types/statuses,
--   positive posting amounts, single-currency transactions and account/currency agreement (composite FKs),
--   simulated = TRUE, unique provider references, idempotency unique per (initiator, key), at most one reversal per
--   original, and a non-negative floor on spendable wallets.
-- What it does NOT enforce (PostgreSQL-only, see db/postgresql-guards/V41__ledger_postgresql_guards.sql, opt-in):
--   >= 2 postings and debits = credits per transaction, immutability of posted entries, balance-projection integrity.

CREATE TABLE ledger_accounts (
    id              VARCHAR(36)  PRIMARY KEY,
    kind            VARCHAR(24)  NOT NULL,
    owner_user_id   VARCHAR(36),
    system_key      VARCHAR(64),
    currency        VARCHAR(3)   NOT NULL,
    allow_negative  BOOLEAN      NOT NULL,
    -- Projection of ledger_postings (credits - debits), changed only in the same transaction as the postings and
    -- reconciled by LedgerReconciliationService. It is never an independent source of truth.
    balance_minor   BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT ck_ledger_acct_kind     CHECK (kind IN ('USER_WALLET', 'DEMO_FUNDING', 'WITHDRAWAL_CLEARING')),
    CONSTRAINT ck_ledger_acct_ccy      CHECK (currency = UPPER(currency) AND LENGTH(currency) = 3),
    CONSTRAINT ck_ledger_wallet_owner  CHECK ((kind = 'USER_WALLET' AND owner_user_id IS NOT NULL AND system_key IS NULL)
                                           OR (kind <> 'USER_WALLET' AND owner_user_id IS NULL AND system_key IS NOT NULL)),
    CONSTRAINT ck_ledger_wallet_nonneg CHECK (kind <> 'USER_WALLET' OR allow_negative = FALSE),
    -- Database-level overspend backstop: a non-negative account can never be driven below zero.
    CONSTRAINT ck_ledger_balance_floor CHECK (allow_negative OR balance_minor >= 0),
    CONSTRAINT fk_ledger_acct_owner    FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT uq_ledger_system_key    UNIQUE (system_key),
    CONSTRAINT uq_ledger_wallet_per_user UNIQUE (owner_user_id, currency, kind),
    CONSTRAINT uq_ledger_acct_id_ccy   UNIQUE (id, currency)
);

CREATE TABLE ledger_transactions (
    id                 VARCHAR(36)  PRIMARY KEY,
    type               VARCHAR(24)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    initiator_user_id  VARCHAR(64)  NOT NULL,      -- a user id, or 'system'; always derived server-side
    idempotency_key    VARCHAR(128) NOT NULL,
    fingerprint        VARCHAR(64)  NOT NULL,
    currency           VARCHAR(3)   NOT NULL,
    reverses_tx_id     VARCHAR(36),
    provider_reference VARCHAR(40)  NOT NULL,
    simulated          BOOLEAN      NOT NULL,
    failure_reason     VARCHAR(500),
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    CONSTRAINT ck_ledger_tx_type     CHECK (type IN ('DEMO_ISSUANCE', 'INTERNAL_TRANSFER', 'SIMULATED_WITHDRAWAL', 'REVERSAL')),
    CONSTRAINT ck_ledger_tx_status   CHECK (status IN ('PENDING', 'POSTED', 'FAILED', 'REVERSED', 'CANCELLED')),
    CONSTRAINT ck_ledger_tx_ccy      CHECK (currency = UPPER(currency) AND LENGTH(currency) = 3),
    CONSTRAINT ck_ledger_tx_simulated CHECK (simulated = TRUE),
    CONSTRAINT ck_ledger_tx_reversal CHECK ((type = 'REVERSAL' AND reverses_tx_id IS NOT NULL)
                                         OR (type <> 'REVERSAL' AND reverses_tx_id IS NULL)),
    CONSTRAINT fk_ledger_tx_reverses FOREIGN KEY (reverses_tx_id) REFERENCES ledger_transactions(id),
    -- Idempotency is enforced by the database and scoped to the initiator: one actor's key can never replay another's.
    CONSTRAINT uq_ledger_idem            UNIQUE (initiator_user_id, idempotency_key),
    CONSTRAINT uq_ledger_provider_ref    UNIQUE (provider_reference),
    -- A transaction can be reversed at most once (also protects against racing reversals).
    CONSTRAINT uq_ledger_single_reversal UNIQUE (reverses_tx_id),
    CONSTRAINT uq_ledger_tx_id_ccy       UNIQUE (id, currency)
);
CREATE INDEX idx_ledger_tx_status    ON ledger_transactions (status);
CREATE INDEX idx_ledger_tx_initiator ON ledger_transactions (initiator_user_id, type, created_at);

CREATE TABLE ledger_postings (
    id              VARCHAR(36) PRIMARY KEY,
    transaction_id  VARCHAR(36) NOT NULL,
    line_no         INTEGER     NOT NULL,
    account_id      VARCHAR(36) NOT NULL,
    direction       VARCHAR(6)  NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    created_at      TIMESTAMP   NOT NULL,
    CONSTRAINT ck_ledger_post_dir    CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_post_amount CHECK (amount_minor > 0),
    CONSTRAINT uq_ledger_post_line   UNIQUE (transaction_id, line_no),
    -- Composite FKs: a posting's currency must equal BOTH its account's and its transaction's currency, so a
    -- transaction cannot mix currencies and a wallet can never receive a foreign-currency posting.
    CONSTRAINT fk_ledger_post_tx     FOREIGN KEY (transaction_id, currency) REFERENCES ledger_transactions (id, currency),
    CONSTRAINT fk_ledger_post_acct   FOREIGN KEY (account_id, currency)     REFERENCES ledger_accounts (id, currency)
);
CREATE INDEX idx_ledger_post_account ON ledger_postings (account_id, created_at);

-- System accounts for the default currency. Other currencies are provisioned by LedgerAccountService.
INSERT INTO ledger_accounts (id, kind, owner_user_id, system_key, currency, allow_negative, balance_minor, created_at)
VALUES ('ledger-sys-demo-funding-zar', 'DEMO_FUNDING', NULL, 'DEMO_FUNDING:ZAR', 'ZAR', TRUE, 0, CURRENT_TIMESTAMP);
INSERT INTO ledger_accounts (id, kind, owner_user_id, system_key, currency, allow_negative, balance_minor, created_at)
VALUES ('ledger-sys-withdrawal-clearing-zar', 'WITHDRAWAL_CLEARING', NULL, 'WITHDRAWAL_CLEARING:ZAR', 'ZAR', TRUE, 0, CURRENT_TIMESTAMP);

-- Link payment records to ledger transactions. Nullable and NOT backfilled: historical rows are left exactly as they are.
ALTER TABLE payments ADD COLUMN ledger_transaction_id VARCHAR(36);
ALTER TABLE payments ADD CONSTRAINT fk_payments_ledger_tx FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions(id);
CREATE UNIQUE INDEX uq_payments_ledger_tx ON payments (ledger_transaction_id);
