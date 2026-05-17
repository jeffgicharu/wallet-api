# Performance testing (k6)

This repo carries five k6 scripts under [`performance/k6/`](./performance/k6/) plus a shared library and a docker-compose target. The aim is twofold: catch performance regressions before they ship (per-PR smoke), and characterise where the system breaks (nightly full suite).

The matching strategic targets are restated in [`TEST_STRATEGY.md`](./TEST_STRATEGY.md).

---

## What and why

Load testing answers questions coverage and integration tests cannot: *can the system serve realistic concurrent traffic at the budget we promised?* The wallet API is a money-moving service — correctness is non-negotiable, but a 2-second deposit endpoint under nominal load is functionally broken. The k6 suite watches both.

Five scripts, each with a different question:

| Script | Goal | Shape |
|---|---|---|
| `smoke.js` | Did anything obvious break? | 1 VU for 30 s — sanity check. Runs in CI on every PR. |
| `load.js` | Does the system meet SLOs under nominal traffic? | Ramp 0 → 50 VU over 1 min, hold 5 min, ramp down. |
| `stress.js` | Where does the system break? | Ramp 0 → 200 VU over 5 min, hold 5 min, ramp down 5 min. |
| `spike.js` | How fast does it recover from a sudden surge? | 10 VU baseline → 300 VU spike for 1 min → back to 10 VU. |
| `workflow.js` | Does the full money-flow succeed end-to-end under load? | 20 VU for 5 min, every iteration is login → balance → deposit → transfer → withdraw → list. |

`smoke.js` can target either the local docker-compose stack or the live origin (`TARGET_BASE_URL=https://wallet-api.jeffgicharu.com k6 run performance/k6/smoke.js`). The other four are local-target-only — running them against the live VPS would crater the demo for everyone else looking at it.

## Performance budgets

Restated from [`TEST_STRATEGY.md`](./TEST_STRATEGY.md):

| Metric | Budget |
|---|---|
| Read endpoints (`GET /api/wallet`, `GET /api/wallet/transactions`, …) p95 | < 200 ms |
| Read endpoints p99 | < 500 ms |
| Write money-moving endpoints (`/deposit`, `/withdraw`, `/transfer`) p95 | < 400 ms |
| Write money-moving endpoints p99 | < 1000 ms |
| System-wide error rate | < 0.1 % |

Each test script imports these as `slo` from [`performance/k6/lib/thresholds.js`](./performance/k6/lib/thresholds.js) and fails the run if any threshold is crossed.

## How to run locally

Bring the perf target up:

```bash
docker compose -f docker-compose.perf.yml up -d --build
bash performance/seed-perf-data.sh
```

The seed script populates 500 users at `perfuser-NNN@perf.local` with starting balance KES 100,000 each, plus ~4,000 random transfers between them (~8,000 total transactions). It writes a `users.json` to `performance/k6/lib/` for the k6 scripts to consume.

Run the tests:

```bash
# Smoke against local
k6 run performance/k6/smoke.js

# Smoke against the live origin
TARGET_BASE_URL=https://wallet-api.jeffgicharu.com k6 run performance/k6/smoke.js

# Full local suite (~30 min total)
k6 run performance/k6/load.js
k6 run performance/k6/spike.js
k6 run performance/k6/workflow.js
k6 run performance/k6/stress.js
```

Tear down:

```bash
docker compose -f docker-compose.perf.yml down -v
```

## Baseline results

Captured on this branch (commit on `test/performance-k6`), against the docker-compose target with seeded data.

### Smoke — local

| Metric | Value |
|---|---|
| Requests | 108 over 30 s (3.6 req/s) |
| Read p95 | **10 ms** (budget 200 ms ✓) |
| Write p95 | **123 ms** (budget 400 ms ✓) |
| Errors | **0 %** |
| All checks | 162 / 162 pass |

### Smoke — live origin (`https://wallet-api.jeffgicharu.com`)

Cloudflare proxy adds significant latency: requests transit CF edge → origin VPS in Germany → back. The live numbers are not directly comparable to local numbers; they are reported here as the **client-observed** latency, which is what an end-user actually experiences.

| Metric | Value | vs local |
|---|---|---|
| Read p95 | **589 ms** | +579 ms — Cloudflare + cross-region proxy + TLS handshake |
| Read p99 | (single-VU run, p99 ≈ p95) | — |
| Write p95 | **442 ms** | +319 ms |
| Errors | **0 %** | — |
| All checks | 66 / 66 pass | — |

The Cloudflare-added latency is **roughly 280–580 ms** over the bare API. SLO budgets in `thresholds.js` are deliberately set against the **API** (local target) — when running smoke against the live origin, the threshold cross is expected and does not fail the PR. The brief documents this exception explicitly.

