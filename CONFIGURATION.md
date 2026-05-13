# Configuration

The application uses Spring profiles to switch between three modes. The default has zero setup and uses H2 in memory; `postgres` matches the bundled `docker-compose.yml`; `prod` reads every connection setting from the environment with no fallback.

## Profiles

| Profile | When to use | Database | JWT secret | CORS origins |
|---|---|---|---|---|
| _(default)_ | local development, integration tests | H2 in-memory | hard-coded dev secret | `http://localhost:3002` |
| `postgres` | `docker compose up`, dev against a local Postgres | PostgreSQL (env-overridable, defaults to `localhost:5432/walletdb` user `postgres`) | env or hard-coded dev secret | `http://localhost:3002` |
| `prod` | live deployment | PostgreSQL — env-only, no defaults | env-only, no default | env-only, no default |

Activate a non-default profile with `SPRING_PROFILES_ACTIVE`:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar wallet-api.jar
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | _(none — uses default profile)_ | Comma-separated profile list. Set to `prod` for live deployments. |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/walletdb` (only in `postgres` profile) | JDBC URL. **Required in `prod`.** |
| `SPRING_DATASOURCE_USERNAME` | `postgres` (only in `postgres` profile) | DB user. **Required in `prod`.** |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` (only in `postgres` profile) | DB password. **Required in `prod`.** |
| `APP_JWT_SECRET` | hard-coded dev secret in default and `postgres` profiles | HMAC-SHA key for signing JWTs. **Required in `prod`.** Should be at least 32 bytes of high-entropy random data. |
| `APP_JWT_EXPIRATION_MS` | `86400000` (24 h) | Token lifetime in milliseconds. |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:3002` (default and `postgres` profiles) | Comma-separated list of origins allowed to call the API from a browser. **Required in `prod`** — the deployed frontend's public URL belongs here, e.g. `https://wallet.jeffgicharu.com`. Multiple origins separated by commas: `https://wallet.jeffgicharu.com,https://staging.wallet.jeffgicharu.com`. |
| `SERVER_PORT` | `8080` | HTTP listen port. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` (only in `postgres` profile) | OTLP endpoint for traces. Disabled by default unless an OTel collector is reachable. |

## Quick recipes

**Local dev (no DB needed):**

```bash
mvn spring-boot:run
# H2 console: http://localhost:8080/h2-console
# Swagger:    http://localhost:8080/swagger-ui.html
```

**Local Postgres via docker-compose:**

```bash
docker compose up
```

**Production (systemd or container):**

Provide every variable in the table above marked _Required in `prod`_. A typical `EnvironmentFile` for a systemd service:

```ini
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/wallet
SPRING_DATASOURCE_USERNAME=wallet
SPRING_DATASOURCE_PASSWORD=...
APP_JWT_SECRET=...
APP_CORS_ALLOWED_ORIGINS=https://wallet.jeffgicharu.com
SERVER_PORT=8081
JAVA_OPTS=-Xmx256m -Xms128m -XX:+UseSerialGC
```

The `prod` profile fails fast at startup if any required variable is missing — there are no fallback values for connection or signing settings.
