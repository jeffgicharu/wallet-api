package com.wallet.exception;

/**
 * Thrown when a requested resource does not exist OR the authenticated
 * caller is not allowed to see it. The "not allowed" case deliberately
 * reuses this exception (rather than a 403) so an attacker probing
 * transaction references cannot distinguish "doesn't exist" from
 * "exists but isn't yours" (issue #20).
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
