-- PULA Food (Phase 3, step 4): structured attributes and aliases for canonical foods, so that
-- "Beef stew and pap", "Beef & pap" and "Beef with pap" can be understood as related meals
-- without relying on raw string matching. Extends canonical_products; no new product system.
CREATE TABLE canonical_food_attributes (
    id                     VARCHAR(36) PRIMARY KEY,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL,
    canonical_product_id   VARCHAR(36) NOT NULL UNIQUE REFERENCES canonical_products(id),
    food_type              VARCHAR(64),
    protein                VARCHAR(64),
    starch                 VARCHAR(64),
    portion_class          VARCHAR(32),
    sides_signature        VARCHAR(255) NOT NULL DEFAULT ''
);
CREATE INDEX idx_cfa_protein ON canonical_food_attributes(protein);

CREATE TABLE canonical_food_aliases (
    id                     VARCHAR(36) PRIMARY KEY,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL,
    canonical_product_id   VARCHAR(36) NOT NULL REFERENCES canonical_products(id),
    alias                  VARCHAR(160) NOT NULL,
    CONSTRAINT uq_cfa_alias UNIQUE (canonical_product_id, alias)
);
CREATE INDEX idx_cfa_alias ON canonical_food_aliases(alias);
