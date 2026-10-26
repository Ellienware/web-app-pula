-- ============================================================
-- V3 — server-side binding for AI proposed actions (Phase D.1).
--
-- Before this, POST /assistant/execute trusted the client to resend the
-- same intent/parameters/circleId it was shown in the proposal — nothing
-- stopped a tampered request from swapping in a different amount or
-- circle between proposal and confirmation. Every proposal the assistant
-- makes is now persisted here; the client only ever gets back this row's
-- id, and execution re-loads and re-validates the stored row rather than
-- trusting anything else the client resends.
-- ============================================================

CREATE TABLE assistant_proposals (
    id               VARCHAR(36) PRIMARY KEY,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    user_id          VARCHAR(36) NOT NULL REFERENCES users(id),
    circle_id        VARCHAR(36),
    intent           VARCHAR(32) NOT NULL,
    parameters_json  TEXT NOT NULL,
    status           VARCHAR(16) NOT NULL,
    expires_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_assistant_proposal_user ON assistant_proposals(user_id);
CREATE INDEX idx_assistant_proposal_status_expiry ON assistant_proposals(status, expires_at);
