package com.wallet.service;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Retry a money-moving operation when a concurrent writer wins the
 * optimistic-lock race on a wallet's @Version (issue #17). At ~20 VU on
 * /api/wallet/transfer ~0.26% of requests 409'd on
 * ObjectOptimisticLockingFailureException; a short bounded retry makes
 * those transparent. Spring Retry's interceptor wraps the @Transactional
 * one, so each attempt runs in a fresh transaction that re-reads the
 * wallet with the current version. After 3 attempts the exception
 * propagates and GlobalExceptionHandler maps it to 409.
 *
 * Backoff: 50ms, 150ms before the 2nd and 3rd attempts (multiplier 3).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Retryable(
        retryFor = ObjectOptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 3, maxDelay = 400))
public @interface RetryOnLockConflict {
}
