package com.wallet.security.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT-layer attack surface: tampered, forged, mis-signed, expired, and
 * missing tokens. Every assertion checks that the request never reaches
 * the controller — i.e., that the security filter chain refuses with a
 * non-success status code, and the actual controller code path is never
 * executed.
 */
class JwtSecurityTest extends SecurityTestBase {

    @Autowired ObjectMapper objectMapper;
    @Value("${jwt.secret}") String jwtSecret;

    @Test
    void rejectsRequestWithNoAuthorizationHeader() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, null, String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsRequestWithAuthorizationHeaderMissingBearerPrefix() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("Authorization", "Token some.token.value");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, new org.springframework.http.HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsExpiredJwt() {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("alice@sec.test")
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() - 10_000))
                .signWith(key)
                .compact();
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(expired), String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsJwtSignedWithWrongSecret() {
        // Mint a JWT with a different but valid HMAC key.
        byte[] wrongKey = "wrong-secret-that-is-at-least-32-bytes-long-deadbeef".getBytes(StandardCharsets.UTF_8);
        SecretKey k = Keys.hmacShaKeyFor(wrongKey);
        String forged = Jwts.builder()
                .subject("attacker@sec.test")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(k)
                .compact();
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(forged), String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsJwtWithTamperedPayload() throws Exception {
        // Get a legitimate token and mutate one byte of its payload.
        String email = "tamper-target@sec.test";
        registerUser(email, "+254700009001");
        String good = login(email);

        String[] parts = good.split("\\.");
        char first = parts[1].charAt(0);
        char swapped = first == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + swapped + parts[1].substring(1) + "." + parts[2];

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(tampered), String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsJwtWithNoneAlgorithm() {
        // Hand-construct an alg:none JWT — header + payload + empty signature.
        String header = b64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = b64url("{\"sub\":\"attacker@sec.test\",\"exp\":" + (System.currentTimeMillis()/1000 + 600) + "}");
        String noneToken = header + "." + payload + ".";

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(noneToken), String.class);
        assertThat(res.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsJwtIssuedForUserWhoNoLongerExists() throws Exception {
        // Register a user, log in to get a real JWT, then delete the user
        // out from under the token. Subsequent calls with that JWT must fail.
        String email = "ghost@sec.test";
        registerUser(email, "+254700009002");
        String token = login(email);

        // Wipe the user (but the JWT is still cryptographically valid).
        jdbcTemplate.update("DELETE FROM wallets WHERE user_id = (SELECT id FROM users WHERE email = ?)", email);
        jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(token), String.class);
        // 401/403/404 are all acceptable here — the contract is "the
        // request must NOT succeed."
        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
    }

    /**
     * jjwt's parser does not advertise an `iss` (issuer) validation step in
     * the current configuration. Characterisation: a JWT signed with the
     * correct secret but with an unexpected `iss` claim is accepted today.
     * If issuer validation is added later, this assertion flips.
     */
    @Test
    void characterisation_jwtWithUnexpectedIssuerIsAcceptedToday() throws Exception {
        String email = "iss-target@sec.test";
        registerUser(email, "+254700009003");

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        String tokenWithUnexpectedIssuer = Jwts.builder()
                .subject(email)
                .issuer("some-attacker-controlled-issuer")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/wallet", HttpMethod.GET, bearerHeaders(tokenWithUnexpectedIssuer), String.class);
        // Current behaviour: accepted (jjwt parser ignores iss unless
        // explicitly required). Flip once issuer-validation is wired up.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
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
        JsonNode body = objectMapper.readTree(res.getBody());
        return body.path("data").path("token").asText();
    }

    private String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
