package com.wallet.security.suite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BCrypt cost factor is a security control: too low (≤ 8) makes offline
 * attacks cheap; too high (≥ 14) makes the API DoS-able with login spam.
 *
 * <p>The current configuration in {@code SecurityConfig#passwordEncoder()}
 * uses {@code new BCryptPasswordEncoder()} — Spring's default cost factor
 * is 10. PR #18 surfaced this as the perf-side bottleneck (issue #15);
 * from a security angle, 10 is acceptable for a customer-facing wallet
 * in 2025 but is the floor of "reasonable", not a stretch.
 */
class BcryptCostSecurityTest extends SecurityTestBase {

    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void passwordEncoderIsBcrypt() {
        // Any encoder that isn't BCrypt is an immediate finding — MD5, SHA1,
        // plaintext, or even bcrypt-via-a-third-party with unknown cost.
        assertThat(passwordEncoder)
                .as("PasswordEncoder must be BCryptPasswordEncoder")
                .isInstanceOf(BCryptPasswordEncoder.class);
    }

    @Test
    void bcryptCostFactorIsPinnedAtExactlyTen() {
        // Issue #15: the cost is now pinned explicitly at 10 in
        // SecurityConfig (not left to Spring's default), so a Spring
        // upgrade can't shift it. 10 is the documented balance between
        // offline-attack resistance and the online login throughput
        // ceiling captured in PERFORMANCE_TESTING.md.
        String hash = passwordEncoder.encode("any-test-password");
        assertThat(hash).startsWith("$2");
        int cost = Integer.parseInt(hash.split("\\$")[2]);
        assertThat(cost)
                .as("BCrypt cost factor (currently %s) must be pinned at 10", cost)
                .isEqualTo(10);
    }
}
