-- Driver USSD PIN (hashed, never plaintext), Taxi QR token, Ride optimistic
-- locking, payment-request expiry, ride ratings, and MoMoRide reports.

ALTER TABLE momoride_drivers ADD COLUMN pin_hash VARCHAR(100);

ALTER TABLE momoride_taxis ADD COLUMN qr_token VARCHAR(64);
CREATE UNIQUE INDEX uq_momoride_taxi_qr_token ON momoride_taxis(qr_token);

-- Optimistic-locking version column backing @Version on Ride, replacing the
-- JVM-only synchronized-block "concurrency control" that could not have
-- protected seat counts across more than one application instance.
ALTER TABLE momoride_rides ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE momoride_payment_requests ADD COLUMN expires_at TIMESTAMP;

CREATE TABLE momoride_ride_ratings (
  id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  ride_id VARCHAR(36) NOT NULL REFERENCES momoride_rides(id),
  rater_id VARCHAR(36) NOT NULL REFERENCES users(id),
  driver_id VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id),
  rating INT NOT NULL,
  comment VARCHAR(500),
  CONSTRAINT uq_momoride_rating_ride_rater UNIQUE(ride_id, rater_id)
);
CREATE INDEX idx_momoride_ratings_driver ON momoride_ride_ratings(driver_id);

CREATE TABLE momoride_reports (
  id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  reporter_id VARCHAR(36) NOT NULL REFERENCES users(id),
  target_type VARCHAR(24) NOT NULL,
  ride_id VARCHAR(36) REFERENCES momoride_rides(id),
  taxi_id VARCHAR(36) REFERENCES momoride_taxis(id),
  payment_id VARCHAR(36) REFERENCES payments(id),
  reported_user_id VARCHAR(36) REFERENCES users(id),
  reason VARCHAR(1000) NOT NULL,
  status VARCHAR(24) NOT NULL
);
CREATE INDEX idx_momoride_reports_status ON momoride_reports(status);
