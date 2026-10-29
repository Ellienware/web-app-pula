-- Allow a logo to be uploaded before its business profile is created.
ALTER TABLE uploaded_images
    ALTER COLUMN business_id DROP NOT NULL;

-- A regular index works in both H2 and PostgreSQL.
CREATE INDEX idx_uploaded_images_pending_owner
    ON uploaded_images(uploaded_by, business_id, created_at);
