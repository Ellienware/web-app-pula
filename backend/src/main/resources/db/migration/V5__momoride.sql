CREATE TABLE momoride_drivers (
 id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 user_id VARCHAR(36) NOT NULL UNIQUE REFERENCES users(id), status VARCHAR(24) NOT NULL
);
CREATE TABLE momoride_taxis (
 id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 driver_id VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id), registration_number VARCHAR(32) NOT NULL UNIQUE,
 public_taxi_id VARCHAR(32) NOT NULL UNIQUE, payment_code VARCHAR(12) NOT NULL UNIQUE,
 make_model VARCHAR(64), status VARCHAR(24) NOT NULL
);
CREATE INDEX idx_momoride_taxi_driver ON momoride_taxis(driver_id);
CREATE TABLE momoride_payment_requests (
 id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 taxi_id VARCHAR(36) NOT NULL REFERENCES momoride_taxis(id), driver_id VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id),
 passenger_id VARCHAR(36) REFERENCES users(id), amount DECIMAL(19,2) NOT NULL, currency VARCHAR(3) NOT NULL,
 status VARCHAR(24) NOT NULL, payment_id VARCHAR(36) UNIQUE REFERENCES payments(id)
);
CREATE INDEX idx_momoride_payment_requests_driver ON momoride_payment_requests(driver_id);
CREATE TABLE momoride_rides (
 id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 driver_id VARCHAR(36) NOT NULL REFERENCES momoride_drivers(id), origin VARCHAR(160) NOT NULL, destination VARCHAR(160) NOT NULL,
 departure_time TIMESTAMP NOT NULL, available_seats INT NOT NULL, price_per_passenger DECIMAL(19,2) NOT NULL,
 currency VARCHAR(3) NOT NULL, status VARCHAR(24) NOT NULL
);
CREATE INDEX idx_momoride_rides_search ON momoride_rides(status, departure_time);
CREATE TABLE momoride_ride_bookings (
 id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
 ride_id VARCHAR(36) NOT NULL REFERENCES momoride_rides(id), passenger_id VARCHAR(36) NOT NULL REFERENCES users(id),
 status VARCHAR(24) NOT NULL, payment_id VARCHAR(36) UNIQUE REFERENCES payments(id),
 CONSTRAINT uq_momoride_booking_ride_user UNIQUE(ride_id, passenger_id)
);
CREATE INDEX idx_momoride_bookings_passenger ON momoride_ride_bookings(passenger_id);
