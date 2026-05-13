# QA Best Practices — Wallet System

How we author, review, and maintain tests across `wallet-api` and `wallet-app`. This is the operational counterpart to [`TEST_STRATEGY.md`](./TEST_STRATEGY.md) (what we test) and [`TEST_PLAN.md`](./TEST_PLAN.md) (what cases we cover for the priority workflow).

---

## 1. Code review checklist

Every PR — feature, bug fix, refactor — gets reviewed against the list below before approve. Reviewers leave a single comment with the items that don't yet apply rather than 14 inline nitpicks.

1. **Tests added for new behaviour.** A new endpoint, a new state transition, a new component — there is at least one test that fails on `main` and passes on the branch.
2. **Regression test for any bug fix written first.** The commit history shows the failing test before the fix. If the test was written after the fix, the reviewer asks the author to demonstrate the test fails on the parent commit.
3. **Coverage is maintained or increased.** JaCoCo line / branch coverage on the api side does not drop; Vitest coverage on the web side does not drop. The CI gate enforces this; the reviewer reads the report.
4. **No flaky tests introduced.** New tests are run locally three times in a row before push. Tests that depend on wall-clock time, real network, or non-deterministic ordering are flagged.
5. **Test names describe behaviour.** "shouldRejectTransferWhenDailyLimitExceeded", not "test1" or "transferTest".
6. **Security claim verified.** A change that touches auth, JWT, CORS, role checks, or input validation has a test that exercises the negative path (forged token, wrong role, malformed input).
7. **No hard-coded secrets.** No JWT secrets, DB passwords, API keys in source. The reviewer greps the diff. Test fixtures use builders, not literal HMAC keys.
8. **No `.skip`, `.only`, `@Disabled`, or `@Ignore` without an issue link.** A skipped test in the diff has a comment naming the issue it is waiting on.
9. **Mocks are at the right boundary.** Unit tests may mock; integration tests do not mock the database, the security filter chain, or the controller-to-service wiring. Reviewer asks: "could this test fail if Hibernate's flush behaviour changed?"
10. **Test data uses factories, not literals.** A 50-line `User` literal at the top of every test means the next field added breaks 30 tests. `aRegisteredUser(phone)` does not.
11. **Logs and stdout silenced in tests.** No `System.out.println`, no `console.log`, no leftover `@Slf4j.debug` in test code.
12. **Documentation updated.** README, `CONFIGURATION.md`, this file, or any of the strategy docs updated alongside behaviour changes.
13. **Cross-repo impact considered.** If the change alters request / response shape, the reviewer checks whether a Pact contract exists and is regenerated. If the change is intentionally breaking, the PR description spells out the rollout order.
14. **Forbidden phrases not introduced.** A grep against the agreed list (kept in repo conventions) is clean.
15. **PR description has a Test Plan section** explaining what was verified, with checkboxes for each layer that applies.

## 2. Test naming conventions

Names are read more often than they are written. Optimise for the reader.

### 2.1 Java (JUnit 5)

BDD-flavoured `should…When…` form. The class name names the unit; the method name names the scenario.

```java
class WalletServiceTest {

    @Test
    void shouldIncreaseBalanceWhenDepositIsValid() { ... }

    @Test
    void shouldRejectTransferWhenDailyLimitExceeded() { ... }

    @Test
    void shouldLockAccountAfterThreeWrongPinAttempts() { ... }

    @Test
    void shouldReturnOriginalTransactionWhenIdempotencyKeyRepeats() { ... }
}
```

The `should` prefix lets tests double as documentation: `WalletServiceTest::shouldRejectTransferWhenDailyLimitExceeded` reads as a sentence. JUnit 5's `@DisplayName` is not used; the method name carries the meaning.

### 2.2 TypeScript (Vitest / RTL)

Two-tier `describe` / `it` form, present-tense verb, behaviour-focused.

```ts
describe('LoginForm', () => {
  it('displays an error toast when the password is wrong', async () => { ... });

  it('disables the submit button while the request is in flight', async () => { ... });

  it('navigates to home on successful login', async () => { ... });
});

describe('PinPad', () => {
  it('fires onComplete when four digits are entered', () => { ... });

  it('clears the indicator when the clear button is pressed', () => { ... });
});
```

A test file is named after the unit it tests: `LoginForm.test.tsx`, `PinPad.test.tsx`. E2E specs live under `tests/e2e/` and are named after the workflow: `onboarding-and-reversal.spec.ts`.

## 3. Test independence

A test is a contract between author and CI: "if this passes, the behaviour described in the name is correct, *no matter what tests ran before me*." Three concrete rules.

