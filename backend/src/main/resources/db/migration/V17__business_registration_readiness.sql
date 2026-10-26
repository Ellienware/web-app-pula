-- PULA Business Setup / registration readiness (brief §7-8).
--
-- registration_status / registration_number are self-declared by the owner
-- and never verified against CIPC or any government registry — PULA
-- provides registration *readiness*, not real government registration.
ALTER TABLE businesses ADD COLUMN registration_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REGISTERED';
ALTER TABLE businesses ADD COLUMN registration_number VARCHAR(100);

-- Supporting documents (ID, proof of address, CIPC certificate, etc.).
-- Metadata only — file_url points to wherever the owner already hosted the
-- file; there is no in-app file/object storage to upload into in this slice.
CREATE TABLE business_documents (
    id            VARCHAR(36) NOT NULL PRIMARY KEY,
    business_id   VARCHAR(36) NOT NULL REFERENCES businesses(id),
    document_type VARCHAR(32) NOT NULL,
    file_url      VARCHAR(2000) NOT NULL,
    note          VARCHAR(500),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_business_documents_business ON business_documents(business_id);
