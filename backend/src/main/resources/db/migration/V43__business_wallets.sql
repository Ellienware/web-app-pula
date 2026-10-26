-- V43: PULA Money BUSINESS wallets (Phase 9B, second pass). Forward-only; runs after V42.
--
-- A business wallet is a ledger account owned by a BUSINESS identity (businesses.id), never by its owner's user id.
-- Who may act on it is decided by business_wallet_members (+ the business owner, derived from businesses.owner_id), and
-- the application enforces per-role permissions. Wallet BALANCES are still only in the Phase 9A ledger.
--
-- This migration CHANGES the 9A ledger_accounts invariants (new kind, new owner column, replaced CHECK constraints). That is
-- deliberate and is the main risk of this change: re-run the full 9A ledger suite and the migration tests after applying it.
-- The opt-in PostgreSQL guard triggers (db/postgresql-guards/V41) do not cover owner_business_id; apply
-- db/postgresql-guards/V44__ledger_guard_business_owner.sql after enabling them.

ALTER TABLE ledger_accounts ADD COLUMN owner_business_id VARCHAR(36);
ALTER TABLE ledger_accounts ADD CONSTRAINT fk_ledger_acct_business FOREIGN KEY (owner_business_id) REFERENCES businesses(id);

ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_acct_kind;
ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_wallet_owner;
ALTER TABLE ledger_accounts DROP CONSTRAINT ck_ledger_wallet_nonneg;

ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_acct_kind
    CHECK (kind IN ('USER_WALLET', 'BUSINESS_WALLET', 'DEMO_FUNDING', 'WITHDRAWAL_CLEARING'));
ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_wallet_owner
    CHECK ((kind = 'USER_WALLET'     AND owner_user_id IS NOT NULL AND owner_business_id IS NULL     AND system_key IS NULL)
        OR (kind = 'BUSINESS_WALLET' AND owner_user_id IS NULL     AND owner_business_id IS NOT NULL AND system_key IS NULL)
        OR (kind IN ('DEMO_FUNDING', 'WITHDRAWAL_CLEARING') AND owner_user_id IS NULL AND owner_business_id IS NULL AND system_key IS NOT NULL));
ALTER TABLE ledger_accounts ADD CONSTRAINT ck_ledger_wallet_nonneg
    CHECK (kind NOT IN ('USER_WALLET', 'BUSINESS_WALLET') OR allow_negative = FALSE);
-- One business wallet per business and currency (the existing uq_ledger_wallet_per_user cannot cover it: owner_user_id is NULL).
ALTER TABLE ledger_accounts ADD CONSTRAINT uq_ledger_business_wallet UNIQUE (owner_business_id, currency, kind);

-- Opaque public wallet id for a business (BW-...), so a business can be addressed without exposing its internal id.
CREATE TABLE business_wallet_profiles (
    business_id VARCHAR(36) PRIMARY KEY,
    public_id   VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT fk_bwp_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT uq_bwp_public   UNIQUE (public_id)
);

-- Delegated access to a business wallet. The business OWNER is not a row here (derived from businesses.owner_id), so an owner
-- can never be demoted or removed by editing this table. Roles: FINANCE (view/request/send/withdraw/fund), STAFF (view/request).
CREATE TABLE business_wallet_members (
    id                 VARCHAR(36) PRIMARY KEY,
    business_id        VARCHAR(36) NOT NULL,
    user_id            VARCHAR(36) NOT NULL,
    role               VARCHAR(16) NOT NULL,
    added_by_user_id   VARCHAR(36) NOT NULL,
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP   NOT NULL,
    CONSTRAINT ck_bwm_role CHECK (role IN ('FINANCE', 'STAFF')),
    CONSTRAINT uq_bwm_member UNIQUE (business_id, user_id),
    CONSTRAINT fk_bwm_business FOREIGN KEY (business_id)      REFERENCES businesses(id),
    CONSTRAINT fk_bwm_user     FOREIGN KEY (user_id)          REFERENCES users(id),
    CONSTRAINT fk_bwm_added_by FOREIGN KEY (added_by_user_id) REFERENCES users(id)
);
CREATE INDEX idx_bwm_user ON business_wallet_members (user_id);

-- A payment request may be made on behalf of a business: the value then lands in the business wallet. requester_user_id keeps
-- recording WHICH user created it (audit); requester_business_id says whose wallet receives the funds.
ALTER TABLE wallet_payment_requests ADD COLUMN requester_business_id VARCHAR(36);
ALTER TABLE wallet_payment_requests ADD CONSTRAINT fk_wpr_business FOREIGN KEY (requester_business_id) REFERENCES businesses(id);
CREATE INDEX idx_wpr_business ON wallet_payment_requests (requester_business_id, created_at);
