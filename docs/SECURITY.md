# PayFlow Security

## Authentication

Every endpoint except `/actuator/health/**` and the OpenAPI documents requires
an OAuth 2.0 bearer token (JWT). `SecurityConfig.jwtDecoder` verifies:

| Check | Rule |
| --- | --- |
| Signature | JWKS from the identity provider (`PAYFLOW_JWT_JWK_SET_URI`), or HS256 with `PAYFLOW_JWT_SECRET` for local development. Unsigned (`alg: none`) tokens are rejected. |
| Expiry | `exp` and `nbf` are enforced with 60 seconds of clock skew |
| Issuer | `iss` must equal `PAYFLOW_JWT_ISSUER` |
| Audience | `aud` must contain `PAYFLOW_JWT_AUDIENCE` |
| Subject | `sub` must be present; it is the account owner ID |

There are **no default signing keys**. Startup fails if neither a JWKS URI nor a
secret is configured, or if the secret is shorter than 32 bytes. The `prod`
profile requires `PAYFLOW_JWT_JWK_SET_URI` and disables the HMAC path.

Sessions are stateless and CSRF protection is off: the API accepts no cookies,
so there is no ambient credential a cross-site request could use.

Evidence: `JwtValidationTests` signs real tokens and checks that valid ones are
accepted and expired, wrong-issuer, wrong-audience, wrong-key, subject-less,
and unsigned ones are rejected.

## Authorization

| Capability | Who |
| --- | --- |
| Open an account for yourself | any authenticated caller |
| Open an account for someone else (`ownerId`) | `payflow.admin` |
| Read an account, balance, or history | its owner, or `payflow.admin` |
| Transfer *from* an account | its owner only (admins cannot move customer money) |
| Transfer *to* an account | anyone who knows the account number |
| Read a transfer | owners of either account, or `payflow.admin` |
| Deposits, withdrawals, status changes, owner assignment, reconciliation | `payflow.admin` |
| Actuator endpoints other than health | `payflow.admin` |

Role checks happen twice. URL rules in `SecurityConfig` give coarse
protection, and the services check again using an explicit `Caller`, so a
new controller cannot skip them by accident. Ownership is verified **after**
the account row is locked, inside the transfer transaction.

Resources the caller may not see return **404, not 403**. This keeps callers
from probing which account numbers or transfer IDs exist. Real 403s are kept
for role failures, where the resource itself is not revealed.

Idempotency keys are unique per caller (`UNIQUE (idempotency_scope,
idempotency_key)`, V7), so one client's keys can neither collide with nor
reveal another client's requests.

Evidence: `SecurityApiTests`.

## Local development tokens

```bash
export PAYFLOW_JWT_SECRET="$(openssl rand -base64 48)"
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run      # same shell
TOKEN=$(scripts/dev-token.sh dev-user)                 # owns seeded PF10001
ADMIN=$(scripts/dev-token.sh ops-admin admin)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/accounts/PF10001
```

Accounts created before V7 have no owner and are visible only to admins until
one is assigned with `PUT /api/v1/accounts/{accountNumber}/owner`.

## Data protection

- Error responses never include stack traces, SQL, or constraint names
  (`GlobalExceptionHandler`); unexpected errors return a generic message and
  are logged server-side with the correlation ID.
- Transfer responses contain no balances or internal IDs.
- `X-Request-Id` values are restricted to `[A-Za-z0-9._-]{1,64}` to prevent
  log injection. Idempotency keys are restricted to visible ASCII.
- Tokens, secrets, and request bodies are not logged.
- Secrets come only from the environment. `.env` is git-ignored, and
  `.env.example` holds placeholders only.

## Database-level controls

- The ledger tables reject `UPDATE` and `DELETE` via triggers. In production
  the application role should also lack `TRUNCATE` and DDL privileges.
- Customer balances have a `CHECK (balance >= 0)`. Postings are verified as
  balanced by a deferred constraint trigger.

## Known limitations

- No rate limiting. Put an API gateway in front for per-client throttling (see
  ARCHITECTURE.md, "Redis").
- No token revocation beyond expiry. Use short-lived tokens from the identity
  provider.
- The default DB password in `application-dev.properties` matches the local
  Docker container and is for localhost only.
- Not audited against PCI DSS or any regulatory framework.
