# Wallet API

A customer wallet system modeled after M-Pesa's core transaction engine. Handles user registration, deposits, withdrawals, and peer-to-peer transfers with transaction fees, double-entry bookkeeping, and idempotent operations — built on the same patterns that power mobile money platforms serving millions of daily transactions.

## Why This Design

Mobile money wallets fail in specific ways: lost money from partial transfers, double-debits from retried requests, and balances that don't reconcile at end of day. Every design choice here addresses a real failure mode:

| Problem | Solution | Implementation |
|---|---|---|
| Retry causes double-debit | Idempotency keys | Each request carries a unique key; duplicates return the original result |
| Transfer partially completes | Double-entry bookkeeping | Every movement creates balanced DEBIT + CREDIT ledger entries |
| Concurrent transfers corrupt balance | Optimistic locking | `@Version` on Wallet entity — concurrent writes fail safely and retry |
| Stolen PIN drains account | Bcrypt-hashed PINs | PINs stored as bcrypt hashes, never plaintext |
| Unauthorized access | JWT stateless auth | Token-based authentication, no server-side sessions |
| Fee disputes | Configurable fee structure | Transfer fees recorded as separate ledger-tracked transactions |

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2 |
| Language | Java 17 |
| Auth | Spring Security + JWT (jjwt) |
| Database | PostgreSQL (H2 for dev) |
| ORM | Spring Data JPA / Hibernate |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Containers | Docker + docker-compose |
| CI/CD | GitHub Actions |

## API Endpoints

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/auth/register` | Register user + create wallet | No |
| POST | `/api/auth/login` | Authenticate, receive JWT | No |
| GET | `/api/wallet` | Balance, limits, wallet info | JWT |
| POST | `/api/wallet/deposit` | Deposit funds | JWT |
| POST | `/api/wallet/withdraw` | Withdraw (requires PIN) | JWT |
| POST | `/api/wallet/transfer` | P2P transfer (requires PIN) | JWT |
| GET | `/api/wallet/transactions` | Paginated history, filterable by type | JWT |
| GET | `/api/wallet/statement` | Double-entry ledger statement | JWT |

## Transaction Flow

```
Client                     Wallet API                          Database
  |                            |                                  |
  |-- POST /transfer --------->|                                  |
  |   (JWT + PIN + idem key)   |                                  |
  |                            |-- verify JWT -------------------->|
  |                            |-- validate PIN (bcrypt) --------->|
  |                            |-- check idempotency key --------->|
  |                            |-- check sender balance ---------->|
  |                            |-- calculate fee ----------------->|
  |                            |                                  |
  |                            |-- BEGIN TRANSACTION              |
  |                            |   debit sender (amount + fee)     |
  |                            |   credit receiver (amount)        |
  |                            |   create DEBIT ledger entry       |
  |                            |   create CREDIT ledger entry      |
  |                            |   record fee transaction          |
  |                            |-- COMMIT                         |
  |                            |                                  |
  |<-- 200 TransferResponse ---|                                  |
```

## Running

```bash
mvn spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### With Docker

```bash
docker compose up   # PostgreSQL + wallet-api
```

## Usage

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Alice Wanjiku","email":"alice@example.com","phoneNumber":"+254700000001","password":"pass123","pin":"1234"}'

# Login (save token)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","password":"pass123"}' | jq -r '.data.token')

# Deposit
curl -X POST http://localhost:8080/api/wallet/deposit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"amount":50000,"idempotencyKey":"dep-001"}'

# Transfer
curl -X POST http://localhost:8080/api/wallet/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"recipientPhone":"+254700000002","amount":5000,"pin":"1234","idempotencyKey":"trf-001"}'
```

## Testing

```bash
mvn test   # 9 tests
```

Covers: deposit + balance update, withdrawal with valid/invalid PIN, insufficient balance, P2P transfer with fees, idempotency deduplication, self-transfer prevention, unknown recipient handling.

## License

MIT
