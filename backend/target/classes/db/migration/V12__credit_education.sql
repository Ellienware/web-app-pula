CREATE TABLE credit_education_entries (
    id                            VARCHAR(36) PRIMARY KEY,
    created_at                    TIMESTAMP NOT NULL,
    updated_at                    TIMESTAMP NOT NULL,
    user_id                       VARCHAR(36) NOT NULL REFERENCES users(id),
    principal                     DECIMAL(19,2) NOT NULL,
    currency                      VARCHAR(3) NOT NULL,
    annual_interest_rate_percent  DECIMAL(6,2) NOT NULL,
    term_months                   INTEGER NOT NULL,
    fees                          DECIMAL(19,2) NOT NULL,
    monthly_payment               DECIMAL(19,2) NOT NULL,
    total_repayment               DECIMAL(19,2) NOT NULL,
    total_interest                DECIMAL(19,2) NOT NULL,
    total_cost_percent            DECIMAL(6,2) NOT NULL
);
CREATE INDEX idx_credit_education_entries_user ON credit_education_entries(user_id);

CREATE TABLE credit_education_warnings (
    entry_id  VARCHAR(36) NOT NULL REFERENCES credit_education_entries(id),
    warning   VARCHAR(500) NOT NULL
);
CREATE INDEX idx_credit_education_warnings_entry ON credit_education_warnings(entry_id);
