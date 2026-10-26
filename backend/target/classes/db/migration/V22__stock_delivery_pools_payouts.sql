-- Shared delivery engine for stock orders + organisation driver pools + driver payouts.
ALTER TABLE delivery_requests ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE delivery_requests ADD COLUMN stock_order_id VARCHAR(36);
ALTER TABLE delivery_requests ADD COLUMN pool_business_id VARCHAR(36);
ALTER TABLE delivery_requests ADD CONSTRAINT uq_dr_stock_order UNIQUE (stock_order_id);
ALTER TABLE delivery_requests ADD CONSTRAINT fk_dr_stock_order FOREIGN KEY (stock_order_id) REFERENCES stock_orders(id);
ALTER TABLE delivery_requests ADD CONSTRAINT fk_dr_pool_business FOREIGN KEY (pool_business_id) REFERENCES businesses(id);
ALTER TABLE delivery_requests ADD CONSTRAINT chk_dr_single_source CHECK (
    (order_id IS NOT NULL AND stock_order_id IS NULL) OR (order_id IS NULL AND stock_order_id IS NOT NULL));

CREATE TABLE delivery_pool_members (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    business_id  VARCHAR(36) NOT NULL REFERENCES businesses(id),
    driver_id    VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id),
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_pool_member UNIQUE (business_id, driver_id)
);
CREATE INDEX idx_pool_driver ON delivery_pool_members(driver_id);

-- A payout is PULA -> driver money movement. Deliberately NOT a row in payments.
CREATE TABLE driver_payouts (
    id                   VARCHAR(36) PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    delivery_request_id  VARCHAR(36) NOT NULL REFERENCES delivery_requests(id),
    driver_id            VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id),
    amount               NUMERIC(19,2) NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    provider             VARCHAR(32) NOT NULL,
    provider_reference   VARCHAR(200),
    idempotency_key      VARCHAR(100) NOT NULL,
    failure_reason       VARCHAR(500),
    CONSTRAINT uq_payout_delivery UNIQUE (delivery_request_id),
    CONSTRAINT uq_payout_idem UNIQUE (idempotency_key)
);
CREATE INDEX idx_payout_driver ON driver_payouts(driver_id);
