
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(30) NOT NULL UNIQUE,
    balance NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_accounts_balance_nonnegative
        CHECK (balance >= 0),

    CONSTRAINT chk_accounts_status
        CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE TABLE payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    transaction_reference UUID NOT NULL UNIQUE,
    from_account_id BIGINT NOT NULL REFERENCES accounts(id),
    to_account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_transaction_amount_positive
        CHECK (amount > 0),

    CONSTRAINT chk_transaction_different_accounts
        CHECK (from_account_id <> to_account_id),

    CONSTRAINT chk_transaction_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_transactions_from_account
    ON payment_transactions(from_account_id, created_at DESC);

CREATE INDEX idx_transactions_to_account
    ON payment_transactions(to_account_id, created_at DESC);
