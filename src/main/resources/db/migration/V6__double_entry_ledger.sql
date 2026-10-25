-- Double-entry ledger.
--
-- Sign convention: for every account, balance = sum(CREDIT) - sum(DEBIT).
-- Each currency has a SYSTEM funding account that takes the opposite side of
-- money entering (deposit) or leaving (withdrawal) PayFlow, so its balance is
-- negative and the sum of all balances in a currency is always zero.

ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_type
        CHECK (account_type IN ('CUSTOMER', 'SYSTEM'));

-- Only customer balances must stay non-negative.
ALTER TABLE accounts
    DROP CONSTRAINT chk_accounts_balance_nonnegative;

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_customer_balance_nonnegative
        CHECK (account_type = 'SYSTEM' OR balance >= 0);

ALTER TABLE payment_transactions
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'TRANSFER';

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_transaction_type
        CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL'));

CREATE TABLE ledger_transactions (
    id BIGSERIAL PRIMARY KEY,
    reference UUID NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    payment_transaction_id BIGINT UNIQUE REFERENCES payment_transactions(id),
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_ledger_transaction_type
        CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'OPENING_BALANCE'))
);

CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    ledger_transaction_id BIGINT NOT NULL REFERENCES ledger_transactions(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    direction VARCHAR(6) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_ledger_entry_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT chk_ledger_entry_amount_positive
        CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_account
    ON ledger_entries(account_id, id);

CREATE INDEX idx_ledger_entries_transaction
    ON ledger_entries(ledger_transaction_id);

-- ---------------------------------------------------------------------------
-- Backfill: system accounts, then ledger history for existing data.
-- ---------------------------------------------------------------------------

INSERT INTO accounts (account_number, balance, currency, status, account_type)
SELECT 'SYS-FUNDING-' || c.currency, 0, c.currency, 'ACTIVE', 'SYSTEM'
FROM (
    SELECT currency FROM accounts
    UNION
    SELECT unnest(ARRAY['INR', 'USD', 'EUR', 'GBP'])
) c;

INSERT INTO ledger_transactions
    (reference, type, payment_transaction_id, currency, description, created_at)
SELECT gen_random_uuid(), 'TRANSFER', p.id, p.currency,
       'Backfilled from payment transaction', p.created_at
FROM payment_transactions p
WHERE p.status = 'COMPLETED';

INSERT INTO ledger_entries
    (ledger_transaction_id, account_id, direction, amount, currency, created_at)
SELECT l.id, p.from_account_id, 'DEBIT', p.amount, p.currency, p.created_at
FROM ledger_transactions l
JOIN payment_transactions p ON p.id = l.payment_transaction_id
UNION ALL
SELECT l.id, p.to_account_id, 'CREDIT', p.amount, p.currency, p.created_at
FROM ledger_transactions l
JOIN payment_transactions p ON p.id = l.payment_transaction_id;

-- Whatever part of a customer balance is not explained by transfers (for
-- example, seeded development balances) becomes an opening balance funded by
-- the system account.
CREATE TEMPORARY TABLE ledger_opening_balances ON COMMIT DROP AS
SELECT a.id AS account_id,
       a.currency,
       a.balance - COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                                     THEN e.amount ELSE -e.amount END), 0) AS amount,
       gen_random_uuid() AS reference
FROM accounts a
LEFT JOIN ledger_entries e ON e.account_id = a.id
WHERE a.account_type = 'CUSTOMER'
GROUP BY a.id, a.currency, a.balance;

DELETE FROM ledger_opening_balances WHERE amount = 0;

INSERT INTO ledger_transactions (reference, type, currency, description)
SELECT reference, 'OPENING_BALANCE', currency, 'Backfilled opening balance'
FROM ledger_opening_balances;

INSERT INTO ledger_entries (ledger_transaction_id, account_id, direction, amount, currency)
SELECT l.id, o.account_id,
       CASE WHEN o.amount > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
       ABS(o.amount), o.currency
FROM ledger_opening_balances o
JOIN ledger_transactions l ON l.reference = o.reference
UNION ALL
SELECT l.id, s.id,
       CASE WHEN o.amount > 0 THEN 'DEBIT' ELSE 'CREDIT' END,
       ABS(o.amount), o.currency
FROM ledger_opening_balances o
JOIN ledger_transactions l ON l.reference = o.reference
JOIN accounts s ON s.account_number = 'SYS-FUNDING-' || o.currency;

UPDATE accounts s
SET balance = COALESCE((
    SELECT SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END)
    FROM ledger_entries e
    WHERE e.account_id = s.id
), 0)
WHERE s.account_type = 'SYSTEM';

-- ---------------------------------------------------------------------------
-- Database-level guarantees. The application enforces the same rules; these
-- catch any write path that bypasses it.
-- ---------------------------------------------------------------------------

CREATE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; post a correcting transaction instead',
        TG_TABLE_NAME;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- A CHECK constraint cannot see sibling rows, so balance is verified by a
-- deferred constraint trigger when the enclosing transaction commits.
CREATE FUNCTION ledger_assert_balanced() RETURNS trigger AS $$
DECLARE
    tx_id BIGINT;
    debits NUMERIC;
    credits NUMERIC;
    entry_count INTEGER;
    currency_mismatches INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entries' THEN
        tx_id := NEW.ledger_transaction_id;
    ELSE
        tx_id := NEW.id;
    END IF;

    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
    INTO debits, credits, entry_count
    FROM ledger_entries
    WHERE ledger_transaction_id = tx_id;

    IF entry_count < 2 OR debits <> credits THEN
        RAISE EXCEPTION
            'ledger transaction % is unbalanced: debits %, credits %, entries %',
            tx_id, debits, credits, entry_count
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT COUNT(*)
    INTO currency_mismatches
    FROM ledger_entries e
    JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
    JOIN accounts a ON a.id = e.account_id
    WHERE e.ledger_transaction_id = tx_id
      AND (e.currency <> t.currency OR a.currency <> t.currency);

    IF currency_mismatches > 0 THEN
        RAISE EXCEPTION 'ledger transaction % mixes currencies', tx_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_balanced
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_balanced();
