-- V47: link a SIMULATED wallet refund to the compensating ledger transaction it posted (partial / cumulative refunds).
-- Forward-only, nullable, NOT backfilled: historical refunds are untouched. Provider (Paystack/legacy) refunds keep it NULL.
-- The unique index guarantees one ledger entry can never back two refunds (NULLs are allowed repeatedly).
ALTER TABLE payment_refunds ADD COLUMN ledger_transaction_id VARCHAR(36);
ALTER TABLE payment_refunds ADD CONSTRAINT fk_payment_refunds_ledger_tx FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions(id);
CREATE UNIQUE INDEX uq_payment_refunds_ledger_tx ON payment_refunds (ledger_transaction_id);
