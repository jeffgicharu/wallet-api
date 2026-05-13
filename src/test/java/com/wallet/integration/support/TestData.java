package com.wallet.integration.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, deterministic test-data generators. Every test method that needs an
 * email, a phone, or an idempotency key calls one of these so tests stay
 * independent of each other (no clashes on emails or phones, even when run in
 * parallel) and can read like a story.
 */
public final class TestData {

    private static final AtomicLong PHONE_COUNTER = new AtomicLong(700_000_001L);
    private static final AtomicLong EMAIL_COUNTER = new AtomicLong(1L);
    private static final AtomicLong KEY_COUNTER = new AtomicLong(1L);

    private TestData() {}

    public static String uniquePhone() {
        return "+254" + PHONE_COUNTER.incrementAndGet();
    }

    public static String uniqueEmail(String prefix) {
        return prefix + "-" + EMAIL_COUNTER.incrementAndGet() + "@test.local";
    }

    public static String uniqueKey(String prefix) {
        return prefix + "-" + KEY_COUNTER.incrementAndGet();
    }
}
