-- V35: Save-to-Buy. A savings goal can be marked as intended for a purchase, and a purchase request ties a goal to an
-- order made through the EXISTING marketplace checkout. Nothing here holds or moves money: no wallet, no balance, no
-- ledger. The order is created and paid by the existing checkout/payment code and merely REFERENCES the request.

ALTER TABLE circle_goals ADD COLUMN purchase_vertical VARCHAR(16);
ALTER TABLE circle_goals ADD COLUMN purchase_purpose  VARCHAR(200);

CREATE TABLE save_to_buy_requests (
    id                   VARCHAR(36) PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    owner_id             VARCHAR(36) NOT NULL REFERENCES users(id),
    goal_id              VARCHAR(36) NOT NULL REFERENCES circle_goals(id),
    circle_id            VARCHAR(36) NOT NULL REFERENCES circles(id),
    scope                VARCHAR(16) NOT NULL,
    vertical             VARCHAR(16) NOT NULL,
    purpose              VARCHAR(500) NOT NULL,
    requested_amount     DECIMAL(19,2) NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    goal_target_snapshot DECIMAL(19,2) NOT NULL,
    named_buyer_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    status               VARCHAR(24) NOT NULL,
    stokvel_request_id   VARCHAR(36) REFERENCES stokvel_requests(id),
    idempotency_key      VARCHAR(100),
    expires_at           TIMESTAMP,
    order_kind           VARCHAR(16),
    order_id             VARCHAR(36),
    checkout_key         VARCHAR(200),
    purchased_amount     DECIMAL(19,2),
    purchased_at         TIMESTAMP,
    completion_reference VARCHAR(200),
    CONSTRAINT ck_s2b_amount CHECK (requested_amount > 0),
    CONSTRAINT uq_s2b_owner_idem UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uq_s2b_stokvel_request UNIQUE (stokvel_request_id)
);
CREATE INDEX idx_s2b_goal ON save_to_buy_requests(goal_id);
CREATE INDEX idx_s2b_circle ON save_to_buy_requests(circle_id, status);
-- One live order per request. Cleared again if the checkout fails, so a retry can attach a new order.
CREATE UNIQUE INDEX uq_s2b_order ON save_to_buy_requests(order_kind, order_id);

-- Orders keep the reference even after a failed attempt (history), so these are NOT unique.
ALTER TABLE orders ADD COLUMN save_to_buy_request_id VARCHAR(36) REFERENCES save_to_buy_requests(id);
CREATE INDEX idx_orders_s2b ON orders(save_to_buy_request_id);
ALTER TABLE stock_orders ADD COLUMN save_to_buy_request_id VARCHAR(36) REFERENCES save_to_buy_requests(id);
CREATE INDEX idx_stock_orders_s2b ON stock_orders(save_to_buy_request_id);
