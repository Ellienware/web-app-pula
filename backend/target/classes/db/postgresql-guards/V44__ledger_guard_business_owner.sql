-- OPT-IN PostgreSQL guard, companion to V41: ledger_account_guard_update() must also freeze owner_business_id (added by V43),
-- so that only balance_minor of a ledger account can ever change. Apply only where V41 is applied. PostgreSQL only.
CREATE OR REPLACE FUNCTION ledger_account_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.kind <> OLD.kind
       OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
       OR NEW.owner_business_id IS DISTINCT FROM OLD.owner_business_id
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
