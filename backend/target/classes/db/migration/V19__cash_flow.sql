-- PULA Cash Flow (brief §16-17).
--
-- Two tables for manually-recorded income/expense entries scoped to a Business.
-- PULA Market sales (Order) and stock procurement spend (StockOrder) are
-- derived at query time — they are NOT duplicated here. These tables only hold
-- entries that happened outside PULA's own transaction flow (cash sales,
-- packaging costs, utility bills, etc.).

CREATE TABLE business_income_entries (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    business_id   VARCHAR(36) NOT NULL REFERENCES businesses(id),
    amount        NUMERIC(19,2) NOT NULL,
    currency      VARCHAR(3) NOT NULL DEFAULT 'ZAR',
    received_date DATE NOT NULL,
    description   VARCHAR(500),
    category      VARCHAR(100),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_bie_business ON business_income_entries(business_id);
CREATE INDEX idx_bie_date ON business_income_entries(received_date);

CREATE TABLE business_expense_entries (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    business_id   VARCHAR(36) NOT NULL REFERENCES businesses(id),
    amount        NUMERIC(19,2) NOT NULL,
    currency      VARCHAR(3) NOT NULL DEFAULT 'ZAR',
    incurred_date DATE NOT NULL,
    description   VARCHAR(500),
    category      VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_bee_business ON business_expense_entries(business_id);
CREATE INDEX idx_bee_date ON business_expense_entries(incurred_date);
