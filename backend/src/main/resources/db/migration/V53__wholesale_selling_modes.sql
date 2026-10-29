-- Wholesale listings may be sold by the pack, by the unit, or both.
-- Existing inventory remains pack-based for backward compatibility; unit checkout
-- must be enabled only after the unit-reservation path is deployed.
ALTER TABLE wholesale_products
    ADD COLUMN selling_mode VARCHAR(30) NOT NULL DEFAULT 'BULK_ONLY';

ALTER TABLE wholesale_products
    ADD COLUMN price_per_unit NUMERIC(19,2);

-- Unit price is intentionally nullable for legacy bulk-only listings.
CREATE INDEX idx_wp_selling_mode ON wholesale_products(selling_mode);
