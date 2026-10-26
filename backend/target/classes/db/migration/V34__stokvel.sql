-- V34: Community stokvels as an extension of Circles. Nothing here holds money: contributions stay in the existing
-- contributions/payments tables; requests/votes only TRACK group decisions; marketplace orders reference requests.
-- Ordinary circles are untouched (no new columns on circles / circle_memberships).

CREATE TABLE stokvel_profiles (
    id                     VARCHAR(36) PRIMARY KEY,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL,
    circle_id              VARCHAR(36) NOT NULL REFERENCES circles(id),
    stokvel_type           VARCHAR(32) NOT NULL,
    contribution_amount    DECIMAL(19,2),
    contribution_frequency VARCHAR(16) NOT NULL,
    start_date             DATE NOT NULL,
    target_date            DATE,
    goal_id                VARCHAR(36) REFERENCES circle_goals(id),
    approval_rule          VARCHAR(16) NOT NULL,
    approval_threshold     INTEGER,
    members_can_vote       BOOLEAN NOT NULL DEFAULT FALSE,
    request_validity_days  INTEGER NOT NULL DEFAULT 14,
    rotation_locked        BOOLEAN NOT NULL DEFAULT FALSE,
    rotation_locked_at     TIMESTAMP,
    CONSTRAINT uq_stokvel_profile_circle UNIQUE (circle_id),
    CONSTRAINT ck_stokvel_amount CHECK (contribution_amount IS NULL OR contribution_amount > 0),
    CONSTRAINT ck_stokvel_validity CHECK (request_validity_days BETWEEN 1 AND 90)
);

-- Payout order. No unique(circle_id, position): positions are rewritten in place on founder override.
CREATE TABLE stokvel_rotation_slots (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    circle_id   VARCHAR(36) NOT NULL REFERENCES circles(id),
    user_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    slot_position INTEGER NOT NULL,
    CONSTRAINT uq_slot_circle_user UNIQUE (circle_id, user_id),
    CONSTRAINT ck_slot_position CHECK (slot_position >= 1)
);

-- One row per completed payout. Append-only; the unique key makes recording a payout idempotent.
CREATE TABLE stokvel_cycles (
    id                 VARCHAR(36) PRIMARY KEY,
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL,
    circle_id          VARCHAR(36) NOT NULL REFERENCES circles(id),
    cycle_number       INTEGER NOT NULL,
    payout_position    INTEGER NOT NULL,
    beneficiary_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    recorded_by_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    expected_amount    DECIMAL(19,2) NOT NULL,
    collected_amount   DECIMAL(19,2) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    reference          VARCHAR(100),
    paid_at            TIMESTAMP NOT NULL,
    CONSTRAINT uq_cycle_circle_number UNIQUE (circle_id, cycle_number)
);

-- Links an existing contribution to a stokvel period. The contribution/payment rows are not modified.
CREATE TABLE stokvel_contribution_links (
    id               VARCHAR(36) PRIMARY KEY,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    circle_id        VARCHAR(36) NOT NULL REFERENCES circles(id),
    user_id          VARCHAR(36) NOT NULL REFERENCES users(id),
    contribution_id  VARCHAR(36) NOT NULL REFERENCES contributions(id),
    period_index     INTEGER NOT NULL,
    CONSTRAINT uq_link_contribution UNIQUE (contribution_id)
);
CREATE INDEX idx_link_circle_user_period ON stokvel_contribution_links(circle_id, user_id, period_index);

CREATE TABLE stokvel_requests (
    id                  VARCHAR(36) PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    circle_id           VARCHAR(36) NOT NULL REFERENCES circles(id),
    goal_id             VARCHAR(36) REFERENCES circle_goals(id),
    request_type        VARCHAR(32) NOT NULL,
    requester_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    buyer_id            VARCHAR(36) REFERENCES users(id),
    amount              DECIMAL(19,2) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    purpose             VARCHAR(500) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    approval_rule       VARCHAR(16) NOT NULL,
    required_approvals  INTEGER NOT NULL,
    eligible_voters     INTEGER NOT NULL,
    idempotency_key     VARCHAR(100),
    expires_at          TIMESTAMP NOT NULL,
    decided_at          TIMESTAMP,
    executed_at         TIMESTAMP,
    related_order_kind  VARCHAR(16),
    related_order_id    VARCHAR(36),
    external_reference  VARCHAR(200),
    CONSTRAINT ck_sreq_amount CHECK (amount > 0),
    CONSTRAINT uq_sreq_idem UNIQUE (circle_id, requester_id, idempotency_key)
);
CREATE INDEX idx_sreq_circle_status ON stokvel_requests(circle_id, status);
CREATE UNIQUE INDEX uq_sreq_related_order ON stokvel_requests(related_order_kind, related_order_id);

-- Votes are append-only: one per voter per request, never edited.
CREATE TABLE stokvel_request_votes (
    id          VARCHAR(36) PRIMARY KEY,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    request_id  VARCHAR(36) NOT NULL REFERENCES stokvel_requests(id),
    voter_id    VARCHAR(36) NOT NULL REFERENCES users(id),
    decision    VARCHAR(16) NOT NULL,
    comment     VARCHAR(300),
    CONSTRAINT uq_vote_request_voter UNIQUE (request_id, voter_id)
);

-- Makes scheduled notices/activity idempotent (one DUE / MISSED / UPCOMING notice per member per period).
CREATE TABLE stokvel_notice_log (
    id            VARCHAR(36) PRIMARY KEY,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    circle_id     VARCHAR(36) NOT NULL REFERENCES circles(id),
    user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    period_index  INTEGER NOT NULL,
    kind          VARCHAR(16) NOT NULL,
    CONSTRAINT uq_notice UNIQUE (circle_id, user_id, period_index, kind)
);

-- Marketplace orders can reference the approved stokvel request that authorised the purchase (tracking, not custody).
ALTER TABLE orders ADD COLUMN stokvel_request_id VARCHAR(36) REFERENCES stokvel_requests(id);
CREATE UNIQUE INDEX uq_orders_stokvel_request ON orders(stokvel_request_id);
ALTER TABLE stock_orders ADD COLUMN stokvel_request_id VARCHAR(36) REFERENCES stokvel_requests(id);
CREATE UNIQUE INDEX uq_stock_orders_stokvel_request ON stock_orders(stokvel_request_id);
