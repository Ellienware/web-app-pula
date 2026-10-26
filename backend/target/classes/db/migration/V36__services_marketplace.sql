-- V36: Services marketplace (Phase 4). Providers are existing Businesses with the new CAN_PROVIDE_SERVICES capability.
-- Payment, reviews, stock materials, Save-to-Buy and audit logging all REUSE existing tables; nothing here is a
-- payment provider, wallet or ledger. Customer prices are never trusted: quote totals are computed on the server.

CREATE TABLE service_categories (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    parent_id   VARCHAR(36) REFERENCES service_categories(id),
    name        VARCHAR(80) NOT NULL,
    slug        VARCHAR(80) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_service_category_slug UNIQUE (slug)
);

CREATE TABLE service_provider_profiles (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    headline              VARCHAR(160),
    about                 VARCHAR(2000),
    contact_email         VARCHAR(160),
    availability_note     VARCHAR(300),
    accepting_requests    BOOLEAN NOT NULL DEFAULT TRUE,
    verification_status   VARCHAR(16) NOT NULL,
    verified_at           TIMESTAMP,
    suspended             BOOLEAN NOT NULL DEFAULT FALSE,
    suspended_reason      VARCHAR(300),
    CONSTRAINT uq_provider_profile_business UNIQUE (business_id)
);
CREATE TABLE service_provider_areas (
    profile_id VARCHAR(36) NOT NULL REFERENCES service_provider_profiles(id),
    area       VARCHAR(80) NOT NULL
);
CREATE INDEX idx_provider_areas ON service_provider_areas(profile_id);
CREATE TABLE service_provider_portfolio (
    profile_id VARCHAR(36) NOT NULL REFERENCES service_provider_profiles(id),
    image_url  VARCHAR(500) NOT NULL
);

CREATE TABLE service_listings (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    category_id           VARCHAR(36) NOT NULL REFERENCES service_categories(id),
    name                  VARCHAR(120) NOT NULL,
    description           VARCHAR(2000),
    pricing_model         VARCHAR(16) NOT NULL,
    price                 DECIMAL(19,2),
    currency              VARCHAR(3) NOT NULL,
    duration_minutes      INTEGER,
    materials_responsibility VARCHAR(16) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    hidden_by_admin       BOOLEAN NOT NULL DEFAULT FALSE,
    rating_average        DECIMAL(3,2) NOT NULL DEFAULT 0,
    rating_count          INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_service_price CHECK (price IS NULL OR price > 0)
);
CREATE INDEX idx_service_listing_business ON service_listings(business_id);
CREATE INDEX idx_service_listing_category ON service_listings(category_id, status);
CREATE TABLE service_listing_areas (
    listing_id VARCHAR(36) NOT NULL REFERENCES service_listings(id),
    area       VARCHAR(80) NOT NULL
);
CREATE INDEX idx_listing_areas ON service_listing_areas(listing_id);
CREATE TABLE service_listing_images (
    listing_id VARCHAR(36) NOT NULL REFERENCES service_listings(id),
    image_url  VARCHAR(500) NOT NULL
);

