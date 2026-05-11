# Security testing

The wallet API handles money, identity (JWT), authorisation (PIN), and an audit trail. Each of those is an asset an attacker would target. This doc describes the layered defences and how each layer is tested.

The matching strategy targets are in [`TEST_STRATEGY.md`](./TEST_STRATEGY.md).

---

## Threat model (short)

| Asset | Threat | Mitigation | Where covered |
|---|---|---|---|
| Wallet balance / ledger | Cross-user data leak, unauthorised transfer | Per-request owner check on every wallet operation; JWT-only auth; CORS allow-list | `CrossUserIsolationSecurityTest`, integration tests, ZAP baseline |
| JWT token | Tampering, forgery, replay after revocation | HMAC-SHA512 signing, `exp` claim, fail-fast key derivation | `JwtSecurityTest` (8 tests covering tamper / forge / `alg:none` / expired / missing / wrong-prefix) |
| User PIN | Brute force | 3-strike lockout (documented) | `RateLimitingSecurityTest` (currently a characterisation — see issue [#9](https://github.com/jeffgicharu/wallet-api/issues/9)) |
| Audit log | Sensitive-data leak in stored records | Only metadata persisted, no raw passwords / PINs | `SensitiveDataSecurityTest.auditLogDoesNotPersistRawPasswordOrPin` |
| Idempotency keys | Replay, double-charge | Reject on duplicate (currently 409; see issue [#10](https://github.com/jeffgicharu/wallet-api/issues/10)) | `WalletIntegrationTest`, `TransferIntegrationTest` |
| API surface | Injection, mass assignment, XSS | Spring Data JPA parameterisation, Jackson default `setterless` rejection, React-side escaping | `InjectionSecurityTest` (5 tests) |

## Tooling overview

| Tool | Layer | Where | Why |
|---|---|---|---|
| **Spotbugs + find-sec-bugs** | SAST (Java-native) | `mvn verify -B` — every PR | Catches insecure patterns the compiler doesn't (CSRF, default-charset crypto, mutable accessors). Bound to the `verify` phase so it gates merge. |
| **GitHub CodeQL** | SAST (semantic) | `.github/workflows/codeql.yml` — PR + push + weekly | Different rule set from Spotbugs; queries auto-update. SARIF uploads to the Security tab. |
| **OWASP Dependency Check** | SCA (Maven deps) | `.github/workflows/ci.yml` `security-scan` job | Already existed; flags CVEs by CVSS ≥ 9. |
| **Snyk** | SCA (Maven, container-aware) | `.github/workflows/dependency-snyk.yml` — PR + weekly | Better signal on Java transitives; requires `SNYK_TOKEN` repo secret — no-ops gracefully without one. |
| **Trivy (fs + image)** | SCA + container | `.github/workflows/container-scan.yml` — PR + daily | Catches base-image CVEs and IaC misconfigs; SARIF uploads. |
| **OWASP ZAP** | DAST (live origin) | `.github/workflows/dast-zap.yml` — weekly baseline + manual active | The only tool that sees what an external attacker sees. |
| **Custom security tests** | Behavioural | `mvn verify -B` — every PR | JUnit + Spring Boot integration tests covering JWT, isolation, injection, rate limiting, sensitive data, BCrypt cost. |

## Where each tool runs

| Trigger | Tools |
|---|---|
| Every PR | Spotbugs, custom suite, CodeQL, OWASP DepCheck, Snyk (if token), Trivy fs + image |
| Push to `main` | Same as PR |
| Daily 02:00 UTC | Trivy (catches new CVE-database entries) |
| Weekly Sunday 04:00 UTC | ZAP baseline against live origin |
| Weekly Sunday 05:00 UTC | Snyk |
| Weekly Sunday 06:00 UTC | CodeQL |
| Manual workflow_dispatch | ZAP API active scan **only** |

## Reading findings

- **Spotbugs** — output in console and `target/spotbugs/`. The `mvn verify -B` exit code tells you whether anything HIGH unsuppressed is left. Exclusions live in `spotbugs-exclude.xml` with a justification comment per entry.
- **CodeQL, Snyk, Trivy** — SARIF uploads land in the GitHub **Security** tab → Code scanning alerts. Each alert links back to the workflow run that found it.
- **OWASP DepCheck** — HTML report uploaded as an artifact (`owasp-dependency-check-report`) per CI run.
- **ZAP** — HTML + JSON reports uploaded as artifacts; the action also opens a tracking issue if anything HIGH+ surfaces.
- **Custom security tests** — surefire output in CI; failures point at the test name and the assertion.

## Triage SLO

| Severity | Acknowledge | Resolve |
|---|---|---|
| CRITICAL | < 1 hour | < 24 hours |
| HIGH | < 24 hours | < 7 days |
| MEDIUM | < 7 days | < 30 days |
| LOW / INFO | best-effort | next planned change |

Filed today as performance-or-security backlog: [#9](https://github.com/jeffgicharu/wallet-api/issues/9) (PIN lockout rollback), [#10](https://github.com/jeffgicharu/wallet-api/issues/10) (idempotency 409), [#15](https://github.com/jeffgicharu/wallet-api/issues/15) (login throughput / bcrypt), [#19](https://github.com/jeffgicharu/wallet-api/issues/19) (`DM_DEFAULT_ENCODING`), [#20](https://github.com/jeffgicharu/wallet-api/issues/20) (cross-user txn lookup), [#21](https://github.com/jeffgicharu/wallet-api/issues/21) (login rate limit), [#22](https://github.com/jeffgicharu/wallet-api/issues/22) (22 HIGH+ dependency CVEs).

## DAST safety guard

The live origin (`wallet-api.jeffgicharu.com`) is in active demo use. ZAP can crater it if pointed at it carelessly — `zap-api-scan` with default settings will fire active payloads at every endpoint until it hits a 500.

The `dast-zap.yml` workflow enforces:

1. **Automatic schedule runs the BASELINE scan only.** Baseline is passive — it crawls and inspects responses, never sends an attack payload.
2. **The ACTIVE / API scan is `workflow_dispatch`-only** with an explicit input parameter. There is no path by which an active scan runs without a human clicking "Run workflow" and confirming.
3. Both modes carry `-d 5` (5 requests / second) so even an active scan does not exceed casual-traffic throughput.

The relevant guard in `dast-zap.yml`:

```yaml
zap-api-active:
  if: github.event_name == 'workflow_dispatch' && github.event.inputs.scan_type == 'api'
```

If you are reviewing a PR that loosens this guard, reject it.

## Running ZAP locally

```bash
mkdir -p security/zap
docker run --network=host --rm \
  -v "$PWD/security/zap:/zap/wrk/:rw" -u root \
  ghcr.io/zaproxy/zaproxy:stable \
  zap-baseline.py \
    -t https://wallet-api.jeffgicharu.com \
    -r api-baseline-report.html \
    -J api-baseline-report.json \
    -d 5
```

Reports land in `./security/zap/` (gitignored). The first baseline against the live origin on this branch reported **0 FAILs, 1 WARN, 66 PASS** — the warn is `Non-Storable Content` on the bare `/` path, which returns 403 by design via the `default-block` nginx server-name. Nothing to action.

## OWASP Top 10 mapping

Which test / tool covers which OWASP category:

| OWASP 2021 | Coverage |
|---|---|
| A01: Broken Access Control | `CrossUserIsolationSecurityTest` + integration tests + Issue [#20](https://github.com/jeffgicharu/wallet-api/issues/20) + admin-role characterisation in `WalletIntegrationTest` (issue [#2](https://github.com/jeffgicharu/wallet-api/issues/2)) |
| A02: Cryptographic Failures | `JwtSecurityTest` (tampering / `alg:none` / wrong-secret / expired), `BcryptCostSecurityTest`, Spotbugs `DM_DEFAULT_ENCODING` (issue [#19](https://github.com/jeffgicharu/wallet-api/issues/19)) |
| A03: Injection | `InjectionSecurityTest` (SQL in path + body, XSS, mass assignment, very-long input) |
| A04: Insecure Design | Threat-model table above + per-PR review checklist in `QA_BEST_PRACTICES.md` |
| A05: Security Misconfiguration | Spotbugs + CodeQL + Trivy image scan (base-image misconfig) |
| A06: Vulnerable & Outdated Components | OWASP DepCheck, Snyk, Trivy fs (issue [#22](https://github.com/jeffgicharu/wallet-api/issues/22) tracks current backlog) |
| A07: Identification & Authentication Failures | `JwtSecurityTest` + `RateLimitingSecurityTest` + issue [#21](https://github.com/jeffgicharu/wallet-api/issues/21) (no login rate limit) + issue [#9](https://github.com/jeffgicharu/wallet-api/issues/9) (PIN lockout) |
| A08: Software & Data Integrity Failures | Pact contract verification (PR #13) covers consumer expectations |
| A09: Security Logging & Monitoring Failures | `SensitiveDataSecurityTest.auditLogDoesNotPersistRawPasswordOrPin` + issue [#12](https://github.com/jeffgicharu/wallet-api/issues/12) (reversals not audited) |
| A10: Server-Side Request Forgery | Not currently applicable — no outbound HTTP from the application. Re-evaluate when a payment-gateway integration is added. |

## How to extend

1. New security test: place under `src/test/java/com/wallet/security/suite/`, extend `SecurityTestBase`, follow the BDD naming convention from `QA_BEST_PRACTICES.md`.
2. New tool: add a workflow under `.github/workflows/` and update the table in this doc + the OWASP mapping if applicable.
3. New finding: file as a GitHub issue with the `security` label. If the gap can be characterised today, add a test that pins the current behaviour and link the issue from the test's Javadoc.

## File layout

```
.github/workflows/
├── ci.yml                          (Spotbugs via mvn verify; OWASP DepCheck job)
├── codeql.yml                      (CodeQL SAST)
├── dependency-snyk.yml             (Snyk, optional via SNYK_TOKEN)
├── container-scan.yml              (Trivy fs + image)
└── dast-zap.yml                    (ZAP baseline weekly + manual active)

src/test/java/com/wallet/security/suite/
├── SecurityTestBase.java
├── JwtSecurityTest.java
├── CrossUserIsolationSecurityTest.java
├── InjectionSecurityTest.java
├── RateLimitingSecurityTest.java
├── SensitiveDataSecurityTest.java
└── BcryptCostSecurityTest.java

spotbugs-exclude.xml                (each entry has a justification comment)
security/                           (gitignored — local scan output)
```
