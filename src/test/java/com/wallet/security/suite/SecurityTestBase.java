package com.wallet.security.suite;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * Shared base for the custom security-test suite.
 *
 * <p>Runs the full Spring Boot context on a random port against the default
 * H2 profile so the test surface mirrors what the integration tests already
 * exercise. Each {@code @BeforeEach} wipes the five domain tables so tests
 * have no order dependency.
 *
 * <p>The suite focuses on what static-analysis can't see: JWT tampering and
 * forgery, cross-user data leakage, injection payloads in HTTP, sensitive
 * data exposure in error responses, and characterisations of known weak
 * spots (rate limiting, PIN-lockout rollback).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class SecurityTestBase {

    @Autowired protected TestRestTemplate restTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @LocalServerPort protected int port;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
                "DELETE FROM ledger_entries; " +
                "DELETE FROM transactions; " +
                "DELETE FROM audit_logs; " +
                "DELETE FROM wallets; " +
                "DELETE FROM users;");
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    protected HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Object> authedJsonEntity(String bearer, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return new HttpEntity<>(body, headers);
    }

    protected HttpEntity<Void> bearerHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return new HttpEntity<>(headers);
    }

    protected Map<String, Object> registerBody(String email, String phone) {
        return Map.of(
                "fullName", "Sec Test " + email,
                "email", email,
                "phoneNumber", phone,
                "password", "password123",
                "pin", "1234"
        );
    }
}
