-- ============================================================
-- V4 — concurrency-safe proposal execution (Phase D.1.1).
--
-- Two simultaneous POST /assistant/execute requests for the same proposal
-- could previously both observe PENDING and both run the action (e.g. both
-- create a contribution). Adds the @Version column AssistantProposal now
-- uses for optimistic locking on the PENDING -> EXECUTING transition, plus
-- the two new terminal/in-flight statuses (EXECUTING, FAILED) that
-- transition supports. No length change needed on the existing `status`
-- VARCHAR(16) column — "EXECUTING" (9 chars) and "FAILED" (6 chars) both
-- fit.
-- ============================================================

ALTER TABLE assistant_proposals
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
