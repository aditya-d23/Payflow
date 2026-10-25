# PayFlow Security

> Audit snapshot (2026-10-25). Updated as controls land.

## Current state

- **No authentication or authorization** (D2). Every endpoint is anonymous.
- Transfer responses leak the counterparty account balance (D1).
- The base configuration contains a local development DB password (D7).

PayFlow must not be exposed outside localhost until Phase 6 is complete.
