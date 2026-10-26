-- V52: seller-uploaded images (additive only; no existing table is altered).
-- Images are stored in the database so they survive restarts and redeploys on ephemeral hosts
-- (no local disk, no external storage provider). Products / menu items / service listings keep
-- referencing images by URL (/api/images/{id}) in their existing image-url columns.
CREATE TABLE uploaded_images (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    business_id   VARCHAR(36)  NOT NULL REFERENCES businesses(id),
    uploaded_by   VARCHAR(36)  NOT NULL REFERENCES users(id),
    content_type  VARCHAR(30)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,
    data          BYTEA        NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT ck_uploaded_images_type CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT ck_uploaded_images_size CHECK (size_bytes > 0 AND size_bytes <= 2097152)
);
CREATE INDEX idx_uploaded_images_business ON uploaded_images(business_id);
