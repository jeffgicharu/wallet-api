# Test Strategy — Wallet System

This document defines the test strategy for the **Wallet System**: the Spring Boot API at `wallet-api.jeffgicharu.com` and the React SPA at `wallet.jeffgicharu.com`. It is the single source of truth that subsequent quality work — coverage ramp, mutation testing, performance, security automation — answers to.

It pairs with [`TEST_PLAN.md`](./TEST_PLAN.md) (the highest-value workflow, broken into runnable scenarios) and [`QA_BEST_PRACTICES.md`](./QA_BEST_PRACTICES.md) (review and authoring conventions).

---

## 1. Purpose and scope

| Item | Value |
|---|---|
| **System under test** | Wallet System — `wallet-api` (Spring Boot 3.2.5 / Java 17 / PostgreSQL 16) + `wallet-app` (React 19 / TypeScript / Vite). |
| **Live deployment** | `https://wallet-api.jeffgicharu.com` (API), `https://wallet.jeffgicharu.com` (SPA). State resets daily 03:00 UTC. |
| **Out of scope here** | Internal tooling, build pipelines, infrastructure-as-code. Those have their own docs. |
| **Audiences** | Engineers writing or reviewing changes; future contributors; reviewers of the quality work itself. |

The strategy answers four questions every change should be measured against:

1. *Where does this fit on the test pyramid?*
2. *What guarantee does it create that we did not have yesterday?*
3. *How fast does it run, and where in CI?*
4. *Who owns the failure when it breaks?*

## 2. Testing philosophy

Five principles. Everything else follows.

### 2.1 Test behaviour, not implementation

A test that pins method names, mock-call orderings, or private internals turns refactoring into a tax. Tests assert observable behaviour: HTTP responses, database state changes, rendered output, fired toasts. If a refactor preserves behaviour, every test must still pass.

### 2.2 Integration over heavy-mock units (especially for JPA)

A `WalletService` test that mocks the repository and asserts `verify(repo).save(any())` is faster but proves almost nothing. The same logic exercised through `@SpringBootTest` against a Testcontainers Postgres catches Hibernate-flush surprises, optimistic-locking conflicts, dialect quirks, and the actual transaction boundary. Trade some seconds for that signal.

### 2.3 Tests are documentation

A reader who has never seen the codebase should understand what each test is asserting from the test name and the first six lines of the body. BDD-style naming (`shouldRejectTransferWhenDailyLimitExceeded`), explicit Given/When/Then comment scaffolding, no clever fixture inheritance.

### 2.4 Fast feedback loops

A failing test discovered in CI 18 minutes after push is roughly 18× more expensive than one discovered locally before push. Targets:

- Unit suite (Java + TS): under 60 seconds.
- Integration suite (Java with Testcontainers): under 5 minutes.
- Full PR pipeline (lint + unit + integration + contract + security scans + container build): under 12 minutes.

### 2.5 Real services in containers, not mocks at the boundary

Mock at the boundary of *paid external services* (Twilio, Stripe, an SMS gateway) — boundaries we cannot stand up cheaply. For the boundary between the application and its own database, message bus, or cache, use Testcontainers. H2 is acceptable for unit tests of pure logic; it is not acceptable for "this query works against production Postgres."

### 2.6 Cross-repo contracts catch what no unit test can

Two repos ship independently. A breaking shape change in `walletApi.deposit()` request body cannot be caught by either repo's unit tests in isolation — only by a contract that both sides agree on, recorded by the consumer (`wallet-app`) and verified by the provider (`wallet-api`) in CI before merge.

## 3. Test pyramid for the Wallet System

The pyramid has the conventional layers, but the tools are pinned per-stack so reviewers know the right answer when proposing a new test.

### 3.1 Unit

Pure functions, helpers, isolated logic. Mocks acceptable here when the unit truly is one unit.

| Stack | Tools | Examples |
|---|---|---|
| Java (`wallet-api`) | JUnit 5, AssertJ, Mockito | `WalletService` fee maths, `JwtTokenProvider` parse / validate, `TransactionType` enum mapping. |
| TypeScript (`wallet-app`) | Vitest | `formatCurrency`, `parseAmount` helpers, `useAuth` hook reducer, anywhere a pure function or hook can be exercised without rendering. |

### 3.2 Integration

Real Spring context, real database, real HTTP for the Java side; isolated to the API for now.

| Stack | Tools | Examples |
|---|---|---|
| Java (`wallet-api`) — controller slice | `@SpringBootTest`, RestAssured / `TestRestTemplate`, Testcontainers Postgres | Full register → login → deposit → check balance through HTTP, against a real Postgres container. |
| Java (`wallet-api`) — repository slice | `@DataJpaTest`, Testcontainers Postgres | `WalletRepository` query methods, optimistic-locking under concurrent updates, native queries (when added). |
| TypeScript (`wallet-app`) | _N/A — covered by component + E2E layers below_ | The SPA has no separate "integration" tier; component tests with MSW and Playwright E2E together cover the integration shape. |

