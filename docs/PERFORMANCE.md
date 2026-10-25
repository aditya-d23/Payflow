# PayFlow Performance

All numbers below were measured on 2026-10-25 with `perf/k6/payflow.js`. They
describe one laptop and one configuration, and are **not** capacity claims or
SLOs for a production deployment.

## Environment

| | |
| --- | --- |
| Host | MacBook Air, Apple M4 (10 cores), Docker Desktop VM with 10 CPUs and 7.7 GiB |
| Stack | `docker compose` (this repo): PostgreSQL 17, Kafka 4.2 (single KRaft node), app |
| App | One instance, `mem_limit: 768m`, JDK 25, `dev` profile, Hikari default pool (10), Kafka enabled |
| Load generator | `grafana/k6:2.2.0` on the Compose network (no host port hop) |
| Load | 20 closed-loop VUs (lookup: 300 req/s open model), 30 s per scenario |

Setup traffic (opening and funding the test accounts) is excluded. Results
come from the scenario-tagged sub-metrics only.

## Results

| Scenario | What it does | Throughput | p50 | p95 | p99 | Checks |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `lookup` | `GET /accounts/{n}/balance` at 300 req/s | 300 req/s (target) | 0.6 ms | 1.7 ms | 2.8 ms | 100 % |
| `transfers` | each VU transfers between its own two accounts | 2,340 transfers/s | 7.4 ms | 16.4 ms | 23.3 ms | 100 % |
| `hot` | all 20 VUs debit **one** account | 966 transfers/s | 17.6 ms | 37.5 ms | 54.8 ms | 100 % |
| `retries` | transfer, then an identical retry (replay) | 5,100 req/s (2,550 pairs/s) | 3.3 ms | 8.6 ms | 12.4 ms | 100 % |
| `nsf` | every transfer rejected for insufficient funds | 6,481 req/s | 2.5 ms | 6.2 ms | 8.7 ms | 100 % |

`transfers` is the run with the final relay configuration (pipelined, batch
500). Relay, Kafka consumer, and API share the container and the database, so
this is the realistic number. An earlier run with the original sequential
relay reached 3,111 transfers/s, but only because its relay barely consumed
resources while it fell 187k events behind (see below).

After about 340k transfers across all runs, `GET /api/v1/admin/reconciliation`
reported `consistent: true`, no customer balance was negative, and the app
logged no errors.

## What the numbers show

- **Lock contention is the expected bottleneck.** `hot` serializes every
  transfer on one row lock, cutting throughput by about 2.4× and raising p99
  about 2.3× compared with disjoint accounts. Correctness held (no failed
  checks, no lock timeouts at this load). Reducing it would mean sharding hot
  accounts or batching postings; not done here.
- **Replays are cheap.** A retry is one indexed lookup and moves no money, so
  idempotent retries are safe for clients to use liberally.
- **Rejections are cheap and side-effect free.** Insufficient funds rolls back
  before anything commits; nothing reaches the ledger or outbox.

## Bottleneck found and fixed: outbox relay throughput

The first `transfers` run left **186,821 events PENDING**. The relay published
one event at a time and waited for each Kafka acknowledgement. That came to
**142 events/s** (measured over 20 s), far below the rate of committed
transfers.

The fix (`KafkaEventPublisher.publishAll`, `OutboxRelay.relayBatch`) sends the
whole claimed batch before waiting for acknowledgements, then marks the
successes with one `UPDATE ... WHERE id = ANY(?)`. Ordering per partition is
preserved by the idempotent producer.

| Relay configuration | Drain rate (idle API) | Under 2.3k transfers/s |
| --- | ---: | --- |
| Sequential, batch 100 (original) | 142 events/s | backlog grows without bound |
| Pipelined, batch 100 | 4,060 events/s | backlog grew 39k events in 30 s |
| Pipelined, batch 500 (**default now**) | n/a | 0 pending at end of run; publish lag p50 528 ms, p99 38 s |

The p99 lag shows the relay still falls briefly behind at saturation on this
single instance. Next steps would be more relay workers (the claim already
supports concurrent relays via `SKIP LOCKED`, see `OutboxPostgresIT`) or
running the relay as its own deployment.

## Reproducing

```bash
cp .env.example .env            # set DB_PASSWORD and PAYFLOW_JWT_SECRET
docker compose up --build -d
set -a; . ./.env; set +a
for s in lookup transfers hot retries nsf; do
  docker run --rm --network payflow-stack_default -v "$PWD/perf/k6:/scripts:ro" \
    -e BASE_URL=http://app:8080 -e PAYFLOW_JWT_SECRET -e SCENARIO=$s \
    -e VUS=20 -e DURATION=30s grafana/k6:2.2.0 run --quiet /scripts/payflow.js
done
```

Each run creates its own accounts (prefixed with a run ID), so runs do not
interfere. Each prints one JSON summary line.

## Timeouts and retries

| Where | Value | Behavior |
| --- | --- | --- |
| Account row lock wait | 3 s | 503 `LOCK_TIMEOUT` + `Retry-After: 1`; key not consumed, safe to retry |
| Transfer transaction | 10 s | rolled back |
| Kafka send / metadata block | 10 s / 5 s | relay marks the event failed and backs off |
| Outbox retry backoff | 1 s doubling to 5 min, 10 attempts | then `DEAD` (alert on `payflow_outbox_events_total{outcome="dead"}`) |
| Consumer | 2 retries, 500 ms apart | then `<topic>.DLT` |

Clients should retry transfers only with the same `Idempotency-Key`. Never
retry a money-moving request with a new key unless the original is known to
have failed (4xx).
