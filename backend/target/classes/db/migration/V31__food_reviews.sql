-- PULA Food (Phase 3, step 5): reviews for food orders.
-- * optional 1-5 sub-ratings (taste, portion, value, quality, packaging, delivery experience);
-- * every review is tied to a completed PULA order, marked verified and sourced 'PULA' by the server;
--   external reviews are never stored in this table or counted in PULA's scores;
-- * duplicate rule moves to a single portable unique key so food can allow one review per
--   (author, order, target) while retail keeps one review per (author, target), both enforced by the DB.
ALTER TABLE reviews ADD COLUMN taste INTEGER;
ALTER TABLE reviews ADD COLUMN portion_size INTEGER;
ALTER TABLE reviews ADD COLUMN value_for_money INTEGER;
ALTER TABLE reviews ADD COLUMN food_quality INTEGER;
ALTER TABLE reviews ADD COLUMN packaging INTEGER;
ALTER TABLE reviews ADD COLUMN delivery_experience INTEGER;
ALTER TABLE reviews ADD COLUMN verified_order BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE reviews ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'PULA';
ALTER TABLE reviews ADD COLUMN dedupe_key VARCHAR(160);

UPDATE reviews SET dedupe_key = author_id || '|' || target_type || '|' || target_id;
ALTER TABLE reviews ALTER COLUMN dedupe_key SET NOT NULL;

ALTER TABLE reviews DROP CONSTRAINT uq_review_author_target;
ALTER TABLE reviews ADD CONSTRAINT uq_review_dedupe UNIQUE (dedupe_key);

ALTER TABLE reviews ADD CONSTRAINT ck_review_subratings CHECK (
    (taste IS NULL OR taste BETWEEN 1 AND 5) AND
    (portion_size IS NULL OR portion_size BETWEEN 1 AND 5) AND
    (value_for_money IS NULL OR value_for_money BETWEEN 1 AND 5) AND
    (food_quality IS NULL OR food_quality BETWEEN 1 AND 5) AND
    (packaging IS NULL OR packaging BETWEEN 1 AND 5) AND
    (delivery_experience IS NULL OR delivery_experience BETWEEN 1 AND 5));
