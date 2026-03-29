# Wallet API

This is a mobile money wallet, similar to M-Pesa's backend. Users register, get a wallet, and can deposit, withdraw, or send money to each other by phone number.

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

Spring Boot 3.2, Java 17, Spring Security + JWT, Spring Data JPA, PostgreSQL (H2 for dev), Docker, GitHub Actions CI.

## Tests

```bash
mvn test   # 21 tests
```

**Unit tests (9):** deposit and balance update, withdrawal with valid/invalid PIN, insufficient balance, P2P transfer with fee calculation, idempotency deduplication, self-transfer prevention, unknown recipient handling.

**Integration tests (12):** full auth flow, duplicate registration rejection, deposit through HTTP, transfer through HTTP, unauthenticated request rejection, transaction lookup by reference, deposit reversal with balance verification, admin stats, wallet freeze blocking transactions, per-wallet reconciliation, wrong PIN through HTTP, daily limit info in wallet response.

## License

MIT
