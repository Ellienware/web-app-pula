-- V49 (Phase 9C part 2): branch stock for non-food products, audited stock movements, per-assignment permissions and descriptive
-- franchise fields. Forward-only and additive: no existing column changes meaning and no row is rewritten except the permission backfill.
--
-- STOCK MODEL. products.stock stays the business's CENTRAL (unallocated) pool and keeps working exactly as before for any order that
-- names no branch. A branch holds an explicit allocation in branch_product_stock, moved there from the pool (ALLOCATE), back
-- (RETURN_TO_CENTRAL) or between branches (TRANSFER). Every move writes one branch_stock_movements row, so
-- central + sum(branches) + units held by open orders is always explainable from the movements.

CREATE TABLE branch_product_stock (
    id              VARCHAR(36) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    branch_id       VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    product_id      VARCHAR(36) NOT NULL REFERENCES products(id),
    quantity        INTEGER NOT NULL DEFAULT 0,
    available       BOOLEAN NOT NULL DEFAULT TRUE,
    price_override  DECIMAL(19,2),
    CONSTRAINT uq_branch_product UNIQUE (branch_id, product_id),
    CONSTRAINT ck_branch_stock_qty CHECK (quantity >= 0),
    CONSTRAINT ck_branch_price_override CHECK (price_override IS NULL OR price_override > 0)
);
CREATE INDEX idx_branch_stock_product ON branch_product_stock (product_id);

CREATE TABLE branch_stock_movements (
    id              VARCHAR(36) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    business_id     VARCHAR(36) NOT NULL REFERENCES businesses(id),
    product_id      VARCHAR(36) NOT NULL REFERENCES products(id),
    from_branch_id  VARCHAR(36) REFERENCES business_branches(id),
    to_branch_id    VARCHAR(36) REFERENCES business_branches(id),
    quantity        INTEGER NOT NULL,
    movement_type   VARCHAR(24) NOT NULL,
    order_id        VARCHAR(36),
    actor_user_id   VARCHAR(36) REFERENCES users(id),
    note            VARCHAR(255),
    CONSTRAINT ck_branch_move_qty CHECK (quantity > 0),
    CONSTRAINT ck_branch_move_type CHECK (movement_type IN ('ALLOCATE', 'RETURN_TO_CENTRAL', 'TRANSFER', 'SALE_RESERVE', 'SALE_RELEASE')),
    CONSTRAINT ck_branch_move_shape CHECK (
        (movement_type = 'ALLOCATE'          AND from_branch_id IS NULL     AND to_branch_id IS NOT NULL)
     OR (movement_type = 'RETURN_TO_CENTRAL' AND from_branch_id IS NOT NULL AND to_branch_id IS NULL)
     OR (movement_type = 'TRANSFER'          AND from_branch_id IS NOT NULL AND to_branch_id IS NOT NULL AND from_branch_id <> to_branch_id)
     OR (movement_type = 'SALE_RESERVE'      AND from_branch_id IS NOT NULL AND to_branch_id IS NULL     AND order_id IS NOT NULL)
     OR (movement_type = 'SALE_RELEASE'      AND from_branch_id IS NULL     AND to_branch_id IS NOT NULL AND order_id IS NOT NULL))
);
CREATE INDEX idx_branch_move_business ON branch_stock_movements (business_id, created_at);
CREATE INDEX idx_branch_move_order ON branch_stock_movements (order_id);
-- At most one reserve and one release per (order, product): makes release idempotent and a replayed checkout unable to double-reserve.
CREATE UNIQUE INDEX uq_branch_move_order_product_type ON branch_stock_movements (order_id, product_id, movement_type);

-- Per-assignment permissions (see BranchPermission). The owner is never stored here: owners always have every permission.
CREATE TABLE branch_staff_permissions (
    branch_staff_id  VARCHAR(36) NOT NULL REFERENCES branch_staff(id),
    permission       VARCHAR(24) NOT NULL,
    PRIMARY KEY (branch_staff_id, permission),
    CONSTRAINT ck_branch_staff_permission CHECK (permission IN ('MANAGE_STOCK', 'MANAGE_PRICES', 'VIEW_ORDERS', 'FULFIL_ORDERS', 'VIEW_REPORTS'))
);
-- Existing MANAGER assignments (created in V48, before permissions existed) keep the access managers had: all five. STAFF keep none.
INSERT INTO branch_staff_permissions (branch_staff_id, permission) SELECT id, 'MANAGE_STOCK'  FROM branch_staff WHERE role = 'MANAGER';
INSERT INTO branch_staff_permissions (branch_staff_id, permission) SELECT id, 'MANAGE_PRICES' FROM branch_staff WHERE role = 'MANAGER';
INSERT INTO branch_staff_permissions (branch_staff_id, permission) SELECT id, 'VIEW_ORDERS'   FROM branch_staff WHERE role = 'MANAGER';
INSERT INTO branch_staff_permissions (branch_staff_id, permission) SELECT id, 'FULFIL_ORDERS' FROM branch_staff WHERE role = 'MANAGER';
INSERT INTO branch_staff_permissions (branch_staff_id, permission) SELECT id, 'VIEW_REPORTS'  FROM branch_staff WHERE role = 'MANAGER';

-- Franchise modelling (descriptive and UNVERIFIED): who the brand is and who operates this outlet, as submitted by the business that
-- manages the branch. No cross-business link is created: outlets sharing a brand are NOT assumed to be one legal entity.
ALTER TABLE business_branches ADD COLUMN brand_name VARCHAR(255);
ALTER TABLE business_branches ADD COLUMN operating_model VARCHAR(16) NOT NULL DEFAULT 'OWNED';
ALTER TABLE business_branches ADD COLUMN operator_name VARCHAR(255);
ALTER TABLE business_branches ADD CONSTRAINT ck_branch_operating_model CHECK (operating_model IN ('OWNED', 'FRANCHISED'));

CREATE INDEX IF NOT EXISTS idx_orders_branch_id ON orders (branch_id);