### Load — local (50 VU sustained, 5 min hold)

| Metric | Value | Budget | Status |
|---|---|---|---|
| Total requests | 21,104 over 6 min 30 s | — | — |
| Throughput | **54 req/s** | — | — |
| HTTP failure rate | 0.01 % | < 0.1 % | ✓ |
| Read p95 | **1,172 ms** | < 200 ms | ❌ over |
| Read p90 | 980 ms | — | — |
| Read avg | 459 ms | — | — |
| Write p95 | **1,244 ms** | < 400 ms | ❌ over |
| Write p90 | 1,043 ms | — | — |
| Write avg | 619 ms | — | — |
| p95 iteration duration | 2.57 s | — | — |

The system is **already over both p95 budgets at 50 VU** — read p95 is roughly 6× the budget, write p95 roughly 3×. Failure rate is fine (0.01 %), so the system is *slow* under nominal load, not *broken*.

### Stress — local (ramp 0 → 200 VU over 5 min, hold 5 min, ramp down 5 min)

| Metric | Value | Budget | Status |
|---|---|---|---|
| Total requests | 49,468 over 15 min | — | — |
| Throughput | **55 req/s** (same as load) | — | — |
| HTTP failure rate | 0.03 % | < 0.1 % | ✓ |
| Read p95 | **8,133 ms** | < 200 ms | ❌ over (40×) |
| Read p99 (max) | 21,170 ms | — | — |
| Write p95 | **6,646 ms** | < 400 ms | ❌ over (16×) |
| Write max | 17,030 ms | — | — |
| p95 iteration duration | 11.4 s | — | — |

**Breaking point.** Throughput plateaus at ~55 req/s — the same as the 50-VU load test. Adding more VUs past ~30 doesn't increase throughput, it just queues. Latency p95 climbs from 1.2 s at 50 VU to 8 s at 200 VU. The bottleneck is one of the listed top three (below) — the throughput ceiling is the saturation point of whichever resource it is.

### Spike — local (10 baseline → 300 VU surge for 1 min → back to 10)

| Metric | Value | Budget | Status |
|---|---|---|---|
| Total requests | 11,726 over 3 min 10 s | — | — |
| Throughput | 62 req/s peak during spike | — | — |
| HTTP failure rate | 0.03 % | < 0.1 % | ✓ |
| Read p95 | **9,343 ms** | < 200 ms | ❌ over |
| Write p95 | **7,691 ms** | < 400 ms | ❌ over |
| p95 iteration duration | 13.8 s | — | — |

**Recovery.** During the spike, read p95 reaches 9.3 s. The post-spike ramp-down (300 → 10 VU in 20 s) drains the request queue; the test ends 1 min after the spike with read p95 still elevated relative to the 10-VU baseline. RTO measurement requires a longer post-spike window — queued for a follow-up.

### Workflow — local (20 VU sustained, 5 min)

