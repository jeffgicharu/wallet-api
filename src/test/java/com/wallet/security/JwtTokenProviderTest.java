package com.wallet.security;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for issue #19: the HMAC key must be derived from an explicit
 * UTF-8 encoding of the secret, not the platform default charset. A secret
 * containing non-ASCII bytes would otherwise produce a different key
 * depending on the JVM's file.encoding, so tokens minted on a UTF-8 host
 * would fail validation on an ISO-8859-1 host (and vice versa).
 */
class JwtTokenProviderTest {

    // A secret with a non-ASCII character ("é") whose UTF-8 and
    // ISO-8859-1 byte encodings differ. 64+ bytes so the HMAC-SHA key is
    // long enough for jjwt.
    private static final String SECRET =
            "wallet-api-signing-secret-with-non-ascii-é-padding-padding-padding-padding";

    @Test
    void keyIsDerivedFromExplicitUtf8Bytes() throws Exception {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3_600_000L);

        Field keyField = JwtTokenProvider.class.getDeclaredField("key");
        keyField.setAccessible(true);
        SecretKey key = (SecretKey) keyField.get(provider);

        // The key bytes must equal the UTF-8 encoding of the secret,
        // independent of the running JVM's default charset.
        assertThat(key.getEncoded())
                .isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void tokenRoundTripsWithNonAsciiSecret() {
        JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3_600_000L);

        String token = provider.generateToken("alice@demo.local");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getEmailFromToken(token)).isEqualTo("alice@demo.local");
    }

    @Test
    void keyIsStableAcrossInstances() throws Exception {
        JwtTokenProvider a = new JwtTokenProvider(SECRET, 3_600_000L);
        JwtTokenProvider b = new JwtTokenProvider(SECRET, 3_600_000L);

        Field keyField = JwtTokenProvider.class.getDeclaredField("key");
        keyField.setAccessible(true);
        SecretKey ka = (SecretKey) keyField.get(a);
        SecretKey kb = (SecretKey) keyField.get(b);

        // A token signed by one instance verifies under the other —
        // deterministic key derivation.
        String token = a.generateToken("bob@demo.local");
        assertThat(b.validateToken(token)).isTrue();
        assertThat(ka.getEncoded()).isEqualTo(kb.getEncoded());
    }
}
