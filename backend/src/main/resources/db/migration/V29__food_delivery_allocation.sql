-- PULA Food (Phase 3, step 3): PULA Ride delivery-fee allocation for food deliveries.
-- One row per delivery (UNIQUE), created when the driver confirms delivery. The fee comes from the
-- order's delivery_fee_snapshot; driver_share + pula_share always equals it (CHECK).
CREATE TABLE delivery_earnings_allocations (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    delivery_request_id   VARCHAR(36) NOT NULL UNIQUE REFERENCES delivery_requests(id),
    delivery_fee_snapshot DECIMAL(19,2) NOT NULL,
    driver_share          DECIMAL(19,2) NOT NULL,
    pula_share            DECIMAL(19,2) NOT NULL,
    split_policy          VARCHAR(32) NOT NULL,
    CONSTRAINT ck_allocation_sum CHECK (driver_share + pula_share = delivery_fee_snapshot),
    CONSTRAINT ck_allocation_nonneg CHECK (driver_share >= 0 AND pula_share >= 0)
);

-- Lets the maintenance task tell the customer once that no driver has taken the delivery yet.
ALTER TABLE delivery_requests ADD COLUMN no_driver_notified_at TIMESTAMP;
