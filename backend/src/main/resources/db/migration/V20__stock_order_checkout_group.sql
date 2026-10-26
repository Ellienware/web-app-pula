-- PULA hardening: stock-order idempotency is scoped to (buyer, supplier, client key)
-- so one checkout can create several supplier orders and one business can never
-- replay another business's key. Legacy rows keep NULL and are unaffected.
ALTER TABLE stock_orders ADD COLUMN checkout_group_key VARCHAR(100);
CREATE UNIQUE INDEX uq_so_buyer_supplier_group
    ON stock_orders(buyer_business_id, supplier_business_id, checkout_group_key);
