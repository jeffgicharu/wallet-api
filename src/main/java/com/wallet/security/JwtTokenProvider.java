package com.wallet.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        // Fail fast with an actionable message if the signing secret is
        // missing or blank (issue #4): a deploy must never silently run on
        // a hard-coded key. The empty default on the @Value above lets us
        // raise this clear error instead of an opaque placeholder failure.
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET is required and must be non-empty. "
                            + "Set the APP_JWT_SECRET environment variable "
                            + "(see CONFIGURATION.md for a local-dev example).");
        }
        // Derive the HMAC key from an explicit UTF-8 encoding. Relying on
        // the platform default charset (issue #19) means a JVM started
        // with a non-UTF-8 default would derive a different key and reject
        // tokens minted on a UTF-8 host.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String email) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public String getEmailFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
