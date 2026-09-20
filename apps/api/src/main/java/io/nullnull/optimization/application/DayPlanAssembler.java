package io.nullnull.optimization.application;

import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.optimization.domain.route.OpeningWindow;
import io.nullnull.optimization.domain.route.PlannedStop;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns what is stored about one day into a {@link DayPlan}, or says why it cannot.
 *
 * <p><b>It is pure.</b> No clock, no database, no randomness - the items and the curated hours
 * arrive as arguments. The application service that owns the ports reads them and calls this, which
 * is what lets every rule below be a unit test rather than a fixture in a container.
 *
 * <p><b>It refuses rather than fills in.</b> Three things can be missing and each of them ends the
 * assembly: the day has no items, the first stop has no start time, or a stop has no duration. None
 * of the three has a safe default. A missing duration is the clearest: assume zero and every later
 * arrival is early by however long the visit really takes, which is exactly the fabricated schedule
 * the card's failure boundary is about - it just arrives through the dwell instead of through a leg.
 *
 * <p>The causes are named for a operator reading a failed run, not for storage. A run that cannot
 * assemble its day fails {@code DATA_INSUFFICIENT}, which already means "there was not enough
 * evidence to judge any alternative"; this enum says which evidence, and no new run-level vocabulary
 * is invented for it.
 */
public final class DayPlanAssembler {

    /** Why a day could not be described. Diagnostic only - never stored, never sent to a client. */
    public enum Cause {

        /** No item of the trip falls on this date. */
        NO_STOPS,

        /** The first stop carries no start time, so the day's clock has nothing to start from. */
        NO_ANCHORED_START,

        /** A stop carries no duration, so every arrival after it would be guessed. */
        UNKNOWN_DWELL
    }

    /** Either the day, or the reason there is no day to speak of. */
    public sealed interface Result {

        record Assembled(DayPlan plan) implements Result { }

        record Incomplete(Cause cause) implements Result { }
    }

    private DayPlanAssembler() {
    }

    /**
     * @param items every item of the trip; the ones on another date are ignored here rather than
     *     filtered by the caller, so "this day's stops" has one definition
     * @param windows the curated reading for each place <em>on this date</em>, keyed by place id. A
     *     place with no entry stays absent: that is how the feasibility layer is told nobody
     *     established this place's hours, and putting an invented window here would silence it
     */
    public static Result assemble(LocalDate date, List<TripItem> items,
            Map<UUID, CatalogOpeningWindow> windows) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(windows, "windows");

        List<TripItem> ofTheDay = items.stream()
                .filter(item -> date.equals(item.date()))
                .sorted(Comparator.comparingInt(TripItem::position))
                .toList();
        if (ofTheDay.isEmpty()) {
            return new Result.Incomplete(Cause.NO_STOPS);
        }
        if (ofTheDay.getFirst().startTime() == null) {
            return new Result.Incomplete(Cause.NO_ANCHORED_START);
        }

        List<PlannedStop> stops = new ArrayList<>(ofTheDay.size());
        Map<UUID, OpeningWindow> readings = new LinkedHashMap<>();
        for (TripItem item : ofTheDay) {
            if (item.durationMinutes() == null) {
                return new Result.Incomplete(Cause.UNKNOWN_DWELL);
            }
            stops.add(new PlannedStop(item.id().toString(), item.placeId(),
                    Duration.ofMinutes(item.durationMinutes()), locksOf(item)));
            CatalogOpeningWindow window = windows.get(item.placeId());
            if (window != null) {
                readings.put(item.placeId(), windowIn(window));
            }
        }
        return new Result.Assembled(
                new DayPlan(date, ofTheDay.getFirst().startTime(), stops, readings));
    }

    /**
     * Every lock the item carries, in stored order. An item holds up to one of each type and they
     * are independent (invariant 7), so none of them is dropped here - {@code PlannedStop} takes the
     * list precisely because a single field could only carry one and whichever was left out would go
     * unchecked.
     */
    private static List<ItemLock> locksOf(TripItem item) {
        return item.constraints() == null ? List.of()
                : item.constraints().stream().map(TripConstraint::lock).toList();
    }

    /**
     * The same conversion {@code OptimizeItemHandler.windowIn} makes for the recommendation request,
     * into this module's own two-state reading. Both sides hold OPEN and CLOSED and nothing else, so
     * this carries a value across a package boundary rather than restating a vocabulary: a domain
     * class may not depend on {@code catalog.application}, which is why the route package has its
     * own copy of the two states at all.
     */
    private static OpeningWindow windowIn(CatalogOpeningWindow window) {
        return window.state() == CatalogOpeningWindow.State.OPEN
                ? OpeningWindow.open(window.opensAt(), window.closesAt())
                : OpeningWindow.closed();
    }
}
