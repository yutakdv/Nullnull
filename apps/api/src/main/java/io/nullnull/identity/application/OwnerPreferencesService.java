package io.nullnull.identity.application;

import io.nullnull.identity.domain.Owner;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.FieldError;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerPreferencesService {
    private final OwnerRepository owners;
    private final TripLookup trips;
    private final LockWaitLimit locks;
    private final Duration lockTimeout;

    public OwnerPreferencesService(OwnerRepository owners, TripLookup trips, LockWaitLimit locks,
            @Value("${nullnull.idempotency.lock-timeout}") Duration lockTimeout) {
        this.owners = owners; this.trips = trips; this.locks = locks; this.lockTimeout = lockTimeout;
    }
    @Transactional(readOnly = true)
    public Owner get(OwnerContext context) {
        return owners.findById(context.ownerId()).filter(owner -> !owner.deleted())
                .orElseThrow(SessionService::unauthorized);
    }
    @Transactional
    public Owner patch(OwnerContext context, PreferencesPatch patch) {
        locks.applyToCurrentTransaction(lockTimeout);
        Owner old = owners.lockAlive(context.ownerId()).orElseThrow(SessionService::unauthorized);
        String locale = patch.locale() == null ? old.locale() : patch.locale();
        String timezone = patch.timezone() == null ? old.timezone() : patch.timezone();
        if (patch.locale() != null && !Set.of("ko-KR", "en-US").contains(locale)) {
            throw invalid("locale", "UNSUPPORTED_LOCALE", "This locale is not supported.");
        }
        if (patch.timezone() != null && (timezone.length() > 100 || !ZoneId.getAvailableZoneIds().contains(timezone))) {
            throw invalid("timezone", "INVALID_TIMEZONE", "A supported IANA timezone is required.");
        }
        var trip = patch.hasActiveTripId() ? patch.activeTripId() : old.activeTripId();
        if (patch.hasActiveTripId() && trip != null && !trips.isActiveOwnedTrip(old.id(), trip)) {
            throw invalid("activeTripId", "TRIP_NOT_FOUND", "The selected trip is unavailable.");
        }
        Owner updated = new Owner(old.id(), old.kind(), old.accountId(), locale, timezone,
                patch.onboardingCompleted() == null ? old.onboardingCompleted() : patch.onboardingCompleted(),
                trip, old.createdAt(), old.deletedAt());
        return owners.updatePreferences(updated);
    }
    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "One or more preferences are invalid.",
                List.of(new FieldError(field, code, message)));
    }
}
