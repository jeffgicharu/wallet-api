# Provider verification (Pact-JVM)

This repo is the **provider** in a consumer-driven contract test. The consumer ([`wallet-app`](https://github.com/jeffgicharu/wallet-app)) writes pact tests that record every request it makes and the response shape it expects; this repo replays every interaction against a real running Spring Boot app and verifies the response matches.

The consumer side and the cross-repo flow are documented in [`wallet-app/PACT.md`](https://github.com/jeffgicharu/wallet-app/blob/main/PACT.md).

---

## Why the provider verifies pacts

The contract is the consumer's expectation. If the API drifts away from that expectation — a renamed field, a status-code change, a new required parameter — the consumer breaks the next time it ships against the deployed API. Provider verification catches that on the API repo's PR, before the change merges, while it's still cheap to fix or coordinate.

Verification fits between the existing layers as follows:

| Layer | What it proves |
|---|---|
| Unit (JUnit + Mockito) | Service-layer logic in isolation. |
| Integration (Testcontainers, when PR #8 merges) | The API works against a real Postgres for given inputs. |
| **Pact provider verification (this repo)** | The API's HTTP shape still matches every consumer's expectations. |
| E2E (Playwright, future) | Both sides work together end-to-end. |

## Running verification locally

Pact-JVM's `@PactUrl` loader rejects responses that aren't `application/json`, and `raw.githubusercontent.com` serves `.json` files as `text/plain`. The provider therefore loads pacts from a local `pacts/` directory using `@PactFolder("pacts")`. Both developers and CI seed that directory with a `curl` from the consumer repo:

```bash
mkdir -p pacts

# while the wallet-app PR is in draft, point at the feature branch:
curl -L -o pacts/wallet-app-wallet-api.json \
  https://raw.githubusercontent.com/jeffgicharu/wallet-app/test/pact-consumer-contracts/pacts/wallet-app-wallet-api.json

# once the wallet-app PR merges to main, the URL becomes:
# https://raw.githubusercontent.com/jeffgicharu/wallet-app/main/pacts/wallet-app-wallet-api.json

mvn test -Dtest=WalletApiProviderPactTest -B
```

Expected output:

```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Pact-JVM prints a per-interaction summary while it runs. Each line shows the provider state, the interaction description, and the verification result for the status code and body matching:

```
Verifying a pact between wallet-app and wallet-api
  Given alice exists with balance 50000 KES
  a wallet read for the authenticated user
    returns a response which
      has status code 200 (OK)
      has a matching body (OK)
```

## State handlers

Each `given(...)` clause in a consumer interaction has a matching `@State("...")` handler in `WalletApiProviderPactTest`. The handler runs **before** the interaction is replayed; it seeds the database with whatever the consumer's "given" assumed.

Handlers seed via the public API (`POST /api/auth/register`, `POST /api/wallet/deposit`, etc.) so the data shape always matches what real users produce. The handler also stores a real JWT for the seeded user in `currentToken`; the test-template request filter swaps the consumer pact's placeholder `Authorization: Bearer test.jwt.token` for the real one before the request is replayed.

The full state map (consumer → provider seed):

| Consumer "given" | Provider seed |
|---|---|
| no user with email alice@demo.local or phone +254700000099 exists | empty database |
| alice exists with balance 50000 KES | register alice + login + deposit 50000 |
| alice has balance 50000 KES and bob is registered with phone +254700000002 | register alice + register bob + login alice + deposit 50000 |
| alice exists; phone +254799000000 is not registered | register alice + login + deposit 50000 (do not register the unknown phone) |
| alice has previously transferred with idempotency key idem-pact-dup | register alice + bob + login alice + deposit 50000 + transfer 5000 with that key |
| alice has balance 100 KES and bob is registered with phone +254700000002 | register alice + bob + login alice + deposit 100 |
| alice has at least one historical transaction | register alice + login + deposit 7500 |
| alice has at least one DEPOSIT transaction | register alice + login + deposit 2500 |
| alice has a deposit with reference DEP-pact-lookup | register alice + login + deposit with idempotencyKey="pact-lookup" |
| alice has a completed deposit with reference DEP-pact-rev | register alice + login + deposit with idempotencyKey="pact-rev" |

Adding a new state means: (1) the consumer adds `provider.given('new state name')` in the pact test, (2) the regenerated pact lands in the consumer PR, (3) the provider PR adds a matching `@State("new state name")` method.

## Database lifecycle

Verification uses the default Spring profile (H2 in memory). Each interaction runs as a separate JUnit test invocation; `@BeforeEach` wipes the five domain tables (`ledger_entries`, `transactions`, `audit_logs`, `wallets`, `users`) before each interaction. State handlers then seed exactly what that interaction needs.

H2 is sufficient for HTTP-shape verification — Pact contracts are about request/response *shape*, not Postgres-specific behaviours. Postgres-specific concerns (dialect quirks, optimistic-locking under concurrent writes, schema-drift) are covered by the Testcontainers integration suite (PR #8 on this repo), which is a different layer with a different signal.

## CI workflow

The `.github/workflows/ci.yml` file gains a `pact-provider-verification` job that runs after `build-and-test`:

1. Checkout
2. Set up JDK 17 (Temurin)
3. Curl the latest consumer pact from the consumer's tracking branch (defaults to `main`; can be overridden via the `PACT_BRANCH` env var when iterating against a consumer feature branch).
4. `mvn test -Dtest=WalletApiProviderPactTest -B`
5. Upload the verification report as an artifact (30-day retention).
6. Surface the verified-interaction count to the job summary.

The job fails the PR if any interaction fails verification. The PR description should make clear which side owns the fix:

- **The API drifted from the contract.** The fix is in this repo: implement what the consumer expects, or coordinate a breaking change per the cross-repo flow in [`QA_BEST_PRACTICES.md`](./QA_BEST_PRACTICES.md#cross-repo-coordination).
- **The consumer's expectation is now wrong.** The fix is in the consumer repo: update the pact test, regenerate the JSON, merge the consumer PR, then re-run this verification.

## Transport: raw.githubusercontent.com (deliberate small-team choice)

The pact file is committed in the consumer repo at `pacts/wallet-app-wallet-api.json` and fetched from `raw.githubusercontent.com` at verification time. This is the same trade-off note from `wallet-app/PACT.md`: a Pact Broker (PactFlow or self-hosted) is the production answer for can-i-deploy checks, webhook-driven verification on consumer pact publish, and tag-based cross-version compatibility. Migrating to a broker is a separate PR; nothing in this provider setup needs to change other than the source on the consumer side and a one-line annotation swap here.

## Maven goal

```bash
mvn test -Dtest=WalletApiProviderPactTest -B
```

The wider `mvn verify -B` runs the unit + integration suite alongside the provider verification.

## Spec version

The consumer writes pacts authored using the V3 API; the serialised file is V2 (the matchers used are all V2-compatible and `pact_ffi` writes the lowest serialisation that covers them). Pact-JVM 4.6.x reads V2 pacts natively, so no spec-version configuration is needed here.

## File layout

```
pacts/                                  (gitignored)
└── wallet-app-wallet-api.json          (curl'd in by CI / dev)

src/test/java/com/wallet/contract/
└── WalletApiProviderPactTest.java      (15 state handlers + 1 verify template)
```
