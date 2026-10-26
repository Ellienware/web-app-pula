-- PULA Food: real refund operations (Paystack-capable, partial-capable) and restaurant settlements.
-- Driver payouts (driver_payouts) are NOT touched: restaurant money is a separate financial concept.

-- 1. Why a food order ended without completing, and when its food was prepared.
ALTER TABLE orders ADD COLUMN cancellation_cause VARCHAR(32);
ALTER TABLE orders ADD COLUMN ready_at TIMESTAMP;

-- Backfill cause for orders that already ended. The order's own recorded decision/reason is the evidence.
UPDATE orders SET cancellation_cause = 'RESTAURANT_EXPIRED'
 WHERE kind = 'FOOD' AND restaurant_decision = 'REJECTED' AND rejection_reason LIKE '%did not respond in time%';
UPDATE orders SET cancellation_cause = 'RESTAURANT_REJECTED'
 WHERE kind = 'FOOD' AND restaurant_decision = 'REJECTED' AND cancellation_cause IS NULL;
UPDATE orders SET cancellation_cause = 'PULA_DELIVERY_FAILURE'
 WHERE kind = 'FOOD' AND restaurant_decision = 'WITHDRAWN' AND rejection_reason LIKE '%no driver was found%';
UPDATE orders SET cancellation_cause = 'CUSTOMER_CANCELLED'
 WHERE kind = 'FOOD' AND restaurant_decision = 'WITHDRAWN' AND cancellation_cause IS NULL;

-- Best available "prepared" time for food orders that already have a delivery: when it was offered to drivers.
UPDATE orders SET ready_at = (SELECT MIN(d.created_at) FROM delivery_requests d WHERE d.order_id = orders.id)
 WHERE kind = 'FOOD' AND ready_at IS NULL;

-- 2. Refund operations. Unique idempotency key: one row per refund operation, however often it is retried.
CREATE TABLE payment_refunds (
    id                        VARCHAR(36)   NOT NULL PRIMARY KEY,
    created_at                TIMESTAMP     NOT NULL,
    updated_at                TIMESTAMP     NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    order_id                  VARCHAR(36)   NOT NULL,
    payment_id                VARCHAR(36)   NOT NULL,
    provider                  VARCHAR(40)   NOT NULL,
    original_reference        VARCHAR(200)  NOT NULL,
    provider_refund_reference VARCHAR(200),
    amount                    DECIMAL(19,2) NOT NULL,
    currency                  VARCHAR(3)    NOT NULL,
    reason                    VARCHAR(32)   NOT NULL,
    status                    VARCHAR(24)   NOT NULL,
    customer_note             VARCHAR(500),
    merchant_note             VARCHAR(500),
    idempotency_key           VARCHAR(120)  NOT NULL,
    provider_status           VARCHAR(40),
    failure_reason            VARCHAR(500),
    attempts                  INTEGER       NOT NULL DEFAULT 0,
    processed_at              TIMESTAMP,
    CONSTRAINT uq_payment_refunds_idem UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_refunds_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_payment_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
CREATE INDEX idx_payment_refunds_order ON payment_refunds (order_id);
CREATE INDEX idx_payment_refunds_payment ON payment_refunds (payment_id);
CREATE INDEX idx_payment_refunds_status ON payment_refunds (status, updated_at);
CREATE INDEX idx_payment_refunds_provider_ref ON payment_refunds (provider_refund_reference);
CREATE INDEX idx_payment_refunds_original_ref ON payment_refunds (original_reference);

-- 3. Restaurant settlements (one per order) doubling as the restaurant payout record.
CREATE TABLE restaurant_settlements (
    id                   VARCHAR(36)   NOT NULL PRIMARY KEY,
    created_at           TIMESTAMP     NOT NULL,
    updated_at           TIMESTAMP     NOT NULL,
    order_id             VARCHAR(36)   NOT NULL,
    business_id          VARCHAR(36)   NOT NULL,
    payment_id           VARCHAR(36),
    type                 VARCHAR(24)   NOT NULL,
    status               VARCHAR(24)   NOT NULL,
    currency             VARCHAR(3)    NOT NULL,
    food_subtotal        DECIMAL(19,2) NOT NULL DEFAULT 0,
    delivery_allocation  DECIMAL(19,2) NOT NULL DEFAULT 0,
    refund_amount        DECIMAL(19,2) NOT NULL DEFAULT 0,
    eligible_revenue     DECIMAL(19,2) NOT NULL DEFAULT 0,
    commission_rate      DECIMAL(7,4)  NOT NULL DEFAULT 0,
    commission_amount    DECIMAL(19,2) NOT NULL DEFAULT 0,
    other_fees           DECIMAL(19,2) NOT NULL DEFAULT 0,
    net_amount           DECIMAL(19,2) NOT NULL DEFAULT 0,
    pula_fulfilment_loss DECIMAL(19,2) NOT NULL DEFAULT 0,
    cause                VARCHAR(32),
    payout_provider      VARCHAR(40),
    payout_reference     VARCHAR(200),
    idempotency_key      VARCHAR(120)  NOT NULL,
    payout_attempts      INTEGER       NOT NULL DEFAULT 0,
    failure_reason       VARCHAR(500),
    hold_reason          VARCHAR(500),
    eligible_at          TIMESTAMP,
    paid_at              TIMESTAMP,
    CONSTRAINT uq_restaurant_settlements_order UNIQUE (order_id),
    CONSTRAINT uq_restaurant_settlements_idem UNIQUE (idempotency_key),
    CONSTRAINT fk_restaurant_settlements_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_restaurant_settlements_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_restaurant_settlements_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);
CREATE INDEX idx_restaurant_settlements_status ON restaurant_settlements (status, eligible_at);
CREATE INDEX idx_restaurant_settlements_business ON restaurant_settlements (business_id);

-- 4. Backfill settlements for food orders that were already COMPLETED before this release, so nobody who
-- earned money is skipped. They are created ON_HOLD (not ELIGIBLE): nothing is paid automatically for
-- historical orders, an admin reviews and releases them. Commission is left at 0 for the same reason.
INSERT INTO restaurant_settlements (id, created_at, updated_at, order_id, business_id, payment_id, type, status, currency,
        food_subtotal, delivery_allocation, eligible_revenue, net_amount, idempotency_key, hold_reason)
SELECT 'bf-' || o.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, o.id, o.business_id, o.payment_id, 'SALE', 'ON_HOLD', o.currency,
       o.subtotal, COALESCE(o.delivery_fee_snapshot, 0), o.subtotal, o.subtotal, 'settlement:' || o.id,
       'Completed before settlements existed: review, then release'
  FROM orders o
 WHERE o.kind = 'FOOD' AND o.status = 'COMPLETED';
