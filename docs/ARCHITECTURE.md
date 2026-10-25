# PayFlow Architecture

> Audit snapshot (2026-10-25). Rewritten as features land; see
> [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) for status.

PayFlow is a single Spring Boot service backed by PostgreSQL.

```
controller  ->  service  ->  repository (Spring Data JPA)  ->  PostgreSQL
                  |
                  +-- TransferService           idempotency lookup / replay (no tx)
                  +-- TransferExecutionService  locks + debit + credit (one tx)
```

The current transfer flow:

1. `TransferService` normalizes the `Idempotency-Key` and computes a SHA-256
   fingerprint of the request.
2. It looks up an existing transaction by key and replays it, or returns 409 if
   the fingerprint differs.
3. Otherwise, `TransferExecutionService.execute` runs in one transaction. It
   locks both accounts with `SELECT ... FOR UPDATE` in account-number order,
   inserts the transaction row (where the unique key is enforced), and debits
   and credits.
4. A unique-key violation from a concurrent duplicate is caught *outside* the
   rolled-back transaction, and the winner's row is returned.

Known gaps are listed in the implementation plan (D1–D13).
