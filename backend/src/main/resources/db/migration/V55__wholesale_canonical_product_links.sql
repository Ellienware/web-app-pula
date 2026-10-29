ALTER TABLE wholesale_products
    ADD COLUMN canonical_product_id VARCHAR(36)
        REFERENCES canonical_products(id);

ALTER TABLE wholesale_products
    ADD COLUMN match_classification VARCHAR(32)
        NOT NULL DEFAULT 'UNIQUE_PRODUCT';

CREATE INDEX idx_wholesale_products_canonical_product
    ON wholesale_products(canonical_product_id);
