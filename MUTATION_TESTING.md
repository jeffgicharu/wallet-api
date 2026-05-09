# Mutation testing (PIT)

Mutation testing complements line / branch coverage. Coverage tells you a line was *reached* during a test; it does not tell you that the test *verified* what the line does. PIT mutates the code (flips an `<` to `<=`, removes a conditional, replaces a return value with `null`) and re-runs the test suite. If a test fails, the mutation was *killed* — proving at least one test asserted the mutated behaviour. If every test still passes, the mutation *survived*: the test suite walked over that code without checking what it does, and a real bug there could ship green.

Coverage is necessary but not sufficient. PIT measures sufficiency.

---

## Setup

PIT 1.18.2 + the JUnit 5 plugin 1.2.3, declared in `pom.xml`. Configuration lives entirely in the plugin block:

| Field | Value | Why |
|---|---|---|
| `targetClasses` | `com.wallet.service.*` | Service layer first — that is where the wallet domain logic lives. Other packages (controllers, DTOs, exceptions) get added once their dedicated test slices land. |
| `targetTests` | `com.wallet.*` | Every test file is allowed to kill mutants in the service layer; PIT prunes which tests actually exercise which mutated lines via its coverage analysis. |
| `mutators` | `STRONGER` | A larger built-in mutator set than `DEFAULTS`. Generates more mutants per line and surfaces weaker tests. Wall-time cost is acceptable here. |
| `outputFormats` | HTML, XML | HTML for human inspection (`target/pit-reports/index.html`); XML for the survivor-detection script in this doc. |
| `timestampedReports` | `false` | The `pit-reports/` folder is overwritten on each run rather than building up subdirectories. |

Run on demand:

```bash
mvn test-compile org.pitest:pitest-maven:mutationCoverage -B
```

Reports land in `target/pit-reports/`. The directory is gitignored.

## Baseline (commit 73f7e1c — `test/mutation-pit`)

| Metric | Value |
|---|---|
| Wall time | **2 min 45 s** (165 s) |
| Tests examined | 45 |
| Mutations generated | 129 |
| **Mutations killed** | **98 (76 %)** |
| Mutations survived | 4 |
| Mutations with no coverage | 27 |
| Test strength (kill / covered) | 96 % |
| Line coverage of mutated classes | 237 / 290 (82 %) |

The headline figure to read is **76 % mutation kill-rate**. Test strength of 96 % says that *when* a test exercises a mutated line, it almost always catches the mutation — the gap to overall kill-rate comes from 27 mutants in lines no test ever runs (the transaction-history read methods).

## Thresholds (ratchet pattern)

PIT plugin config in `pom.xml`:

```xml
<coverageThreshold>80</coverageThreshold>   <!-- floor(82 - 2) -->
<mutationThreshold>74</mutationThreshold>   <!-- floor(76 - 2) -->
```

Two points below the measured baseline so a small good-faith refactor has headroom but a regression fails the build. The strategic target documented in `TEST_STRATEGY.md` is **≥ 70 % mutation kill-rate on the service layer**, already exceeded — future PRs should ratchet upward as more tests land.

## CI strategy

Wall time is **2 min 45 s**, comfortably under the 5-minute "run on every PR" threshold from the QA standards. The job `pit-mutation` lands as a regular per-PR job in `.github/workflows/ci.yml`, gated on `build-and-test` succeeding so PIT does not waste minutes when the unit suite is already broken. The HTML / XML reports are uploaded as a job artifact (30-day retention) so reviewers can drill into any mutant from the Actions tab.

If the run wall-time later climbs past 5 minutes (e.g., when more service classes get mutators applied to them), the job moves to a path-filtered trigger plus a nightly `cron` schedule per the standards. Past 15 minutes, nightly only.

## Reading the report

`target/pit-reports/index.html` is the entry point. It opens with the bundle-level kill / coverage / strength numbers, then a table of packages with per-class drill-down. Click a class to see source listing with each line annotated:

- **Green** — every mutant on the line was killed.
- **Yellow** — some survived; line numbers click through to the survivor detail.
- **Red** — no test exercised the line.
- **Pink** — the line is uncovered for some mutants and unkilled for others.

Each survivor shows the mutator name, the original code, the mutation, and the tests that ran without catching it. That is enough information to write a regression test.

## Investigating a surviving mutant

1. Find it in the HTML report (or `target/pit-reports/mutations.xml`). Note the mutator (`ConditionalsBoundaryMutator`, `VoidMethodCallMutator`, etc.), the file:line, and the source snippet.
2. Read the source. Form a hypothesis about why the mutation does not change observable behaviour for any current test (most often: a missing assertion, a missing edge-case input, or an effect that no test reads back).
3. Write a focused test that asserts the behaviour the mutation would break. Run that test once on the un-mutated code (it should pass) and once with the mutation applied (it should fail).
4. Re-run PIT. The mutant should now show as KILLED, and the kill-rate ticks up.

The point is not to game the score by writing micro-asserts; it is to find the *real* gap the mutation pointed at.

## Top surviving mutants (baseline)

The four survivors in covered code, ranked by impact:

| # | Location | Operator | Hypothesis |
|---|---|---|---|
| 1 | `WalletService:118 withdraw()` | `VoidMethodCallMutator` | Removes the `createLedgerEntry(...)` call. No test for `withdraw` reads back the ledger row, so the assertion that the withdraw produced a ledger entry is missing. The integration test that covers withdraw asserts the resulting balance but not the journal. |
| 2 | `WalletService:114 withdraw()` | `RemoveConditionalMutator_EQUAL_IF` | Replaces the equality check (likely `balance.compareTo(amount) < 0` or `request.getAmount() == BigDecimal.ZERO`) with `true`. Tests cover both paths but probably do not exercise the boundary value at the exact equality (e.g., balance equal to amount). |
| 3 | `WalletService:114 withdraw()` | `RemoveConditionalMutator_EQUAL_ELSE` | Same line, opposite mutation. Same root: missing exact-boundary test. |
| 4 | `WalletService:97 withdraw()` | `ConditionalsBoundaryMutator` | `>` ↔ `>=` (or `<` ↔ `<=`). Same family of weakness — boundary-value test missing. |

The pattern is consistent: `withdraw()` has happy-path and clearly-bad-path tests but no exact-boundary tests, and no test reads the ledger journal back. Closing the four survivors needs roughly:

- One test asserting that a successful `withdraw` produces exactly one DEBIT ledger entry of the right amount on the wallet (closes mutant #1).
- One test that withdraws an amount exactly equal to the balance (closes mutants #2 and #3 together).
- One test that withdraws one cent less and one cent more than the maximum allowed amount (closes mutant #4).

**Rough effort: half a day** of test-writing work to get from 76 % to ~95 %+ kill-rate in covered code. Filed for future work — not addressed in this PR.

## No-coverage mutants

Twenty-seven mutants live in the transaction-history read methods (`getTransactions`, `getTransactionsByType`, `getTransactionsByDateRange`, `getTransactionByReference`, `getStatement`) — methods the existing service unit test does not exercise. Closing the no-coverage gap is a different kind of work: writing the missing tests. The PIT score will jump significantly once a controller-slice or repository-slice PR lands tests for these read paths. Tracked as part of the audit gap "five untested packages" (see `AUDIT.md`).

## File layout

```
pom.xml                         (PIT plugin block, target classes, ratchet thresholds)
target/pit-reports/             (gitignored — HTML + XML output)
.github/workflows/ci.yml        (pit-mutation job)
```
