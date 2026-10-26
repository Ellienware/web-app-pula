CREATE TABLE affordability_assessments (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    amount      DECIMAL(19,2) NOT NULL,
    currency    VARCHAR(3) NOT NULL,
    context     VARCHAR(255),
    category    VARCHAR(255),
    result      VARCHAR(32) NOT NULL,
    reasoning   VARCHAR(1000) NOT NULL
);
CREATE INDEX idx_affordability_assessments_user ON affordability_assessments(user_id);