### 3.3 Contract — `wallet-app` ↔ `wallet-api`

Consumer-driven Pact. The frontend writes pacts during its own component tests; the API verifies them in its own pipeline.

| Tool | Where |
|---|---|
| Pact-JVM (provider verification) | `wallet-api` PR pipeline, before integration tests. |
| `@pact-foundation/pact` (consumer side) | `wallet-app` PR pipeline, alongside component tests. |

### 3.4 Component (SPA only)

| Stack | Tools | Examples |
|---|---|---|
| TypeScript (`wallet-app`) | React Testing Library, Vitest, MSW for API mocking, jest-axe for inline a11y assertions | `LoginPage` form validation, `PinPad` keypress handling, `SendPage` multi-step wizard navigation, `HistoryPage` transaction list rendering, error toast behaviour. |

### 3.5 End-to-end

Real browser, real TLS, real money flow.

| Tool | Where |
|---|---|
| Playwright (Chromium + WebKit + Firefox) | PR pipeline against a `docker compose`-stood-up local stack; nightly smoke run against the deployed origins. |

### 3.6 Accessibility

| Tool | Where |
|---|---|
| `@axe-core/playwright` | Inside the same Playwright suite — every E2E flow asserts zero serious / critical violations. |

### 3.7 Performance

Three flavours, three tools, three different windows.

| Tool | Style | When |
|---|---|---|
| JMeter (existing `.jmx`) | Real-traffic threading, GUI-friendly | Nightly against staging or live origin. |
| Gatling (Maven plugin) | In-process, scenario-driven, Scala / Java DSL | PR pipeline as a smoke profile (small load, quick) plus nightly stress profile. |
| K6 | JS-scripted, distributed-friendly | Nightly against the live origin to capture real-network latency. |

Smoke / load / stress / spike are kept as separate profiles; results write to a shared dashboard rather than being compared by eye.

### 3.8 Security

| Layer | Tool | When |
|---|---|---|
| SAST (Java) | Spotbugs Maven plugin + GitHub CodeQL | PR pipeline. |
| SAST (TypeScript) | GitHub CodeQL | PR pipeline. |
| Dependency scanning (Java) | OWASP Dependency Check (already in CI) + Snyk | PR + nightly. |
| Dependency scanning (npm) | `npm audit --audit-level=moderate` + Snyk | PR + nightly. |
| Container | Trivy filesystem + Trivy image | PR (on image build). |
| DAST | OWASP ZAP baseline + full-active | Nightly against `wallet-api.jeffgicharu.com`. |
| Custom security suite | RestAssured-based: auth-bypass attempts, JWT manipulation (none-alg, swapped-key), idempotency abuse, race conditions on the same wallet | PR pipeline as part of integration. |

### 3.9 Mutation

| Stack | Tool | When |
|---|---|---|
| Java | PIT (`pitest-maven`) | Nightly, scoped to `com.wallet.service` first, expanding outward. |
| TypeScript | Stryker | Nightly. |

## 4. Coverage targets

These are the numbers we are building towards, not the numbers we have today. Today's baseline lives in [`AUDIT.md`](./AUDIT.md).

| Metric | Target | Today (from `AUDIT.md`) |
|---|---|---|
| `wallet-api` line coverage | ≥ 80 % | 46 % (JaCoCo `check` rule pinned at 40 %) |
| `wallet-api` branch coverage | ≥ 70 % | 9 % |
| `wallet-app` line coverage on stateful components and forms | ≥ 70 % | 0 % |
| PIT mutation kill-rate on `com.wallet.service` | ≥ 70 % | not measured |
| Stryker mutation score on `wallet-app` `src/components` and `src/pages` | ≥ 65 % | not measured |

The line-coverage targets are floors, not ceilings. Mutation kill-rate is the honest signal — high line coverage with a low kill-rate means tests assert "code ran" rather than "code did the right thing."

## 5. CI quality gates

