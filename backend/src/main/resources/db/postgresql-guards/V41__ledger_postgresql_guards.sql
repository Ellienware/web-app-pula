-- V41 (PostgreSQL 11+ ONLY, OPT-IN, written in Phase 9A, NOT YET EXECUTED against a real PostgreSQL server).
--
-- Why this is outside db/migration: it uses PL/pgSQL and constraint triggers, which H2 cannot run, and it has not yet
-- been verified on PostgreSQL. It is applied only when a PostgreSQL environment adds this location, e.g.
--     spring.flyway.locations=classpath:db/migration,classpath:db/postgresql-guards
-- Verify on a DISPOSABLE database first (see LedgerPostgresGuardsTest and docs/PHASE_9A_REPORT.md).
--
-- Adds database-level enforcement of:
--   1. every ledger transaction has >= 2 postings, a single currency, and debits = credits   (checked at COMMIT)
--   2. posted entries are immutable: no UPDATE/DELETE/TRUNCATE on ledger_postings; ledger_transactions may only change
--      status (along the allowed transitions)/failure_reason/updated_at; accounts keep their identity columns
--   3. the balance projection of every non-negative (wallet) account equals the sum of its postings   (checked at COMMIT)
--
-- Rollback (Flyway Community has no undo; run manually if ever required):
--   DROP TRIGGER IF EXISTS ... for each trigger below, then DROP FUNCTION ledger_assert_tx_balanced(),
--   ledger_assert_projection(), ledger_reject_change(), ledger_tx_guard_update(), ledger_account_guard_update().

CREATE OR REPLACE FUNCTION ledger_assert_tx_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    tx_id      VARCHAR(36);
    n          INTEGER;
    ccys       INTEGER;
    imbalance  BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_transactions' THEN
        tx_id := NEW.id;
    ELSE
        tx_id := NEW.transaction_id;
    END IF;

    SELECT COUNT(*),
           COUNT(DISTINCT currency),
           COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO n, ccys, imbalance
      FROM ledger_postings
     WHERE transaction_id = tx_id;

    IF n < 2 THEN
        RAISE EXCEPTION 'ledger transaction % has % posting(s); at least 2 are required', tx_id, n
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_min_two_postings';
    END IF;
    IF ccys <> 1 THEN
        RAISE EXCEPTION 'ledger transaction % mixes % currencies', tx_id, ccys
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_single_currency';
    END IF;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced by % minor units (credits - debits)', tx_id, imbalance
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_balanced';
    END IF;
    RETURN NULL;
END;
$$;

-- Deferred: evaluated at COMMIT, after the application has inserted the transaction row AND all of its postings.
CREATE CONSTRAINT TRIGGER ctrg_ledger_tx_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_tx_balanced();

CREATE CONSTRAINT TRIGGER ctrg_ledger_postings_balanced
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_tx_balanced();

-- ---------------------------------------------------------------------------------------------- immutability

CREATE OR REPLACE FUNCTION ledger_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: ledger entries are immutable (use a reversal)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '23000', CONSTRAINT = 'ledger_immutable';
END;
$$;

CREATE TRIGGER trg_ledger_postings_immutable
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER trg_ledger_postings_no_truncate
    BEFORE TRUNCATE ON ledger_postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER trg_ledger_tx_no_delete
    BEFORE DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER trg_ledger_tx_no_truncate
    BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER trg_ledger_accounts_no_delete
    BEFORE DELETE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
CREATE TRIGGER trg_ledger_accounts_no_truncate
    BEFORE TRUNCATE ON ledger_accounts
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

CREATE OR REPLACE FUNCTION ledger_tx_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.type <> OLD.type
       OR NEW.initiator_user_id <> OLD.initiator_user_id
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.fingerprint <> OLD.fingerprint
       OR NEW.currency <> OLD.currency
       OR NEW.provider_reference <> OLD.provider_reference
       OR NEW.simulated <> OLD.simulated
       OR NEW.created_at <> OLD.created_at
       OR NEW.reverses_tx_id IS DISTINCT FROM OLD.reverses_tx_id THEN
        RAISE EXCEPTION 'only status, failure_reason and updated_at of ledger transaction % may change', OLD.id
            USING ERRCODE = '23000', CONSTRAINT = 'ledger_immutable';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PENDING' AND NEW.status IN ('POSTED', 'FAILED', 'CANCELLED'))
        OR (OLD.status = 'POSTED'  AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'illegal ledger status transition % -> % for %', OLD.status, NEW.status, OLD.id
            USING ERRCODE = '23000', CONSTRAINT = 'ledger_status_transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_tx_guard_update
    BEFORE UPDATE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_tx_guard_update();

CREATE OR REPLACE FUNCTION ledger_account_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.kind <> OLD.kind
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.system_key IS DISTINCT FROM OLD.system_key
       OR NEW.currency <> OLD.currency
       OR NEW.allow_negative <> OLD.allow_negative
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'only balance_minor of ledger account % may change', OLD.id
            USING ERRCODE = '23000', CONSTRAINT = 'ledger_immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_account_guard_update
    BEFORE UPDATE ON ledger_accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_account_guard_update();

-- ---------------------------------------------------------------------------------- projection integrity

-- For NON-NEGATIVE accounts (user wallets) the stored balance must equal credits - debits at commit, so the projection can
-- never drift, even if something bypasses the application. System accounts (allow_negative = TRUE) are skipped because
-- every issuance touches the funding account and summing its entire history per commit would grow without bound; they are
-- covered by LedgerReconciliationService instead. Cost for wallets: one indexed sum over that wallet's postings per commit.
CREATE OR REPLACE FUNCTION ledger_assert_projection() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    acct        VARCHAR(36);
    neg_ok      BOOLEAN;
    projection  BIGINT;
    ledger_sum  BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger_accounts' THEN
        acct := NEW.id;
    ELSE
        acct := NEW.account_id;
    END IF;

    SELECT allow_negative, balance_minor INTO neg_ok, projection FROM ledger_accounts WHERE id = acct;
    IF neg_ok THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0)
      INTO ledger_sum
      FROM ledger_postings
     WHERE account_id = acct;

    IF projection <> ledger_sum THEN
        RAISE EXCEPTION 'ledger account % balance projection % differs from postings %', acct, projection, ledger_sum
            USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_projection';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ctrg_ledger_accounts_projection
    AFTER INSERT OR UPDATE OF balance_minor ON ledger_accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_projection();

CREATE CONSTRAINT TRIGGER ctrg_ledger_postings_projection
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_projection();
