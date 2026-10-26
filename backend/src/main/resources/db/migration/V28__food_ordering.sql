-- PULA Food (Phase 3, step 2): modifiers in the cart, food orders, restaurant accept/reject.
-- Retail rows keep working unchanged: every new column is nullable or has a retail default.

-- A cart line is now (user, product, modifier set). The signature is a SHA-256 hex of the
-- sorted modifier ids, '' when there are none, so the same dish with different extras can
-- be separate lines while a duplicate add still merges.
ALTER TABLE cart_items DROP CONSTRAINT uq_cart_user_product;
ALTER TABLE cart_items ADD COLUMN modifier_signature VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE cart_items ADD CONSTRAINT uq_cart_user_product_mods UNIQUE (user_id, product_id, modifier_signature);
CREATE UNIQUE INDEX uq_cart_item_modifier ON cart_item_modifiers(cart_item_id, modifier_id);

-- Business-level food settings (single-location businesses have no branch row to close).
ALTER TABLE businesses ADD COLUMN temporarily_closed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE businesses ADD COLUMN accept_timeout_minutes INTEGER;

-- Food orders.
ALTER TABLE orders ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'RETAIL';
ALTER TABLE orders ADD COLUMN branch_id VARCHAR(36) REFERENCES business_branches(id);
ALTER TABLE orders ADD COLUMN restaurant_decision VARCHAR(16);
ALTER TABLE orders ADD COLUMN decision_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN accept_deadline_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN rejection_reason VARCHAR(255);
ALTER TABLE orders ADD COLUMN delivery_fee_snapshot DECIMAL(19,2);
ALTER TABLE orders ADD COLUMN customer_note VARCHAR(500);
CREATE INDEX idx_orders_pending_decision ON orders(restaurant_decision, accept_deadline_at);
