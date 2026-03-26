# Wallet API

An M-Pesa-style customer wallet system built with Spring Boot. Supports user registration, deposits, withdrawals, and peer-to-peer transfers with transaction fees, double-entry bookkeeping, and idempotent operations.

## Features

- **User Registration & Authentication** - JWT-based stateless auth with bcrypt password hashing
- **Wallet Management** - Each user gets a KES wallet on registration
- **Deposits** - Add funds to your wallet
- **Withdrawals** - Withdraw funds with PIN verification
- **P2P Transfers** - Send money to any registered user by phone number
- **Transaction Fees** - Configurable percentage fee on transfers (default 1%)
- **Double-Entry Ledger** - Every transaction creates balanced debit/credit entries with balance snapshots
- **Idempotency** - Duplicate requests are safely rejected using idempotency keys
- **Optimistic Locking** - Prevents race conditions on concurrent balance updates
- **Paginated History** - Transaction history and account statements with pagination
- **Swagger UI** - Interactive API documentation

## Tech Stack

| Component | Technology |
|---|---|
| Framework | Spring Boot 3.2 |
| Language | Java 17 |
| Security | Spring Security + JWT (jjwt) |
| Database | H2 (dev) / PostgreSQL (prod) |
| ORM | Spring Data JPA / Hibernate |
| Validation | Jakarta Bean Validation |
| API Docs | SpringDoc OpenAPI (Swagger UI) |
| Build | Maven |
| Testing | JUnit 5 + Spring Boot Test |

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+

### Run with H2 (zero setup)

```bash
mvn spring-boot:run
```

The app starts on `http://localhost:8080` with an in-memory H2 database. No external database needed.

### Run with PostgreSQL

1. Create a database:
   ```sql
   CREATE DATABASE walletdb;
   ```

2. Start with the postgres profile:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=postgres
   ```

   Or set environment variables to override defaults:
   ```bash
   export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/walletdb
   export SPRING_DATASOURCE_USERNAME=your_user
   export SPRING_DATASOURCE_PASSWORD=your_password
   ```

### Build & Run as JAR

```bash
mvn clean package
java -jar target/wallet-api-1.0.0.jar
```

## API Endpoints

### Authentication

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| POST | `/api/auth/register` | Register a new user | No |
| POST | `/api/auth/login` | Login and get JWT token | No |

### Wallet Operations

| Method | Endpoint | Description | Auth |
|---|---|---|---|
| GET | `/api/wallet` | Get wallet balance and info | JWT |
| POST | `/api/wallet/deposit` | Deposit funds | JWT |
| POST | `/api/wallet/withdraw` | Withdraw funds (requires PIN) | JWT |
| POST | `/api/wallet/transfer` | P2P transfer (requires PIN) | JWT |
| GET | `/api/wallet/transactions` | Paginated transaction history | JWT |
| GET | `/api/wallet/statement` | Double-entry ledger statement | JWT |

### Swagger UI

Once the app is running, visit: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## Usage Examples

### Register a user

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Alice Wanjiku",
    "email": "alice@example.com",
    "phoneNumber": "+254700000001",
    "password": "password123",
    "pin": "1234"
  }'
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "password": "password123"
  }'
```

### Deposit

```bash
curl -X POST http://localhost:8080/api/wallet/deposit \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "amount": 50000.00,
    "idempotencyKey": "dep-001",
    "description": "Salary deposit"
  }'
```

### Transfer money

```bash
curl -X POST http://localhost:8080/api/wallet/transfer \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "recipientPhone": "+254700000002",
    "amount": 5000.00,
    "pin": "1234",
    "idempotencyKey": "trf-001",
    "description": "Lunch money"
  }'
```

### Check balance

```bash
curl http://localhost:8080/api/wallet \
  -H "Authorization: Bearer <token>"
```

## Project Structure

```
src/main/java/com/wallet/
├── WalletApplication.java          # Entry point
├── config/
│   ├── SecurityConfig.java         # Spring Security + JWT filter chain
│   └── OpenApiConfig.java          # Swagger/OpenAPI configuration
├── controller/
│   ├── AuthController.java         # Registration and login endpoints
│   └── WalletController.java       # Wallet operation endpoints
├── dto/
│   ├── request/                    # Validated request DTOs
│   └── response/                   # Response DTOs
├── entity/
│   ├── User.java                   # User account
│   ├── Wallet.java                 # Wallet with optimistic locking
│   ├── Transaction.java            # Transaction record
│   └── LedgerEntry.java            # Double-entry ledger
├── enums/
│   ├── TransactionType.java        # DEPOSIT, WITHDRAWAL, TRANSFER, FEE
│   ├── TransactionStatus.java      # PENDING, COMPLETED, FAILED, REVERSED
│   └── EntryType.java              # DEBIT, CREDIT
├── exception/
│   ├── GlobalExceptionHandler.java # Centralized error handling
│   ├── InsufficientBalanceException.java
│   ├── DuplicateTransactionException.java
│   └── InvalidPinException.java
├── repository/                     # Spring Data JPA repositories
├── security/
│   ├── JwtTokenProvider.java       # JWT generation and validation
│   ├── JwtAuthenticationFilter.java # Request filter
│   └── UserDetailsServiceImpl.java # User loading for Spring Security
└── service/
    ├── AuthService.java            # Registration and login logic
    └── WalletService.java          # Core wallet operations
```

## Design Decisions

### Double-Entry Bookkeeping

Every transaction creates corresponding ledger entries. A transfer creates:
- A **DEBIT** entry on the sender's wallet (amount + fee)
- A **CREDIT** entry on the receiver's wallet (amount)

Each ledger entry records `balanceBefore` and `balanceAfter`, providing a complete audit trail.

### Idempotency

All mutating operations require an `idempotencyKey`. If a request is retried with the same key, it is rejected with HTTP 409 Conflict. This prevents duplicate charges from network retries or client bugs.

### Optimistic Locking

The `Wallet` entity uses `@Version` for optimistic locking. If two concurrent requests try to modify the same wallet, one will fail with a conflict error and can be safely retried.

### PIN Security

PINs are bcrypt-hashed (not stored in plaintext). They are required for withdrawals and transfers but not for deposits or balance checks.

## Configuration

Key settings in `application.yml`:

| Property | Default | Description |
|---|---|---|
| `wallet.default-currency` | KES | Default currency for new wallets |
| `wallet.transfer-fee-percent` | 1.0 | Fee percentage on transfers |
| `wallet.max-transfer-amount` | 500000.00 | Maximum single transfer |
| `wallet.min-transfer-amount` | 10.00 | Minimum single transfer |
| `jwt.expiration-ms` | 86400000 | JWT token TTL (24 hours) |

## Running Tests

```bash
mvn test
```

9 tests covering:
- Wallet balance after deposit
- Withdrawal with valid/invalid PIN
- Insufficient balance rejection
- P2P transfer with fee calculation
- Idempotency key duplicate rejection
- Self-transfer prevention
- Unknown recipient handling

## License

MIT
