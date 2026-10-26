-- Canonical product catalogue + the optional link from each seller's
-- Product listing into it, enabling price comparison (Phase G.1).
CREATE TABLE canonical_products (
    id            VARCHAR(36) PRIMARY KEY,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    name          VARCHAR(255) NOT NULL,
    brand         VARCHAR(255),
    category      VARCHAR(255) NOT NULL,
    subcategory   VARCHAR(255),
    size          VARCHAR(50),
    unit          VARCHAR(50),
    barcode       VARCHAR(64)
);
-- Plain UNIQUE index, not a partial one: standard SQL treats every NULL as
-- distinct from every other NULL, so this already allows any number of
-- products with no barcode while still rejecting two identical real ones —
-- no WHERE clause needed, and it's portable to plain SQL UNIQUE semantics
-- rather than a Postgres-specific partial-index feature.
CREATE UNIQUE INDEX uq_canonical_products_barcode ON canonical_products(barcode);
CREATE INDEX idx_canonical_products_category ON canonical_products(category);

ALTER TABLE products ADD COLUMN canonical_product_id VARCHAR(36) REFERENCES canonical_products(id);
ALTER TABLE products ADD COLUMN match_classification VARCHAR(32) NOT NULL DEFAULT 'UNIQUE_PRODUCT';
CREATE INDEX idx_products_canonical_product ON products(canonical_product_id);
