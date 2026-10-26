-- V38: Bulk-purchase allocation between businesses (Phase 6).
-- Coordination only: no wallet, no ledger, no payment table. Each allocation gets its OWN stock_orders row which is paid by
-- the existing stock checkout. V1-V37 are untouched.

CREATE TABLE wholesale_volume_tiers (
    id                   VARCHAR(36) PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    wholesale_product_id VARCHAR(36) NOT NULL REFERENCES wholesale_products(id),
    min_packs            INTEGER NOT NULL,
    price_per_pack       DECIMAL(19,2) NOT NULL,
    CONSTRAINT ck_wvt_min CHECK (min_packs > 0),
    CONSTRAINT ck_wvt_price CHECK (price_per_pack > 0),
    CONSTRAINT uq_wvt_product_min UNIQUE (wholesale_product_id, min_packs)
);

CREATE TABLE bulk_purchase_groups (
    id                      VARCHAR(36) PRIMARY KEY,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    creator_business_id     VARCHAR(36) NOT NULL REFERENCES businesses(id),
    supplier_business_id    VARCHAR(36) NOT NULL REFERENCES businesses(id),
    wholesale_product_id    VARCHAR(36) NOT NULL REFERENCES wholesale_products(id),
    name                    VARCHAR(200) NOT NULL,
    description             VARCHAR(2000),
    procurement_reference   VARCHAR(40) NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    base_price_per_pack     DECIMAL(19,2) NOT NULL,
    pack_size_snapshot      INTEGER NOT NULL,
    moq_packs               INTEGER NOT NULL,
    target_packs            INTEGER NOT NULL,
    deadline                TIMESTAMP NOT NULL,
    shortfall_policy        VARCHAR(24) NOT NULL,
    extension_hours         INTEGER NOT NULL DEFAULT 48,
    extension_count         INTEGER NOT NULL DEFAULT 0,
    confirmation_windows_used INTEGER NOT NULL DEFAULT 0,
    allow_self_collection   BOOLEAN NOT NULL DEFAULT TRUE,
    allow_supplier_delivery BOOLEAN NOT NULL DEFAULT FALSE,
    allow_pula_ride         BOOLEAN NOT NULL DEFAULT FALSE,
    collection_note         VARCHAR(500),
    status                  VARCHAR(24) NOT NULL,
    final_price_per_pack    DECIMAL(19,2),
    final_aggregate_packs   INTEGER,
    finalised_at            TIMESTAMP,
    order_placed_at         TIMESTAMP,
    supplier_confirmed_packs INTEGER,
    supplier_reference      VARCHAR(100),
    supplier_note           VARCHAR(500),
    cancel_reason           VARCHAR(500),
    CONSTRAINT uq_bpg_reference UNIQUE (procurement_reference),
    CONSTRAINT ck_bpg_target CHECK (target_packs > 0 AND moq_packs > 0)
);
CREATE INDEX idx_bpg_status_deadline ON bulk_purchase_groups(status, deadline);
CREATE INDEX idx_bpg_supplier ON bulk_purchase_groups(supplier_business_id, status);
CREATE INDEX idx_bpg_creator ON bulk_purchase_groups(creator_business_id);

CREATE TABLE bulk_group_tiers (
    group_id        VARCHAR(36) NOT NULL REFERENCES bulk_purchase_groups(id),
    min_packs       INTEGER NOT NULL,
    price_per_pack  DECIMAL(19,2) NOT NULL
);
CREATE INDEX idx_bgt_group ON bulk_group_tiers(group_id);

CREATE TABLE bulk_allocations (
    id                        VARCHAR(36) PRIMARY KEY,
    created_at                TIMESTAMP NOT NULL,
    updated_at                TIMESTAMP NOT NULL,
    version                   BIGINT NOT NULL DEFAULT 0,
    group_id                  VARCHAR(36) NOT NULL REFERENCES bulk_purchase_groups(id),
    business_id               VARCHAR(36) NOT NULL REFERENCES businesses(id),
    status                    VARCHAR(24) NOT NULL,
    requested_packs           INTEGER NOT NULL,
    confirmed_packs           INTEGER,
    agreed_max_price_per_pack DECIMAL(19,2),
    agreed_at                 TIMESTAMP,
    fulfilment_method         VARCHAR(24) NOT NULL,
    delivery_address          VARCHAR(500),
    final_price_per_pack      DECIMAL(19,2),
    discount_per_pack         DECIMAL(19,2),
    subtotal                  DECIMAL(19,2),
    delivery_fee              DECIMAL(19,2),
    total                     DECIMAL(19,2),
    stock_order_id            VARCHAR(36) REFERENCES stock_orders(id),
    save_to_buy_request_id    VARCHAR(36),
    allocated_packs           INTEGER,
    shortage_packs            INTEGER NOT NULL DEFAULT 0,
    issue_packs               INTEGER NOT NULL DEFAULT 0,
    refund_due_amount         DECIMAL(19,2) NOT NULL DEFAULT 0,
    CONSTRAINT uq_bulk_alloc_group_business UNIQUE (group_id, business_id),
    CONSTRAINT ck_bulk_alloc_requested CHECK (requested_packs > 0)
);
CREATE INDEX idx_bulk_alloc_business ON bulk_allocations(business_id, status);
CREATE INDEX idx_bulk_alloc_group ON bulk_allocations(group_id, status);
-- An order can belong to only one allocation (NULLs are allowed many times).
CREATE UNIQUE INDEX uq_bulk_alloc_order ON bulk_allocations(stock_order_id);

CREATE TABLE bulk_group_events (
    id            VARCHAR(36) PRIMARY KEY,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    group_id      VARCHAR(36) NOT NULL REFERENCES bulk_purchase_groups(id),
    business_id   VARCHAR(36),
    actor_user_id VARCHAR(36),
    type          VARCHAR(40) NOT NULL,
    message       VARCHAR(500),
    packs         INTEGER,
    amount        DECIMAL(19,2)
);
CREATE INDEX idx_bge_group ON bulk_group_events(group_id, created_at);
