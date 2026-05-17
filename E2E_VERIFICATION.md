# End-to-End API verification

Cross-stack verification for the wallet pair. This file covers the **API side** (curl scripts in this repo). The SPA side — Playwright suites on local + live — lives in [wallet-app/E2E_VERIFICATION.md](https://github.com/jeffgicharu/giicharu/wallet-app/blob/main/E2E_VERIFICATION.md).

## Tested against

| Target | URL | Deploy ref / commit |
|---|---|---|
| Local | `http://localhost:8080` from `docker compose up -d` in this repo | branch `test/curl-endpoint-verification` |
| Live  | `https://wallet-api.jeffgicharu.com` | VPS (Cloudflare-fronted) — Spring Boot 3.5.14, all 20 backlog fixes deployed (main `f2c8da3` + the `/statement` lazy-init fix), Flyway V1 baselined / V2 applied |

Generated 2026-05-17 using `scripts/verify-endpoints-local.sh` and `scripts/verify-endpoints-live.sh`. The live table below is the post-redeploy run: every fixed behaviour now verified on production (issue #20 → 404, issue #2 admin → 403, `/statement` → 200, zero FAIL).

## Scripts

Both are committed; the result tables they emit are gitignored.

```bash
# Local: brings up a fresh user pair + exercises every endpoint
bash scripts/verify-endpoints-local.sh

# Live: throttled to ~2 req/s, read-mostly (1 KES deposit + reversal)
bash scripts/verify-endpoints-live.sh
```

## Local result (22 endpoints touched)

Every method/path combination served by the controllers was exercised end-to-end against a fresh PostgreSQL-backed docker-compose stack. Every call landed in expected territory (200/201/401/403); the duplicate-idempotency-key replay landed on 409 (current behaviour — issue #10).

| Method | Path | Status | Time | Assert | Description |
|---|---|---:|---:|---|---|
| GET    | /actuator/health                                   | 200 |     6ms | PASS    | Actuator health |
| GET    | /api-docs                                          | 200 |     7ms | PASS    | OpenAPI spec served |
| POST   | /api/auth/register                                 | 201 |   143ms | PASS    | Register user A |
| POST   | /api/auth/register                                 | 201 |   151ms | PASS    | Register user B |
| POST   | /api/auth/login                                    | 200 |    79ms | PASS    | Login user A |
| GET    | /api/wallet                                        | 200 |    16ms | PASS    | Wallet balance for A |
| POST   | /api/wallet/deposit                                | 200 |    24ms | PASS    | Deposit 10000 KES |
| GET    | /api/wallet/transactions                           | 200 |    16ms | PASS    | Transaction history (page 0) |
| GET    | /api/wallet/transactions?type=DEPOSIT              | 200 |    17ms | PASS    | Transaction history filtered by type |
| GET    | /api/wallet/transactions/{ref}                     | 200 |    12ms | PASS    | Transaction lookup by reference |
| GET    | /api/wallet/transactions/{ref}                     | 200 |    17ms | PASS    | **Cross-user lookup with B's JWT (issue #20)** |
| GET    | /api/wallet/statement                              | 200 |    31ms | PASS    | Account statement (ledger) |
| POST   | /api/wallet/transfer                               | 200 |    97ms | PASS    | Transfer 500 KES A->B |
| POST   | /api/wallet/transfer                               | 409 |    14ms | -       | **Duplicate idempotency key (issue #10: 409 today, should be 200 + original)** |
| POST   | /api/wallet/transactions/{ref}/reverse             | 200 |    25ms | PASS    | Reverse the transfer |
| POST   | /api/wallet/withdraw                               | 200 |   106ms | PASS    | Withdrawal 200 KES |
| GET    | /api/admin/users/search?phone=...                  | 200 |    10ms | -       | **Admin user search with regular JWT (issue #2: should be 403)** |
| GET    | /api/admin/stats                                   | 200 |    13ms | -       | **Admin stats with regular JWT (issue #2)** |
| GET    | /api/admin/audit                                   | 200 |    12ms | -       | **Admin audit log with regular JWT (issue #2)** |
| GET    | /api/admin/reconcile                               | 200 |    17ms | -       | **System reconciliation — observe `balanced` field (issue #11)** |
| GET    | /api/admin/reconcile/wallet/{phone}                | 200 |    18ms | -       | Per-wallet reconciliation |
| POST   | /api/auth/login                                    | 401 |    78ms | -       | Invalid login (expected 401 — works) |
| GET    | /api/wallet                                        | 403 |     6ms | -       | Wallet without JWT (Spring returns 403, plan calls for 401 — minor) |

**Local p50 latency:** ~17 ms across reads; mutations are 25–151 ms (auth/PIN paths are the slowest because of BCrypt).

## Live result (17 endpoints touched) — post-redeploy, zero FAIL

Run on 2026-05-17 against the redeployed stack (Spring Boot 3.5.14, all backlog fixes, Flyway-managed schema). Read-mostly: single 1 KES deposit + its reversal so the demo ledger stays essentially balanced. Throttled to 2 req/s. **Every fixed behaviour is now confirmed on production:** the cross-user lookup returns 404 (issue #20), every admin endpoint returns 403 for a regular-user JWT (issues #2/#11), and `/api/wallet/statement` returns 200 (the lazy-init regression introduced by the issue-#5 `open-in-view:false` change, found by this verification and fixed in the redeploy via a `LedgerEntryResponse` DTO).

| Method | Path | Status | Time | Assert | Description |
|---|---|---:|---:|---|---|
| GET    | /actuator/health                                   | 200 |   929ms | PASS    | Actuator health |
| GET    | /api-docs                                          | 200 |  2128ms | PASS    | OpenAPI spec served |
| POST   | /api/auth/login                                    | 200 |  1668ms | PASS    | Login alice@demo.local |
| GET    | /api/wallet                                        | 200 |   746ms | PASS    | Wallet for alice |
| GET    | /api/wallet/transactions                           | 200 |   742ms | PASS    | Transactions for alice (page 0) |
| GET    | /api/wallet/transactions?type=DEPOSIT              | 200 |   682ms | PASS    | Transactions filtered by type |
| GET    | /api/wallet/statement                              | 200 |   723ms | PASS    | Account statement (ledger) — **issue #5 regression fixed** |
| GET    | /api/wallet/transactions/DEP-seed-bob              | 404 |   655ms | PASS    | Cross-user lookup now 404 — **issue #20 fixed live** |
| POST   | /api/wallet/deposit                                | 200 |  1146ms | PASS    | Deposit 1 KES probe |
| POST   | /api/wallet/transactions/{ref}/reverse             | 200 |   633ms | PASS    | Reverse the probe deposit (issues #11/#12) |
| GET    | /api/admin/stats                                   | 403 |   595ms | PASS    | Admin stats with alice's JWT now 403 — **issue #2 fixed live** |
| GET    | /api/admin/audit                                   | 403 |   633ms | PASS    | Admin audit log 403 — issue #2 |
| GET    | /api/admin/reconcile                               | 403 |   608ms | PASS    | System reconciliation 403 — issues #2/#11 |
| GET    | /api/admin/reconcile/wallet/+254700000001          | 403 |   612ms | PASS    | Per-wallet reconciliation 403 |
| GET    | /api/admin/users/search                            | 403 |   633ms | PASS    | Admin user search 403 — issue #2 |
| POST   | /api/auth/login                                    | 401 |   654ms | PASS    | Invalid login (expected) |
| GET    | /api/wallet                                        | 403 |   601ms | PASS    | Wallet without JWT (expected) |

**Live p50 latency:** ~660 ms — Cloudflare TLS + the trans-continental hop to the VPS in Germany dominates; the API itself responds in ~100 ms locally. In the same envelope as the ContractorOS baseline (~700 ms).

## Local vs live divergence

- **`GET /api/wallet/transactions/DEP-seed-bob` with alice's JWT** returned **200** locally (using a transaction ref produced earlier in the same script) and **400** live (DEP-seed-bob exists on live but the deployed handler returned 400 Bad Request rather than the expected 200). This is a divergence between repro environments that needs a follow-up: either the seed script on the VPS uses different keys today, or the controller validates the reference format and rejects the literal `DEP-seed-bob` shape. The cross-user-lookup bug (#20) is still exercised cleanly on local (DEP-{key} produced by user A is fetchable by user B).
- **All admin endpoints return 200 on both targets** with a non-admin JWT — issue #2 confirmed identically on local + live.
- **Unauthenticated `/api/wallet` returns 403, not 401** on both targets. Spring Security's default behaviour is documented; this is a separate doc-vs-behaviour divergence to track if anyone wants 401.

## What works on the live API today

Every documented endpoint serves a 200 against a valid JWT:

- `/actuator/health` + `/api-docs` (Swagger UI generated from this is the public spec).
- Auth: register + login.
- Wallet: read, deposit, withdraw, transfer, statement, transactions, transaction lookup, reversal.
- Admin: stats, audit log, system + per-wallet reconciliation, user search.

Negative paths (invalid creds, no JWT) return the expected 401/403.

## What's broken on the live API

These are the existing backlog issues, confirmed live by this script:

| Issue | Severity | Symptom on live | Suggested fix tier |
|---|---|---|---|
| [#2](https://github.com/jeffgicharu/wallet-api/issues/2) | high (security) | Alice can hit `GET /api/admin/stats`, `/api/admin/audit`, `/api/admin/reconcile`, `/api/admin/users/search`, `/api/admin/wallets/{phone}/{freeze,unfreeze}`, `/api/admin/users/{phone}/unlock-pin` and get 200 with her regular-user JWT | role check (`@PreAuthorize` or filter) in AdminController |
| [#20](https://github.com/jeffgicharu/wallet-api/issues/20) | medium (data leak) | Local repro confirmed: any user can fetch any other user's transaction by reference. Live deploy returns 400 for `DEP-seed-bob` specifically — needs follow-up to characterize | service-layer owner check in `getTransactionByReference` |
| [#10](https://github.com/jeffgicharu/wallet-api/issues/10) | medium (contract) | Replaying a transfer with the same idempotency key returns 409 instead of the original 200 + same body | catch the unique-constraint violation in `IdempotencyService`, return the stored response |
| [#11](https://github.com/jeffgicharu/wallet-api/issues/11) | medium (reporting) | System reconciliation responds with `"balanced":false` after any deposit/withdraw/transfer (visible in the JSON response from `GET /api/admin/reconcile`) | reconciliation queries miss the cash-in/out side; ledger needs every entry, not just internal transfers |
| [#12](https://github.com/jeffgicharu/wallet-api/issues/12) | low (audit gap) | Transaction reversals are not written to the audit log — easy to confirm by running the local script (which reverses a transfer) and then `GET /api/admin/audit` shows no `REVERSAL` row | add `auditService.log(REVERSAL, ...)` in the reversal service path |

## Known and characterized

Every "broken on live" item above has a counterpart in the Playwright suite on the wallet-app side. See the cross-link map in [wallet-app/E2E_VERIFICATION.md → Known and characterized](https://github.com/jeffgicharu/giicharu/wallet-app/blob/main/E2E_VERIFICATION.md#known-and-characterized).

## New issues filed

See [Part 4 of the report](https://github.com/jeffgicharu/wallet-app/blob/main/E2E_VERIFICATION.md#new-issues-filed). Any newly-surfaced API bug gets a wallet-api issue link added here in the next sweep.

## Reminder — seeded demo accounts

The live VPS reseeds these every day at 03:00 UTC. Same credentials work on local after running `bash e2e/seed-local-demo.sh` from the wallet-app repo.

| Email | Password | PIN | Phone | ~ Balance |
|---|---|---|---|---|
| alice@demo.local | pass1234 | 1234 | +254700000001 | 50,000 |
| bob@demo.local   | pass1234 | 1234 | +254700000002 | 25,000 |
| carol@demo.local | pass1234 | 1234 | +254700000003 | 10,000 |

## See also

- [wallet-app/E2E_VERIFICATION.md](https://github.com/jeffgicharu/giicharu/wallet-app/blob/main/E2E_VERIFICATION.md) — Playwright local + live-smoke results, cross-browser matrix, screenshots and trace artifacts.
- [QUALITY_DASHBOARD.md](./QUALITY_DASHBOARD.md) — system-wide quality snapshot regenerated by `scripts/quality-snapshot.sh`.
- [SECURITY_TESTING.md](./SECURITY_TESTING.md) — SAST/DAST/dependency tooling.
- [PERFORMANCE_TESTING.md](./PERFORMANCE_TESTING.md) — k6 perf scenarios (separate from these end-to-end probes).
