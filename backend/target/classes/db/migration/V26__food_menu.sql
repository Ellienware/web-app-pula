-- PULA Food: menu categories, optional structured food details on a Product,
-- and per-branch availability / price override. Menu items ARE products.
CREATE TABLE menu_categories (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    business_id  VARCHAR(36) NOT NULL REFERENCES businesses(id),
    name         VARCHAR(100) NOT NULL,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_menu_category_name UNIQUE (business_id, name)
);
CREATE INDEX idx_menu_category_business ON menu_categories(business_id);

ALTER TABLE products ADD COLUMN menu_category_id VARCHAR(36) REFERENCES menu_categories(id);
CREATE INDEX idx_products_menu_category ON products(menu_category_id);

CREATE TABLE product_food_details (
    id                  VARCHAR(36) PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    product_id          VARCHAR(36) NOT NULL UNIQUE REFERENCES products(id),
    food_type           VARCHAR(64),
    protein             VARCHAR(64),
    starch              VARCHAR(64),
    portion_size        VARCHAR(32),
    serving_size        VARCHAR(64),
    spice_level         VARCHAR(32),
    portion_description VARCHAR(255),
    prep_time_minutes   INTEGER,
    daily_quantity      INTEGER
);
CREATE INDEX idx_food_details_protein ON product_food_details(protein);
CREATE INDEX idx_food_details_starch ON product_food_details(starch);

CREATE TABLE product_food_sides (
    food_details_id  VARCHAR(36) NOT NULL REFERENCES product_food_details(id),
    side             VARCHAR(64) NOT NULL,
    PRIMARY KEY (food_details_id, side)
);
CREATE TABLE product_dietary_tags (
    food_details_id  VARCHAR(36) NOT NULL REFERENCES product_food_details(id),
    tag              VARCHAR(64) NOT NULL,
    PRIMARY KEY (food_details_id, tag)
);
CREATE TABLE product_allergens (
    food_details_id  VARCHAR(36) NOT NULL REFERENCES product_food_details(id),
    allergen         VARCHAR(64) NOT NULL,
    PRIMARY KEY (food_details_id, allergen)
);

CREATE TABLE branch_item_availability (
    id              VARCHAR(36) PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    branch_id       VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    product_id      VARCHAR(36) NOT NULL REFERENCES products(id),
    available       BOOLEAN NOT NULL DEFAULT TRUE,
    price_override  DECIMAL(19,2),
    CONSTRAINT uq_branch_item UNIQUE (branch_id, product_id)
);
CREATE INDEX idx_branch_item_product ON branch_item_availability(product_id);
