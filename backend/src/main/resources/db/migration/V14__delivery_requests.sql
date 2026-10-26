-- MoMoRide <-> MoMoMarket delivery integration (Phase H.1). Both new
-- columns on orders are nullable so every existing order is unaffected.
ALTER TABLE orders ADD COLUMN fulfilment_method VARCHAR(32);
ALTER TABLE orders ADD COLUMN delivery_address VARCHAR(500);

CREATE TABLE delivery_requests (
    id                VARCHAR(36) PRIMARY KEY,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    order_id          VARCHAR(36) NOT NULL UNIQUE REFERENCES orders(id),
    driver_id         VARCHAR(36) REFERENCES momoride_drivers(id),
    status            VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    pickup_location   VARCHAR(500) NOT NULL,
    dropoff_location  VARCHAR(500) NOT NULL,
    estimated_fee     DECIMAL(19,2),
    accepted_at       TIMESTAMP,
    picked_up_at      TIMESTAMP,
    delivered_at      TIMESTAMP,
    cancelled_at      TIMESTAMP,
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_delivery_requests_status ON delivery_requests(status);
CREATE INDEX idx_delivery_requests_driver ON delivery_requests(driver_id);
