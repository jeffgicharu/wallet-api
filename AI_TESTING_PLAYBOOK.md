# AI-assisted testing playbook

How we use AI assistants effectively when authoring tests in this polyglot Java + TypeScript codebase. Grounded in real examples from the recent quality work (PRs #8 integration suite, #13 Pact provider verification, #14 PIT mutation testing, #18 k6 performance, #23 security suite).

This is not "let the AI write the test." It is "let the AI scaffold the shape, then harden by hand." The difference matters.

---

## Why AI in testing

For a system that spans Spring Boot, Postgres, Vitest, MSW, Pact, k6, OWASP ZAP, Stryker, and PIT, the test code is shape-heavy: same skeleton repeated across many endpoints, many interactions, many configurations. AI assistants shine at shape-heavy code. They are weaker at:

- Domain logic that doesn't exist in their training data (double-entry ledger consistency, the specific way *this* wallet handles optimistic locking).
- Security assertions where the test must encode an *expectation* of refusal — getting the inversion right requires reading the production code first.
- Performance threshold tuning, where the "right" number depends on the deploy target.

The honest framing: AI roughly doubles the throughput of safe scaffolding (Pact interaction bodies, k6 stage definitions, parameterised JUnit cases) and is neutral-to-negative on the things that actually require thinking.

## The four-step workflow

Every test in PRs #4, #8, #10, #13, #14, #18, #23 was authored against this loop. Skipping a step produces tests that pass but don't catch anything.

### Step 1 — Frame the test

Before opening any AI prompt, read the production code. Identify:

- What behaviour is the test claiming?
- What inputs select that branch?
- What observable side-effects can the test read back to *verify*?
- What edge cases would break the behaviour without breaking the happy path?

Output of this step is a 3–6 line bullet list, in English, of what the test must assert. The AI prompt that follows takes this list as input.

### Step 2 — Prompt with context

Three things go in every prompt:

1. **The production code under test**, pasted verbatim — at least the method body, ideally the surrounding class.
2. **The framework conventions in *this* repo** — JUnit 5 + AssertJ + Spring's `TestRestTemplate` on the Java side; Vitest + RTL + MSW on the TypeScript side; `@pact-foundation/pact` V3 with `MatchersV3` for consumer pacts; PIT and Stryker for mutation. Naming and folder conventions from `QA_BEST_PRACTICES.md`.
3. **An exemplar test from the same codebase**, pasted in full. The AI mirrors the style of the exemplar far more reliably than it does abstract instructions.

The bullet list from Step 1 is the actual ask: "Write a test that asserts each of these points, with assertions strong enough that each bullet would fail independently if the corresponding behaviour broke."

### Step 3 — Review with skepticism

Read the draft as if it was written by a junior engineer who has never seen the codebase. Specifically:

- Do the symbol names match what's actually in the codebase? AI invents methods that "should" exist.
- Do assertions actually verify the claim, or are they `assertThat(result).isNotNull()` filler?
- Is the test asserting on observable behaviour, or on implementation detail (call counts on mocks, internal field names)?
- For component tests: are queries `getByRole` / `getByLabelText`, or is the AI reaching for `getByTestId` as an escape hatch?
- For Pact tests: are matchers semantically correct? `like(5000)` is wrong if the body field is a string; `regex(...)` is wrong if the pattern doesn't match the production output.

If any of these go wrong, the AI's confidence will make the wrong thing look right. The bullet list from Step 1 is the calibration.

### Step 4 — Run + harden

Run the test. Watch it fail (with a clear message), then pass for the right reasons. Then:

- Add the test to the relevant mutation-testing scope. Run PIT (Java) or Stryker (TypeScript).
- See whether the new test killed any mutants that previously survived. If yes, the test exercises real behaviour. If no, the test is decorative — either delete it or strengthen it until it kills a mutant.
- Commit only if mutation showed a net-positive.

---

## Worked example 1 — Java integration test (PR #8)

**Goal.** Cross-user data isolation: Alice authenticated with her own JWT must not see Bob's transactions in her history endpoint. Surfaced as a need during the audit (issue #2 cluster); landed in PR #8's `AuthorizationIntegrationTest`.

### Step 1 framing

Before any prompt:

- Inputs: two registered users, each with their own deposits.
- Behaviour: `GET /api/wallet/transactions` returns only the caller's transactions.
- Observable: the JSON response's `data.content` array.
- Edge case: even if both deposits have the *same amount*, the response must show only the caller's.

### Step 2 prompt

```
You are writing an integration test for a Spring Boot 3.2 wallet API.

Production code under test (the controller method and the service method
it calls — pasted from src/main/java/com/wallet/controller/WalletController.java
and WalletService.java):

[150 lines pasted]

Conventions in this repo:
- JUnit 5 + AssertJ, BDD method naming: shouldXWhenY()
- @SpringBootTest(webEnvironment = RANDOM_PORT) with Testcontainers Postgres
- We extend IntegrationTestBase — wipes DB per test
- TestRestTemplate for HTTP

Exemplar test from the same codebase (pasted in full):
[80 lines of an existing integration test for happy-path deposit]

Write a single test method:
  shouldIsolateBobsTransactionsFromAlicesView
that asserts:
  - Alice with 2 deposits + Bob with 1 deposit
  - Alice's GET /api/wallet/transactions returns exactly 2 items
  - Bob's GET returns exactly 1 item
  - The contents differ (Alice never sees Bob's)
```

### Step 3 review of the raw output

The AI's first draft (representative — paraphrased from the actual session):

```java
@Test
void shouldIsolateBobsTransactionsFromAlicesView() throws Exception {
    String alice = registerAndLogin("iso-alice", TestData.uniquePhone());
    String bob = registerAndLogin("iso-bob", TestData.uniquePhone());

    deposit(alice, 1000);
    deposit(alice, 2000);
    deposit(bob, 5000);

    ResponseEntity<String> aliceTxns = restTemplate.exchange(
            "/api/wallet/transactions",
            HttpMethod.GET, authedHeaders(alice), String.class);
    assertThat(aliceTxns.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(aliceTxns.getBody()).isNotNull();
}
```

Two failures on review:

1. **Filler assertion.** `assertThat(body).isNotNull()` is the AI's hedge — it makes the test "do something". It does not check isolation. A bug where Alice sees Bob's transactions would still pass this test.
2. **No Bob-side assertion.** The test asserts Alice's side only; doesn't prove Bob's view differs.

### Step 4 hardened version (the committed test)

```java
@Test
void shouldIsolateBobsTransactionsFromAlicesView() throws Exception {
    String alice = registerAndLogin("iso-alice", TestData.uniquePhone());
    String bob = registerAndLogin("iso-bob", TestData.uniquePhone());

    deposit(alice, 1000);
    deposit(alice, 2000);
    deposit(bob, 5000);

    ResponseEntity<String> aliceTxns = restTemplate.exchange(
            "/api/wallet/transactions",
            HttpMethod.GET, authedHeaders(alice), String.class);
    ResponseEntity<String> bobTxns = restTemplate.exchange(
            "/api/wallet/transactions",
            HttpMethod.GET, authedHeaders(bob), String.class);

    JsonNode aliceList = objectMapper.readTree(aliceTxns.getBody())
            .path("data").path("content");
    JsonNode bobList = objectMapper.readTree(bobTxns.getBody())
            .path("data").path("content");

    // Alice has 2 deposits; Bob has 1. Neither sees the other's transactions.
    assertThat(aliceList.size()).isEqualTo(2);
    assertThat(bobList.size()).isEqualTo(1);
}
```

The difference is roughly six lines of added structure. PIT mutation on `getTransactions` confirms the hardened version kills the `replace size() with 0` mutant — the AI's draft did not.

---

## Worked example 2 — TypeScript component test (PR #4)

**Goal.** `PinPad` component: tapping a digit fills the next indicator dot, tapping four digits fires `onComplete` with the value, tapping more does nothing (overflow protection).

### Step 1 framing

- Inputs: rendered `<PinPad onComplete={spy} onCancel={spy} title="Enter PIN" />`.
- Behaviour: user taps digit buttons; component tracks state internally.
- Observable: `onComplete` mock arguments, count of taps before `onComplete` fires, dot-indicator count.
- Edge case: tapping a 5th digit after 4 is reached must NOT re-fire `onComplete`.

### Step 2 prompt

Standard React Testing Library prompt with `PinPad.tsx` source pasted, plus an exemplar test from `LoginPage.test.tsx`. The ask: three test cases — happy path, overflow protection, delete clears most recent.

### Step 3 review of the raw output

The AI's first overflow-protection test (representative):

```ts
it('does not fire onComplete on additional taps after four digits', async () => {
  const onComplete = vi.fn();
  render(<PinPad title="Enter PIN" onComplete={onComplete} onCancel={vi.fn()} />);

  for (let i = 0; i < 6; i++) {
    fireEvent.click(screen.getByRole('button', { name: String((i % 9) + 1) }));
  }

  expect(onComplete).toHaveBeenCalled();
});
```

Three failures:

1. `fireEvent` instead of `userEvent` — repo convention is `userEvent` for higher-fidelity user simulation.
2. `expect(onComplete).toHaveBeenCalled()` — this passes whether onComplete fires once or six times. The test doesn't catch the overflow bug.
3. Tap pattern `(i % 9) + 1` is hard to read and produces a numeric value that's not the test's stated intent.

### Step 4 hardened version (the committed test)

```ts
it('does not fire onComplete on additional taps after four digits', async () => {
  const onComplete = vi.fn();
  const user = userEvent.setup();
  render(<PinPad title="Enter PIN" onComplete={onComplete} onCancel={vi.fn()} />);

  await user.click(screen.getByRole('button', { name: '1' }));
  await user.click(screen.getByRole('button', { name: '2' }));
  await user.click(screen.getByRole('button', { name: '3' }));
  await user.click(screen.getByRole('button', { name: '4' }));
  await user.click(screen.getByRole('button', { name: '5' }));
  await user.click(screen.getByRole('button', { name: '6' }));

  expect(onComplete).toHaveBeenCalledTimes(1);
  expect(onComplete).toHaveBeenCalledWith('1234');
});
```

The crucial change is `toHaveBeenCalledTimes(1)` plus `toHaveBeenCalledWith('1234')`. Stryker confirms the hardened version kills the `Conditional → true` mutant on the overflow guard; the AI's draft did not.

---

## Worked example 3 — Pact consumer contract (PR #13)

**Goal.** Consumer-side pact for `POST /api/wallet/transfer` happy path, recording the request shape and the expected response shape using `@pact-foundation/pact` V3 matchers.

### Step 1 framing

- The frontend's `walletApi.transfer(...)` is the call site — body shape is `{ recipientPhone, amount, pin, idempotencyKey }`.
- Expected response: `{ success: true, message: ..., data: { reference, type, status, amount, createdAt }, timestamp }`.
- Matchers needed: `like(...)` for fields that vary by value but match type (timestamps, references), exact strings for constants ("TRANSFER", "COMPLETED").

### Step 2 prompt

```
You are writing a consumer-side Pact V3 test for a TypeScript frontend
calling a Spring Boot API. Use @pact-foundation/pact (PactV3 class) and
MatchersV3 helpers.

Production code — the frontend's API client method (pasted from
src/api/wallet.ts):

[the transfer wrapper, ~5 lines]

The Java provider's response shape (paraphrased from
src/main/java/com/wallet/dto/response/ApiResponse.java and
TransactionResponse.java):

[20 lines of DTO definitions]

Exemplar interaction from the same pact test file (login happy path
already in the file, pasted in full):

[40 lines]

Write the next interaction:
  POST /api/wallet/transfer — happy path returns 200 with TRANSFER transaction
Provider state: "alice has balance 50000 KES and bob is registered"
Request body: matches what walletApi.transfer() actually sends.
Response: matchers loose on reference/timestamps, exact on type/status.
```

### Step 3 review of the raw output

The AI's first draft (paraphrased):

```ts
provider
  .given('alice has balance 50000 KES and bob is registered')
  .uponReceiving('a transfer')
  .withRequest({
    method: 'POST',
    path: '/api/wallet/transfer',
    body: like({
      recipientPhone: '+254700000002',
      amount: 5000,
      pin: '1234',
      idempotencyKey: 'trf-001',
    }),
  })
  .willRespondWith({
    status: 200,
    body: like({
      success: true,
      data: like({ reference: 'TRF-001', type: 'TRANSFER' }),
    }),
  });
```

Three problems:

1. **`like()` wrapping the entire body.** This makes every field type-checked but doesn't pin exact values where the consumer depends on them. The status field must be exactly `'COMPLETED'` for the frontend's `if (status === 'COMPLETED')` branch to work — `like('COMPLETED')` is too permissive.
2. **No `Content-Type` header in the request.** Axios sends it; if the pact doesn't record it, the provider verifier might match against a request missing that header.
3. **No bearer-token header.** Same omission — the consumer always sends `Authorization: Bearer ...` and the contract should record that requirement.

### Step 4 hardened version (excerpt from the committed pact test)

```ts
provider
  .given('alice has balance 50000 KES and bob is registered with phone +254700000002')
  .uponReceiving('a transfer of 5000 KES from alice to bob')
  .withRequest({
    method: 'POST',
    path: '/api/wallet/transfer',
    headers: {
      'Authorization': regex(/^Bearer .+/, 'Bearer test.jwt.token'),
      'Content-Type': 'application/json',
    },
    body: {
      recipientPhone: '+254700000002',
      amount: 5000,
      pin: '1234',
      idempotencyKey: 'trf-pact-001',
    },
  })
  .willRespondWith({
    status: 200,
    headers: jsonHeaders,
    body: {
      success: true,
      message: like('Transfer successful'),
      data: {
        reference: like('TRF-trf-pact-001'),
        type: 'TRANSFER',            // exact — frontend branches on this
        status: 'COMPLETED',         // exact — frontend branches on this
        amount: 5000,
        createdAt: like('2026-05-08T09:00:00Z'),
      },
      timestamp: like('2026-05-08T09:00:00Z'),
    },
  });
```

Provider verification (PR #13's wallet-api side) confirmed 15 / 15 interactions verified including this one. The AI's first draft, if shipped, would have produced a pact that the provider could satisfy in too many shapes — a contract that doesn't actually constrain.

---

## Anti-patterns to refuse from AI

The AI will reach for these unless explicitly told not to. The exemplar paste in Step 2 is the most effective counter, because the AI mirrors what it sees.

1. **Mocking the SUT.** The AI will sometimes suggest `@MockBean(WalletService.class)` in an integration test for `WalletService`. Refuse. The whole point is to exercise it.
2. **Weak assertions.** `assertThat(result).isNotNull()` — see worked example 1. `expect(true).toBe(true)` filler — sometimes appears in component tests. Reject in review.
3. **Snapshot-everything.** RTL tests don't need `toMatchSnapshot` for every render — see `QA_BEST_PRACTICES.md` § "Snapshot tests are last-resort."
4. **Mocking the database when integration is the goal.** Spring Data JPA's behaviour against Postgres differs from H2 (and from mocks in subtle ways like JPA flush ordering). If the test class is an integration test, the DB stays real.
5. **Asserting on implementation detail.** `verify(repo).save(any())` is fine for an isolated unit test, awful for an integration test that should read the row back through the API.
6. **Helper consolidation that hides intent.** AI loves to extract a `setupAliceAndBob()` helper that does five things. The bullet list in Step 1 should still be readable from the test method body.

---

## Productivity calibration (honest)

Over the recent PRs the AI was:

- **Helpful** for: Pact interaction bodies (PR #13 — 14 of 15 interactions started from an AI draft and were edited down), k6 stage tables and threshold rules (PR #18), parameterised JUnit cases (`@ParameterizedTest` value sources for the fee-band test in PR #8), the boilerplate of MSW handlers (PR #4).
- **Neutral** for: state-handler implementations in Pact provider verification (PR #13 wallet-api side) — the work was specific enough that the AI's drafts needed full rewriting.
- **Negative** for:
  - **Optimistic-lock contention test** (PR #8 `shouldSerialiseConcurrentTransfersFromSameWalletViaOptimisticLocking`). The AI's first draft used a fixed thread pool with simple counters and missed that the `OptimisticLockException` surfaces as a 409 only when Hibernate's wrapper translation kicks in — the actual code path sometimes returns 500 instead, and the test had to accept "any non-200" as rejection. The AI didn't have the concurrency reasoning to predict this.
  - **Idempotency-key duplicate test** (PR #8). The AI's first draft asserted the documented behaviour from the README (retry returns the original transaction). The actual behaviour is 409 (issue #10). The AI's confidence in the README would have produced a test that failed for the wrong reason.
  - **Cross-user authorisation tests** (PR #8, PR #23). The AI's first instinct is to test the happy path. The brief for these tests was always "test the rejection." Getting the prompt right required explicit "the test should fail today if the assertion is too lax" framing.

The pattern: AI is great at *shape* (what does a test of this kind look like), neutral at *adaptation* (how does this test apply to this codebase), and negative at *inversion* (does this test prove a refusal rather than a success).

---

## Prompt templates

Copy, paste, fill in the bracketed sections.

### Template 1 — Java integration test

```
You are writing an integration test for a Spring Boot 3.2 wallet API.

PRODUCTION CODE UNDER TEST (paste the method body + surrounding context):
[paste]

CONVENTIONS IN THIS REPO:
- JUnit 5 + AssertJ, BDD method naming: shouldXWhenY()
- @SpringBootTest(webEnvironment = RANDOM_PORT) with Testcontainers Postgres
- Extend IntegrationTestBase (which wipes DB per test)
- TestRestTemplate for HTTP, ObjectMapper for response parsing

EXEMPLAR TEST FROM THE SAME CODEBASE (paste in full):
[paste]

ASSERTIONS TO MAKE (one bullet per assertion the test must prove):
- [bullet 1]
- [bullet 2]
- [bullet 3]

DO NOT:
- Mock the SUT
- Use weak assertions like isNotNull(); every assertion proves a specific claim
- Assert on call counts or other implementation detail
- Wrap setup in a multi-purpose helper that hides what the test sets up
```

### Template 2 — React component test

```
You are writing a React Testing Library + Vitest component test for a React 19 SPA.

PRODUCTION CODE UNDER TEST (paste the component .tsx):
[paste]

CONVENTIONS IN THIS REPO:
- Vitest + RTL + @testing-library/user-event
- Query order: getByRole > getByLabelText > getByText > (last resort) getByTestId
- Use renderWithProviders from src/test/render.tsx — wraps QueryClient + AuthProvider + MemoryRouter
- MSW for any API calls, with handlers in src/test/msw-server.ts

EXEMPLAR TEST FROM THE SAME CODEBASE (paste in full):
[paste]

ASSERTIONS TO MAKE:
- [bullet 1]
- [bullet 2]

DO NOT:
- Use fireEvent — userEvent for everything
- Use getByTestId unless no semantic query works
- Use toMatchSnapshot — assertions on visible behaviour only
- Mock the SUT
```

### Template 3 — Consumer Pact V3 interaction

```
You are writing a consumer-side Pact V3 interaction for a TypeScript frontend
calling a Spring Boot API. Use @pact-foundation/pact (PactV3 class) and
MatchersV3 helpers.

THE FRONTEND'S CALL SITE (paste the axios call from src/api/wallet.ts):
[paste]

THE PROVIDER'S RESPONSE SHAPE (paste DTO definitions):
[paste]

EXEMPLAR INTERACTION FROM THE SAME FILE (paste one already-passing interaction):
[paste]

WRITE:
- Provider state: [describe the data the provider needs to seed]
- Description: [one phrase, present-tense, like "a successful login"]
- Request: include Authorization Bearer header if the call site sends it
- Response: exact-match on values the consumer branches on; like() for values that only need to be the right TYPE

DO NOT:
- Wrap the entire body in like() — pin exact values where the consumer depends on them
- Omit the Authorization or Content-Type headers
- Use snake_case unless the API actually returns snake_case
```

### Template 4 — k6 performance scenario

```
You are writing a k6 performance test for a Java REST API.

CONVENTIONS IN THIS REPO:
- import { slo } from './lib/thresholds.js' — SLO budgets, do not override
- Use pickUser(__VU, __ITER) and pickRecipient(__VU, __ITER) from ./lib/data.js
- Tag every HTTP request with kind: 'read' | 'write' for the thresholds to apply
- Use recordResponse(...) from ./lib/metrics.js after each request

EXEMPLAR SCENARIO FROM THE SAME CODEBASE (paste load.js or workflow.js):
[paste]

WRITE A SCENARIO THAT:
- Shape: [stages, e.g., "ramp 0→100 VU over 2m, hold 5m, ramp down"]
- Traffic mix: [percentage split, e.g., "70% reads / 30% writes"]
- Tags: every request carries kind=read or kind=write

DO NOT:
- Override the threshold object — use the shared slo
- Mint new VU users in the loop — use the pre-seeded list
- Forget the iteration sleep — every iteration should sleep 0.5s minimum on load scenarios
```

### Template 5 — Mutation-driven test hardening

```
You are reviewing a surviving mutant in a Java codebase.

PRODUCTION CODE WITH THE MUTATION:
[paste the line + the mutation operator from PIT's HTML report]

EXISTING TESTS THAT EXERCISE THIS LINE (paste them):
[paste]

WHY DOES THE MUTATION SURVIVE? (form a hypothesis):
[your guess in 1 sentence]

WRITE ONE TEST that would kill this mutant. The test should fail on the
mutated code and pass on the un-mutated code, and should assert observable
behaviour rather than internal state.
```

---

## When NOT to reach for AI

- **Novel domain rules.** The wallet's double-entry ledger has invariants that aren't in the AI's training data. Tests for ledger consistency must be reasoned about from the entity definitions, not generated.
- **Security-critical assertions.** Tests that prove a refusal (cross-user reads, JWT tampering, CORS rejection) need the human to set the inversion. AI defaults to the happy path.
- **Performance threshold tuning.** The numbers in `TEST_STRATEGY.md` (p95 < 200 ms read, < 400 ms write) come from a strategic decision, not an AI suggestion.
- **Debugging an existing flaky test.** The AI can't see the test failing in CI; it can only see the source. Diagnosis is human work.
- **Anything where the test must encode "we expect this to break the way it currently breaks"** (characterisation tests of known bugs — issues #9, #10 in this repo). Those tests pin the *current* behaviour, which the AI doesn't know.

---

## See also

- [`TEST_STRATEGY.md`](./TEST_STRATEGY.md) — system-wide strategy and SLO budgets.
- [`TEST_PLAN.md`](./TEST_PLAN.md) — scenarios for the onboarding-to-reversal workflow.
- [`QA_BEST_PRACTICES.md`](./QA_BEST_PRACTICES.md) — review checklist + commit conventions.
- [`MUTATION_TESTING.md`](./MUTATION_TESTING.md) — PIT setup and ratchet.
- [`QUALITY_DASHBOARD.md`](./QUALITY_DASHBOARD.md) — current system-wide metrics.
