-- Money Confidence core (Phase I.1): a user's self-described income
-- pattern, individual income entries for the irregular-income planner, and
-- a single active budget with categorized allocations. None of this reads
-- or writes Community Savings / MoMoMarket / MoMoRide tables directly —
-- the insights layer that eventually connects them queries those tables
-- read-only rather than duplicating their data in here.

CREATE TABLE financial_profiles (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    user_id      VARCHAR(36) NOT NULL UNIQUE REFERENCES users(id),
    income_type  VARCHAR(32) NOT NULL DEFAULT 'VARIABLE_INCOME',
    currency     VARCHAR(3) NOT NULL
);

CREATE TABLE income_records (
    id             VARCHAR(36) PRIMARY KEY,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL,
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    amount         DECIMAL(19,2) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    received_date  DATE NOT NULL,
    source         VARCHAR(255)
);
CREATE INDEX idx_income_records_user ON income_records(user_id);
CREATE INDEX idx_income_records_user_date ON income_records(user_id, received_date);

CREATE TABLE budgets (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    user_id     VARCHAR(36) NOT NULL UNIQUE REFERENCES users(id),
    currency    VARCHAR(3) NOT NULL,
    label       VARCHAR(255) NOT NULL
);

CREATE TABLE budget_categories (
    id                VARCHAR(36) PRIMARY KEY,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    budget_id         VARCHAR(36) NOT NULL REFERENCES budgets(id),
    name              VARCHAR(255) NOT NULL,
    allocated_amount  DECIMAL(19,2) NOT NULL
);
CREATE INDEX idx_budget_categories_budget ON budget_categories(budget_id);