1. **No order dependency.** Every test must pass when run alone (`mvn -Dtest=ClassName#methodName`, `vitest path/to/file.test.tsx`).
2. **Fresh database per integration test class.** Testcontainers' per-class lifecycle is the default. Inside a class, `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` or an explicit `TRUNCATE` in `@BeforeEach` keeps tests independent. Tests do not assume the order JUnit happens to pick.
3. **Fresh React tree per RTL test.** `cleanup()` is called automatically by `@testing-library/react`'s `afterEach`. Component tests do not share `render()` calls across `it` blocks. Vitest's `beforeEach` resets MSW handlers.

A test that depends on a sibling test's side effects is a bug, not an optimisation.

## 4. Flaky test policy

Zero tolerance.

- A test that fails intermittently is treated as broken until proven otherwise.
- The author who introduced the test is the first responder.
- A flaky test is **immediately quarantined** (added to a `flaky` group, excluded from the main suite, opened as a `flaky` labelled issue) within 1 working day of detection.
- The fix SLO is **48 hours from quarantine to either delete or fix**. A test that is quarantined for more than 48 hours without a fix is deleted; the underlying behaviour is then re-tested with a deterministic approach.
- If three flaky tests pile up at once, the team stops feature work until the count is back to zero. This rule has teeth so the system never accumulates "we just retry that one."

## 5. Mock vs real dependencies

The default is **real**. Mock only at boundaries we cannot stand up cheaply.

| Boundary | Default | Reasoning |
|---|---|---|
| `wallet-api` ↔ Postgres | **Real** (Testcontainers) | The whole point of integration tests is catching dialect quirks, transaction-isolation surprises, and Hibernate flush behaviour. H2 is acceptable for unit tests of pure logic, never for "this works in prod." |
| `wallet-api` ↔ HTTP layer | **Real** (`@SpringBootTest` with `MOCK` web environment, RestAssured / `MockMvc`) | The security filter chain, JWT filter, and global exception handler all live here. Mocking them out tests a tree without its trunk. |
| `wallet-app` ↔ `wallet-api` (component layer) | **MSW** | Component tests should not need a Java process running. MSW lets us stub the response shape that Pact has already verified. |
| `wallet-app` ↔ `wallet-api` (E2E layer) | **Real** | Playwright drives a browser against a real running api. |
| External SaaS (Twilio, Stripe, an SMS gateway) — when added | **Mock** | Boundary we cannot stand up cheaply, and which can rate-limit / charge / page on every CI run. |

The shorthand: *if a developer can stand it up locally with one docker-compose command, do not mock it.*

## 6. Commit conventions for tests

Conventional Commits with explicit scope so test commits are searchable and CI can route them.

### Java (`wallet-api`)

| Prefix | Use |
|---|---|
| `test(api):` | Unit tests in `src/test/java/com/wallet/service` and similar. |
| `test(api-int):` | Integration tests under `src/test/java/com/wallet/integration`. |
| `test(api-contract):` | Pact provider verification tests. |
| `test(api-perf):` | Gatling / JMeter scenarios. |
| `test(api-security):` | Security-focused integration tests (auth bypass attempts, JWT manipulation). |

### TypeScript (`wallet-app`)

| Prefix | Use |
|---|---|
| `test(web):` | Unit tests for helpers / hooks under `src/__tests__/unit`. |
| `test(web-component):` | RTL tests under `src/__tests__/components`. |
| `test(web-e2e):` | Playwright specs under `tests/e2e`. |

### Examples

```
test(api-int): assert daily limit blocks the 6th transfer that crosses 300k

test(api-contract): regenerate pact for walletApi.transfer with idempotencyKey

test(web-component): pin pad clears indicator when clear is pressed

test(web-e2e): full onboarding to reversal flow
```

## 7. PR requirements

A PR cannot merge without these.

1. **Tests for new features.** A behavioural feature without a test is treated as incomplete, not "we'll add tests later." There is no later.
2. **Regression test for every bug fix, written first.** The author writes the failing test, commits it, then commits the fix. The reviewer can run the parent commit's tests and see them fail.
3. **No `.skip`, `.only`, `@Disabled`, `@Ignore`, or `xit` without a referenced issue.** A test that is currently skipped includes a comment with the issue URL and an estimated unblock date.
4. **Coverage doesn't drop.** CI gates enforce; reviewer also reads the JaCoCo / Vitest report attached to the PR run.
5. **Mutation score doesn't drop on the touched packages.** Nightly job; if a PR drops the kill-rate on `com.wallet.service` from 78 % to 70 %, the next PR is required to recover it.
6. **PR description includes a `## Test plan` section** mirroring the convention used in PR #1: a checkbox list, one item per layer that applies (build, unit, integration, contract, component, E2E, manual).

