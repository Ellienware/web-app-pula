-- PULA Market — Stock / B2B procurement (brief §6, §11-15).
-- All tables are scoped to businesses with CAN_SUPPLY_BUSINESSES (supplier) or
-- CAN_BUY_STOCK (buyer) — enforced in application code, not DB constraints,
-- since adding DB-level capability checks would require a function or trigger
-- that duplicates service-layer logic.

-- Supplier's B2B wholesale catalogue. Separate from 'products' (consumer
-- catalogue) because pack-size/MOQ/available-stock semantics don't apply to
-- consumer listings, and mixing them would corrupt price-comparison logic.
CREATE TABLE wholesale_products (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    supplier_business_id  VARCHAR(36) NOT NULL REFERENCES businesses(id),
    name                  VARCHAR(200) NOT NULL,
    description           VARCHAR(2000),
    sku                   VARCHAR(100),
    category              VARCHAR(100),
    pack_size             INT NOT NULL DEFAULT 1,
    moq                   INT NOT NULL DEFAULT 1,
    price_per_pack        NUMERIC(19,2) NOT NULL,
    currency              VARCHAR(3) NOT NULL DEFAULT 'ZAR',
    available_stock       INT NOT NULL DEFAULT 0,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL
);
CREATE INDEX idx_wp_supplier ON wholesale_products(supplier_business_id);
CREATE INDEX idx_wp_category ON wholesale_products(category);
CREATE INDEX idx_wp_active ON wholesale_products(active);

-- Buyer's active stock cart. One row per (buyer_business, wholesale_product).
-- "The cart is simply these rows" — same zero-Cart-entity pattern as cart_items.
CREATE TABLE stock_cart_items (
    id                      VARCHAR(36) NOT NULL PRIMARY KEY,
    buyer_business_id       VARCHAR(36) NOT NULL REFERENCES businesses(id),
    wholesale_product_id    VARCHAR(36) NOT NULL REFERENCES wholesale_products(id),
    quantity_packs          INT NOT NULL,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL,
    UNIQUE (buyer_business_id, wholesale_product_id)
);
CREATE INDEX idx_sci_buyer ON stock_cart_items(buyer_business_id);

-- B2B stock procurement order — one per (buyer, supplier) per checkout
-- (multi-supplier carts create one order per supplier).
CREATE TABLE stock_orders (
    id                      VARCHAR(36) NOT NULL PRIMARY KEY,
    buyer_business_id       VARCHAR(36) NOT NULL REFERENCES businesses(id),
    supplier_business_id    VARCHAR(36) NOT NULL REFERENCES businesses(id),
    subtotal                NUMERIC(19,2) NOT NULL,
    delivery_fee_snapshot   NUMERIC(19,2) NOT NULL,
    total                   NUMERIC(19,2) NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'ZAR',
    status                  VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT',
    fulfilment_method       VARCHAR(32) NOT NULL,
    delivery_address        VARCHAR(500),
    idempotency_key         VARCHAR(100) UNIQUE,
    payment_id              VARCHAR(36) UNIQUE REFERENCES payments(id),
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL
);
CREATE INDEX idx_so_buyer ON stock_orders(buyer_business_id);
CREATE INDEX idx_so_supplier ON stock_orders(supplier_business_id);
CREATE INDEX idx_so_status ON stock_orders(status);

-- Frozen line items for each stock order (price/name/sku captured at order time).
CREATE TABLE stock_order_items (
    id                      VARCHAR(36) NOT NULL PRIMARY KEY,
    stock_order_id          VARCHAR(36) NOT NULL REFERENCES stock_orders(id),
    wholesale_product_id    VARCHAR(36) NOT NULL REFERENCES wholesale_products(id),
    product_name_snapshot   VARCHAR(200) NOT NULL,
    sku_snapshot            VARCHAR(100),
    pack_size_snapshot      INT NOT NULL,
    quantity_packs          INT NOT NULL,
    price_per_pack_snapshot NUMERIC(19,2) NOT NULL,
    line_total              NUMERIC(19,2) NOT NULL,
    created_at              TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP NOT NULL
);
CREATE INDEX idx_soi_order ON stock_order_items(stock_order_id);
