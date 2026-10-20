ALTER TABLE payment_transactions
    ADD COLUMN idempotency_key VARCHAR(255),
    ADD COLUMN idempotency_request_fingerprint CHAR(64);

ALTER TABLE payment_transactions
    ADD CONSTRAINT uq_payment_transactions_idempotency_key
        UNIQUE (idempotency_key);
