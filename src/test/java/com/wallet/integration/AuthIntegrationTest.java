package com.wallet.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.integration.support.IntegrationTestBase;
import com.wallet.integration.support.TestData;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper objectMapper;
    @Value("${jwt.secret}") String jwtSecret;

    @Test
    void shouldRegisterNewUserAndCreateWalletRow() throws Exception {
        String phone = TestData.uniquePhone();
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail("alice"), phone)),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").path("token").asText()).isNotEmpty();

        Long userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        Long walletCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets", Long.class);
        assertThat(userCount).isEqualTo(1L);
        assertThat(walletCount).isEqualTo(1L);
    }

    @Test
    void shouldRejectRegistrationWhenEmailAlreadyExists() {
        String email = TestData.uniqueEmail("dup-email");
        registerOk(email, TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(email, TestData.uniquePhone())),
                String.class);

        // AuthService throws IllegalArgumentException → BAD_REQUEST (400)
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldRejectRegistrationWhenPhoneAlreadyExists() {
        String phone = TestData.uniquePhone();
        registerOk(TestData.uniqueEmail("a"), phone);

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(TestData.uniqueEmail("b"), phone)),
                String.class);

        // AuthService throws IllegalArgumentException → BAD_REQUEST (400)
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldReturnJwtOnSuccessfulLogin() throws Exception {
        String email = TestData.uniqueEmail("login-ok");
        registerOk(email, TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/login",
                jsonEntity(Map.of("email", email, "password", "password123")),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(res.getBody());
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotEmpty();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    void shouldRejectLoginWithWrongPasswordAndIssueNoToken() throws Exception {
        String email = TestData.uniqueEmail("login-bad");
        registerOk(email, TestData.uniquePhone());

        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/login",
                jsonEntity(Map.of("email", email, "password", "WRONG")),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = objectMapper.readTree(res.getBody());
        assertThat(body.path("data").isMissingNode() || body.path("data").isNull()).isTrue();
    }

    @Test
    void shouldRejectExpiredJwtOnProtectedEndpoint() {
        // Forge a JWT signed with the same secret but with exp in the past.
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        String expired = Jwts.builder()
                .subject("alice@test.local")
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() - 10_000))
                .signWith(key)
                .compact();

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(expired), String.class);

        // The JwtAuthenticationFilter rejects the parse → SecurityFilterChain
        // returns the standard "no auth" status. With this app's config that
        // surfaces as 403 (no AuthenticationEntryPoint customisation). The
        // important assertion is "the request did not reach the controller."
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void shouldRejectTamperedJwtOnProtectedEndpoint() throws Exception {
        // Get a real token, then mutate its payload while keeping the original
        // signature. The signature no longer matches → server must reject.
        String email = TestData.uniqueEmail("tamper");
        String good = registerAndLogin(email, TestData.uniquePhone());

        String[] parts = good.split("\\.");
        // Replace one character in the payload with a different valid base64url char.
        char first = parts[1].charAt(0);
        char swapped = first == 'A' ? 'B' : 'A';
        String tamperedPayload = swapped + parts[1].substring(1);
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, authedHeaders(tampered), String.class);

        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private void registerOk(String email, String phone) {
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/register",
                jsonEntity(registerBody(email, phone)),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String registerAndLogin(String email, String phone) throws Exception {
        registerOk(email, phone);
        ResponseEntity<String> res = restTemplate.postForEntity(
                "/api/auth/login",
                jsonEntity(Map.of("email", email, "password", "password123")),
                String.class);
        return objectMapper.readTree(res.getBody()).path("data").path("token").asText();
    }
}
