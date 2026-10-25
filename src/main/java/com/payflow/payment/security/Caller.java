package com.payflow.payment.security;

import com.payflow.payment.entity.Account;

import java.util.Objects;

/**
 * The authenticated principal a request acts for. Passed explicitly to the
 * service layer so every authorization decision is visible at the call site.
 *
 * @param subject the JWT {@code sub} claim, used as the account owner ID
 * @param admin   whether the token carries the {@code payflow.admin} scope
 */
public record Caller(String subject, boolean admin) {

    public static final String ADMIN_SCOPE = "payflow.admin";
    public static final String ADMIN_AUTHORITY = "SCOPE_" + ADMIN_SCOPE;

    /**
     * Subjects in this namespace are internal (for example the idempotency
     * scope of pre-authentication transfers) and are rejected in tokens.
     */
    public static final String RESERVED_SUBJECT_PREFIX = "payflow:";

    public Caller {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }

    public static Caller customer(String subject) {
        return new Caller(subject, false);
    }

    public static Caller admin(String subject) {
        return new Caller(subject, true);
    }

    public boolean owns(Account account) {
        return subject.equals(account.getOwnerId());
    }

    public boolean canView(Account account) {
        return admin || owns(account);
    }
}
