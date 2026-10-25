# PayFlow Operations

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | none | `dev` (local defaults, seeds PF10001) or `prod` (no defaults). With no profile, startup fails because no datasource is configured. |
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | dev: local container; prod: **required** | PostgreSQL |
| `DB_POOL_SIZE` | `20` (prod) | Hikari maximum pool size |
| `PAYFLOW_JWT_ISSUER` / `PAYFLOW_JWT_AUDIENCE` | `payflow-local` / `payflow-api`; prod: **required** | Token `iss` / `aud` |
| `PAYFLOW_JWT_JWK_SET_URI` | prod: **required** | Identity provider's JWKS endpoint |
| `PAYFLOW_JWT_SECRET` | none | HS256 key, at least 32 bytes, non-prod only |
| `PAYFLOW_KAFKA_ENABLED` | `false` | Relay outbox events to Kafka and start the sample consumer |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `PAYFLOW_KAFKA_CREATE_TOPICS` | `true` | Create topics at startup; set `false` where topics are provisioned |
| `payflow.currencies.supported` | `INR,USD,EUR,GBP` | |
| `payflow.outbox.*` | batch 500, lease 30 s, 10 attempts, backoff 1 s to 5 min | See `OutboxProperties` |

Copy `.env.example` to `.env` (git-ignored) for local values.

## Running

```bash
# Option A: everything in Docker (own PostgreSQL on 5433, Kafka, app on 8080)
cp .env.example .env            # set DB_PASSWORD and PAYFLOW_JWT_SECRET
docker compose up --build -d
docker compose ps
docker compose logs -f app
docker compose down             # keeps the stack's data volume
docker compose down -v          # also deletes this stack's volume (its data only)

# Option B: app on the host against an existing PostgreSQL (e.g. payflow-postgres)
export PAYFLOW_JWT_SECRET="$(openssl rand -base64 48)"
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

The Compose stack uses its own project name (`payflow-stack`), volume, and
host ports, and never touches a separately created `payflow-postgres`
container.

## Upgrading an existing database

Flyway applies pending migrations at startup inside transactions. A database
at V2 (for example the original local `payflow` database) goes through:

| Migration | Effect on existing data |
| --- | --- |
| V3 | Adds idempotency columns |
| V4 | Fingerprint column CHAR → VARCHAR |
| V5 | `completed_at` backfilled from `created_at` for completed transfers |
| V6 | Adds funding accounts and the ledger; existing transfers and balances become balanced postings |
| V7 | Ownership column (existing accounts get **no owner**) and caller-scoped idempotency |
| V8 | Outbox tables |

No balances change. Afterwards, accounts created before V7 are visible only
to admins. Assign them owners:

```bash
ADMIN=$(scripts/dev-token.sh ops-admin admin)
curl -X PUT localhost:8080/api/v1/accounts/PF10002/owner \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"ownerId":"dev-user"}'
```

Take a backup before upgrading anything that matters:
`docker exec payflow-postgres pg_dump -U payflow payflow > payflow-backup.sql`.

## Health and metrics

| Endpoint | Access | Use |
| --- | --- | --- |
| `/actuator/health/liveness` | public | Restart if failing |
| `/actuator/health/readiness` | public | Route traffic only when UP |
| `/actuator/health` | public status; details for admins | |
| `/actuator/prometheus` | admin | Scrape target |
| `/actuator/metrics` | admin | Ad-hoc inspection |

Metrics worth alerting on:

| Metric | Alert when |
| --- | --- |
| `payflow_outbox_events_total{outcome="dead"}` | increases at all |
| `payflow_outbox_pending` | keeps growing over several minutes |
| `payflow_transfers_total{outcome="lock_timeout"}` | sustained non-zero rate |
| `payflow_transfers_total{outcome="error"}` | any (unexpected failures) |
| `http_server_requests_seconds` p99 | above your latency objective |
| `hikaricp_connections_pending` | sustained > 0 (pool exhausted) |

Labels are bounded (transfer type, outcome code, URI template). Account
numbers, keys, and IDs are never labels.

## Logs

Each line carries the request's correlation ID: `INFO [<id>]` in `dev`, or
the `correlationId` field in `prod` ECS JSON. Clients can supply
`X-Request-Id`; every error body returns it as `correlationId`, which is the
first thing to ask a caller for.

## Runbooks

**Reconciliation reports `consistent: false`.** Stop and investigate before
anything else; balances and ledger disagree. Something wrote balances
outside `LedgerService` (for example manual SQL). The report names the
accounts. Fix with a correcting posting, never by editing ledger rows (they
are append-only).

**Outbox events marked DEAD.** Inspect them:
`SELECT event_id, event_type, attempts, last_error FROM outbox_events WHERE status = 'DEAD';`.
Once the cause is fixed, requeue:
`UPDATE outbox_events SET status = 'PENDING', attempts = 0, available_at = now() WHERE status = 'DEAD';`.
Consumers deduplicate, so republishing is safe.

**Records in `payflow.transfers.DLT`.** The consumer could not process them
after retries (malformed or unsupported version). Inspect them with
`kafka-console-consumer`; fix the consumer; then replay to the main topic.

**Lock timeouts.** One account is very hot or a transaction is stuck. Check
`pg_stat_activity` for long-running transactions. Clients retry with the same
`Idempotency-Key`.

**Stopping safely.** `SIGTERM` (or `docker compose stop`) lets in-flight
requests finish. Transfers are single transactions, so an interrupted one
rolls back. The outbox relay's leases expire and other instances, or the
next start, pick the events up.
