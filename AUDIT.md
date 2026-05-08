# Quality baseline — wallet-api

A snapshot of what exists today, what's known to work, and where the gaps are. The aim is to give every follow-up change a single page to anchor against, so we can argue "we added X" or "we fixed Y" against a fixed baseline rather than a moving target.

---

## Stack and versions

| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.5 |
| Build | Maven | 3.9.x (no `./mvnw` wrapper checked in) |
| Persistence | Spring Data JPA on Hibernate | bundled with Boot 3.2.5 |
| Database | PostgreSQL 16 (prod) / H2 in-memory (dev, tests) | 16.11 on the deploy host |
| Auth | Spring Security + jjwt | jjwt 0.12.5 |
| API docs | springdoc-openapi | 2.5.0 |
| Observability | Micrometer Prometheus registry, OTel exporter | bundled with Boot 3.2.5 |
| Boilerplate | Lombok | bundled |

Live deployment: `https://wallet-api.jeffgicharu.com` (running on a 1.9 GiB VPS behind nginx + Cloudflare; capped at `-Xmx256m -XX:+UseSerialGC`). Detailed deployment notes are kept locally and not committed; see PR #1 (`feat/env-driven-configuration`) for the env-driven configuration that this audit assumes the reader has skimmed.

## Architecture

Single Maven module, single Spring Boot application. Source organised into nine packages under `com.wallet`:

| Package | Files | Responsibility |
|---|---|---|
| `config` | 3 | Spring configuration: `SecurityConfig` (JWT filter chain), `OpenApiConfig` (Swagger metadata), `MetricsConfig` (Micrometer tags). |
| `controller` | 3 | HTTP entry points: `AuthController` (`/api/auth/**`), `WalletController` (`/api/wallet/**`), `AdminController` (`/api/admin/**`). |
| `dto` | 9 | Request and response payloads, split into `dto.request` and `dto.response`; `ApiResponse<T>` is the envelope wrapper. |
| `entity` | 5 | JPA entities: `User`, `Wallet`, `Transaction`, `LedgerEntry`, `AuditLog`. |
| `enums` | 3 | Domain enums: `TransactionType`, `TransactionStatus`, `EntryType`. |
| `exception` | 4 | Custom checked exceptions (`InsufficientBalanceException`, `InvalidPinException`, `DuplicateTransactionException`) and a centralised `GlobalExceptionHandler`. |
| `repository` | 5 | Spring Data JPA repositories — one per entity. |
| `security` | 3 | `JwtAuthenticationFilter`, `JwtTokenProvider`, `UserDetailsServiceImpl`. |
| `service` | 3 | Business logic: `AuthService`, `WalletService`, `AuditService`. |

Public HTTP surface (10 user endpoints + 9 admin endpoints) is documented in the README and exposed via Swagger at `/swagger-ui.html`. Persistence is double-entry: every money-moving operation produces both a `Transaction` and a pair of `LedgerEntry` rows that the reconciliation endpoints aggregate.

## Build and run

Verified on this baseline:

```bash
mvn clean verify -B          # build + tests + JaCoCo report + JaCoCo check
mvn spring-boot:run          # local dev — H2 in memory, Swagger on :8080
docker compose up            # local dev with PostgreSQL
```

`mvn clean verify -B` completes in roughly 1 m on a developer laptop, runs all 21 tests, produces the JaCoCo HTML report under `target/site/jacoco/`, and enforces a 40 % line-coverage threshold via `jacoco:check`.

The bundled Docker image is multi-stage: `maven:3.9-eclipse-temurin-17` for build, `eclipse-temurin:17-jre-alpine` for runtime. There are also Helm and k8s manifests under `helm/` and `k8s/`, currently unused by the live deployment (which runs the fat-jar directly under systemd).

## Live deployment context

The application is reachable at `https://wallet-api.jeffgicharu.com`. This matters for the audit because subsequent quality work can target an actual hosted instance rather than a localhost sandbox:

- DAST scans (OWASP ZAP, Nuclei) can run against the deployed origin without exposing local dev servers.
- Performance plans (JMeter, Gatling, K6) can push load against an origin that already has nginx, TLS, Cloudflare, and a real Postgres in the loop.
- Contract tests can be wired up so the pact verifier replays consumer pacts against the live provider in CI.

State on the box resets daily at 03:00 UTC via `/opt/wallet-api/seed-demo.sh`, so noisy probes can run overnight without permanently corrupting the demo accounts.

## Existing tests

Two test classes, twenty-one tests, all in `src/test/java/com/wallet/`:

### `service.WalletServiceTest` — 9 unit tests, ~232 lines

Mockito-based tests against `WalletService` with mocked repositories. Coverage:

