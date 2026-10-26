-- Driver consent: a wholesaler INVITES a driver; the driver must accept before receiving pool jobs.
-- Existing members stay ACTIVE. active mirrors (status = 'ACTIVE') so eligibility queries stay simple.
ALTER TABLE delivery_pool_members ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
