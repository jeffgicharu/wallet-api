# Wallet API

[![CI](https://github.com/jeffgicharu/wallet-api/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jeffgicharu/wallet-api/actions/workflows/ci.yml)
[![CodeQL](https://github.com/jeffgicharu/wallet-api/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/jeffgicharu/wallet-api/actions/workflows/codeql.yml)
[![Coverage](https://img.shields.io/badge/line%20coverage-87%25-brightgreen)](./QUALITY_DASHBOARD.md)
[![Mutation](https://img.shields.io/badge/PIT%20mutation-76%25-brightgreen)](./MUTATION_TESTING.md)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

This is a mobile money wallet, similar to M-Pesa's backend. Users register, get a wallet, and can deposit, withdraw, or send money to each other by phone number.

## Live Demo

- **API:** https://wallet-api.jeffgicharu.com
- **Web app:** https://wallet.jeffgicharu.com
- **Swagger UI:** https://wallet-api.jeffgicharu.com/swagger-ui.html

Demo accounts (state resets daily at 03:00 UTC):

| Email | Password | PIN | Approx. balance |
|---|---|---|---|
| `alice@demo.local` | `pass1234` | `1234` | KES 50,000 |
| `bob@demo.local`   | `pass1234` | `1234` | KES 25,000 |
| `carol@demo.local` | `pass1234` | `1234` | KES 10,000 |

This is a public demo. Do not use real PII, real money, or real credentials.

The interesting part isn't the CRUD. It's the things that go wrong with money. What happens when a transfer request is sent twice because of a network glitch? What if two transfers hit the same wallet at the same time? What if someone brute-forces a PIN? What if a transaction needs to be reversed after the fact? What if the books don't balance at end of day? This project handles all of that.

## What It Does

**For users:**
- Register and login with JWT authentication
- Deposit and withdraw funds (withdrawals require a 4-digit PIN)
- Send money to any registered user by phone number, with automatic fee calculation
- Look up any transaction by its reference number
- View transaction history with filtering by type
- View account statements showing a full double-entry ledger

**For administrators:**
- Search for any user by phone number or email
- Freeze and unfreeze wallets (blocks all transactions on that wallet)
- Unlock PIN-locked accounts
- View platform-wide statistics (total users, transaction volume)
- Run system-wide or per-wallet reconciliation (verify debits equal credits)
- Browse the full audit trail of every administrative action

**For reliability:**
- Transaction reversal for deposits, withdrawals, and transfers with proper ledger entries
- Daily transfer limits enforced in real time (KES 300,000 per day)
- PIN lockout after 3 consecutive failures with 15-minute cooldown
- Idempotency keys on every money-moving operation to prevent duplicate charges
- Optimistic locking on wallet balances to prevent race conditions
- Frozen wallet check on every operation

## How Money Stays Safe

**Double-entry bookkeeping.** When Alice sends Bob KES 5,000, the system creates four ledger entries: a DEBIT on Alice's account, a CREDIT on Bob's account, and separate entries for the transfer fee. At any point, the reconciliation endpoint can verify that debits equal credits across the entire system.

**Idempotency.** Every request that moves money requires an `idempotencyKey`. If the client retries the same request (because the network dropped the response), the system recognizes the key and returns the original result instead of processing the transfer again.

**PIN lockout.** After 3 wrong PIN attempts, the account is locked for 15 minutes. An admin can unlock it manually through the admin panel. The counter resets on a successful PIN entry.

**Transaction reversal.** Completed transactions can be reversed. The reversal refunds the sender (including the fee), debits the receiver, creates proper ledger entries for the reversal, and marks the original transaction as REVERSED. You can't reverse a transaction that's already been reversed or one that failed.

**Daily limits.** Each wallet has a KES 300,000 daily transfer limit. The system checks how much has been transferred today before processing each new transfer. The wallet endpoint returns both the limit and the current daily usage.

## Quick Start

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html
```

Or with Docker (includes PostgreSQL):

```bash
docker compose up
```

## Try It Out

```bash
# 1. Register a user
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Alice Wanjiku","email":"alice@example.com","phoneNumber":"+254700000001","password":"pass123","pin":"1234"}'

# 2. Login and grab the token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"pass123"}' | jq -r '.data.token')

# 3. Deposit some money
curl -X POST http://localhost:8080/api/wallet/deposit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"amount":50000,"idempotencyKey":"dep-001"}'

# 4. Send money to another user
curl -X POST http://localhost:8080/api/wallet/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"recipientPhone":"+254700000002","amount":5000,"pin":"1234","idempotencyKey":"trf-001"}'

# 5. Reverse the transfer
curl -X POST http://localhost:8080/api/wallet/transactions/TRF-trf-001/reverse \
  -H "Authorization: Bearer $TOKEN" \
  -G -d "reason=Customer+request"

# 6. Check reconciliation
curl http://localhost:8080/api/admin/reconcile/wallet/+254700000001 \
  -H "Authorization: Bearer $TOKEN"
```

## API Reference

### User Endpoints (requires JWT)

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/auth/register` | Create account + wallet |
| POST | `/api/auth/login` | Get JWT token |
| GET | `/api/wallet` | Balance, daily limit usage |
| POST | `/api/wallet/deposit` | Deposit funds |
| POST | `/api/wallet/withdraw` | Withdraw (needs PIN) |
| POST | `/api/wallet/transfer` | Send money (needs PIN) |
| GET | `/api/wallet/transactions` | History (filterable by type) |
| GET | `/api/wallet/transactions/{ref}` | Look up by reference |
| POST | `/api/wallet/transactions/{ref}/reverse` | Reverse a transaction |
| GET | `/api/wallet/statement` | Double-entry ledger |

### Admin Endpoints (requires JWT)

| Method | Endpoint | What it does |
|---|---|---|
| GET | `/api/admin/users/search?phone=...` | Look up user by phone or email |
| POST | `/api/admin/wallets/{phone}/freeze` | Freeze a wallet |
| POST | `/api/admin/wallets/{phone}/unfreeze` | Unfreeze a wallet |
| POST | `/api/admin/users/{phone}/unlock-pin` | Clear PIN lockout |
| GET | `/api/admin/stats` | Platform-wide statistics |
| GET | `/api/admin/reconcile` | System-wide ledger reconciliation |
| GET | `/api/admin/reconcile/wallet/{phone}` | Per-wallet reconciliation |
| GET | `/api/admin/audit` | Full audit trail |
| GET | `/api/admin/audit/{type}/{id}` | Audit trail for specific entity |

## Built With

Spring Boot 3.2, Java 17, Spring Security + JWT, Spring Data JPA, PostgreSQL (H2 for dev), Docker, Kubernetes + Helm, GitHub Actions CI, Prometheus metrics.

## Tests

```bash
mvn test   # 21 tests
```

**Unit tests (9):** deposit and balance update, withdrawal with valid/invalid PIN, insufficient balance, P2P transfer with fee calculation, idempotency deduplication, self-transfer prevention, unknown recipient handling.

**Integration tests (12):** full auth flow, duplicate registration rejection, deposit through HTTP, transfer through HTTP, unauthenticated request rejection, transaction lookup by reference, deposit reversal with balance verification, admin stats, wallet freeze blocking transactions, per-wallet reconciliation, wrong PIN through HTTP, daily limit info in wallet response.

## Performance Testing

JMeter test plans are in `src/test/jmeter/`. The test plan simulates:

- **50 concurrent users** running the auth flow (register + login) with 10-second ramp-up
- **100 concurrent users** performing wallet operations (login, check balance, deposit, transaction history) for 2 minutes
- Response time assertions: 500ms for balance checks, 1s for deposits, 2s for registration
- Dynamic data generation with unique emails, phone numbers, and idempotency keys per thread

Run with: `jmeter -n -t src/test/jmeter/wallet-api-load-test.jmx -l results.jtl`

## Quality engineering

This repo is one half of a polyglot wallet system; the SPA is at [`jeffgicharu/wallet-app`](https://github.com/jeffgicharu/wallet-app). The system-wide quality dashboard with current metrics, open issues, and links to every quality doc lives in [`QUALITY_DASHBOARD.md`](./QUALITY_DASHBOARD.md). Regenerate it locally with `bash scripts/quality-snapshot.sh` after `mvn verify`.

**Per-area docs at this repo root:**

| Document | Covers |
|---|---|
| [AUDIT.md](./AUDIT.md) | Baseline state — what exists, what doesn't, what's measured |
| [TEST_STRATEGY.md](./TEST_STRATEGY.md) | Test pyramid per stack, SLO budgets, tooling inventory |
| [TEST_PLAN.md](./TEST_PLAN.md) | Scenarios for the customer-onboarding-to-reversal workflow |
| [QA_BEST_PRACTICES.md](./QA_BEST_PRACTICES.md) | Code-review checklist, naming, flaky-test policy, cross-repo flow |
| [PACT.md](./PACT.md) | Provider verification setup, state-handler map, transport choice |
| [MUTATION_TESTING.md](./MUTATION_TESTING.md) | PIT setup, ratchet, top-survivor register, fix effort estimates |
| [PERFORMANCE_TESTING.md](./PERFORMANCE_TESTING.md) | k6 scenarios, SLO budgets, bottleneck register |
| [SECURITY_TESTING.md](./SECURITY_TESTING.md) | Threat model, tooling-by-trigger, OWASP Top 10 mapping, DAST safety guard |
| [AI_TESTING_PLAYBOOK.md](./AI_TESTING_PLAYBOOK.md) | AI-assisted testing workflow, worked examples, prompt templates, anti-patterns |

**CI workflows:**

| Workflow | Trigger | What it runs |
|---|---|---|
| [`ci.yml`](./.github/workflows/ci.yml) | every PR + push | `mvn verify` (unit + integration + security + Spotbugs + JaCoCo), OWASP Dependency Check, Docker build, SonarCloud (push-only) |
| [`codeql.yml`](./.github/workflows/codeql.yml) | every PR + push + weekly | CodeQL Java analysis |
| [`container-scan.yml`](./.github/workflows/container-scan.yml) | every PR + push + daily | Trivy filesystem + Trivy image |
| [`dependency-snyk.yml`](./.github/workflows/dependency-snyk.yml) | every PR + push + weekly | Snyk (no-ops gracefully without `SNYK_TOKEN`) |
| [`dast-zap.yml`](./.github/workflows/dast-zap.yml) | weekly + manual | OWASP ZAP baseline against the live origin; active scan manual-only |
| [`perf-nightly.yml`](./.github/workflows/perf-nightly.yml) | nightly + manual | k6 load + spike + workflow + stress |

## License

MIT
