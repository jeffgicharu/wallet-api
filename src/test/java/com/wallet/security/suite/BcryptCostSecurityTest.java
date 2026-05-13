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
    void bcryptCostFactorIsInTheReasonableRange() {
        // BCrypt's $2[ay]$<cost>$<22-char salt><31-char hash> format. We
        // parse the cost from a freshly-generated hash. 10-12 is the
        // commonly-accepted band for online auth in 2025 — 8 is fast
        // enough for offline brute-force in hours; 14 makes a single
        // login take ~1 second.
        String hash = passwordEncoder.encode("any-test-password");
        assertThat(hash).startsWith("$2");
        int cost = Integer.parseInt(hash.split("\\$")[2]);
        assertThat(cost)
                .as("BCrypt cost factor (currently %s) should be 10..12", cost)
                .isBetween(10, 12);
    }
}
