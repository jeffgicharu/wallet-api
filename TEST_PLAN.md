# Test Plan — Customer onboarding through to first reversed transfer

This plan operationalises the [`TEST_STRATEGY.md`](./TEST_STRATEGY.md) for the single highest-value workflow in the Wallet System: a brand-new customer who registers, logs in, deposits, transfers money to another customer, withdraws cash, and finally reverses the original deposit.

---

## 1. Why this workflow

The strategy lists nine cross-cutting concerns: registration, JWT auth, idempotency, balance arithmetic, fee calculation, daily limits, double-entry ledger, PIN lockout, transaction reversal. **Onboarding-through-to-reversal is the only single workflow that exercises every one of them**, plus the cross-repo wiring (multi-step UI wizard with the `PinPad` component, frontend ↔ backend contract, CORS, JWT lifecycle in the SPA). Anything that breaks in this flow tends to break the whole demo. Conversely, anything that locks this flow down with high-quality tests buys disproportionate coverage of the system.

A second reason: every cross-cutting concern in this flow has at least one known-good test pattern from the existing 21 tests, which keeps the plan ramp from "speculative" to "extend what works."

## 2. Workflow overview

```mermaid
sequenceDiagram
  autonumber
  actor Alice as Alice (new customer)
  participant SPA as wallet-app (React)
  participant API as wallet-api (Spring Boot)
  participant DB as PostgreSQL

  Alice->>SPA: Open https://wallet.jeffgicharu.com
  SPA->>SPA: No JWT in sessionStorage → redirect to /login
  Alice->>SPA: Submit register form
  SPA->>API: POST /api/auth/register
  API->>DB: INSERT user, INSERT wallet (balance=0)
  API-->>SPA: 200 { token, fullName, phone }
  SPA->>SPA: store token in sessionStorage
  Note right of SPA: Auth context now hydrated
  Alice->>SPA: Open /deposit, enter 50,000
  SPA->>API: POST /api/wallet/deposit (idempotencyKey)
  API->>DB: BEGIN; INSERT transaction; UPDATE wallet (optimistic version); INSERT 2 ledger_entries; COMMIT
  API-->>SPA: 200 { transaction with reference DEP-... }
  SPA->>SPA: Toast success; balance card updates
  Alice->>SPA: Open /send, recipient phone, amount 5,000, PIN 1234
  SPA->>API: POST /api/wallet/transfer (idempotencyKey, PIN, recipient)
  API->>DB: validate PIN, check daily limit, lock both wallets, debit Alice + fee, credit Bob, 4 ledger entries
  API-->>SPA: 200 { transaction TRF-... }
  Alice->>SPA: Open /withdraw, amount 1,000, PIN 1234
  SPA->>API: POST /api/wallet/withdraw (idempotencyKey, PIN)
  API->>DB: validate PIN, debit Alice, 2 ledger entries
  API-->>SPA: 200 { transaction WDR-... }
  Alice->>SPA: Reverse the deposit (ref DEP-...)
  SPA->>API: POST /api/wallet/transactions/DEP-.../reverse?reason=...
  API->>DB: BEGIN; check not already reversed; debit Alice; create REV ledger entries; mark original REVERSED; COMMIT
  API-->>SPA: 200 { reversal transaction REV-... }
  SPA->>SPA: Refresh history; show REVERSED status on original
```

## 3. Test scenarios

Sixty-six scenarios. Priorities: **P0** = blocks ship, **P1** = ships in the same iteration, **P2** = polish.

