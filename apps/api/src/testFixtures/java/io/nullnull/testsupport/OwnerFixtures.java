package io.nullnull.testsupport;

import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.shared.ids.UuidV7;
import java.time.Clock;
import java.util.UUID;

/**
 * Owners for suites that need one. Later slices create "owner A" and "owner B" by calling
 * {@link #createAnonymous(OwnerRepository, Clock)} twice instead of repeating the same setup, and use
 * {@link #anonymous(Clock)} when only the domain value is needed. Values are neutral test data: no real
 * account, no real location.
 */
public final class OwnerFixtures {

    public static final String LOCALE = "ko-KR";
    public static final String TIMEZONE = "Asia/Seoul";

    private OwnerFixtures() {
    }

    /** A fresh anonymous owner value with a new UUID v7 identifier. */
    public static Owner anonymous(Clock clock) {
        return anonymous(UuidV7.create(clock), clock);
    }

    /** A fresh anonymous owner value with the given identifier, for tests that assert on the id. */
    public static Owner anonymous(UUID id, Clock clock) {
        return Owner.anonymous(id, LOCALE, TIMEZONE, clock.instant());
    }

    /** Persists a fresh anonymous owner and returns the stored value. */
    public static Owner createAnonymous(OwnerRepository owners, Clock clock) {
        return owners.create(anonymous(clock));
    }
}
