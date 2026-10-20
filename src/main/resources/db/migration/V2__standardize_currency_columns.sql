
ALTER TABLE accounts
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE payment_transactions
    ALTER COLUMN currency TYPE VARCHAR(3);
