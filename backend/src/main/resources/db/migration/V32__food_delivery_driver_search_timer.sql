-- PULA Food: dedicated start-of-driver-search timestamp for unclaimed food deliveries.
-- Replaces the use of updated_at (which moves on every write, including the no-driver notification)
-- for the no-driver notification and the customer's cancellation window.
ALTER TABLE delivery_requests ADD COLUMN driver_search_started_at TIMESTAMP;

-- Existing open food deliveries keep their current waiting time: the last write is the best
-- available approximation of when their search began (it is exactly what the old logic used).
UPDATE delivery_requests
   SET driver_search_started_at = COALESCE(updated_at, created_at)
 WHERE order_id IS NOT NULL
   AND status = 'REQUESTED';