| Metric | Value | Budget | Status |
|---|---|---|---|
| Workflows completed | **2,699** | — | — |
| Workflows failed (any step) | 36 | — | — |
| **Workflow success rate** | **98.7 %** | — | — |
| Throughput | 9.1 workflows/s, 54 req/s | — | — |
| Error rate (custom metric) | 0.26 % | < 0.1 % | ❌ over (linked to bottleneck #3) |
| Read p95 | 330 ms | < 200 ms | ❌ over |
| Write p95 | **703 ms** | < 400 ms | ❌ over |

20 VU running the full money-flow loop sustains **9 successful workflows per second** with a 98.7 % completion rate. The 1.3 % failure rate (36 of 2,735) maps roughly 1:1 to the 0.21 % HTTP failure rate (36 of 16,410) — every HTTP failure caused a workflow to fail, all in the transfer step where optimistic-lock contention surfaces as 409s.

## Login throughput ceiling — accepted trade-off (issue #15)

`POST /api/auth/login` is intentionally CPU-bound: it BCrypt-verifies the
password at cost factor **10** (now pinned explicitly in
`SecurityConfig`, no longer Spring's default — see
`BcryptCostSecurityTest`). BCrypt being slow *is* the security property
— it caps offline brute-force. Cost 10 is the deliberate balance: ~50–100
ms of CPU per check, fast enough for interactive login, slow enough that
an attacker can't cheaply grind stolen hashes.

Consequence and ceiling:

- A single core does on the order of **10–20 password verifications/sec**
  at cost 10. With the small VPS's core count this is the hard ceiling on
  *fresh-login* throughput, and it is **by design** — raising throughput
  by lowering the cost would weaken every stored hash.
- This is **not** a per-request tax. Only `/api/auth/login` and
  `/api/auth/register` hit BCrypt. Every subsequent request authenticates
  via the JWT in the `Authorization` header: `JwtAuthenticationFilter`
  validates the token signature and loads the user — it **never**
  re-hashes or re-verifies a password. So a client that logs in once and
  reuses its token is unaffected by the BCrypt ceiling for the token's
  lifetime.
- The k6 `workflow.js` scenario mints a fresh JWT every iteration, which
  is why it stresses login disproportionately versus real usage where a
  token is reused across many calls.
- The login rate limit (issue #21, 5/min/IP) further bounds how much
  BCrypt load a single client can induce.

Net: the login throughput ceiling is accepted and documented rather than
"fixed" — the fix would be a security regression. If horizontal scale is
ever needed, add API instances (BCrypt parallelises across cores/nodes)
rather than lowering the cost factor.

## Top three bottlenecks

The throughput ceiling at ~55 req/s is the same in both load and stress runs, which means the system saturates on a fixed resource and adding more VUs only grows the queue. Originally three plausible roots, ranked by suspected impact:

1. **[#15](https://github.com/jeffgicharu/wallet-api/issues/15) — RESOLVED (accepted trade-off).** Throughput plateaus at ~55 req/s because every k6 iteration re-logs-in and BCrypt at cost 10 is CPU-bound by design. Cost factor pinned at 10; ceiling documented above; JWT-validated requests skip BCrypt entirely. Not a code defect — see the "Login throughput ceiling" section.
2. **[#16](https://github.com/jeffgicharu/wallet-api/issues/16) — Read endpoints degrade to p95 1,172 ms at 50 VU; suspected HikariCP pool saturation.** Default pool size is 10. Once 10+ requests are mid-transaction, every read queues behind the same pool. Read p95 climbs from 10 ms at 1 VU to 1.2 s at 50 VU to 8 s at 200 VU.
3. **[#17](https://github.com/jeffgicharu/wallet-api/issues/17) — Workflow test surfaces 0.26 % error rate from optimistic-lock contention on `/api/wallet/transfer`.** 20 concurrent VUs transferring to deterministically-spread recipients produce ~1 in 400 `OptimisticLockException` → 409s. Crosses the 0.1 % error-rate budget. Overlaps with [#10](https://github.com/jeffgicharu/wallet-api/issues/10) (idempotency-key retry semantics).

## CI strategy

Two scheduled triggers, two budgets:

- **Performance Smoke (Local)** — `.github/workflows/ci.yml` `perf-smoke` job. Runs on every PR. Brings up `docker-compose.perf.yml`, runs `seed-perf-data.sh`, runs `smoke.js`, fails the build if SLOs are crossed. Wall time ≈ 90 s after the Docker layer cache.
- **Performance Full Suite** — `.github/workflows/perf-nightly.yml` cron at 03:00 UTC. Same docker-compose bring-up, then runs load + spike + workflow + stress in sequence. Uploads HTML reports as artifacts (30-day retention). Posts a summary table to a tracking issue (`Nightly performance trend`) so trend lines are visible without clicking through.

The split is the standard ladder from the QA standards: ~ 90 s on every PR; ~ 30 min once per night, where the answer matters but the wait does not.

## How to add a new test scenario

1. Create `performance/k6/your-scenario.js` importing helpers from `lib/`.
2. Set `options.scenarios` for the desired load shape.
3. Tag every HTTP request with `kind: 'read' | 'write'` so the existing thresholds apply.
4. Add `recordResponse(...)` calls so the custom `errors` Rate metric stays meaningful.
5. If the scenario is intended for the per-PR smoke job, keep wall time under 2 min; otherwise add it to the nightly workflow.

## File layout

```
docker-compose.perf.yml                 (perf target — Postgres + wallet-api on port 8080)
performance/
├── seed-perf-data.sh                   (idempotent seed; 500 users, 4k transfers)
├── results/                            (gitignored — k6 summary JSONs and HTML reports)
└── k6/
    ├── smoke.js
    ├── load.js
    ├── stress.js
    ├── spike.js
    ├── workflow.js
    └── lib/
        ├── thresholds.js               (SLO budgets)
        ├── auth.js                     (login + bearer-header helper)
        ├── data.js                     (SharedArray + pickUser/pickRecipient)
        ├── metrics.js                  (errorRate, recordResponse helpers)
        └── users.json                  (gitignored — populated by seed script)
.github/workflows/
├── ci.yml                              (perf-smoke job, every PR)
└── perf-nightly.yml                    (full suite, 03:00 UTC cron)
```
