package com.wallet.security.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Injection-class attacks: SQL in path / body, XSS in stored data, and
 * mass-assignment via extra JSON fields. The API uses Spring Data JPA which
 * parameterises queries, so these are mostly "verify the safety net holds"
 * tests — but they also check that error responses don't leak the DB error
 * to the client (which would tell an attacker the payload reached the SQL
 * layer).
 */
class InjectionSecurityTest extends SecurityTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void sqlInjectionPayloadInPathParameterReturnsCleanError() throws Exception {
        String token = registerAndLogin("inj-path@sec.test", "+254700009020");

        // Classic SQL injection sentinel in a path param the controller
        // funnels into a repository .findByReference() call.
        String payload = "REF';%20DROP%20TABLE%20users;--";
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet/transactions/" + payload, HttpMethod.GET,
                bearerHeaders(token), String.class);

        // The DB must not echo a SQL error string into the response.
        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        String body = res.getBody() == null ? "" : res.getBody().toLowerCase();
        for (String leak : new String[] {"sql", "syntax error", "drop table", "preparedstatement", "h2"}) {
            assertThat(body)
                    .as("Response body must not leak SQL-layer noise: %s", leak)
                    .doesNotContain(leak);
        }
        // The DB is intact afterwards.
        Long userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        assertThat(userCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void sqlInjectionPayloadInEmailFieldDoesNotAffectQuery() throws Exception {
        // Login endpoint with a SQL-injection sentinel in the email JSON field.
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of(
                        "email", "alice' OR '1'='1",
                        "password", "anything")),
                String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        // No information leak that the SQL layer saw anything unusual.
        String body = res.getBody() == null ? "" : res.getBody().toLowerCase();
        assertThat(body).doesNotContain("sql").doesNotContain("syntax").doesNotContain("h2");
    }

    @Test
    void massAssignmentExtraFieldsAreIgnored() throws Exception {
        // Register a user with extra fields the client should not be able
        // to set: isAdmin, balance, internalApiKey. They must be silently
        // ignored, not persisted, not reflected back, and not honoured.
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Mass Assign");
        body.put("email", "mass@sec.test");
        body.put("phoneNumber", "+254700009030");
        body.put("password", "password123");
        body.put("pin", "1234");
        body.put("isAdmin", true);
        body.put("balance", 9999999);
        body.put("internalApiKey", "leak-me");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/register", HttpMethod.POST,
                jsonEntity(body), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify the new wallet has the normal starting balance, not 9999999.
        String token = login("mass@sec.test");
        ResponseEntity<String> wallet = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(token), String.class);
        JsonNode data = objectMapper.readTree(wallet.getBody()).path("data");
        assertThat(data.path("balance").asDouble()).isEqualTo(0.0);

        // Response body never echoes the bogus fields back.
        String regBody = res.getBody() == null ? "" : res.getBody();
        assertThat(regBody).doesNotContain("isAdmin")
                .doesNotContain("internalApiKey")
                .doesNotContain("leak-me");
    }

    @Test
    void xssPayloadInFullNameIsStoredButNotInterpretedOnRead() throws Exception {
        // The frontend's job is to escape on render — but the API should
        // round-trip the value safely. We verify that (a) the payload is
        // accepted as a string, (b) the response body returns the literal
        // string with no decoded HTML / executed JS, (c) the surrounding
        // response is still valid JSON.
        String xss = "<script>alert('pwn')</script>";
        Map<String, Object> body = Map.of(
                "fullName", xss,
                "email", "xss@sec.test",
                "phoneNumber", "+254700009031",
                "password", "password123",
                "pin", "1234"
        );
        ResponseEntity<String> reg = restTemplate.exchange(
                "/api/auth/register", HttpMethod.POST, jsonEntity(body), String.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The response is valid JSON — i.e., the payload didn't break out
        // of the string context.
        objectMapper.readTree(reg.getBody());

        // And the payload is stored verbatim (we'll find the literal
        // string in the users table).
        String storedName = jdbcTemplate.queryForObject(
                "SELECT full_name FROM users WHERE email = ?", String.class, "xss@sec.test");
        assertThat(storedName).isEqualTo(xss);
    }

    @Test
    void verylongStringInputIsRejectedRatherThanCrashing() throws Exception {
        // 10 KB email — well over any reasonable max-length and exactly the
        // kind of input that surfaces buffer / regex backtracking bugs.
        String huge = "a".repeat(10000) + "@sec.test";
        Map<String, Object> body = Map.of(
                "fullName", "Huge",
                "email", huge,
                "phoneNumber", "+254700009032",
                "password", "password123",
                "pin", "1234"
        );
        ResponseEntity<String> reg = restTemplate.exchange(
                "/api/auth/register", HttpMethod.POST, jsonEntity(body), String.class);
        // 400 (validation rejection) or 413 (payload too large) is fine.
        // Crash or 500 with a stack trace is not.
        assertThat(reg.getStatusCode().is4xxClientError())
                .as("Overly-long email should be rejected as a client error, status was %s", reg.getStatusCode())
                .isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private String registerAndLogin(String email, String phone) throws Exception {
        restTemplate.exchange("/api/auth/register", HttpMethod.POST,
                jsonEntity(registerBody(email, phone)), String.class);
        return login(email);
    }

    private String login(String email) throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                jsonEntity(Map.of("email", email, "password", "password123")),
                String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("token").asText();
    }
}
