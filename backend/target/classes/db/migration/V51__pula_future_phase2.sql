-- V51: PULA Future phase 2 (planning + reminders only; still no money movement).
-- Goal pausing needs NO schema change: circle_goals.status is VARCHAR(32) with no check constraint, so GoalStatus.PAUSED is just a new value.

-- A calculation may now use settled merchant income as its basis, and stores the explanation of that basis.
ALTER TABLE allocation_calculations DROP CONSTRAINT ck_ac_source;
ALTER TABLE allocation_calculations ADD CONSTRAINT ck_ac_source
    CHECK (income_source IN ('USER_ENTERED','BUSINESS_MANUAL_ENTRIES','MERCHANT_SIMULATED_SETTLEMENTS'));
ALTER TABLE allocation_calculations ADD COLUMN income_notes VARCHAR(2000);

-- Business reserve / commitment line items (planning inputs, not proof that money is set aside).
CREATE TABLE business_plan_items (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    plan_id       VARCHAR(36) NOT NULL REFERENCES allocation_plans(id),
    kind          VARCHAR(30) NOT NULL,
    label         VARCHAR(120) NOT NULL,
    amount_minor  BIGINT NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    CONSTRAINT ck_bpi_kind CHECK (kind IN ('OPERATING_EXPENSES','SUPPLIER_COMMITMENTS','RENT_UTILITIES','PAYROLL','TAXES','EMERGENCY_RESERVE','PLANNED_INVENTORY')),
    CONSTRAINT ck_bpi_amount CHECK (amount_minor >= 0)
);
CREATE INDEX idx_bpi_plan ON business_plan_items(plan_id);

-- Opt-in reminders. No row means OFF. Reminders only ever create in-app notifications.
CREATE TABLE reminder_preferences (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    kind        VARCHAR(30) NOT NULL,
    enabled     BOOLEAN NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_rp_user_kind UNIQUE (user_id, kind),
    CONSTRAINT ck_rp_kind CHECK (kind IN ('GOAL_DEADLINE','PLAN_REVIEW','SAVE_TO_BUY_PROGRESS'))
);

-- One row per reminder actually sent; the unique key prevents duplicates.
CREATE TABLE reminder_log (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    reminder_key  VARCHAR(160) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    CONSTRAINT uq_rl_user_key UNIQUE (user_id, reminder_key)
);
