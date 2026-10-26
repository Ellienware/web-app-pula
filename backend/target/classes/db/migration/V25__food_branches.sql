-- PULA Food (Phase 3, step 1): optional branches for food businesses.
-- A business with zero branch rows behaves as a single location using the
-- existing businesses.* columns. Branch values only override when present.
-- business_type is VARCHAR(32) with no CHECK constraint (V8), so the new
-- food business types need no DDL.
CREATE TABLE business_branches (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    name                  VARCHAR(255) NOT NULL,
    address               VARCHAR(500),
    area                  VARCHAR(255),
    latitude              DECIMAL(9,6),
    longitude             DECIMAL(9,6),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    temporarily_closed    BOOLEAN NOT NULL DEFAULT FALSE,
    prep_time_minutes     INTEGER,
    delivery_fee          DECIMAL(19,2),
    minimum_order_amount  DECIMAL(19,2)
);
CREATE INDEX idx_branch_business ON business_branches(business_id);
CREATE INDEX idx_branch_area ON business_branches(area);

CREATE TABLE branch_operating_hours (
    branch_id        VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    operating_hours  VARCHAR(255)
);
CREATE INDEX idx_branch_hours ON branch_operating_hours(branch_id);

CREATE TABLE branch_fulfilment_options (
    branch_id          VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    fulfilment_option  VARCHAR(32) NOT NULL,
    PRIMARY KEY (branch_id, fulfilment_option)
);

CREATE TABLE branch_delivery_areas (
    branch_id  VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    area       VARCHAR(255) NOT NULL,
    PRIMARY KEY (branch_id, area)
);
