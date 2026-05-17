package com.wallet.security.suite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate-limiting and abuse-prevention surface. Several of these tests
 * <em>fail loudly</em> in the current codebase because the relevant
 * mitigations are not implemented — when that happens they ship as
 * {@link Disabled} with an explicit link to the tracking issue so the
 * gate {@code mvn verify -B} stays green and the gap is visible.
 */
// Pin the login rate limit to the production value (5 / minute) for
// this class only. @TestPropertySource forks a dedicated context with
// its own in-memory bucket map, so the limit is deterministic here and
// the rest of the suite (raised ceiling via surefire) is unaffected.
@TestPropertySource(properties = {
        "security.login-rate-limit.max-attempts=5",
        "security.login-rate-limit.window-seconds=60"
})
class RateLimitingSecurityTest extends SecurityTestBase {

    @Autowired ObjectMapper objectMapper;

    /**
     * Issue #21 fixed: LoginRateLimitFilter throttles /api/auth/login to
     * 5 attempts / minute / IP (pinned via @TestPropertySource above).
     * The 6th rapid attempt returns 429 with a Retry-After header.
     */
    @Test
    void shouldRateLimitRapidFailedLoginAttempts() {
        Map<String, Object> body = Map.of(
                "email", "rate-target@sec.test",
                "password", "WRONG"
        );

        // The rate-limit bucket is keyed by client IP and shared across
        // every test in this context. Sibling tests' logins would drain
        // the shared localhost bucket before this test runs, so pin a
        // unique X-Forwarded-For first hop — the filter keys a fresh
        // bucket off it, isolating this assertion.
        org.springframework.http.HttpHeaders h =
                new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", "203.0.113.77");
        org.springframework.http.HttpEntity<Object> req =
                new org.springframework.http.HttpEntity<>(body, h);

        int firstThrottledAt = -1;
        ResponseEntity<String> throttled = null;
        for (int i = 1; i <= 20; i++) {
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/auth/login", HttpMethod.POST,
                    req, String.class);
            if (res.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                firstThrottledAt = i;
                throttled = res;
                break;
            }
            // Before the limit trips, a bad password is a normal 401.
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Capacity is 5: throttling must kick in within the first
        // handful of attempts (the exact attempt depends on the token
        // bucket's initial-state semantics; 5 or 6 are both correct for
        // a 5/min limit). The guarantee that matters: an attacker cannot
        // run unbounded online password attempts.
        assertThat(firstThrottledAt)
                .as("rapid failed logins must be throttled within ~5 attempts")
                .isBetween(5, 7);
        assertThat(throttled).isNotNull();
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    /**
     * Issue #9 fixed: the failed-PIN counter persists in a REQUIRES_NEW
     * transaction, so the 3-strike lockout actually fires. A PIN
     * brute-forcer is stopped at the 3rd attempt (409 account locked)
     * rather than running unbounded.
     */
    @Test
    void pinLockoutTriggersOnThirdWrongAttempt() throws Exception {
        String token = registerAndLogin("pin-brute@sec.test", "+254700009040");
        deposit(token, 5000, "pin-brute-dep");

        int lockedAt = -1;
        for (int i = 1; i <= 50; i++) {
            Map<String, Object> body = Map.of(
                    "amount", 100,
                    "pin", "9999",
                    "idempotencyKey", "brute-" + i
            );
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/wallet/withdraw", HttpMethod.POST,
                    authedJsonEntity(token, body), String.class);
            if (res.getStatusCode() == HttpStatus.CONFLICT) {
                lockedAt = i;
                break;
            }
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // 2 x 401 then the 3rd attempt locks (409). The brute-forcer
        // cannot keep guessing.
        assertThat(lockedAt)
                .as("PIN lockout must fire by the 3rd wrong attempt (issue #9)")
                .isEqualTo(3);
    }

    @Test
    void dailyTransferLimitBreaksThroughRepeatedSmallTransfers() throws Exception {
        // 60 × 5000 KES = 300,000 sits exactly AT the daily limit and just
        // passes; 70 × 5000 = 350,000 goes over. Issue 70 attempts to make
        // the assertion robust against the limit's boundary semantics.
        String alice = registerAndLogin("daily-attack@sec.test", "+254700009041");
        String bobPhone = "+254700009042";
        register("daily-victim@sec.test", bobPhone);
        deposit(alice, 500_000, "daily-att-dep");

        boolean dailyLimitTriggered = false;
        for (int i = 0; i < 70; i++) {
            Map<String, Object> body = new HashMap<>();
            body.put("recipientPhone", bobPhone);
            body.put("amount", 5000);
            body.put("pin", "1234");
            body.put("idempotencyKey", "daily-" + i);
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/wallet/transfer", HttpMethod.POST,
                    authedJsonEntity(alice, body), String.class);
            if (res.getStatusCode() == HttpStatus.BAD_REQUEST) {
                // The daily-limit message is mapped via IllegalArgumentException → 400.
                String msg = res.getBody() == null ? "" : res.getBody().toLowerCase();
                if (msg.contains("daily") || msg.contains("limit")) {
                    dailyLimitTriggered = true;
                    break;
                }
            }
        }
        assertThat(dailyLimitTriggered)
                .as("Daily transfer limit must block at some point within 60×5000 KES transfers")
                .isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private String registerAndLogin(String email, String phone) throws Exception {
        restTemplate.exchange("/api/auth/register", HttpMethod.POST,
                jsonEntity(registerBody(email, phone)), String.class);
        return login(email);
    }

    private void register(String email, String phone) {
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

    private void deposit(String token, int amount, String key) {
        restTemplate.exchange("/api/wallet/deposit", HttpMethod.POST,
                authedJsonEntity(token, Map.of("amount", amount, "idempotencyKey", key)),
                String.class);
    }
}