## 8. Cross-repo coordination

Two repos, one system. Most of the time they evolve independently; the moments where they touch are where bugs live.

### 8.1 Routine changes

A change that touches the API request / response shape on the wallet-api side regenerates the Pact contract on the wallet-app side. The flow:

1. Wallet-app developer writes the consumer test that records the new pact.
2. Wallet-app PR opens; CI publishes the pact to a broker (or to the artefact store, in the lighter setup).
3. Wallet-api PR pulls the new pact and runs provider verification.
4. Both PRs merge in the same iteration. The order is "consumer first, provider second" — the consumer's CI does not depend on the provider; the provider's CI does depend on the consumer's pact.

### 8.2 Breaking API changes

Any change that removes a response field, renames an endpoint, or requires a new field to be sent:

1. The change is announced as breaking in the PR description on `wallet-api`. The Pact contract version bumps.
2. The wallet-app PR is opened first, with both a new pact (for the new shape) and a backward-compatibility shim if the old shape needs to keep working during the rollout.
3. Deploy order: api with both shapes (old + new) → web with the new shape → api with only the new shape, after web has been live long enough that no stale clients remain.
4. The provider verification job runs against **both** the old and new pact files until the rollout completes.

For a small-team demo, the lightweight version of this is: open the two PRs at the same time, link them in both descriptions, and merge them within an hour of each other.

### 8.3 Issue cross-referencing

Issues filed on either repo include a one-line "system context" header — does this affect both repos, or just one. The four issues filed during the deployment ([#2](https://github.com/jeffgicharu/wallet-api/issues/2)–[#5](https://github.com/jeffgicharu/wallet-api/issues/5)) all live on `wallet-api` because they are server-side; an a11y finding on the SPA would live on `wallet-app`. A finding that affects both — say, a JWT lifecycle change — opens an issue on each, links them in the description, and progresses on both branches together.

## 9. Onboarding

A new contributor running every test layer locally for the first time. Total wall time on a developer laptop: roughly 20 minutes of clicks, mostly waiting on Maven to download.

### 9.1 wallet-api

```bash
git clone git@github.com:jeffgicharu/wallet-api.git
cd wallet-api

# Java 17 (Temurin recommended)
java --version    # 17.x
mvn --version     # 3.9.x

# Unit + integration + JaCoCo
mvn clean verify -B

# JaCoCo report
open target/site/jacoco/index.html

# Run the app locally (H2 in memory)
mvn spring-boot:run
# Swagger: http://localhost:8080/swagger-ui.html

# Run against Postgres (docker-compose)
docker compose up
```

Expected first-time output: 21 tests passing, JaCoCo report shows ≥ 40 % line coverage. If anything fails, see § 9.3.

### 9.2 wallet-app

```bash
git clone git@github.com:jeffgicharu/wallet-app.git
cd wallet-app

node --version    # 22.x
npm --version     # 10.x

npm ci
npm run lint
npm run build
npm run dev       # http://localhost:3002
```

Once Vitest lands (`feat/...` PR for the test runner), `npm test` will run the unit + component suites. Once Playwright lands, `npx playwright test` will run E2E against a `docker compose`-stood-up backend.

### 9.3 Reading CI failures

A red CI run on a PR shows a stack of jobs. Open them in this order, top to bottom:

1. **Lint** — fastest signal. If `mvn` or `eslint` is failing, fix locally, push, the rest probably passes.
2. **Unit + integration** — read the first failure's stack trace; ignore "tests after the first failure" until it is fixed.
3. **Coverage** — the artefact link points at the JaCoCo or Vitest report. Find the file with red highlighting on a line you touched.
4. **Security scans** — OWASP DepCheck / Snyk reports give the CVE id; check whether the fix is `npm audit fix` (web) or a `pom.xml` version bump (api). If it's a transitive dependency we cannot upgrade, add an entry to `owasp-suppressions.xml` with a justification.
5. **Mutation** (nightly) — Stryker / PIT report shows survived mutants. Each one is either a test that doesn't actually assert what it claims to assert, or dead code.

### 9.4 Where to ask

- **Issue tracker on the affected repo** — first port of call for a defect or a question that future contributors will also have.
- **PR comments on this `docs/quality-standards` PR (or its successors)** — for "should we test it this way" debates that affect the strategy.
- **Direct DM** — for immediate unblockers ("this Testcontainers timeout — what should I look at"). Convert anything that proves to be common into an issue afterwards.
