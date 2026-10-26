-- Stores only redacted text — see ScamAssessment's javadoc and
-- ScamShieldService#redact, which strips anything that looks like an OTP,
-- PIN, password, or CVV before this table is ever written to.
CREATE TABLE scam_assessments (
    id             VARCHAR(36) PRIMARY KEY,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL,
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    submitted_text VARCHAR(5000) NOT NULL,
    risk_level     VARCHAR(16) NOT NULL
);
CREATE INDEX idx_scam_assessments_user ON scam_assessments(user_id);

CREATE TABLE scam_assessment_indicators (
    assessment_id  VARCHAR(36) NOT NULL REFERENCES scam_assessments(id),
    indicator      VARCHAR(500) NOT NULL
);
CREATE INDEX idx_scam_assessment_indicators_assessment ON scam_assessment_indicators(assessment_id);
