-- V3 declared the fingerprint as CHAR(64), but the entity maps it as VARCHAR,
-- so Hibernate schema validation rejected V3 on PostgreSQL. Align with the
-- convention V2 established for currency columns.
ALTER TABLE payment_transactions
    ALTER COLUMN idempotency_request_fingerprint TYPE VARCHAR(64);
