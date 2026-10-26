-- V45: Stokvel discovery, invite links and membership requests. Forward-only; adds columns/tables, changes no existing row.
--
-- Reuses the existing model: a member is still a row in circle_memberships and a stokvel is still a circles row + stokvel_profiles row.
-- Nothing here holds money. (Version 44 is intentionally absent from db/migration: db/postgresql-guards/V44 is an opt-in file.)
--
-- Privacy default: every existing and new stokvel is PRIVATE (discoverable = FALSE) until an owner/admin opts in.

ALTER TABLE stokvel_profiles ADD COLUMN discoverable             BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE stokvel_profiles ADD COLUMN area                     VARCHAR(80);
ALTER TABLE stokvel_profiles ADD COLUMN public_summary           VARCHAR(300);
ALTER TABLE stokvel_profiles ADD COLUMN membership_requirements  VARCHAR(500);
ALTER TABLE stokvel_profiles ADD COLUMN max_members              INTEGER;
ALTER TABLE stokvel_profiles ADD COLUMN invite_requires_approval BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE stokvel_profiles ADD CONSTRAINT ck_stokvel_max_members CHECK (max_members IS NULL OR max_members BETWEEN 2 AND 500);
CREATE INDEX idx_stokvel_discoverable ON stokvel_profiles (discoverable);

-- A request to join. pending_key is "<circle_id>:<user_id>" while PENDING and NULL otherwise, so UNIQUE(pending_key) allows at most
-- one open request per person per stokvel on every database (partial indexes are not portable), whatever the application does.
CREATE TABLE stokvel_join_requests (
    id              VARCHAR(36)  PRIMARY KEY,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    circle_id       VARCHAR(36)  NOT NULL REFERENCES circles(id),
    user_id         VARCHAR(36)  NOT NULL REFERENCES users(id),
    status          VARCHAR(16)  NOT NULL,
    source          VARCHAR(16)  NOT NULL,
    message         VARCHAR(300),
    pending_key     VARCHAR(80),
    invite_link_id  VARCHAR(36),
    decided_by_id   VARCHAR(36)  REFERENCES users(id),
    decided_at      TIMESTAMP,
    decision_note   VARCHAR(300),
    CONSTRAINT ck_sjr_status  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_sjr_source  CHECK (source IN ('DISCOVERY', 'INVITE_LINK')),
    CONSTRAINT ck_sjr_pending CHECK ((status = 'PENDING' AND pending_key IS NOT NULL) OR (status <> 'PENDING' AND pending_key IS NULL)),
    CONSTRAINT ck_sjr_decided CHECK (status = 'PENDING' OR decided_at IS NOT NULL),
    CONSTRAINT uq_sjr_pending UNIQUE (pending_key)
);
CREATE INDEX idx_sjr_circle ON stokvel_join_requests (circle_id, status, created_at);
CREATE INDEX idx_sjr_user   ON stokvel_join_requests (user_id, created_at);

-- Shareable invite code/link. The raw code is shown ONCE at creation; only its SHA-256 is stored.
CREATE TABLE stokvel_invite_links (
    id                 VARCHAR(36)  PRIMARY KEY,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    circle_id          VARCHAR(36)  NOT NULL REFERENCES circles(id),
    created_by_id      VARCHAR(36)  NOT NULL REFERENCES users(id),
    code_hash          VARCHAR(64)  NOT NULL,
    code_hint          VARCHAR(8)   NOT NULL,
    requires_approval  BOOLEAN      NOT NULL,
    max_uses           INTEGER      NOT NULL,
    uses_count         INTEGER      NOT NULL DEFAULT 0,
    expires_at         TIMESTAMP    NOT NULL,
    revoked_at         TIMESTAMP,
    revoked_by_id      VARCHAR(36)  REFERENCES users(id),
    CONSTRAINT uq_sil_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_sil_max_uses  CHECK (max_uses BETWEEN 1 AND 100),
    CONSTRAINT ck_sil_uses      CHECK (uses_count >= 0 AND uses_count <= max_uses)
);
CREATE INDEX idx_sil_circle ON stokvel_invite_links (circle_id, created_at);

-- One row per person per link: the same person can never consume a link twice, and a retry is recognised.
CREATE TABLE stokvel_invite_link_uses (
    id          VARCHAR(36)  PRIMARY KEY,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    link_id     VARCHAR(36)  NOT NULL REFERENCES stokvel_invite_links(id),
    user_id     VARCHAR(36)  NOT NULL REFERENCES users(id),
    outcome     VARCHAR(16)  NOT NULL,
    CONSTRAINT uq_silu_link_user UNIQUE (link_id, user_id),
    CONSTRAINT ck_silu_outcome   CHECK (outcome IN ('JOINED', 'REQUESTED'))
);
