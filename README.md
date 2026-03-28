# Wallet API

This is a mobile money wallet — think M-Pesa's backend. Users register, get a wallet, and can deposit, withdraw, or send money to each other by phone number.

The interesting part isn't the CRUD — it's the things that go wrong with money. What happens when a transfer request is sent twice because of a network glitch? What if two transfers hit the same wallet at the same time? What if you need to prove exactly where every shilling went at end of day? This project handles all of that.

## What It Does

- **Register and login** with JWT authentication
- **Deposit and withdraw** funds (withdrawals require a 4-digit PIN)
- **Send money** to any registered user by phone number, with automatic fee calculation
- **Transaction history** with filtering by type (deposits, withdrawals, transfers)
- **Account statements** showing a full double-entry ledger — every debit has a matching credit

## How Money Stays Safe

A few things make this different from a basic wallet tutorial:

**Double-entry bookkeeping** — When Alice sends Bob KES 5,000, the system doesn't just subtract from Alice and add to Bob. It creates four ledger entries: a DEBIT on Alice's account, a CREDIT on Bob's account, and separate entries for the transfer fee. At any point, you can run the statement endpoint and verify that debits equal credits. This is how real financial systems work.

**Idempotency** — Every request that moves money requires an `idempotencyKey`. If the client retries the same request (because the network dropped the response), the system recognizes the key and returns the original result instead of processing the transfer again. No double charges.

**Optimistic locking** — The wallet entity uses `@Version`. If two concurrent requests try to modify the same balance, the second one fails with a conflict instead of silently corrupting the data.

**PIN security** — PINs are bcrypt-hashed. They're validated on withdrawals and transfers but never stored or returned in plaintext.

## Quick Start

```bash
mvn spring-boot:run
# Open http://localhost:8080/swagger-ui.html
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
```

## API Reference

| Method | Endpoint | What it does | Auth |
|---|---|---|---|
| POST | `/api/auth/register` | Create account + wallet | No |
| POST | `/api/auth/login` | Get JWT token | No |
| GET | `/api/wallet` | Check balance and wallet info | JWT |
| POST | `/api/wallet/deposit` | Add funds | JWT |
| POST | `/api/wallet/withdraw` | Withdraw (needs PIN) | JWT |
| POST | `/api/wallet/transfer` | Send money (needs PIN) | JWT |
| GET | `/api/wallet/transactions` | Transaction history (filterable) | JWT |
| GET | `/api/wallet/statement` | Double-entry ledger | JWT |

## Built With

Spring Boot 3.2, Java 17, Spring Security + JWT, Spring Data JPA, PostgreSQL (H2 for dev), Docker, GitHub Actions CI.

## Tests

```bash
mvn test   # 9 tests
```

Covers deposits, withdrawals with valid/invalid PIN, insufficient balance, P2P transfers with fee calculation, idempotency deduplication, self-transfer prevention, and unknown recipient handling.

## License

MIT
