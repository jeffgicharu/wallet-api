package com.wallet.security.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-user data-isolation guarantees. Alice authenticated with her own JWT
 * must never see Bob's data, and the API surface must never leak information
 * that a row exists at all when she doesn't own it.
 */
class CrossUserIsolationSecurityTest extends SecurityTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void aliceCannotSeeBobsTransactionsViaHerOwnHistoryEndpoint() throws Exception {
        registerUser("alice-iso@sec.test", "+254700009010");
        registerUser("bob-iso@sec.test", "+254700009011");
        String aliceToken = login("alice-iso@sec.test");
        String bobToken = login("bob-iso@sec.test");
        deposit(aliceToken, 1000, "iso-alice");
        deposit(bobToken, 9999, "iso-bob");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions?page=0&size=50", HttpMethod.GET,
                bearerHeaders(aliceToken), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode content = objectMapper.readTree(res.getBody())
                .path("data").path("content");
        // Alice's view contains her deposit and nothing else.
        assertThat(content.size()).isEqualTo(1);
        // Specifically, no amount of 9999 (Bob's deposit) leaks through.
        boolean leaked = false;
        for (JsonNode n : content) {
            if (n.path("amount").asDouble() == 9999.0) leaked = true;
        }
        assertThat(leaked).as("Bob's 9999 deposit must not appear in Alice's history").isFalse();
    }

    /**
     * Characterisation of [issue #20](https://github.com/jeffgicharu/wallet-api/issues/20):
     * `GET /api/wallet/transactions/{ref}` does not check the authenticated
     * owner. Alice authenticated with her own JWT can fetch any transaction
     * in the system if she guesses the reference — a direct cross-user
     * data leak.
     *
     * <p>The test pins the broken behaviour (Alice gets 200 with Bob's body)
     * so when issue #20 is fixed, the assertion flips and forces the test
     * to be updated to assert 404 (preferred) or 403.
     */
    @Test
    void issueCharacterisation_aliceCanLookUpBobsTransactionByReferenceToday() throws Exception {
        registerUser("alice-ref@sec.test", "+254700009012");
        registerUser("bob-ref@sec.test", "+254700009013");
        String aliceToken = login("alice-ref@sec.test");
        String bobToken = login("bob-ref@sec.test");
        deposit(aliceToken, 1000, "ref-alice");
        String bobDepRef = deposit(bobToken, 5000, "ref-bob");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions/" + bobDepRef, HttpMethod.GET,
                bearerHeaders(aliceToken), String.class);

        // Current (buggy) behaviour: 200 with Bob's transaction body.
        // When #20 closes, flip to: assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        assertThat(data.path("reference").asText()).isEqualTo(bobDepRef);
    }

    @Test
    void aliceGetsHerOwnBalanceNotBobsEvenIfBobHasMoreMoney() throws Exception {
        registerUser("alice-bal@sec.test", "+254700009014");
        registerUser("bob-bal@sec.test", "+254700009015");
        String aliceToken = login("alice-bal@sec.test");
        String bobToken = login("bob-bal@sec.test");
        deposit(aliceToken, 100, "bal-alice");
        deposit(bobToken, 99999, "bal-bob");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(aliceToken), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(res.getBody()).path("data");
        // Alice sees 100, not 99999, not 100099 — only her balance.
        assertThat(data.path("balance").asDouble()).isEqualTo(100.0);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private void registerUser(String email, String phone) {
        restTemplate.exchange("/api/auth/register", HttpMethod.POST,
                jsonEntity(registerBody(email, phone)), String.class);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("email", email, "password", "password123")),
                String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("token").asText();
    }

    private String deposit(String token, int amount, String key) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of("amount", amount, "idempotencyKey", key)),
                String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("reference").asText();
    }
}
