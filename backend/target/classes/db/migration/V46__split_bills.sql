-- V46: Split bills and shared expenses (Phase 9C). Forward-only; EXTENDS the existing expenses / expense_shares tables.
--
-- No new group, expense, transfer or balance system: a split bill is still an `expenses` row in a Circle, a participant's
-- portion is still an `expense_shares` row, and money still moves ONLY through the Phase 9A/9B shared ledger. This migration
-- adds consent/lifecycle state, a link from each share to the ledger transaction that settled it, item-based split data and an
-- immutable event history. Wallet balances are NOT stored here.
--
-- Every amount is SIMULATED demo-wallet value. `expenses.amount` / `expense_shares.amount` remain the RECORDED amounts
-- (what the bill cost / what each person owes). Funds actually moved are only those shares with settlement_method = 'LEDGER'
-- and a ledger_transaction_id; the two are never the same thing.
--
-- Existing rows: every already-settled share becomes PAID with settlement_method SELF (the payer's own share) or LEGACY (marked
-- settled by hand before this phase; NO ledger transaction exists and none is invented). Every unsettled share becomes PENDING:
-- nobody had consented to it, so nobody is treated as having accepted.

ALTER TABLE expenses ADD COLUMN note                VARCHAR(1000);
ALTER TABLE expenses ADD COLUMN created_by_id       VARCHAR(36);
ALTER TABLE expenses ADD COLUMN expense_date        DATE;
ALTER TABLE expenses ADD COLUMN due_date            DATE;
ALTER TABLE expenses ADD COLUMN status              VARCHAR(16)  DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE expenses ADD COLUMN revision            INTEGER      DEFAULT 1 NOT NULL;
ALTER TABLE expenses ADD COLUMN lock_version        BIGINT       DEFAULT 0 NOT NULL;
ALTER TABLE expenses ADD COLUMN client_request_key  VARCHAR(100);
ALTER TABLE expenses ADD COLUMN cancelled_at        TIMESTAMP;
ALTER TABLE expenses ADD COLUMN cancelled_by_id     VARCHAR(36);
ALTER TABLE expenses ADD COLUMN cancel_reason       VARCHAR(300);

UPDATE expenses SET created_by_id = paid_by_id WHERE created_by_id IS NULL;
UPDATE expenses SET expense_date = CAST(created_at AS DATE) WHERE expense_date IS NULL;

ALTER TABLE expenses ADD CONSTRAINT fk_expense_created_by   FOREIGN KEY (created_by_id)   REFERENCES users(id);
ALTER TABLE expenses ADD CONSTRAINT fk_expense_cancelled_by FOREIGN KEY (cancelled_by_id) REFERENCES users(id);
ALTER TABLE expenses ADD CONSTRAINT ck_expense_status CHECK (status IN ('ACTIVE', 'CANCELLED'));
ALTER TABLE expenses ADD CONSTRAINT ck_expense_split_type CHECK (split_type IN ('EQUAL', 'CUSTOM', 'PERCENTAGE', 'ITEM_BASED'));
ALTER TABLE expenses ADD CONSTRAINT ck_expense_cancel_meta CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL);
-- A retried create (same client key, same creator) can never produce a second expense. NULL keys are not constrained.
ALTER TABLE expenses ADD CONSTRAINT uq_expense_client_key UNIQUE (created_by_id, client_request_key);
CREATE INDEX idx_expense_payer ON expenses (paid_by_id);
CREATE INDEX idx_expense_due   ON expenses (due_date);

ALTER TABLE expense_shares ADD COLUMN status                 VARCHAR(16) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE expense_shares ADD COLUMN percentage_bp          INTEGER;
ALTER TABLE expense_shares ADD COLUMN settlement_method      VARCHAR(16);
ALTER TABLE expense_shares ADD COLUMN ledger_transaction_id  VARCHAR(36);
ALTER TABLE expense_shares ADD COLUMN refund_transaction_id  VARCHAR(36);
ALTER TABLE expense_shares ADD COLUMN claim_user_id          VARCHAR(36);
ALTER TABLE expense_shares ADD COLUMN settle_round           INTEGER     DEFAULT 0 NOT NULL;
ALTER TABLE expense_shares ADD COLUMN responded_at           TIMESTAMP;
ALTER TABLE expense_shares ADD COLUMN response_note          VARCHAR(300);
ALTER TABLE expense_shares ADD COLUMN settled_by_id          VARCHAR(36);

