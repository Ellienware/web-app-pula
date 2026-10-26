-- Smart Basket Optimization (Phase K, brief §10): real, seller-configured
-- minimum-order and delivery-fee data. Both nullable/non-breaking for
-- existing rows; null means "not configured", never defaulted to a
-- fabricated number.
ALTER TABLE businesses ADD COLUMN minimum_order_amount DECIMAL(19,2);
ALTER TABLE businesses ADD COLUMN delivery_fee DECIMAL(19,2);