| ID | Scenario | Type | Stack | Priority | Preconditions | Steps | Expected outcome |
|---|---|---|---|---|---|---|---|
| **A. Registration & onboarding** | | | | | | | |
| A1 | Register new user — happy path | integration | api | P0 | Empty `users` table | POST `/api/auth/register` with valid body | 200; user + wallet rows exist; JWT returned; wallet balance = 0 |
| A2 | Register form — happy path through SPA | E2E | both | P0 | Empty users; SPA loaded | Fill register form, submit | Redirects to `/`; balance card shows 0 |
| A3 | Duplicate email rejected | integration | api | P0 | A user with this email exists | POST `/api/auth/register` with same email | 400 / 409; original user unchanged; no second wallet |
| A4 | Duplicate phone rejected | integration | api | P0 | A user with this phone exists | POST `/api/auth/register` with same phone | 400 / 409; rejected with clear message |
| A5 | Invalid email format rejected | integration | api | P1 | none | POST with `email: "not-an-email"` | 400 with validation error pointing at the field |
| A6 | Phone format validation | integration | api | P1 | none | POST with `phoneNumber: "1234"` (too short) | 400 with validation error |
| A7 | PIN must be exactly 4 digits | integration | api | P1 | none | POST with `pin: "12"` and `pin: "abcd"` | 400 in both cases |
| A8 | Register form client-side validation | component | web | P1 | RegisterPage rendered | Enter mismatched / invalid fields, submit | Field-level error messages shown, no API call |
| **B. Login & JWT** | | | | | | | |
| B1 | Login — happy path | integration | api | P0 | Registered user | POST `/api/auth/login` with correct creds | 200; token returned; subsequent authenticated calls succeed |
| B2 | Login form — happy path through SPA | E2E | both | P0 | Demo user `alice@demo.local` exists | Submit login form | Token stored in sessionStorage; redirect to `/` |
| B3 | Wrong password | integration | api | P0 | User exists | POST login with bad password | 401; no token; (audit log entry if implemented) |
| B4 | Unknown email | integration | api | P0 | none | POST login with unregistered email | 401; same shape as wrong-password (no enumeration) |
| B5 | JWT tampering rejected | integration / security | api | P0 | Valid JWT | Modify any byte in payload before signature; call `/api/wallet` | 401 |
| B6 | JWT expired | integration | api | P1 | JWT issued with `expiration-ms=1` | Wait, then call `/api/wallet` | 401 |
| B7 | `alg: none` JWT rejected | security | api | P0 | none | Forge `eyJhbGciOiJub25lIn0...` token, call `/api/wallet` | 401 |
| B8 | Missing Authorization header on protected endpoint | integration | api | P0 | none | GET `/api/wallet` with no header | 401 |
| B9 | 401 from API redirects SPA to `/login` | component | web | P1 | App with stale token | Make any API call that returns 401 | `sessionStorage` cleared, navigation to `/login`, error toast |
| **C. Deposit** | | | | | | | |
| C1 | Deposit success increases balance | integration | api | P0 | Registered user, balance 0 | POST `/deposit` `{amount: 50000, idempotencyKey: "k1"}` | 200; balance 50000.00; one transaction; two ledger entries (debit cash, credit wallet) |
| C2 | Idempotency — duplicate key returns same transaction | integration | api | P0 | Deposit `k1` already done | POST `/deposit` again with same `k1` | 200; same transaction reference; balance still 50000 |
| C3 | Idempotency — different keys produce two deposits | integration | api | P1 | Deposit `k1` done | POST with `k2` | 200; new transaction; balance 100000 |
| C4 | Negative amount rejected | integration | api | P0 | none | POST `/deposit` with `amount: -10` | 400 |
| C5 | Below minimum amount rejected | integration | api | P1 | none | POST `/deposit` with `amount: 0.5` | 400 (min `1.00`) |
| C6 | Above maximum amount rejected | integration | api | P1 | none | POST `/deposit` with `amount: 9999999` | 400 (max `1000000.00`) |
| C7 | Frozen wallet rejects deposit | integration | api | P0 | Wallet frozen via `/api/admin/wallets/{phone}/freeze` | POST `/deposit` | 4xx with frozen-wallet message; balance unchanged |
| C8 | Deposit through SPA | E2E | both | P0 | Logged in | Open `/deposit`, tap preset 5000, submit | Toast success; balance card updates without reload |
| **D. Transfer** | | | | | | | |
| D1 | Transfer success — both balances updated, fee charged | integration | api | P0 | Alice 50000, Bob 0 | Alice POST `/transfer` `{recipient: bob, amount: 5000, pin: 1234, idempotencyKey: "t1"}` | 200; Alice 44950 (fee 1 % = 50); Bob 5000; transaction TRF-...; 4 ledger entries (debit alice, credit bob, debit fee, credit fee account) |
| D2 | Insufficient balance rejected | integration | api | P0 | Alice 100 | POST `/transfer` `{amount: 5000, ...}` | 400 / 409; balances unchanged; idempotency key not consumed |
| D3 | Self-transfer rejected | integration | api | P0 | Alice 50000 | POST `/transfer` to her own phone | 400 |
| D4 | Unknown recipient rejected | integration | api | P0 | Alice 50000 | POST `/transfer` to a phone with no user | 404 |
| D5 | Daily limit enforcement | integration | api | P0 | Alice transferred 295000 today | POST `/transfer` `{amount: 6000}` | 400 / 409; over-limit message; no movement |
| D6 | PIN required for transfer | integration | api | P0 | none | POST `/transfer` without PIN | 400 (validation) |
| D7 | Wrong PIN — counter increments | integration | api | P0 | Alice; failedPinAttempts=0 | POST `/transfer` with wrong PIN | 401; Alice's failedPinAttempts=1; no movement |
| D8 | Three wrong PINs — 15-min lockout | integration | api | P0 | Alice; failedPinAttempts=2 | POST `/transfer` with wrong PIN; then any PIN-using endpoint | First call: 401, lockout set; subsequent: 423 / 4xx with lockedUntil |
| D9 | PIN lockout clears after window | integration | api | P1 | Alice locked, lockedUntil in past | POST `/transfer` with correct PIN | 200; failedPinAttempts reset |
| D10 | Optimistic locking — concurrent transfers from same wallet | integration | api | P0 | Alice 50000, two threads each try transfer 30000 | Run in parallel | Exactly one succeeds, exactly one is rejected (`OptimisticLockException` mapped to 409); ledger has one transfer pair |
| D11 | Idempotency — same key returns same transfer (no double-charge) | integration | api | P0 | Transfer `t1` done; Alice 44950 | POST same `/transfer` again with `t1` | 200; same response; Alice still 44950 |
| D12 | Send wizard — happy path through SPA with PinPad | E2E | both | P0 | Alice logged in | `/send`: enter recipient phone, amount, confirm, enter 1234 on PinPad | Success screen with reference; balance updated; toast |
| D13 | PinPad component — accepts 4 digits, fires on complete | component | web | P0 | PinPad mounted | Tap 1, 2, 3, 4 | Indicator dots fill; `onComplete("1234")` fires once |
| D14 | PinPad component — clear button resets state | component | web | P1 | PinPad with 1, 2 entered | Tap clear | Indicator empties; no `onComplete` fired |
| **E. Withdraw** | | | | | | | |
| E1 | Withdraw success — balance decreases | integration | api | P0 | Alice 50000 | POST `/withdraw` `{amount: 1000, pin: 1234, idempotencyKey: "w1"}` | 200; balance 49000; transaction WDR-...; 2 ledger entries |
| E2 | Withdraw rejected on insufficient balance | integration | api | P0 | Alice 50 | POST `/withdraw` `{amount: 1000}` | 400 / 409; balance unchanged |
| E3 | Withdraw shares PIN-lockout state with transfer | integration | api | P0 | Alice locked from D8 | POST `/withdraw` even with correct PIN | 423 / 4xx; same lockedUntil |
| E4 | Withdraw through SPA with PinPad | E2E | both | P1 | Alice logged in | `/withdraw`: tap preset 1000, PIN 1234 | Success; balance updated |
| **F. Reversal** | | | | | | | |
| F1 | Reverse a deposit — balance refunded, ledger entries created | integration | api | P0 | Alice deposited 50000 (DEP-x) | POST `/transactions/DEP-x/reverse?reason=demo` | 200; new REV-... transaction; Alice -50000; 2 reversing ledger entries; original transaction status REVERSED |
| F2 | Reverse a transfer — both wallets restored | integration | api | P0 | Alice→Bob 5000 transfer (TRF-x) | Reverse TRF-x | Alice +5050 (refund + fee); Bob -5000; 4 reversing ledger entries; audit log entry |
| F3 | Cannot reverse already-reversed transaction | integration | api | P0 | TRF-x reversed | Reverse TRF-x again | 4xx with explicit message; no movement |
| F4 | Cannot reverse a failed transaction | integration | api | P1 | TX with status FAILED | Reverse it | 4xx |
| F5 | Reversal creates an audit-log entry | integration | api | P1 | TX reversed | Query `/api/admin/audit/{type}/{id}` | Audit row present with action, actor, timestamp |
| F6 | Reverse the deposit through SPA | E2E | both | P1 | Alice has the deposit visible in `/history` | Tap row → reverse with reason | Original row shows REVERSED; balance updates; new REV row in history |
| **G. Authorisation & isolation** | | | | | | | |
| G1 | `/api/admin/*` rejects unauthenticated | integration | api | P0 | none | GET `/api/admin/stats` without token | 401 |
| G2 | `/api/admin/*` accepts ANY authenticated principal (current behaviour, tracks issue #2) | integration | api | P0 | A regular user JWT | GET `/api/admin/stats` | 200 — **flagged as the bug captured in issue #2; this scenario is the regression test that flips to 403 once the role check ships** |
| G3 | Wallet A cannot read Wallet B's history | integration | api | P0 | Alice + Bob both registered, Alice has txns | Bob's JWT calls `/api/wallet/transactions` | Returns Bob's empty list — never Alice's transactions |
| G4 | Admin freeze blocks subsequent transactions | integration | api | P1 | Alice frozen | Any wallet endpoint | 4xx with frozen message |
| G5 | Admin unlock-PIN clears `failedPinAttempts` | integration | api | P1 | Alice locked | POST `/api/admin/users/{phone}/unlock-pin` | failedPinAttempts=0; lockedUntil null; subsequent valid PIN works |
| **H. Frontend cross-cutting** | | | | | | | |
| H1 | Login form — error toast on wrong password | component | web | P1 | LoginPage mounted, MSW returns 401 | Submit | Toast appears with API error message; no navigation |
| H2 | Balance card show / hide toggle | component | web | P2 | HomePage with mocked wallet | Click toggle | Numeric balance replaced by dots; click again restores |
| H3 | Transaction list — colour-coded amounts | component | web | P2 | HistoryPage with mixed in / out txns | Render | Inbound green, outbound red, reversed strikethrough |
| H4 | Logout clears sessionStorage and redirects | component | web | P0 | Authenticated app | Trigger logout | sessionStorage empty; navigation to `/login` |
| **I. Contract** | | | | | | | |
| I1 | `authApi.login` matches API response shape | contract | both | P0 | Pact recorded by web | Provider verification on api PR | Pact verifier passes; if shape changes, both PRs must coordinate |
| I2 | `walletApi.deposit` request and response contract | contract | both | P0 | Pact recorded by web | Provider verification | Pact verifier passes |
| I3 | `walletApi.transfer` contract including idempotency key field | contract | both | P0 | Pact recorded by web | Provider verification | Pact verifier passes |
| I4 | New optional API field is additive (forward-compatible) | contract | both | P1 | Pact recorded with old shape | API adds an optional response field | Provider verifier still passes — consumer ignores unknown fields |
| **J. Performance** | | | | | | | |
| J1 | `GET /api/wallet` p95 < 200 ms under nominal load | performance | api | P0 | 100-thread profile from JMeter | Run nominal load | p95 ≤ 200 ms; error rate < 0.1 % |
| J2 | `POST /api/wallet/transfer` p95 < 400 ms under nominal load | performance | api | P0 | Same | Run nominal load | p95 ≤ 400 ms |
| **K. Security smoke** | | | | | | | |
| K1 | CORS preflight from disallowed origin returns 403 | integration | api | P0 | CORS configured for wallet.jeffgicharu.com only | OPTIONS with `Origin: https://evil.example.com` | 403; no `Access-Control-Allow-Origin` header |
| K2 | Idempotency cannot be abused for replay across users | security | api | P0 | Alice deposit `k1` succeeded | Bob calls deposit with same key `k1` | Each user has its own idempotency namespace; Bob gets a fresh deposit |

**Total: 66 scenarios** spanning unit, integration, contract, component, E2E, security, and performance layers.

## 4. Test data strategy

### Layered fixtures

- **Unit (Java):** in-test builders. `User.builder().email(...).build()`. Lombok `@Builder` already in place.
- **Integration (Java):** `TestDataFactory` helpers under `src/test/java/com/wallet/support/`. Methods like `aRegisteredUser(phone)`, `aWalletWithBalance(phone, 50000)`, `aTransferFrom(alice).to(bob).of(5000)`. Each helper goes through the public API (POST `/register`, POST `/deposit`) so the data is realistic and the helper doubles as a smoke test of those endpoints.
- **Component (TS):** `src/test/factories/` exports `makeWallet()`, `makeTransaction(type)`, `makeUser()`. MSW handlers consume these to mock API responses.
- **E2E (Playwright):** uses the demo accounts already seeded by `/opt/wallet-api/seed-demo.sh` on the live VPS, which resets daily 03:00 UTC. The on-VPS seed script is the canonical reference for what data exists in any environment.

### Anonymisation

The wallet system handles synthetic demo accounts only — `alice@demo.local`, `bob@demo.local`, `carol@demo.local`, fictional Kenyan phone numbers in the `+254700000xxx` range, demo PINs `1234`. There is no real PII in any environment, so anonymisation pipelines are not in scope. If a real-data path ever appears, that becomes a separate document.

## 5. Environment strategy

| Environment | Purpose | Data | Lifetime |
|---|---|---|---|
| Local laptop (Java unit) | Pure logic | Builder-fixtures | Per-test |
| Testcontainers Postgres | Java integration | Migrated schema, fixture-built rows | Per-test class |
| Vitest + jsdom | Web component | MSW mocks | Per-test file |
| `docker compose up` (api + postgres + nginx) | Full-stack E2E in CI | Seed script run once at start | Per-PR |
| Live origin (`wallet-api.jeffgicharu.com`, `wallet.jeffgicharu.com`) | Nightly smoke + DAST + perf | Demo seed, resets 03:00 UTC | Continuous |

The two integration tiers (Testcontainers Postgres and live origin) bracket the system: the first proves that the application works against a real Postgres, the second proves that the deployed copy of that application keeps working as the surrounding world (TLS, Cloudflare, DNS, nginx) changes.

## 6. Risk areas

Top four risks, ranked. Each maps to scenarios that are the regression test for it.

| Rank | Risk | Linked issue | Covering scenarios |
|---|---|---|---|
| 1 | Privilege escalation — `AdminController` lacks role check, any authenticated user can hit admin endpoints. | [#2](https://github.com/jeffgicharu/wallet-api/issues/2) | G2 (today's behaviour, flips to a 403-asserting test once fixed); G4, G5 (admin happy paths, also tighten when role check lands) |
| 2 | Schema drift — production uses `ddl-auto: update` instead of a migration tool. Subtle entity changes can change the schema in unexpected ways at boot. | [#3](https://github.com/jeffgicharu/wallet-api/issues/3) | The whole integration suite running on Testcontainers Postgres is the regression net here; specifically D10 (optimistic locking) and F1 / F2 (ledger entries) catch dialect-specific surprises early. |
| 3 | Hard-coded JWT default in the dev profile — accidental activation outside dev would accept JWTs forged with the published secret. | [#4](https://github.com/jeffgicharu/wallet-api/issues/4) | B5, B7 (JWT tampering and `alg:none`) plus a planned static check (Spotbugs custom rule) that the literal default never appears outside `application.yml`. |
| 4 | Open Session In View — masks N+1 and surprises during refactor. | [#5](https://github.com/jeffgicharu/wallet-api/issues/5) | D10, F1, F2 — the integration tests that touch multiple entities — fail loudly if Hibernate is silently flushing during rendering. |

## 7. Out of scope (for this plan)

- **Performance — full load / stress / spike profiles.** Smoke profile (J1, J2) is in this plan; the longer profiles live in a separate `PERFORMANCE_TESTING.md` (to be added) and run nightly.
- **DAST.** OWASP ZAP runs from a separate CI workflow against the live origin; its findings flow into the dashboard, not into this plan.
- **Mutation testing.** PIT and Stryker scoring lives in `MUTATION_TESTING.md` (to be added).
- **Visual regression.** Not yet adopted; deferred to a follow-up.
- **Internationalisation.** The SPA is English-only today; i18n testing is deferred until i18n itself is added.
- **Accessibility — full WCAG audit.** axe-core gating in E2E is in scope (H4 implicitly, plus a dedicated a11y sweep across all pages); a manual WCAG audit is deferred.
