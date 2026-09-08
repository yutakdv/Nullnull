package io.nullnull.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable owner state (docs/architecture/ERD.md §1 OWNERS, §4 "Identity"). P0 owners are anonymous:
 * this record holds no email, name, credential or location. The bounds match {@code OwnerProfile}
 * in docs/api/openapi.yaml and the check constraints in V002__owners.sql.
 */
public record Owner(UUID id, OwnerKind kind, UUID accountId, String locale, String timezone,
        boolean onboardingCompleted, UUID activeTripId, Instant createdAt, Instant deletedAt) {

    public static final int LOCALE_MIN_LENGTH = 2;
    public static final int LOCALE_MAX_LENGTH = 35;
    public static final int TIMEZONE_MIN_LENGTH = 1;
    public static final int TIMEZONE_MAX_LENGTH = 100;

    public Owner {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        requireLength("locale", locale, LOCALE_MIN_LENGTH, LOCALE_MAX_LENGTH);
        requireLength("timezone", timezone, TIMEZONE_MIN_LENGTH, TIMEZONE_MAX_LENGTH);
        if (kind == OwnerKind.ANONYMOUS && accountId != null) {
            throw new IllegalArgumentException("an anonymous owner has no account");
        }
    }

    /** A newly created anonymous owner: no account, onboarding not finished, no active trip. */
    public static Owner anonymous(UUID id, String locale, String timezone, Instant createdAt) {
        return new Owner(id, OwnerKind.ANONYMOUS, null, locale, timezone, false, null, createdAt, null);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    private static void requireLength(String field, String value, int min, int max) {
        Objects.requireNonNull(value, field);
        // char_length() in PostgreSQL counts code points, so the Java check must too.
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw new IllegalArgumentException(field + " length must be " + min + ".." + max);
        }
    }
}
