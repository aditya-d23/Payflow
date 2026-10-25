#!/usr/bin/env bash
# Mints a short-lived HS256 bearer token for LOCAL DEVELOPMENT ONLY.
#
#   export PAYFLOW_JWT_SECRET="$(openssl rand -base64 48)"   # once per shell
#   scripts/dev-token.sh dev-user              # customer token
#   scripts/dev-token.sh ops-admin admin       # adds the payflow.admin scope
#
# The application must run with the same PAYFLOW_JWT_SECRET. Production uses an
# identity provider (PAYFLOW_JWT_JWK_SET_URI) and never accepts these tokens.
set -euo pipefail

subject="${1:?usage: dev-token.sh SUBJECT [admin]}"
role="${2:-}"
secret="${PAYFLOW_JWT_SECRET:?set PAYFLOW_JWT_SECRET (at least 32 bytes)}"
issuer="${PAYFLOW_JWT_ISSUER:-payflow-local}"
audience="${PAYFLOW_JWT_AUDIENCE:-payflow-api}"
ttl="${PAYFLOW_TOKEN_TTL_SECONDS:-3600}"

if [[ ! "$subject" =~ ^[A-Za-z0-9._@-]{1,255}$ ]]; then
  echo "subject may only contain letters, digits, '.', '_', '@' and '-'" >&2
  exit 1
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
scope=""
if [[ "$role" == "admin" ]]; then
  scope=',"scope":"payflow.admin"'
fi

header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","iss":"%s","aud":["%s"],"iat":%d,"exp":%d%s}' \
  "$subject" "$issuer" "$audience" "$now" "$((now + ttl))" "$scope" | b64url)
signature=$(printf '%s.%s' "$header" "$payload" \
  | openssl dgst -sha256 -mac HMAC -macopt "key:$secret" -binary | b64url)

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