UPDATE expense_shares SET status = 'PAID', settlement_method = 'LEGACY' WHERE settled = TRUE;
UPDATE expense_shares SET settlement_method = 'SELF'
 WHERE settled = TRUE AND debtor_id = (SELECT e.paid_by_id FROM expenses e WHERE e.id = expense_shares.expense_id);

ALTER TABLE expense_shares ADD CONSTRAINT fk_eshare_ledger_tx  FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions(id);
ALTER TABLE expense_shares ADD CONSTRAINT fk_eshare_refund_tx  FOREIGN KEY (refund_transaction_id) REFERENCES ledger_transactions(id);
ALTER TABLE expense_shares ADD CONSTRAINT fk_eshare_claim_user FOREIGN KEY (claim_user_id)         REFERENCES users(id);
ALTER TABLE expense_shares ADD CONSTRAINT fk_eshare_settled_by FOREIGN KEY (settled_by_id)         REFERENCES users(id);
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_status CHECK (status IN
    ('PENDING', 'ACCEPTED', 'DISPUTED', 'DECLINED', 'PROCESSING', 'PAID', 'REFUNDED', 'REVERSED', 'CANCELLED'));
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_method CHECK (settlement_method IS NULL
    OR settlement_method IN ('LEDGER', 'SELF', 'EXTERNAL', 'LEGACY', 'NONE'));
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_amount CHECK (amount >= 0);
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_round  CHECK (settle_round >= 0);
-- A share is only PAID by the ledger when the ledger transaction that moved the value is named.
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_paid_ledger CHECK (
    NOT (status = 'PAID' AND settlement_method = 'LEDGER') OR ledger_transaction_id IS NOT NULL);
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_processing_claim CHECK (status <> 'PROCESSING' OR claim_user_id IS NOT NULL);
ALTER TABLE expense_shares ADD CONSTRAINT ck_eshare_refunded_tx CHECK (status <> 'REFUNDED' OR refund_transaction_id IS NOT NULL);
-- One ledger transaction can settle (and one can refund) at most one share.
ALTER TABLE expense_shares ADD CONSTRAINT uq_eshare_ledger_tx UNIQUE (ledger_transaction_id);
ALTER TABLE expense_shares ADD CONSTRAINT uq_eshare_refund_tx UNIQUE (refund_transaction_id);
CREATE INDEX idx_eshare_status ON expense_shares (status, updated_at);

-- Item-based split. An item is split equally among its assignees (remainder minor units to the earliest assignees in the
-- expense's participant order). Items are only data for the calculation; shares remain the source of what is owed.
CREATE TABLE expense_items (
    id           VARCHAR(36)  PRIMARY KEY,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    expense_id   VARCHAR(36)  NOT NULL,
    sort_order   INTEGER      NOT NULL,
    name         VARCHAR(120) NOT NULL,
    amount_minor BIGINT       NOT NULL,
    CONSTRAINT fk_eitem_expense FOREIGN KEY (expense_id) REFERENCES expenses(id),
    CONSTRAINT ck_eitem_amount  CHECK (amount_minor > 0)
);
CREATE INDEX idx_eitem_expense ON expense_items (expense_id, sort_order);

CREATE TABLE expense_item_assignees (
    item_id  VARCHAR(36) NOT NULL,
    user_id  VARCHAR(36) NOT NULL,
    PRIMARY KEY (item_id, user_id),
    CONSTRAINT fk_eia_item FOREIGN KEY (item_id) REFERENCES expense_items(id),
    CONSTRAINT fk_eia_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Immutable history: who did what, when, to which expense or share. Rows are only ever inserted.
CREATE TABLE expense_events (
    id             VARCHAR(36)   PRIMARY KEY,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL,
    expense_id     VARCHAR(36)   NOT NULL,
    share_id       VARCHAR(36),
    actor_user_id  VARCHAR(36),
    event_type     VARCHAR(32)   NOT NULL,
    revision       INTEGER       NOT NULL,
    details        VARCHAR(1000),
    ledger_reference VARCHAR(40),
    CONSTRAINT fk_eevent_expense FOREIGN KEY (expense_id)    REFERENCES expenses(id),
    CONSTRAINT fk_eevent_share   FOREIGN KEY (share_id)      REFERENCES expense_shares(id),
    CONSTRAINT fk_eevent_actor   FOREIGN KEY (actor_user_id) REFERENCES users(id)
);
CREATE INDEX idx_eevent_expense ON expense_events (expense_id, created_at);
