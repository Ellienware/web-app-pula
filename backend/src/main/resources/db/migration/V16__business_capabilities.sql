-- PULA Business capabilities (brief §6): what a business is allowed to
-- transact — CAN_SELL_TO_CONSUMERS, CAN_BUY_STOCK, CAN_SUPPLY_BUSINESSES,
-- CAN_MANAGE_DELIVERY_FLEET — independent of BusinessType (what kind of
-- business it is) and independent of FulfilmentOption (how it delivers).
-- A business may hold more than one capability at once.
--
-- @ElementCollection(EAGER) Set<BusinessCapability> on Business, following
-- the same join-table pattern already used for business_fulfilment_options.
CREATE TABLE business_capabilities (
    business_id  VARCHAR(36) NOT NULL REFERENCES businesses(id),
    capability   VARCHAR(32) NOT NULL,
    PRIMARY KEY (business_id, capability)
);
CREATE INDEX idx_business_capabilities ON business_capabilities(business_id);

-- Every business that existed before this migration was, by definition,
-- a consumer-facing seller under the pre-capability model (there was no
-- other kind yet) — backfill CAN_SELL_TO_CONSUMERS for all of them so
-- existing storefronts keep appearing in PULA Market — Shop unchanged.
-- New businesses get this same default in application code (see
-- Business.capabilities field initializer), not here.
INSERT INTO business_capabilities (business_id, capability)
SELECT id, 'CAN_SELL_TO_CONSUMERS' FROM businesses;
