# PayFlow Implementation Plan and Checklist

This file is the single source of truth for project status. Every item marked
done links to the evidence that verifies it (test class, migration, or command).

Status legend: `[x]` done and verified · `[~]` partial · `[ ]` not started

## Phase 0 audit (2026-10-25, commit `2b84138`)

### Environment

| Item | Finding |
| --- | --- |
| Build | Maven via wrapper (`./mvnw`, Maven 3.10.0) — no Gradle |
| JDK | Oracle JDK 25.0.2, `maven.compiler.release=25` |
| Framework | Spring Boot 4.1.1 (Hibernate 7.4, Spring Security 7.1, Jackson 3, Flyway 12.4, Testcontainers 2.0.5 managed) |
| Database | Local container `payflow-postgres` (postgres:17, port 5432). Flyway at **V2**; V3 not yet applied. Contains 2 accounts and 1 completed transfer, which every new migration must preserve. |
| Remote | `origin` → github.com/aditya-d23/Payflow, `main` pushes directly (no protection observed) |
| Baseline tests | 13 tests, 0 failures (`./mvnw test`, H2 only) |

### Implemented before this plan

- Account entity, create and get-by-account-number endpoints.
- Transfer endpoint with pessimistic `SELECT ... FOR UPDATE` locking in
  deterministic (account-number) order, inside one `@Transactional` method.
- Database-backed idempotency: unique `payment_transactions.idempotency_key`,
  SHA-256 request fingerprint, conflict on payload mismatch, race handling by
  catching the unique violation outside the failed transaction.
- Paginated transaction history with stable ordering.
- Flyway migrations V1–V3, `ddl-auto=validate`, `open-in-view=false`.

### Defects found

| # | Severity | Defect |
| --- | --- | --- |
| D1 | High | Transfer responses serialize the `Account` entities, leaking the **counterparty's balance** and internal IDs to the sender. |
| D2 | High | No authentication or authorization. Anyone can read any balance and move money out of any account. |
| D3 | High | `IllegalArgumentException` (same account, currency mismatch) is unhandled, so it returns HTTP 500. |
| D4 | High | Account status is ignored. FROZEN and CLOSED accounts can send and receive. |
| D5 | Medium | Amount precision is not tied to the currency (0.0001 INR accepted). |
| D6 | Medium | Tests never run Flyway (`ddl-auto=create-drop` on H2), so migrations and PostgreSQL locking are untested. |
| D7 | Medium | `spring.profiles.active=dev` and a DB password are hardcoded in the base `application.properties`, so any deployment seeds dev data. |
| D8 | Medium | Error bodies are inconsistent (plain string, `{error}` map, framework default) and carry no correlation ID. |
| D9 | Medium | No ledger. Mutable balances are the only financial record. |
| D10 | Medium | Lock acquisition has no timeout, so a stuck transaction can hold request threads indefinitely. |
| D11 | Low | Idempotency keys are globally unique rather than scoped to a caller, which leaks key usage across tenants once auth exists. |
| D12 | Low | No GET-transfer or balance endpoint, no funding path other than dev seeding. |
| D13 | Low | No Dockerfile, Compose, CI, README, or operational docs (HELP.md is generated boilerplate). |

## Checklist

### Phase 1: Stabilize
- [x] Profiles: `dev` (local defaults), `test`, `prod` (no credential defaults). The base config has no datasource, so `dev` must now be activated explicitly (D7).
- [x] DB settings from `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SERVER_PORT`; `.env.example`
- [x] PostgreSQL Testcontainers suite running Flyway + `ddl-auto=validate` (`support/PostgresIntegrationTest`, run by failsafe in `./mvnw verify`)
- [x] Migration upgrade test from V2 with existing data (`FlywayMigrationPostgresIT`)
- [x] **D14 (new, fixed):** V3 created `idempotency_request_fingerprint CHAR(64)` while the entity expects VARCHAR, so the app failed to start on PostgreSQL after V3. Fixed forward in `V4__fingerprint_as_varchar.sql`.

### Phase 2: Accounts
- [x] Response DTOs (no entity exposure), status enum, supported currencies (`AccountApiTests`, `CurrencyPolicyTests`)
- [x] `GET /api/v1/accounts/{accountNumber}/balance`, `PATCH .../status` (close requires zero balance, terminal)
- [x] Duplicate creation race handled by the unique constraint (409 `ACCOUNT_ALREADY_EXISTS`)
- [x] Client-supplied opening balances are ignored; accounts open at zero

