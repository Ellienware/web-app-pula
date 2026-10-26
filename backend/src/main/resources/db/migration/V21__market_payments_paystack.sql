-- Payment routing metadata + webhook dedupe (Paystack for PULA Market collections).
ALTER TABLE payments ADD COLUMN purpose VARCHAR(32);
ALTER TABLE payments ADD COLUMN checkout_url VARCHAR(1000);

CREATE TABLE payment_webhook_events (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    provider    VARCHAR(32) NOT NULL,
    dedupe_key  VARCHAR(100) NOT NULL,
    event_type  VARCHAR(64),
    reference   VARCHAR(200),
    CONSTRAINT uq_webhook_dedupe UNIQUE (dedupe_key)
);
