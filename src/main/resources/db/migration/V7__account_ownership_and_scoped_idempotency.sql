-- Accounts belong to the authenticated subject (JWT `sub`) that opened them.
-- Accounts created before authentication existed have no owner and are only
-- visible to administrators until one is assigned.
ALTER TABLE accounts
    ADD COLUMN owner_id VARCHAR(255);

CREATE INDEX idx_accounts_owner
    ON accounts(owner_id);

-- Idempotency keys are unique per caller, not globally: two clients choosing
-- the same key must not collide or learn about each other's requests.
ALTER TABLE payment_transactions
    ADD COLUMN idempotency_scope VARCHAR(255);

UPDATE payment_transactions
SET idempotency_scope = 'legacy'
WHERE idempotency_key IS NOT NULL;

ALTER TABLE payment_transactions
    DROP CONSTRAINT uq_payment_transactions_idempotency_key;

ALTER TABLE payment_transactions
    ADD CONSTRAINT uq_payment_transactions_idempotency
        UNIQUE (idempotency_scope, idempotency_key);

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_idempotency_scope
        CHECK (idempotency_key IS NULL OR idempotency_scope IS NOT NULL);
