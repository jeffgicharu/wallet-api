package com.wallet.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP token-bucket rate limit in front of POST /api/auth/login
 * (issue #21). Default: 5 attempts per minute per client IP. On excess
 * the request never reaches the controller — it returns 429 with a
 * Retry-After header instead, which throttles online password attacks
 * without locking out a legitimate user for long.
 *
 * <p>Buckets live in an in-memory map keyed by client IP. This is a
 * single-instance limiter — adequate for the current single-VM deploy;
 * a distributed deploy would back this with the Bucket4j Redis/Hazelcast
 * proxy instead.
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final int capacity;
    private final Duration refillPeriod;

    public LoginRateLimitFilter(
            @Value("${security.login-rate-limit.max-attempts:5}") int maxAttempts,
            @Value("${security.login-rate-limit.window-seconds:60}") long windowSeconds) {
        this.capacity = maxAttempts;
        this.refillPeriod = Duration.ofSeconds(windowSeconds);
    }

    private Bucket newBucket() {
        // Refill the whole capacity once per window (fixed window-ish);
        // simple and predictable for a login throttle.
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, refillPeriod)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // First hop is the original client when behind Cloudflare/nginx.
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only throttle the login endpoint.
        return !("POST".equalsIgnoreCase(request.getMethod())
                && "/api/auth/login".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(clientIp(request), k -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds =
                TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(Math.max(retryAfterSeconds, 1)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Too many login attempts. "
                        + "Please retry later.\"}");
    }
}
