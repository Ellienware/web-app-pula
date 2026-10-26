-- V48: general multi-branch management (Phase 9C). Extends the existing business_branches table (created for PULA Food in V25)
-- instead of adding a parallel table. Forward-only; existing rows are preserved and keep working:
--   * every new column is nullable or has a default,
--   * status is derived from the legacy `active` flag so food discovery/checkout (which read `active`) behave exactly as before.
ALTER TABLE business_branches ADD COLUMN branch_code VARCHAR(20);
ALTER TABLE business_branches ADD COLUMN city VARCHAR(100);
ALTER TABLE business_branches ADD COLUMN province VARCHAR(100);
ALTER TABLE business_branches ADD COLUMN postal_code VARCHAR(10);
ALTER TABLE business_branches ADD COLUMN phone VARCHAR(20);
ALTER TABLE business_branches ADD COLUMN email VARCHAR(255);
ALTER TABLE business_branches ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE business_branches ADD COLUMN verification_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED';

UPDATE business_branches SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'SUSPENDED' END;

ALTER TABLE business_branches ADD CONSTRAINT ck_branch_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));
ALTER TABLE business_branches ADD CONSTRAINT ck_branch_verification CHECK (verification_status IN ('UNVERIFIED'));
ALTER TABLE business_branches ADD CONSTRAINT ck_branch_latlon CHECK (
    (latitude IS NULL AND longitude IS NULL)
    OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

-- A branch code is unique WITHIN its business (two unrelated businesses may share a code). Rows without a code never collide.
CREATE UNIQUE INDEX uq_branch_business_code ON business_branches (business_id, branch_code);

-- Who works at which branch. The business OWNER is not stored here (ownership stays on businesses.owner_id).
CREATE TABLE branch_staff (
    id           VARCHAR(36) PRIMARY KEY,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL,
    branch_id    VARCHAR(36) NOT NULL REFERENCES business_branches(id),
    user_id      VARCHAR(36) NOT NULL REFERENCES users(id),
    role         VARCHAR(16) NOT NULL,
    assigned_by  VARCHAR(36) NOT NULL REFERENCES users(id),
    CONSTRAINT ck_branch_staff_role CHECK (role IN ('MANAGER', 'STAFF')),
    CONSTRAINT uq_branch_staff UNIQUE (branch_id, user_id)
);
CREATE INDEX idx_branch_staff_user ON branch_staff (user_id);
