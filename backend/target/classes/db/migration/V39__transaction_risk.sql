-- V39: Transaction-risk layer (Phase 7). Adds an audit/decision store only. No payment table is created or altered.
CREATE TABLE transaction_intents (
    id                VARCHAR(36) PRIMARY KEY,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    user_id           VARCHAR(36) NOT NULL,
    business_id       VARCHAR(36),
    type              VARCHAR(24) NOT NULL,
    reference_key     VARCHAR(120) NOT NULL,
    amount            DECIMAL(19,2) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    recipient_key     VARCHAR(80),
    fingerprint       VARCHAR(64) NOT NULL,
    simulated         BOOLEAN NOT NULL,
    status            VARCHAR(16) NOT NULL,
    payment_reference VARCHAR(120),
    outcome_detail    VARCHAR(200),
    CONSTRAINT uq_intent_user_type_ref_fp UNIQUE (user_id, type, reference_key, fingerprint),
    CONSTRAINT ck_intent_amount CHECK (amount > 0)
);
CREATE INDEX idx_intent_user_type ON transaction_intents(user_id, type, status, updated_at);
CREATE INDEX idx_intent_payment_ref ON transaction_intents(payment_reference);

CREATE TABLE risk_assessments (
    id                  VARCHAR(36) PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    intent_id           VARCHAR(36) NOT NULL REFERENCES transaction_intents(id),
    user_id             VARCHAR(36) NOT NULL,
    business_id         VARCHAR(36),
    type                VARCHAR(24) NOT NULL,
    amount              DECIMAL(19,2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    score               INTEGER NOT NULL,
    level               VARCHAR(12) NOT NULL,
    decision            VARCHAR(12) NOT NULL,
    rule_ids            VARCHAR(600),
    unavailable_signals VARCHAR(300),
    explanation         VARCHAR(300),
    rule_set_version    VARCHAR(40) NOT NULL,
    mode                VARCHAR(8) NOT NULL,
    expires_at          TIMESTAMP NOT NULL,
    simulated           BOOLEAN NOT NULL,
    review_status       VARCHAR(12) NOT NULL,
    step_up_status      VARCHAR(14) NOT NULL,
    reviewed_by         VARCHAR(36),
    reviewed_at         TIMESTAMP,
    review_note         VARCHAR(300),
    step_up_confirmed_at TIMESTAMP,
    CONSTRAINT ck_ra_score CHECK (score >= 0 AND score <= 100)
);
CREATE INDEX idx_ra_intent ON risk_assessments(intent_id, created_at);
CREATE INDEX idx_ra_user_type ON risk_assessments(user_id, type, created_at);
CREATE INDEX idx_ra_review ON risk_assessments(review_status, decision, created_at);

CREATE TABLE risk_events (
    id             VARCHAR(36) PRIMARY KEY,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL,
    assessment_id  VARCHAR(36) NOT NULL REFERENCES risk_assessments(id),
    intent_id      VARCHAR(36) NOT NULL,
    type           VARCHAR(40) NOT NULL,
    actor_user_id  VARCHAR(36),
    detail         VARCHAR(300)
);
CREATE INDEX idx_re_assessment ON risk_events(assessment_id, created_at);