| Gate | wallet-api | wallet-app | Blocking? |
|---|---|---|---|
| Lint | `mvn` build catches syntax, Spotbugs catches a class of bugs | `npm run lint` (currently configured but not invoked) | Yes |
| Unit + integration | `mvn verify` | `npm test` (Vitest) | Yes |
| Contract | Pact provider verification | Consumer pact generation in component tests | Yes |
| Coverage threshold | JaCoCo `check` rule (40 % today, ramping per § 4) | Vitest coverage with `c8` (when introduced) | Yes |
| Mutation score | PIT report under `target/pit-reports/` | Stryker report | Nightly only — non-blocking but tracked |
| Security — dependencies | OWASP DepCheck (currently non-blocking, fail at CVSS ≥ 9) | `npm audit` | Yes once advisories cleared |
| Security — SAST | Spotbugs + CodeQL | CodeQL | Yes for new findings |
| Security — container | Trivy on built image | Trivy on built image | Yes for HIGH/CRITICAL |
| Security — DAST | OWASP ZAP nightly | _SPA hits API; covered by API DAST_ | Tracked, not blocking |
| Performance | JMeter / Gatling smoke profile | Lighthouse on built dist | Tracked, not blocking |

## 6. Non-functional targets

Money correctness over speed. The latency budgets are deliberately generous on the write path so the system never trades correctness for throughput.

| Target | Value |
|---|---|
| API p95 latency — read (`GET /api/wallet`, `GET /api/wallet/transactions`) | < 200 ms |
| API p95 latency — write money-moving (`/deposit`, `/withdraw`, `/transfer`) | < 400 ms |
| API error rate under nominal load | < 0.1 % |
| Reconciliation — debits = credits | Exact equality, asserted by the reconciliation endpoint and a CI smoke job |
| Dependency vulnerability budget | Zero `CRITICAL`, max 5 `HIGH` outstanding for more than 7 days |
| `wallet-app` Lighthouse performance score | ≥ 85 (mobile profile) |
| `wallet-app` total bundle size | ≤ 500 kB ungzipped (currently 392 kB) |
| `wallet-app` accessibility — axe-core serious / critical findings | Zero |

## 7. Tooling inventory

This is the canonical list. Every test, scan, or report in either repo lives here. Owner is the team that responds to a failure first.

| # | Tool | Layer | Stack | Where it runs | Owner |
|---|---|---|---|---|---|
| 1 | JUnit 5 + AssertJ + Mockito | Unit | api | pre-commit, PR | api team |
| 2 | Vitest | Unit + component | web | pre-commit, PR | web team |
| 3 | SpringBootTest + RestAssured + `@DataJpaTest` | Integration (controller and repository slices) | api | PR | api team |
| 4 | Testcontainers (Postgres 16) | Integration support | api | PR | api team |
| 5 | React Testing Library | Component | web | PR | web team |
| 6 | MSW (Mock Service Worker) | Component (API mocking) | web | PR | web team |
| 7 | Pact-JVM (provider) + `@pact-foundation/pact` (consumer) | Contract | both | PR | both teams, jointly |
| 8 | Playwright | E2E + a11y harness | web (drives api) | PR (docker-compose), nightly (live origin) | web team |
| 9 | `@axe-core/playwright` | Accessibility | web | PR (inside Playwright) | web team |
| 10 | JaCoCo | Coverage | api | PR (CI gate) | api team |
| 11 | PIT (`pitest-maven`) | Mutation | api | nightly | api team |
| 12 | Stryker | Mutation | web | nightly | web team |
| 13 | JMeter | Performance | api | nightly (live origin) | api team |
| 14 | Gatling Maven plugin | Performance | api | PR (smoke), nightly (stress / spike) | api team |
| 15 | K6 | Performance | api | nightly (live origin) | platform team |
| 16 | Spotbugs | SAST | api | PR | api team |
| 17 | GitHub CodeQL | SAST | both | PR | platform team |
| 18 | OWASP Dependency Check | SCA | api | PR | api team |
| 19 | `npm audit` (and `pnpm audit` when migrated) | SCA | web | PR | web team |
| 20 | Snyk | SCA + container helper | both | nightly | platform team |
| 21 | Trivy | Container scan + filesystem scan | both | PR (image build) | platform team |
| 22 | OWASP ZAP (baseline + active) | DAST | api | nightly (live origin) | platform team |

Twenty-two rows; every row maps to a gate in §5 or a target in §6. New tools that don't earn a row don't enter the system.

## 8. Glossary

- **Pyramid layer** — Where on §3 a given test sits.
- **Quality gate** — A CI check whose failure blocks merge (or, for nightly checks, surfaces in the dashboard rather than blocking).
- **Live origin** — `wallet-api.jeffgicharu.com` and `wallet.jeffgicharu.com`. Tests against the live origin run nightly only, never on every push.
- **Smoke / load / stress / spike** — Performance profiles. Smoke is "nothing crashed under tiny load." Load is "p95 holds at expected traffic." Stress is "find the breaking point." Spike is "what happens when traffic doubles in a minute."
- **Provider / consumer** — In the Pact sense. `wallet-api` is the provider; `wallet-app` is the consumer.
