package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.route.OpeningWindow;
import io.nullnull.optimization.domain.route.PlannedStop;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One day of a trip, described completely enough for {@code RouteFeasibility} to judge an order of
 * it (BA-083 step 3).
 *
 * <p>"Completely enough" is the whole of it, and it is why {@link DayPlanAssembler} can refuse to
 * build one. Every field here is read from what is stored; none of it is defaulted. A day missing
 * any of them is not a day with gaps to fill in - it is a day this layer cannot say anything about,
 * and filling one in would be the invented travel time the card forbids, wearing a different hat.
 *
 * <p>{@code anchoredAt} is the first stop's own start time, and it is where the day's clock starts.
 * Travel <em>to</em> the first stop is not modelled: nothing stored says where the traveller begins
 * the day, and {@code RouteFeasibility} accordingly gives stop zero an arrival equal to this value.
 *
 * <p>{@code windows} is keyed by place id rather than stop key because a place can appear on two
 * days and its hours belong to the place. A stop whose place has no entry is unverified, never open
 * - {@link OpeningWindow} has no third state and the feasibility layer spends a separate reason on
 * the absence.
 */
public record DayPlan(LocalDate date, LocalTime anchoredAt, List<PlannedStop> stops,
        Map<UUID, OpeningWindow> windows) {

    public DayPlan {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(anchoredAt, "anchoredAt");
        stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
        windows = Map.copyOf(Objects.requireNonNull(windows, "windows"));
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a day plan describes at least one stop");
        }
    }
}
