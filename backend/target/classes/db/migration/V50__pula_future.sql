-- V50: PULA Future (planning + simulation ONLY). Forward-only. Nothing here is a ledger, wallet, contribution or payment record.
-- Existing goals are reused: circle_goals gains two nullable descriptive columns; no goal/savings/contribution tables are duplicated.
ALTER TABLE circle_goals ADD COLUMN category VARCHAR(40);
ALTER TABLE circle_goals ADD COLUMN priority VARCHAR(10);

-- A user's (or their business's) instruction for how a hypothetical income could be divided. Planning only.
CREATE TABLE allocation_plans (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    business_id         VARCHAR(36) REFERENCES businesses(id),
    name                VARCHAR(120) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    reserves_minor      BIGINT NOT NULL DEFAULT 0,
    commitments_minor   BIGINT NOT NULL DEFAULT 0,
    min_buffer_minor    BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT ck_ap_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    CONSTRAINT ck_ap_nonneg CHECK (reserves_minor >= 0 AND commitments_minor >= 0 AND min_buffer_minor >= 0)
);
CREATE INDEX idx_ap_owner ON allocation_plans(owner_user_id);

CREATE TABLE allocation_plan_lines (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    plan_id         VARCHAR(36) NOT NULL REFERENCES allocation_plans(id),
    goal_id         VARCHAR(36) REFERENCES circle_goals(id),
    label           VARCHAR(120) NOT NULL,
    line_type       VARCHAR(10) NOT NULL,
    fixed_minor     BIGINT NOT NULL DEFAULT 0,
    percent_bp      INTEGER NOT NULL DEFAULT 0,
    position        INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT ck_apl_type CHECK (line_type IN ('FIXED','PERCENT')),
    CONSTRAINT ck_apl_vals CHECK (fixed_minor >= 0 AND percent_bp >= 0 AND percent_bp <= 10000)
);
CREATE INDEX idx_apl_plan ON allocation_plan_lines(plan_id);

-- Immutable result of one calculation. Always a SIMULATION: there is deliberately no link to payments, wallet or ledger tables.
CREATE TABLE allocation_calculations (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    plan_id               VARCHAR(36) NOT NULL REFERENCES allocation_plans(id),
    idempotency_key       VARCHAR(80) NOT NULL,
    income_basis_minor    BIGINT NOT NULL,
    income_source         VARCHAR(30) NOT NULL,
    income_as_of          DATE NOT NULL,
    reserves_minor        BIGINT NOT NULL,
    commitments_minor     BIGINT NOT NULL,
    allocatable_minor     BIGINT NOT NULL,
    total_allocated_minor BIGINT NOT NULL,
    remaining_minor       BIGINT NOT NULL,
    valid                 BOOLEAN NOT NULL,
    warnings              VARCHAR(2000),
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    CONSTRAINT uq_ac_plan_key UNIQUE (plan_id, idempotency_key),
    CONSTRAINT ck_ac_source CHECK (income_source IN ('USER_ENTERED','BUSINESS_MANUAL_ENTRIES'))
);
CREATE INDEX idx_ac_plan ON allocation_calculations(plan_id, created_at);

CREATE TABLE allocation_calculation_lines (
    id               VARCHAR(36) NOT NULL PRIMARY KEY,
    calculation_id   VARCHAR(36) NOT NULL REFERENCES allocation_calculations(id),
    goal_id          VARCHAR(36),
    label            VARCHAR(120) NOT NULL,
    allocated_minor  BIGINT NOT NULL,
    position         INTEGER NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_acl_calc ON allocation_calculation_lines(calculation_id);

-- Saved hypothetical projections. Never an investment account; no money columns link to real records.
CREATE TABLE projection_scenarios (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_user_id         VARCHAR(36) NOT NULL REFERENCES users(id),
    label                 VARCHAR(120) NOT NULL,
    starting_minor        BIGINT NOT NULL,
    monthly_minor         BIGINT NOT NULL,
    years                 INTEGER NOT NULL,
    annual_return_pct     NUMERIC(7,2) NOT NULL,
    annual_inflation_pct  NUMERIC(7,2) NOT NULL,
    annual_fee_pct        NUMERIC(7,2) NOT NULL,
    contribution_growth_pct NUMERIC(7,2) NOT NULL,
    total_contributions_minor BIGINT NOT NULL,
    future_value_minor    BIGINT NOT NULL,
    future_value_real_minor BIGINT NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL
);
CREATE INDEX idx_ps_owner ON projection_scenarios(owner_user_id, created_at);
