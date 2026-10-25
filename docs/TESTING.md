# PayFlow Testing

> Audit snapshot (2026-10-25). Updated as suites land.

## Baseline

`./mvnw test` runs 13 tests, all passing, against in-memory H2 with
`ddl-auto=create-drop`. Flyway migrations and PostgreSQL locking behavior are
not exercised (D6).