- `deposit_success_increasesBalance`
- `withdraw_validPin_decreasesBalance`
- `withdraw_invalidPin_throws`
- `withdraw_insufficientBalance_throws`
- `transfer_success_movesMoneyAndChargesFee`
- `transfer_duplicateIdempotencyKey_returnsOriginal`
- `transfer_toSelf_throws`
- `transfer_unknownRecipient_throws`
- one balance / state assertion test

These cover the happy path and the most obvious negative paths for the three money-moving operations. They do not exercise daily-limit enforcement, frozen-wallet guard, optimistic-locking conflicts, or the transaction-reversal flow.

### `integration.WalletIntegrationTest` — 12 tests, ~227 lines

Spring Boot test slice (`@SpringBootTest` with H2 in memory) running real Tomcat, real `JwtAuthenticationFilter`, real JPA, real `WalletService`. Endpoints exercised through `MockMvc`. Coverage:

- Full registration → login → use flow
- Duplicate registration rejected
- Deposit through HTTP, balance verified after
- Transfer through HTTP, balance change on both sides
- Unauthenticated request rejected (401)
- Transaction lookup by reference
- Deposit reversal with balance verification
- Admin stats endpoint
- Wallet freeze blocking transactions
- Per-wallet reconciliation
- Wrong PIN through HTTP
- Daily transfer-limit information in `/api/wallet` response

This slice is the strongest part of the existing test surface — it covers JWT, security filter, JPA flush, and HTTP serialisation in one path.

### JMeter plan — `src/test/jmeter/wallet-api-load-test.jmx`

Single test plan, two thread groups:

1. Auth flow: 50 threads, 10-second ramp-up, 60-second duration, register-then-login per iteration with response-time assertions (≤ 2 s for register, ≤ 1 s for login).
2. Wallet operations: 100 threads sustained for 2 minutes — login, balance, deposit, transactions list — with assertions (≤ 500 ms for balance, ≤ 1 s for deposit).

Variables `BASE_URL` and `PORT` default to `localhost:8080`; the plan can be retargeted at the live origin via `-JBASE_URL=wallet-api.jeffgicharu.com -JPORT=443` plus the HTTPS protocol switch (currently the plan hard-codes HTTP — see Gaps below).

## Existing CI

`.github/workflows/ci.yml` defines three jobs:

| Job | Triggers | What runs |
|---|---|---|
| `build-and-test` | push & PR to `main` | `actions/setup-java@v4` → `mvn verify -B` → upload JaCoCo HTML as artifact → `docker build -t wallet-api:${SHA} .`. |
| `security-scan` | depends on build-and-test | `mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=9 -DsuppressionFile=owasp-suppressions.xml \|\| true` (non-blocking) → upload OWASP HTML report. |
| `sonarcloud` | push events only | `mvn verify sonar:sonar` against SonarCloud (skips silently if `SONAR_TOKEN` is missing). |

No deploy job, no integration with a container registry, no contract test job, no mutation testing, no DAST, no SAST beyond what SonarCloud catches.

## Coverage baseline

From the JaCoCo report on a clean `mvn clean verify -B` against `main`:

| Counter | Covered | Total | Coverage |
|---|---|---|---|
| Instructions | 2 657 | 5 755 | **46 %** |
| Branches | 48 | 512 | **9 %** |
| Lines | 478 | 661 | **72 %** (per Lines counter) |
| Methods | 267 | 450 | **59 %** |
| Classes | 43 | 43 | **100 %** |

The bundle line-coverage figure that the JaCoCo `check` rule enforces is `coveredratio LINE` ≥ 0.40, currently sitting just above that floor.

By package:

| Package | Line coverage |
|---|---|
| `com.wallet.config` | **100 %** (3 classes) |
| `com.wallet.enums` | **100 %** (3 classes) |
| `com.wallet.security` | **94 %** (3 classes) |
| `com.wallet.service` | **71 %** (3 classes) |
| `com.wallet.entity` | **57 %** (10 classes — Lombok-generated equals / hashCode pulls this down) |
| `com.wallet.exception` | **42 %** (4 classes) |
| `com.wallet.controller` | **41 %** (3 classes) |
| `com.wallet.dto.response` | **26 %** (8 classes) |
| `com.wallet.dto.request` | **14 %** (5 classes) |

Branch coverage is the more honest signal at 9 %: for every covered statement there are roughly nine uncovered conditional branches, which is what we'd expect when most negative paths are not exercised.

## Identified gaps

### Test surface

