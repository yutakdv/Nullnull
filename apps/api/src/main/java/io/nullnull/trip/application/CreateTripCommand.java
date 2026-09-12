package io.nullnull.trip.application;

import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripTitles;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.util.List;

/**
 * A validated createTrip request. Validation happens here rather than in the controller so the same
 * rules apply however the command arrives, and so the idempotency guard hashes a request that has
 * already been rejected or accepted as a whole.
 *
 * <p>{@code seedItems} is absent on purpose and the controller refuses it: seeding items needs
 * SeedTripItem.startTime, whose wire format contradicts itself across docs/api/openapi.yaml
 * (format: time, which requires an offset), docs/architecture/ERD.md (a PostgreSQL `time`, which
 * cannot store one) and docs/api/README.md (offset-less local time). The BA-030 card forbids fixing
 * that boundary while PM-008 is open (#145), so this command cannot express it.
 */
public record CreateTripCommand(String title, TripDateRange range, PlanningLevel planningLevel,
        List<TripInterest> interests) {

    public CreateTripCommand {
        interests = TripInterest.validated(interests);
    }

    public static CreateTripCommand of(String title, LocalDate startDate, LocalDate endDate, String timezone,
            String planningLevel, List<TripInterest> interests, String ownerLocale) {
        if (startDate == null) {
            throw new TripValidationException("startDate", "NotNull", "startDate is required");
        }
        if (endDate == null) {
            throw new TripValidationException("endDate", "NotNull", "endDate is required");
        }
        PlanningLevel level;
        try {
            level = PlanningLevel.of(planningLevel);
        } catch (IllegalArgumentException unknown) {
            throw new TripValidationException("planningLevel", "Enum",
                    "planningLevel must be one of NOTHING, MUST_VISIT_ONLY, MOSTLY_PLANNED");
        }
        return new CreateTripCommand(TripTitles.resolve(title, ownerLocale),
                TripDateRange.of(startDate, endDate, timezone), level, interests);
    }
}
