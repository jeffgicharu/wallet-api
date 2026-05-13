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
 * Sensitive-data exposure surface. Error responses, audit logs, and
 * routine response bodies must never leak passwords, PINs, JWT secrets,
 * stack traces, or internal field names.
 */
class SensitiveDataSecurityTest extends SecurityTestBase {

    @Autowired ObjectMapper objectMapper;

    @Test
    void registerResponseDoesNotIncludePasswordOrPinHash() throws Exception {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/register", HttpMethod.POST,
                jsonEntity(registerBody("nosecret@sec.test", "+254700009050")),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String body = res.getBody() == null ? "" : res.getBody().toLowerCase();
        // No password / pin / hash field of any kind in the response.
        for (String forbidden : new String[] {"password", "passwordhash", "passwordHash", "pin\"", "pinhash", "bcrypt"}) {
            assertThat(body)
                    .as("Register response must not leak: %s", forbidden)
                    .doesNotContain(forbidden.toLowerCase());
        }
    }

    @Test
    void walletResponseDoesNotIncludeInternalFieldsOrUserPassword() throws Exception {
        String token = registerAndLogin("nointernal@sec.test", "+254700009051");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = res.getBody() == null ? "" : res.getBody().toLowerCase();
        for (String forbidden : new String[] {"password", "pin", "version\"", "bcrypt", "$2a$", "internal_"}) {
            assertThat(body)
                    .as("Wallet response must not leak: %s", forbidden)
                    .doesNotContain(forbidden.toLowerCase());
        }
    }

    @Test
    void errorResponseFromBadJsonDoesNotLeakStackTrace() {
        // Send malformed JSON to a real endpoint.
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{not valid json", h),
                String.class);

        String body = res.getBody() == null ? "" : res.getBody();
        // Never leak stack-frame markers, class names with full packages,
        // or generic Java exception types.
        for (String leak : new String[] {
                "at org.springframework",
                "at com.wallet",
                "Exception:",
                "JsonParseException",
                "Caused by:",
                "java.lang."}) {
            assertThat(body)
                    .as("Error body must not leak stack-trace fragment: %s", leak)
                    .doesNotContain(leak);
        }
    }

    @Test
    void auditLogDoesNotPersistRawPasswordOrPin() throws Exception {
        // Register a user and trigger an admin action to ensure audit_logs gets a row.
        String token = registerAndLogin("audit-target@sec.test", "+254700009052");
        deposit(token, 500, "audit-dep");
        // The AuditLog entity column is `detail`, not `description`.
        Long passwordMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE detail LIKE '%password123%'",
                Long.class);
        assertThat(passwordMatches).isZero();
    }

    /**
     * The deployment-readiness PR (#1, `feat/env-driven-configuration`) wires up
     * a `CorsConfigurationSource` driven by `APP_CORS_ALLOWED_ORIGINS`. On `main`
     * the security filter chain has no CORS, so OPTIONS preflights are rejected
     * by the security layer regardless of origin. The CORS assertions land green
     * once PR #1 merges and this branch rebases.
     */
    @org.junit.jupiter.api.Disabled("CORS config lives on PR #1; test passes once PR #1 merges to main")
    @Test
    void corsPreflightFromAllowedOriginReturnsAccessControlHeaders() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("Origin", "https://wallet.jeffgicharu.com");
        h.set("Access-Control-Request-Method", "POST");
        h.set("Access-Control-Request-Headers", "Content-Type, Authorization");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.OPTIONS,
                new org.springframework.http.HttpEntity<>(h), String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        String acao = res.getHeaders().getFirst("Access-Control-Allow-Origin");
        assertThat(acao).isNotNull();
        assertThat(acao).isNotEqualTo("*");
    }

    @org.junit.jupiter.api.Disabled("CORS config lives on PR #1; test passes once PR #1 merges to main")
    @Test
    void corsPreflightFromDisallowedOriginIsRejected() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("Origin", "https://evil.example.com");
        h.set("Access-Control-Request-Method", "POST");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/auth/login", HttpMethod.OPTIONS,
                new org.springframework.http.HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
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

    private void deposit(String token, int amount, String key) {
        restTemplate.exchange("/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of("amount", amount, "idempotencyKey", key)),
                String.class);
    }
}