### Phase 3: Transfers
- [x] Domain exceptions mapped to 4xx (D3) (`TransferApiTests`)
- [x] Account status enforcement (D4), currency precision (D5)
- [x] `TransferResponse` DTO (D1), `GET /api/v1/transfers/{reference}`, `completed_at` (V5)
- [x] Lock timeout of 3s, mapped to 503 `LOCK_TIMEOUT` + `Retry-After` (D10); the key is not consumed, so retries are safe
- [x] PostgreSQL concurrency tests (`TransferConcurrencyPostgresIT`): 25-way overdraw race, 40 opposite-direction transfers (no deadlock), 10 concurrent duplicates, lock timeout, trigger-forced failures on credit and on the transaction record, 60 mixed transfers conserving total
- [x] History query N+1 fixed with fetch joins

### Phase 4: Idempotency
- [x] Key format validation (1–255 visible ASCII), `Idempotent-Replayed` header
- [x] Caller-scoped uniqueness (D11): `UNIQUE (idempotency_scope, idempotency_key)` in V7 (`SecurityApiTests.idempotencyKeysAreScopedToTheCaller`)
- [x] Fingerprint unit tests (`TransferFingerprintTests`)
- [x] PostgreSQL: concurrent duplicates (`TransferConcurrencyPostgresIT`), same key with a different payload racing on disjoint accounts, replay after an application restart, rejected attempts do not consume the key (`IdempotencyPostgresIT`)
- [x] "Unknown outcome" is not observable server-side: the key claim and the money movement commit atomically, so a key is either absent (retry executes) or bound to a completed transfer (retry replays). Documented in ARCHITECTURE.md.

### Phase 5: Ledger
- [x] ledger_transactions and ledger_entries (V6). Append-only via triggers, balance and currency checked by a deferred constraint trigger at commit (`LedgerPostgresIT`)
- [x] `LedgerService` is the only code that changes balances, in the caller's transaction (`Propagation.MANDATORY`)
- [x] Deposits and withdrawals via per-currency `SYS-FUNDING-*` system accounts, reusing transfer locking and idempotency (`FundingApiTests`). The `SYS-` prefix is reserved.
- [x] Backfill of pre-existing transfers and opening balances; upgrade from V2 reconciles (`FlywayMigrationPostgresIT`)
- [x] Reconciliation (`GET /api/v1/admin/reconciliation`) checks per-account balance vs ledger, zero net per currency, balanced postings, and completed transfers with ledger postings. Verified after 120 concurrent mixed operations, and detects out-of-band balance edits.
- [x] Dev seeding now funds PF10001 through a deposit, so the ledger stays reconciled

### Phase 6: Security
- [x] JWT resource server validating signature, exp/nbf, iss, aud, sub. JWKS in prod, HS256 for local dev, no default keys (`JwtValidationTests`)
- [x] Ownership on accounts, transfers, and history (404 for non-owned); `payflow.admin` for funding, status, owner assignment, reconciliation, actuator (`SecurityApiTests`)
- [x] JSON 401/403 bodies with correlation ID and `WWW-Authenticate: Bearer`
- [x] `scripts/dev-token.sh` for local tokens; SECURITY.md

### Phase 7: Outbox and events
- [x] Outbox table (V8) written in the transfer and account-creation transactions; rolled-back transfers emit nothing (`OutboxPostgresIT`)
- [x] Relay: `FOR UPDATE SKIP LOCKED` claim with lease, publish outside transactions, capped exponential backoff, DEAD after N attempts, expired-lease recovery, 4 concurrent relays with no duplicate claims (`OutboxPostgresIT`, `OutboxPropertiesTests`)
- [x] Kafka publisher (acks=all, idempotent producer, keyed by aggregate, event headers) and deduplicating consumer with `.DLT` dead-lettering. End to end with Testcontainers Kafka 4.2 (`KafkaOutboxPostgresIT`). Off by default (`PAYFLOW_KAFKA_ENABLED`); without Kafka, transfers are unaffected and the relay logs.
- [x] No TransferFailed event: rejected transfers roll back and have no durable business state

### Phase 8: Redis
- [x] Evaluated and **not adopted**: no state that may live outside PostgreSQL, and no hot read path worth a cache on financial data. Rate limiting belongs at the gateway. Reasoning in ARCHITECTURE.md.

### Phase 9: API quality
- [x] Standard error body with code and correlation ID (D8)
- [x] OpenAPI 3 via springdoc 3.1.1 at `/v3/api-docs`, Swagger UI in non-prod (`ObservabilityTests`)

### Phase 10–14: Ops
- [x] Actuator health with liveness/readiness probes (public, no details); Prometheus, metrics, info admin-only; `payflow.transfers{type,outcome}`, `payflow.transfer.duration`, outbox metrics, Hikari pool metrics; bounded labels verified; correlation ID in every log line; ECS JSON logs in prod (`ObservabilityTests`)
- [ ] Dockerfile and Compose (must not collide with `payflow-postgres`)
- [ ] GitHub Actions CI
- [ ] k6 performance scripts and measured results

### Phase 15–16: Documentation and release verification
- [ ] README, ARCHITECTURE, SECURITY, TESTING, API, OPERATIONS
- [ ] Final verification run recorded here
