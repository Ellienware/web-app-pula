
-- ============================================================
-- V2 — enforce the orders.payment_id relationship.
-- Remove the old index before creating the constraints.
-- ============================================================

DROP INDEX IF EXISTS idx_order_payment;

ALTER TABLE orders
    ADD CONSTRAINT fk_orders_payment
    FOREIGN KEY (payment_id) REFERENCES payments(id);

ALTER TABLE orders
    ADD CONSTRAINT uq_orders_payment UNIQUE (payment_id);

