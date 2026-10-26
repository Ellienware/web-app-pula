-- V37: Unified marketplace settlement. The existing restaurant_settlements table (V33) is GENERALISED rather than
-- duplicated: it already holds the settlement lifecycle, payout bookkeeping and idempotency, so it now also carries
-- retail, stock and service settlements. The table name is historical. Existing food rows keep their data and their
-- legacy idempotency key; new rows use "settlement:{SOURCE_TYPE}:{sourceId}". No wallet, ledger or payout provider is added.

ALTER TABLE restaurant_settlements ALTER COLUMN order_id DROP NOT NULL;

ALTER TABLE restaurant_settlements ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'FOOD_ORDER';
ALTER TABLE restaurant_settlements ADD COLUMN source_id VARCHAR(36);
UPDATE restaurant_settlements SET source_id = order_id WHERE source_id IS NULL;
ALTER TABLE restaurant_settlements ADD COLUMN seller_kind VARCHAR(24) NOT NULL DEFAULT 'RESTAURANT';
ALTER TABLE restaurant_settlements ADD COLUMN gross_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
UPDATE restaurant_settlements SET gross_amount = food_subtotal;
ALTER TABLE restaurant_settlements ADD COLUMN payment_fee_amount DECIMAL(19,2) NOT NULL DEFAULT 0;
ALTER TABLE restaurant_settlements ADD COLUMN adjustment_amount DECIMAL(19,2) NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_settlement_source ON restaurant_settlements (source_type, source_id);
CREATE INDEX idx_settlement_source_status ON restaurant_settlements (source_type, status);
CREATE INDEX idx_settlement_business ON restaurant_settlements (business_id, status);

-- Append-only: an adjustment is never edited or deleted; the settlement's adjustment_amount is the sum of its rows.
CREATE TABLE settlement_adjustments (
    id               VARCHAR(36) PRIMARY KEY,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    settlement_id    VARCHAR(36) NOT NULL REFERENCES restaurant_settlements(id),
    kind             VARCHAR(16) NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    reason           VARCHAR(300) NOT NULL,
    created_by_id    VARCHAR(36) NOT NULL REFERENCES users(id),
    idempotency_key  VARCHAR(100) NOT NULL,
    CONSTRAINT ck_adjustment_amount CHECK (amount <> 0),
    CONSTRAINT uq_adjustment_idem UNIQUE (settlement_id, idempotency_key)
);
CREATE INDEX idx_adjustment_settlement ON settlement_adjustments (settlement_id);