CREATE TABLE service_requests (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    customer_id           VARCHAR(36) NOT NULL REFERENCES users(id),
    listing_id            VARCHAR(36) NOT NULL REFERENCES service_listings(id),
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    description           VARCHAR(2000) NOT NULL,
    location_text         VARCHAR(300) NOT NULL,
    preferred_date        DATE,
    preferred_time        VARCHAR(40),
    requirements          VARCHAR(1000),
    materials_responsibility VARCHAR(16) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    idempotency_key       VARCHAR(100),
    expires_at            TIMESTAMP,
    CONSTRAINT uq_service_request_idem UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_service_request_customer ON service_requests(customer_id, status);
CREATE INDEX idx_service_request_business ON service_requests(business_id, status);

CREATE TABLE service_quotes (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    request_id            VARCHAR(36) NOT NULL REFERENCES service_requests(id),
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    version_no            INTEGER NOT NULL,
    status                VARCHAR(24) NOT NULL,
    hours                 DECIMAL(9,2),
    labour_amount         DECIMAL(19,2) NOT NULL,
    materials_amount      DECIMAL(19,2) NOT NULL,
    delivery_amount       DECIMAL(19,2) NOT NULL,
    other_amount          DECIMAL(19,2) NOT NULL,
    other_description     VARCHAR(200),
    total                 DECIMAL(19,2) NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    valid_until           TIMESTAMP NOT NULL,
    notes                 VARCHAR(1000),
    customer_message      VARCHAR(500),
    decided_at            TIMESTAMP,
    CONSTRAINT ck_quote_total CHECK (total > 0),
    CONSTRAINT uq_quote_request_version UNIQUE (request_id, version_no)
);

CREATE TABLE service_bookings (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    request_id            VARCHAR(36) NOT NULL REFERENCES service_requests(id),
    quote_id              VARCHAR(36) NOT NULL REFERENCES service_quotes(id),
    customer_id           VARCHAR(36) NOT NULL REFERENCES users(id),
    business_id           VARCHAR(36) NOT NULL REFERENCES businesses(id),
    listing_id            VARCHAR(36) NOT NULL REFERENCES service_listings(id),
    status                VARCHAR(16) NOT NULL,
    total                 DECIMAL(19,2) NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    scheduled_date        DATE,
    scheduled_time        VARCHAR(40),
    started_at            TIMESTAMP,
    completed_at          TIMESTAMP,
    cancelled_at          TIMESTAMP,
    cancelled_by_id       VARCHAR(36) REFERENCES users(id),
    cancel_reason         VARCHAR(300),
    payment_id            VARCHAR(36) REFERENCES payments(id),
    payment_attempts      INTEGER NOT NULL DEFAULT 0,
    save_to_buy_request_id VARCHAR(36) REFERENCES save_to_buy_requests(id),
    CONSTRAINT uq_booking_request UNIQUE (request_id),
    CONSTRAINT uq_booking_quote UNIQUE (quote_id),
    CONSTRAINT ck_booking_total CHECK (total > 0)
);
CREATE INDEX idx_booking_customer ON service_bookings(customer_id, status);
CREATE INDEX idx_booking_business ON service_bookings(business_id, status);

-- Materials come from PULA Stock (wholesale products). The stock order is the existing StockOrder.
CREATE TABLE service_material_lines (
    id                    VARCHAR(36) PRIMARY KEY,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    request_id            VARCHAR(36) NOT NULL REFERENCES service_requests(id),
    wholesale_product_id  VARCHAR(36) NOT NULL REFERENCES wholesale_products(id),
    quantity_packs        INTEGER NOT NULL,
    supplied_by           VARCHAR(16) NOT NULL,
    unit_price_snapshot   DECIMAL(19,2) NOT NULL,
    stock_order_id        VARCHAR(36) REFERENCES stock_orders(id),
    added_by_id           VARCHAR(36) NOT NULL REFERENCES users(id),
    CONSTRAINT ck_material_qty CHECK (quantity_packs > 0)
);
CREATE INDEX idx_material_request ON service_material_lines(request_id);

-- Reviews are the EXISTING reviews table, extended: a review may now come from a completed service booking.
ALTER TABLE reviews ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE reviews ADD COLUMN service_booking_id VARCHAR(36) REFERENCES service_bookings(id);

-- Seed catalogue (admin-managed afterwards; nothing in code depends on these rows).
INSERT INTO service_categories (id, created_at, updated_at, parent_id, name, slug, sort_order, active) VALUES
 ('00000000-0000-4000-8000-0000000000a1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Construction & Home Improvement', 'construction-home-improvement', 10, TRUE),
 ('00000000-0000-4000-8000-0000000000a2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Automotive', 'automotive', 20, TRUE),
 ('00000000-0000-4000-8000-0000000000a3', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Personal', 'personal', 30, TRUE),
 ('00000000-0000-4000-8000-0000000000a4', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Cleaning & Maintenance', 'cleaning-maintenance', 40, TRUE),
 ('00000000-0000-4000-8000-0000000000a5', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'Professional & Creative', 'professional-creative', 50, TRUE);
INSERT INTO service_categories (id, created_at, updated_at, parent_id, name, slug, sort_order, active)
SELECT v.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, v.parent_id, v.name, v.slug, v.sort_order, TRUE FROM (VALUES
 ('00000000-0000-4000-8000-0000000001a1', '00000000-0000-4000-8000-0000000000a1', 'Plumbing', 'plumbing', 1),
 ('00000000-0000-4000-8000-0000000001a2', '00000000-0000-4000-8000-0000000000a1', 'Electrical', 'electrical', 2),
 ('00000000-0000-4000-8000-0000000001a3', '00000000-0000-4000-8000-0000000000a1', 'Painting', 'painting', 3),
 ('00000000-0000-4000-8000-0000000001a4', '00000000-0000-4000-8000-0000000000a1', 'Tiling', 'tiling', 4),
 ('00000000-0000-4000-8000-0000000001a5', '00000000-0000-4000-8000-0000000000a1', 'Building', 'building', 5),
 ('00000000-0000-4000-8000-0000000001a6', '00000000-0000-4000-8000-0000000000a1', 'Carpentry', 'carpentry', 6),
 ('00000000-0000-4000-8000-0000000002a1', '00000000-0000-4000-8000-0000000000a2', 'Mechanics', 'mechanics', 1),
 ('00000000-0000-4000-8000-0000000002a2', '00000000-0000-4000-8000-0000000000a2', 'Car wash', 'car-wash', 2),
 ('00000000-0000-4000-8000-0000000002a3', '00000000-0000-4000-8000-0000000000a2', 'Tyres', 'tyres', 3),
 ('00000000-0000-4000-8000-0000000002a4', '00000000-0000-4000-8000-0000000000a2', 'Auto electrical', 'auto-electrical', 4),
 ('00000000-0000-4000-8000-0000000003a1', '00000000-0000-4000-8000-0000000000a3', 'Hair', 'hair', 1),
 ('00000000-0000-4000-8000-0000000003a2', '00000000-0000-4000-8000-0000000000a3', 'Beauty', 'beauty', 2),
 ('00000000-0000-4000-8000-0000000003a3', '00000000-0000-4000-8000-0000000000a3', 'Barber', 'barber', 3),
 ('00000000-0000-4000-8000-0000000003a4', '00000000-0000-4000-8000-0000000000a3', 'Fitness', 'fitness', 4),
 ('00000000-0000-4000-8000-0000000004a1', '00000000-0000-4000-8000-0000000000a4', 'Home cleaning', 'home-cleaning', 1),
 ('00000000-0000-4000-8000-0000000004a2', '00000000-0000-4000-8000-0000000000a4', 'Garden maintenance', 'garden-maintenance', 2),
 ('00000000-0000-4000-8000-0000000004a3', '00000000-0000-4000-8000-0000000000a4', 'Repairs', 'repairs', 3),
 ('00000000-0000-4000-8000-0000000005a1', '00000000-0000-4000-8000-0000000000a5', 'Graphic design', 'graphic-design', 1),
 ('00000000-0000-4000-8000-0000000005a2', '00000000-0000-4000-8000-0000000000a5', 'Photography', 'photography', 2),
 ('00000000-0000-4000-8000-0000000005a3', '00000000-0000-4000-8000-0000000000a5', 'IT support', 'it-support', 3),
 ('00000000-0000-4000-8000-0000000005a4', '00000000-0000-4000-8000-0000000000a5', 'Accounting', 'accounting', 4),
 ('00000000-0000-4000-8000-0000000005a5', '00000000-0000-4000-8000-0000000000a5', 'Marketing', 'marketing', 5)
) AS v(id, parent_id, name, slug, sort_order);
