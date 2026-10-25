-- V7 gave pre-authentication transfers the idempotency scope 'legacy', which
-- is also a valid JWT subject: a caller with sub=legacy would share (and could
-- replay) those keys. Move them into the reserved 'payflow:' namespace, which
-- the token validator rejects as a subject.
UPDATE payment_transactions
SET idempotency_scope = 'payflow:legacy'
WHERE idempotency_scope = 'legacy';
