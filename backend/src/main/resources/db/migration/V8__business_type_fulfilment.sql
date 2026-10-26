-- Seller typing (Business.businessType) and fulfilment options
-- (Business.fulfilmentOptions), per the ecosystem-evolution brief's
-- distinction between formal retailers, local businesses, informal
-- traders, and service providers, and its four fulfilment methods.
--
-- Every existing business becomes LOCAL_BUSINESS by default (a neutral,
-- non-stigmatizing default per the brief's own instruction) — sellers can
-- update it themselves via PATCH /api/market/businesses/me.
ALTER TABLE businesses ADD COLUMN business_type VARCHAR(32) NOT NULL DEFAULT 'LOCAL_BUSINESS';

-- @ElementCollection(EAGER) Set<FulfilmentOption> on Business, following the
-- same join-table pattern already used for business_operating_hours.
CREATE TABLE business_fulfilment_options (
    business_id        VARCHAR(36) NOT NULL REFERENCES businesses(id),
    fulfilment_option  VARCHAR(32) NOT NULL,
    PRIMARY KEY (business_id, fulfilment_option)
);
CREATE INDEX idx_business_fulfilment_options ON business_fulfilment_options(business_id);