- **Zero tests in `controller`, `dto`, `repository`, `security`, and `exception`.** The integration test exercises some of these transitively, but there is no targeted controller slice (`@WebMvcTest`), no repository slice (`@DataJpaTest`), no `JwtTokenProvider` unit test, and no `GlobalExceptionHandler` mapping test.
- **No mutation testing.** PIT is not declared in `pom.xml`. With branch coverage at 9 % the real signal of test strength is unknown — high line coverage with low branch coverage and no mutation kill-rate usually means tests assert "code ran" rather than "code did the right thing."
- **No contract tests.** The wallet-app consumes this API but neither side records or verifies a Pact. A schema-shape change could ship green and break the frontend the next time it's redeployed.
- **No Spotbugs / SAST beyond SonarCloud.** SonarCloud catches some bugs and code smells; Spotbugs would catch a different (and larger) class of low-level Java bugs (null-deref, resource leaks, integer-overflow patterns).
- **No Testcontainers.** Integration tests run on H2, which differs from PostgreSQL in subtle ways: `ddl-auto: update` quirks, dialect-specific SQL, transaction isolation behaviour, optimistic-locking with `@Version`, JSON / array column types if they're ever introduced. A Testcontainers-backed Postgres slice would catch these.

### Performance

- **Single JMeter plan, no clear separation between smoke, load, stress, and spike.** The current plan blends three thread groups; results are aggregated. There is no smoke profile that runs in CI on every push, no stress profile that pushes until the SLO breaks, no spike profile that simulates a traffic burst.
- **Plan hard-codes HTTP.** Pointing it at the live `https://wallet-api.jeffgicharu.com` requires editing the plan; ought to be a property override.
- **No Gatling or K6 plan**, so there's no in-process latency / concurrency model alongside JMeter's real-traffic model. Both have a place; today we have one.
- **No automated SLO assertions tied to CI.** The plan's response-time assertions are local; nothing surfaces a regression as a failed pipeline.

### Security

- **No DAST against the deployed instance.** OWASP ZAP, Nuclei, or even a curated `nikto` run could be wired up against `https://wallet-api.jeffgicharu.com` to catch baseline web-security misses.
- **No Trivy or Snyk scan in CI.** OWASP DepCheck catches CVE-published Maven dependencies; it does not scan the Docker base image, the Dockerfile itself, or transitive npm-style ecosystem chains. Trivy on the built image plus Snyk on `pom.xml` would close that.
- **Findings already filed as issues:**
  - **#2** — `AdminController` endpoints accessible to any authenticated user (no role check). Severity: HIGH.
  - **#3** — Production profile uses `ddl-auto: update` instead of a migration tool.
  - **#4** — Default Spring profile contains a hard-coded JWT secret.
  - **#5** — `spring.jpa.open-in-view=true` triggers warning; recommend explicit transaction boundaries.

### Reporting and observability

- **No quality dashboard.** JaCoCo, OWASP, and (future) PIT, Spotbugs, ZAP all produce HTML or JSON; nothing aggregates them into a single page that says "where are we today".
- **No accessibility considerations** (this is an API, so out of scope for a11y; flagged for completeness so the same audit pattern applied to the frontend is consistent).

## Tooling on the developer machine

Pre-flight verification of every external tool the next phase is likely to want:

| Tool | Verified | Notes |
|---|---|---|
| PIT mutation plugin | `mvn org.pitest:pitest-maven:help -B` → BUILD SUCCESS | Resolves and runs help. Not yet declared in `pom.xml`. |
| Spotbugs Maven plugin | `mvn com.github.spotbugs:spotbugs-maven-plugin:help -B` → BUILD SUCCESS | Resolves. Not yet declared in `pom.xml`. |
| Gatling Maven plugin | `mvn io.gatling:gatling-maven-plugin:help -B` → BUILD SUCCESS | Resolves. Not yet declared in `pom.xml`. |
| Pact-JVM provider plugin | `mvn dependency:get -Dartifact=au.com.dius.pact.provider:maven:4.6.7 -B` → BUILD SUCCESS | Plugin downloads cleanly. The plugin does not expose a `:help` goal, so the canonical `mvn ...:help` invocation returns `MojoNotFoundException` — this is a property of the plugin, not a missing artifact. Treat the `dependency:get` form as the verification command. |
| Testcontainers | _not in `pom.xml`_ | Listed in this audit's Gaps section as an item to add. |
| JMeter | `~/.local/opt/apache-jmeter-5.6.3/bin/jmeter --version` → 5.6.3 | Installed during the deployment recon; not on `PATH`. |
| K6 | `k6 version` → v1.7.1 | Globally installed. |
| Docker | `docker --version` → 29.1.3 | Globally installed; needed for container scans and ZAP. |
| Trivy | `trivy --version` → "dev" build | Globally installed (Debian package). Vulnerability DB v2 present. |
| Snyk CLI | `snyk --version` → 1.1304.2 | Globally installed (npm). Auth happens at first scan invocation. |
| GitHub CLI | `gh --version` → 2.85.0 | Globally installed and authenticated. |

## What this audit is not

It does not propose fixes, and it does not change any code. Its only commits are this `AUDIT.md` and the matching audit on the wallet-app side.

The four issues filed against this repo (#2 through #5) capture findings that surfaced during the live deployment but are out of scope for the deployment PR; future work picks them up under their own labels (`security`, `tech-debt`, `database`, `bug`, `api`).
