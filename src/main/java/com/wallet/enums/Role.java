package com.wallet.enums;

/**
 * Authorization role for a {@link com.wallet.entity.User}. New users default
 * to {@link #USER}; {@link #ADMIN} is required for the /api/admin/** endpoints
 * (issue #2). Stored as a string column so the enum can be reordered safely.
 */
public enum Role {
    USER,
    ADMIN
}
