package com.wallet.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Pact-JVM provider verification for the wallet-api.
 *
 * <p>The consumer pact is loaded from a local {@code pacts/} directory. The
 * directory is gitignored — neither developers nor CI commit it. Both seed it
 * the same way: a curl from
 * {@code raw.githubusercontent.com/jeffgicharu/wallet-app/<branch>/pacts/wallet-app-wallet-api.json}.
 * The {@code @PactUrl} loader was tried first but rejects the file because
 * raw.githubusercontent.com serves {@code .json} as {@code text/plain}; the
 * folder loader has no such constraint.
 *
 * <p>Local: copy the consumer's committed pact into the provider's pacts dir.
 * CI: a curl step before {@code mvn test} downloads the latest pact for the
 * tracking branch (consumer feature branch while in draft, then main).
 *
 * <p>Each interaction in the pact runs as a separate JUnit test invocation.
 * The {@link #verify(PactVerificationContext, HttpRequest)} template is invoked
 * once per interaction; before each invocation, the matching {@code @State}
 * handler seeds the database with whatever the consumer's "given" clause
 * declared. The handler also stores a real JWT for the seeded user in
 * {@link #currentToken}; the request filter swaps the placeholder
 * {@code Bearer test.jwt.token} header from the consumer pact for the real
 * one before the request is replayed.
 *
 * <p>Tests run against the default Spring profile (H2 in-memory), which is
 * sufficient for HTTP-shape contract verification. Postgres-specific concerns
 * (dialect quirks, optimistic-locking, schema drift) are covered by the
 * Testcontainers integration suite, not this contract verification.
 */
@Provider("wallet-api")
@PactFolder("pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WalletApiProviderPactTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate restTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    /** Real JWT for the user seeded by the current state handler. */
    private String currentToken;

    @BeforeEach
    void setUp(PactVerificationContext context) {
        if (context != null) {
            context.setTarget(new HttpTestTarget("localhost", port, "/"));
        }
        currentToken = null;
        wipeDatabase();
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context, HttpRequest request) {
        // Replace the consumer's placeholder bearer token with the real JWT
        // minted by the state handler. Interactions that don't expect an
        // Authorization header (register, login) leave currentToken null and
        // this branch is a no-op.
        if (currentToken != null && request.getFirstHeader("Authorization") != null) {
            request.removeHeaders("Authorization");
            request.addHeader("Authorization", "Bearer " + currentToken);
        }
        context.verifyInteraction();
    }

    // ─── State handlers ──────────────────────────────────────────────

    @State("no user with email alice@demo.local or phone +254700000099 exists")
    void state_emptyDatabase() {
        // wipe handled by @BeforeEach; nothing further to seed.
    }

    @State("alice exists with balance 50000 KES")
    void state_aliceExistsWithBalance50k() {
        registerAlice();
        loginAsAlice();
        deposit(50000, "state-dep-alice-50k");
    }

    @State("alice has balance 50000 KES and bob is registered with phone +254700000002")
    void state_aliceAndBobBothRegistered() {
        registerAlice();
        registerBob();
        loginAsAlice();
        deposit(50000, "state-dep-pair-50k");
    }

    @State("alice exists; phone +254799000000 is not registered")
    void state_aliceExistsRecipientUnknown() {
        registerAlice();
        loginAsAlice();
        deposit(50000, "state-dep-nf-50k");
        // do not register +254799000000
    }

    @State("alice has previously transferred with idempotency key idem-pact-dup")
    void state_aliceHasPriorIdempotentTransfer() {
        registerAlice();
        registerBob();
        loginAsAlice();
        deposit(50000, "state-dep-dup-50k");
        // Seed a transfer with the exact idempotency key the consumer will
        // retry. The retry triggers DuplicateTransactionException → 409.
        transferAliceToBob(5000, "1234", "idem-pact-dup");
    }

    @State("alice has balance 100 KES and bob is registered with phone +254700000002")
    void state_alicePoorBobRegistered() {
        registerAlice();
        registerBob();
        loginAsAlice();
        deposit(100, "state-dep-poor-100");
    }

    @State("alice has at least one historical transaction")
    void state_aliceHasHistory() {
        registerAlice();
        loginAsAlice();
        deposit(7500, "state-dep-history");
    }

    @State("alice has at least one DEPOSIT transaction")
    void state_aliceHasDeposit() {
        registerAlice();
        loginAsAlice();
        deposit(2500, "state-dep-filtered");
    }

    @State("alice has a deposit with reference DEP-pact-lookup")
    void state_aliceHasSpecificDeposit() {
        registerAlice();
        loginAsAlice();
        // The reference is generated as "DEP-" + idempotencyKey, so passing
        // "pact-lookup" produces the exact reference the consumer expects.
        deposit(3000, "pact-lookup");
    }

    @State("alice has a completed deposit with reference DEP-pact-rev")
    void state_aliceHasReversibleDeposit() {
        registerAlice();
        loginAsAlice();
        deposit(2000, "pact-rev");
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private void wipeDatabase() {
        // Order matters because of FK relationships. H2's TRUNCATE doesn't
        // CASCADE the way Postgres' does, so explicit DELETEs in dependency
        // order are the portable choice.
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM audit_logs");
        jdbcTemplate.execute("DELETE FROM wallets");
        jdbcTemplate.execute("DELETE FROM users");
    }

    private void registerAlice() {
        register("Alice Demo", "alice@demo.local", "+254700000001", "password123", "1234");
    }

    private void registerBob() {
        register("Bob Demo", "bob@demo.local", "+254700000002", "password123", "1234");
    }

    private void register(String name, String email, String phone, String pw, String pin) {
        Map<String, Object> body = Map.of(
                "fullName", name,
                "email", email,
                "phoneNumber", phone,
                "password", pw,
                "pin", pin);
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/register", HttpMethod.POST, jsonEntity(body), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Register failed: " + res.getStatusCode() + " " + res.getBody());
        }
    }

    private void loginAsAlice() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("email", "alice@demo.local", "password", "password123")),
                String.class);
        try {
            JsonNode body = objectMapper.readTree(res.getBody());
            currentToken = body.path("data").path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Login parse failed", e);
        }
    }

    private void deposit(int amount, String idempotencyKey) {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(currentToken, Map.of("amount", amount, "idempotencyKey", idempotencyKey)),
                String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Deposit failed: " + res.getStatusCode() + " " + res.getBody());
        }
    }

    private void transferAliceToBob(int amount, String pin, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "recipientPhone", "+254700000002",
                "amount", amount,
                "pin", pin,
                "idempotencyKey", idempotencyKey);
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transfer", HttpMethod.POST,
                authedJsonEntity(currentToken, body), String.class);
        if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Transfer failed: " + res.getStatusCode() + " " + res.getBody());
        }
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    private HttpEntity<Object> authedJsonEntity(String bearer, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(bearer);
        return new HttpEntity<>(body, h);
    }
}
