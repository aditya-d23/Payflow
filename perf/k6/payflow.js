// PayFlow load tests. Run one scenario at a time (see docs/PERFORMANCE.md):
//
//   docker run --rm --network payflow-stack_default -v "$PWD/perf/k6:/scripts" \
//     -e BASE_URL=http://app:8080 -e PAYFLOW_JWT_SECRET -e SCENARIO=transfers \
//     grafana/k6:2.2.0 run /scripts/payflow.js
//
// Scenarios: lookup | transfers | hot | retries | nsf
// Tokens are minted locally with the same HS256 secret the app is using, so
// this only works against a dev-profile deployment.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SECRET = __ENV.PAYFLOW_JWT_SECRET;
const SCENARIO = __ENV.SCENARIO || 'transfers';
const VUS = parseInt(__ENV.VUS || '20', 10);
const DURATION = __ENV.DURATION || '30s';
const RUN = __ENV.RUN_ID || Date.now().toString(36).toUpperCase();

if (!SECRET) {
  throw new Error('PAYFLOW_JWT_SECRET is required');
}

const scenarios = {
  // Read path: account balance at a fixed arrival rate.
  lookup: { executor: 'constant-arrival-rate', rate: parseInt(__ENV.RATE || '300', 10),
            timeUnit: '1s', duration: DURATION, preAllocatedVUs: VUS, maxVUs: VUS * 4 },
  // Write path, low contention: each VU moves money between its own two accounts.
  transfers: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  // Write path, maximum contention: every VU debits the same source account.
  hot: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  // Each iteration sends a transfer and an identical retry.
  retries: { executor: 'constant-vus', vus: VUS, duration: DURATION },
  // Every transfer is rejected for insufficient funds.
  nsf: { executor: 'constant-vus', vus: VUS, duration: DURATION },
};

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Scenario-tagged sub-metrics exclude setup() traffic from the results.
  thresholds: {
    checks: ['rate>0.99'],
    [`http_req_duration{scenario:${SCENARIO}}`]: ['max>=0'],
    [`http_reqs{scenario:${SCENARIO}}`]: ['count>=0'],
  },
};

function b64url(value) {
  return encoding.b64encode(value, 'rawurl');
}

function token(subject, admin) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const claims = { sub: subject, iss: 'payflow-local', aud: ['payflow-api'],
                   iat: now, exp: now + 3600 };
  if (admin) {
    claims.scope = 'payflow.admin';
  }
  const payload = b64url(JSON.stringify(claims));
  const signature = crypto.hmac('sha256', SECRET, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

function headers(bearer, key) {
  const h = { 'Content-Type': 'application/json', Authorization: `Bearer ${bearer}` };
  if (key) {
    h['Idempotency-Key'] = key;
  }
  return { headers: h };
}

function post(path, body, bearer, key, tags) {
  return http.post(`${BASE}${path}`, JSON.stringify(body),
      Object.assign(headers(bearer, key), { tags: tags || {} }));
}

function openAccount(number, owner, admin) {
  const res = post('/api/v1/accounts', { accountNumber: number, currency: 'INR', ownerId: owner }, admin);
  if (res.status !== 201) {
    throw new Error(`open ${number}: ${res.status} ${res.body}`);
  }
}

function fund(number, amount, admin) {
  const res = post('/api/v1/deposits', { accountNumber: number, amount, currency: 'INR' },
      admin, `fund-${number}`);
  if (res.status !== 201) {
    throw new Error(`fund ${number}: ${res.status} ${res.body}`);
  }
}

export function setup() {
  const admin = token('perf-admin', true);
  const users = [];
  for (let i = 0; i < VUS * 4; i++) {
    const owner = `perf-${RUN}-${i}`;
    const a = `P${RUN}A${i}`;
    const b = `P${RUN}B${i}`;
    openAccount(a, owner, admin);
    openAccount(b, owner, admin);
    if (SCENARIO !== 'nsf') {
      fund(a, 1000000, admin);
    }
    users.push({ owner, token: token(owner, false), a, b });
  }
  const hotOwner = `perf-${RUN}-hot`;
  const hot = `P${RUN}HOT`;
  openAccount(hot, hotOwner, admin);
  fund(hot, 10000000, admin);
  return { users, hot, hotToken: token(hotOwner, false) };
}

export default function (data) {
  const vu = exec.vu.idInTest - 1;
  const user = data.users[vu % data.users.length];
  const key = `${RUN}-${SCENARIO}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}`;

  if (SCENARIO === 'lookup') {
    const res = http.get(`${BASE}/api/v1/accounts/${user.a}/balance`, headers(user.token));
    check(res, { 'balance 200': (r) => r.status === 200 });
    return;
  }

  if (SCENARIO === 'hot') {
    const res = post('/api/v1/transfers',
        { fromAccountNumber: data.hot, toAccountNumber: user.b, amount: 1.0, currency: 'INR' },
        data.hotToken, key);
    check(res, { 'transfer 201': (r) => r.status === 201 });
    return;
  }

  if (SCENARIO === 'nsf') {
    const res = post('/api/v1/transfers',
        { fromAccountNumber: user.a, toAccountNumber: user.b, amount: 5.0, currency: 'INR' },
        user.token, key);
    check(res, { 'rejected 422': (r) => r.status === 422 });
    return;
  }

  const body = { fromAccountNumber: user.a, toAccountNumber: user.b, amount: 1.0, currency: 'INR' };
  const first = post('/api/v1/transfers', body, user.token, key, { attempt: 'first' });
  check(first, { 'transfer 201': (r) => r.status === 201 });

  if (SCENARIO === 'retries') {
    const retry = post('/api/v1/transfers', body, user.token, key, { attempt: 'retry' });
    check(retry, {
      'retry 201': (r) => r.status === 201,
      'retry replayed': (r) => r.headers['Idempotent-Replayed'] === 'true',
      'same transfer': (r) => r.json('transactionReference') === first.json('transactionReference'),
    });
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const d = m[`http_req_duration{scenario:${SCENARIO}}`].values;
  const requests = m[`http_reqs{scenario:${SCENARIO}}`].values.count;
  const seconds = parseInt(DURATION, 10);
  const summary = {
    scenario: SCENARIO, vus: VUS, duration: DURATION,
    requests,
    rps: Number((requests / seconds).toFixed(1)),
    iterations: m.iterations ? m.iterations.values.count : 0,
    checks_pass_rate: m.checks ? m.checks.values.rate : null,
    latency_ms: Object.fromEntries(Object.entries(d).map(([k, v]) => [k, Number(v.toFixed(2))])),
  };
  return { stdout: JSON.stringify(summary) + '\n' };
}
