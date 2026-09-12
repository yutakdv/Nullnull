package io.nullnull.trip.application;

import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripScheduleRules;
import io.nullnull.trip.domain.TripTitles;
import io.nullnull.trip.domain.TripValidationException;
import java.time.LocalDate;
import java.util.List;

/**
 * A validated createTrip request. Validation happens here rather than in the controller so the same
 * rules apply however the command arrives, and so the idempotency guard hashes a request that has
 * already been accepted or rejected as a whole.
 */
public record CreateTripCommand(String title, TripDateRange range, PlanningLevel planningLevel,
        List<TripInterest> interests, List<TripItem> seedItems) {

    public CreateTripCommand {
        interests = TripInterest.validated(interests);
        seedItems = seedItems == null ? List.of() : List.copyOf(seedItems);
        // Every seeded item must fit the trip it is being created inside: within the range, within
        // the per-day and per-trip caps, and one item per slot. Checked here so a rejected create
        // never reaches the store and therefore cannot leave a partial trip behind.
        TripScheduleRules.requireInsideRange(range, seedItems);
        TripScheduleRules.requireWithinCaps(seedItems);
        TripScheduleRules.requireDistinctPositions(seedItems);
    }

    public static CreateTripCommand of(String title, LocalDate startDate, LocalDate endDate,
            String timezone, String planningLevel, List<TripInterest> interests,
            List<TripItem> seedItems, String ownerLocale) {
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
                TripDateRange.of(startDate, endDate, timezone), level, interests, seedItems);
    }
}
