-- PULA Food: modifier groups (sides, extras) and their modifiers, attached to
-- menu items. "Required" is derived from min_select >= 1.
-- cart_item_modifiers / order_item_modifiers are created here but only used
-- from the ordering step; order snapshots are never recomputed after payment.
CREATE TABLE modifier_groups (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    business_id  VARCHAR(36) NOT NULL REFERENCES businesses(id),
    name         VARCHAR(100) NOT NULL,
    min_select   INTEGER NOT NULL DEFAULT 0,
    max_select   INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT ck_modifier_group_bounds CHECK (min_select >= 0 AND max_select >= min_select AND max_select >= 1)
);
CREATE INDEX idx_modifier_group_business ON modifier_groups(business_id);

CREATE TABLE modifiers (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    group_id     VARCHAR(36) NOT NULL REFERENCES modifier_groups(id),
    name         VARCHAR(100) NOT NULL,
    price_delta  DECIMAL(19,2) NOT NULL DEFAULT 0,
    available    BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_modifier_price CHECK (price_delta >= 0)
);
CREATE INDEX idx_modifier_group ON modifiers(group_id);

CREATE TABLE product_modifier_groups (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    product_id  VARCHAR(36) NOT NULL REFERENCES products(id),
    group_id    VARCHAR(36) NOT NULL REFERENCES modifier_groups(id),
    CONSTRAINT uq_product_modifier_group UNIQUE (product_id, group_id)
);
CREATE INDEX idx_pmg_product ON product_modifier_groups(product_id);

CREATE TABLE cart_item_modifiers (
    id               VARCHAR(36) PRIMARY KEY,
    cart_item_id     VARCHAR(36) NOT NULL REFERENCES cart_items(id),
    modifier_id      VARCHAR(36) NOT NULL REFERENCES modifiers(id)
);
CREATE INDEX idx_cart_item_modifier ON cart_item_modifiers(cart_item_id);

CREATE TABLE order_item_modifiers (
    id                    VARCHAR(36) PRIMARY KEY,
    order_item_id         VARCHAR(36) NOT NULL REFERENCES order_items(id),
    modifier_id           VARCHAR(36) REFERENCES modifiers(id),
    modifier_name_snapshot VARCHAR(100) NOT NULL,
    price_delta_snapshot  DECIMAL(19,2) NOT NULL
);
CREATE INDEX idx_order_item_modifier ON order_item_modifiers(order_item_id);
