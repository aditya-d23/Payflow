# PayFlow API

Base path `/api/v1`. All endpoints require `Authorization: Bearer <JWT>`
(see [SECURITY.md](SECURITY.md)). The machine-readable contract is served at
`/v3/api-docs`, and Swagger UI at `/swagger-ui.html` outside `prod`.

## Endpoints

| Method | Path | Who | Notes |
| --- | --- | --- | --- |
| `POST` | `/accounts` | any caller | Opens an account owned by the caller at balance 0. `ownerId` is admin-only. 201 + `Location`. |
| `GET` | `/accounts/{accountNumber}` | owner, admin | |
| `GET` | `/accounts/{accountNumber}/balance` | owner, admin | |
| `GET` | `/accounts/{accountNumber}/transactions?page=&size=` | owner, admin | Newest first, stable order, `size` 1–100 |
| `PATCH` | `/accounts/{accountNumber}/status` | admin | `ACTIVE`, `FROZEN`, `CLOSED`. Close requires zero balance and is final. |
| `PUT` | `/accounts/{accountNumber}/owner` | admin | Assigns accounts created before ownership existed |
| `POST` | `/transfers` | owner of source | **Requires `Idempotency-Key`**. 201 + `Location`. |
| `GET` | `/transfers/{transactionReference}` | either party, admin | |
| `POST` | `/deposits` | admin | **Requires `Idempotency-Key`**. Credits a customer from the funding account. |
| `POST` | `/withdrawals` | admin | **Requires `Idempotency-Key`**. Debits a customer to the funding account. |
| `GET` | `/admin/reconciliation` | admin | Balance vs. ledger report |

Accounts and transfers the caller may not see return **404**, never 403, so
their existence is not revealed.

## Examples

These commands run against the Docker Compose stack, or any `dev`-profile
instance using the same `PAYFLOW_JWT_SECRET`.

```bash
BASE=${BASE:-http://localhost:8080}
ALICE=$(scripts/dev-token.sh alice)
BOB=$(scripts/dev-token.sh bob)
ADMIN=$(scripts/dev-token.sh ops-admin admin)
```

### Open accounts

```bash
curl -s -X POST "$BASE/api/v1/accounts" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ALICE-001","currency":"INR"}'
curl -s -X POST "$BASE/api/v1/accounts" \
  -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d '{"accountNumber":"BOB-001","currency":"INR"}'
```

```json
{"accountNumber":"ALICE-001","ownerId":"alice","currency":"INR","status":"ACTIVE",
 "balance":0,"createdAt":"2026-10-25T12:10:19.711Z"}
```

### Fund an account (admin)

```bash
curl -s -X POST "$BASE/api/v1/deposits" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: fund-alice-001' \
  -d '{"accountNumber":"ALICE-001","amount":500.00,"currency":"INR"}'
```

### Transfer

```bash
curl -s -i -X POST "$BASE/api/v1/transfers" \
  -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7d1f6a4e-pay-bob' \
  -d '{"fromAccountNumber":"ALICE-001","toAccountNumber":"BOB-001","amount":125.50,"currency":"INR"}'
```

```http
HTTP/1.1 201
Location: /api/v1/transfers/da63adc8-db6b-4357-8488-d3efb8ee57a2
Idempotent-Replayed: false
X-Request-Id: 2eebff7b-3dce-4839-991a-c357383264b7

{"transactionReference":"da63adc8-db6b-4357-8488-d3efb8ee57a2","type":"TRANSFER",
 "status":"COMPLETED","fromAccountNumber":"ALICE-001","toAccountNumber":"BOB-001",
 "amount":125.5000,"currency":"INR","createdAt":"…","completedAt":"…"}
```

Sending the same request with the same key again returns the same body with
`Idempotent-Replayed: true` and moves no money. Sending a *different* body
with that key returns 409 `IDEMPOTENCY_KEY_REUSED`.

### Balance and history

```bash
curl -s "$BASE/api/v1/accounts/ALICE-001/balance" -H "Authorization: Bearer $ALICE"
curl -s "$BASE/api/v1/accounts/ALICE-001/transactions?page=0&size=10" -H "Authorization: Bearer $ALICE"
```

### Reconciliation (admin)

```bash
curl -s "$BASE/api/v1/admin/reconciliation" -H "Authorization: Bearer $ADMIN"
```

```json
{"consistent":true,"checkedAt":"…","accountMismatches":[],"currencyImbalances":[],
 "unbalancedLedgerTransactions":[],"completedTransfersWithoutLedger":[]}
```

## Request rules

- `amount` must be positive, have at most 15 integer digits, and use no more
  decimal places than the currency's minor unit (INR, USD, EUR, GBP: 2).
- `currency` must be one of `payflow.currencies.supported` (default
  `INR,USD,EUR,GBP`), and must match both accounts.
- `accountNumber` must be 3–30 characters of `[A-Za-z0-9-]`. The `SYS-` prefix
  is reserved for funding accounts.
- `Idempotency-Key` must be 1–255 visible ASCII characters, unique per caller.
- `X-Request-Id` (optional) must be `[A-Za-z0-9._-]{1,64}`. It is echoed back
  and logged; otherwise one is generated.

## Errors

Every error has the same shape:

```json
{"timestamp":"2026-10-25T12:10:19.844Z","status":404,"code":"ACCOUNT_NOT_FOUND",
 "message":"Account not found: PF10001","path":"/api/v1/transfers",
 "correlationId":"2eebff7b-3dce-4839-991a-c357383264b7"}
```

Validation errors add `fieldErrors: [{"field":"amount","message":"…"}]`.
Clients should branch on `code`.

| Status | Codes |
| --- | --- |
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_IDEMPOTENCY_KEY`, `UNSUPPORTED_CURRENCY`, `INVALID_AMOUNT` |
| 401 | `UNAUTHENTICATED` (with `WWW-Authenticate: Bearer`) |
| 403 | `ACCESS_DENIED` (missing `payflow.admin` scope) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSFER_NOT_FOUND`, `RESOURCE_NOT_FOUND` |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` |
| 409 | `ACCOUNT_ALREADY_EXISTS`, `IDEMPOTENCY_KEY_REUSED`, `DATA_CONFLICT` |
| 422 | `INSUFFICIENT_FUNDS`, `SAME_ACCOUNT_TRANSFER`, `CURRENCY_MISMATCH`, `ACCOUNT_NOT_ACTIVE`, `INVALID_ACCOUNT_STATE` |
| 503 | `LOCK_TIMEOUT` (with `Retry-After`; retry with the same key) |
| 500 | `INTERNAL_ERROR` (generic message; details only in server logs) |
