# PayFlow Testing

106 automated tests: 76 in the fast suite and 30 PostgreSQL/Kafka
integration tests. All pass on `./mvnw verify` as of 2026-10-25.

## Commands

| Goal | Command | Needs |
| --- | --- | --- |
| Fast suite (unit + H2 web/service tests) | `./mvnw test` | JDK 25 |
| Everything, including PostgreSQL and Kafka ITs | `./mvnw verify` | JDK 25, Docker |
| Integration tests only | `./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` | Docker |
| One IT class | `./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LedgerPostgresIT` | Docker |
| Performance | see [PERFORMANCE.md](PERFORMANCE.md) | Docker |

Surefire runs `*Tests` classes. Failsafe runs `*IT` classes in
`integration-test`. Integration tests start their own throwaway containers
(`postgres:17-alpine`, `apache/kafka:4.2.0`) via Testcontainers. They never
touch a developer database. Nothing in the suite depends on test ordering:
every test cleans up after itself (truncation).

## Why two databases

H2 (PostgreSQL mode, schema generated from the entities) keeps the fast suite
fast. It is used for HTTP contracts, validation, and authorization, where H2
and PostgreSQL behave the same.

Anything that depends on real PostgreSQL behavior runs against PostgreSQL:
Flyway migrations, `ddl-auto=validate`, row locks and lock timeouts, unique
index races, triggers, `SKIP LOCKED`. H2's locking and constraint timing
differ, so passing there proves nothing about concurrency.

## Suites

### Unit

| Class | Proves |
| --- | --- |
| `CurrencyPolicyTests` | Currency normalization; minor-unit precision; equivalent amounts normalize identically; startup guard for unstorable currencies |
| `TransferFingerprintTests` | Fingerprint is deterministic, covers every field and direction, and field boundaries cannot be shifted |
| `LedgerServiceTests` | Unbalanced, single-leg, mixed-currency, and non-positive postings are rejected |
| `OutboxPropertiesTests` | Retry backoff doubles, caps, and does not overflow |
| `AccountValidationTests`, `DevDataInitializerTests` | Request validation; dev seeding goes through the ledger and is idempotent |

### HTTP and service (H2)

| Class | Proves |
| --- | --- |
| `AccountApiTests` | Create/read/balance/status; zero opening balance even if a balance is sent; duplicates → 409; field errors |
| `TransferApiTests` | Status and error code for every business rule; no balances or IDs in responses; replay header; malformed input; correlation ID echo |
| `TransferServiceIntegrationTests` | The original transfer and idempotency tests, including rollback after a forced post-debit failure |
| `FundingApiTests` | Deposits and withdrawals through the funding account; idempotent; `SYS-` reserved; reconciles |
| `SecurityApiTests` | Authorization matrix: 401 without a token, 404 for others' accounts and transfers, 403 for admin operations, caller-scoped idempotency keys, admin-only owner assignment |
| `JwtValidationTests` | Real signed tokens through the real decoder: valid accepted; expired, wrong issuer, wrong audience, wrong key, no subject, and `alg: none` rejected |
| `ObservabilityTests` | Probes public without details; Prometheus admin-only, with bounded labels (no account numbers or keys); OpenAPI document |

### PostgreSQL integration (Testcontainers)

| Class | Proves |
| --- | --- |
| `FlywayMigrationPostgresIT` | Migrations apply and match the entities; a V2 database with live data upgrades to the latest version and **reconciles** after the ledger backfill |
| `TransferConcurrencyPostgresIT` | 25 concurrent debits never overdraw (exactly 10 of 100 succeed against 1000); 40 opposite-direction transfers, no deadlock; 10 concurrent duplicates move money once; lock timeout in 2–6 s with no side effects and the key still usable; trigger-forced failures while crediting and while completing the record roll everything back; 60 mixed transfers conserve the total |
| `IdempotencyPostgresIT` | Replay after a real application restart; same key with a different payload racing on disjoint accounts gives one success and one 409; rejected attempts don't consume the key |
| `LedgerPostgresIT` | One balanced entry pair per transfer; reconciliation after 120 concurrent mixed deposits, withdrawals, and transfers (all must succeed); detects out-of-band balance edits; ledger is append-only; the database rejects an unbalanced posting at commit; system accounts can't be used in customer transfers |
| `OutboxPostgresIT` | Exactly one event per committed transfer and none on rollback; ordered publication; multi-batch drain; retry then success; DEAD after max attempts; one failure in a batch doesn't block the rest; expired leases reclaimed; 4 concurrent relays never claim the same event |
| `KafkaOutboxPostgresIT` | End to end through a real broker: notifications for both parties, exactly once despite a forced redelivery; keys and headers; poison message to `.DLT` without blocking later messages |

## What is not covered

- Multi-instance deployments (concurrency is tested with many threads and
  connections against one database, which exercises the same locks).
- Network partitions between app, database, and broker beyond broker-down
  retries.
- Load beyond one laptop (see PERFORMANCE.md).
- No mutation testing and no formal coverage target. Coverage percentages
  are not a goal here; the financial failure modes above are.
