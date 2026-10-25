ALTER TABLE payment_transactions
    ADD COLUMN completed_at TIMESTAMPTZ;

-- Transfers committed before this column existed completed in the same
-- transaction that created them.
UPDATE payment_transactions
SET completed_at = created_at
WHERE status = 'COMPLETED';
