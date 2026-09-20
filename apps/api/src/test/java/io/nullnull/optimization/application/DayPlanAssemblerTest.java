package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.optimization.application.DayPlanAssembler.Cause;
import io.nullnull.optimization.application.DayPlanAssembler.Result;
import io.nullnull.optimization.domain.route.OpeningWindow;
import io.nullnull.trip.domain.ConstraintSource;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-083 step 3: what one stored day has to say before an ordering of it can be judged.
 *
 * <p>Four of the five cases below are refusals, and that ratio is the design. Assembly has no safe
 * default for anything it reads: a guessed start time or a guessed dwell does not show up as a
 * missing value further on, it shows up as a schedule that looks verified.
 */
@DisplayName("BA-083 day plan assembly")
class DayPlanAssemblerTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final LocalDate OTHER_DAY = LocalDate.parse("2026-09-22");
    private static final UUID PLACE_A = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93911");
    private static final UUID PLACE_B = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93912");
    private static final UUID ITEM_A = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93921");
    private static final UUID ITEM_B = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93922");

    private static TripItem item(UUID id, UUID place, LocalDate date, int position,
            LocalTime start, Integer duration, TripConstraint... constraints) {
        return new TripItem(id, place, date, position, start, duration, null, List.of(constraints));
    }

    private static TripConstraint lock(ItemLock held) {
        return new TripConstraint(held, ConstraintSource.USER);
    }

    private static DayPlan assembled(Result result) {
        assertThat(result).isInstanceOf(Result.Assembled.class);
        return ((Result.Assembled) result).plan();
    }

    private static Cause refused(Result result) {
        assertThat(result).isInstanceOf(Result.Incomplete.class);
        return ((Result.Incomplete) result).cause();
    }

    @Test
    @DisplayName("BA-083-T29 a day is assembled from that date's items in position order, with their locks and hours")
    void theDayIsAssembledFromWhatIsStored() {
        // The out-of-order positions and the item on another date are both load-bearing: without
        // them this passes for an assembler that returns the list it was handed.
        Result result = DayPlanAssembler.assemble(DAY, List.of(
                item(ITEM_B, PLACE_B, DAY, 1, LocalTime.parse("13:00"), 45),
                item(UUID.randomUUID(), PLACE_A, OTHER_DAY, 0, LocalTime.parse("08:00"), 30),
                item(ITEM_A, PLACE_A, DAY, 0, LocalTime.parse("09:00"), 60,
                        lock(new ItemLock.MustVisit()), lock(new ItemLock.Date(DAY)))),
                Map.of(PLACE_A, new CatalogOpeningWindow(CatalogOpeningWindow.State.OPEN,
                        LocalTime.parse("08:00"), LocalTime.parse("22:00"))));

        DayPlan plan = assembled(result);
        assertThat(plan.date()).isEqualTo(DAY);
        // The clock starts at the FIRST stop's own time - not at the earliest time in the list,
        // which the other day's 08:00 would be.
        assertThat(plan.anchoredAt()).isEqualTo(LocalTime.parse("09:00"));
        assertThat(plan.stops()).extracting(stop -> stop.key())
                .containsExactly(ITEM_A.toString(), ITEM_B.toString());
        assertThat(plan.stops().getFirst().dwell()).isEqualTo(Duration.ofMinutes(60));
        // Both locks, not the first: an item carries up to one of each type and dropping any of
        // them is how a lock goes unchecked (BA-083-T27).
        assertThat(plan.stops().getFirst().locks())
                .containsExactly(new ItemLock.MustVisit(), new ItemLock.Date(DAY));
        assertThat(plan.windows()).containsOnlyKeys(PLACE_A);
        assertThat(plan.windows().get(PLACE_A))
                .isEqualTo(OpeningWindow.open(LocalTime.parse("08:00"), LocalTime.parse("22:00")));
    }

    @Test
    @DisplayName("BA-083-T30 a date the trip has no item on is refused rather than assembled empty")
    void aDateWithNoItemsIsRefused() {
        // An empty day is not a day with nothing to check: there is no ordering to judge, and a
        // feasible verdict over zero stops would read as "this day works".
        assertThat(refused(DayPlanAssembler.assemble(DAY,
                List.of(item(ITEM_A, PLACE_A, OTHER_DAY, 0, LocalTime.parse("09:00"), 30)), Map.of())))
                .isEqualTo(Cause.NO_STOPS);
    }

    @Test
    @DisplayName("BA-083-T31 a first stop with no start time is refused rather than given a default one")
    void aDayWithNoAnchorIsRefused() {
        // Nothing stored says when this traveller's day begins. Any value chosen here moves every
        // arrival in the day by however wrong it is, and the verdict would still say "feasible".
        assertThat(refused(DayPlanAssembler.assemble(DAY,
                List.of(item(ITEM_A, PLACE_A, DAY, 0, null, 30)), Map.of())))
                .isEqualTo(Cause.NO_ANCHORED_START);
    }

    @Test
    @DisplayName("BA-083-T32 a stop with no duration is refused rather than treated as taking no time")
    void aStopWithNoDwellIsRefused() {
        // The refusal is on the SECOND stop, so it is not the anchor rule firing again. Treating a
        // null duration as zero makes every later arrival early by the length of the real visit -
        // the same fabricated schedule a made-up travel time produces, arriving through the dwell.
        assertThat(refused(DayPlanAssembler.assemble(DAY, List.of(
                item(ITEM_A, PLACE_A, DAY, 0, LocalTime.parse("09:00"), 60),
                item(ITEM_B, PLACE_B, DAY, 1, LocalTime.parse("13:00"), null)), Map.of())))
                .isEqualTo(Cause.UNKNOWN_DWELL);
    }

    @Test
    @DisplayName("BA-083-T33 a place with no curated reading stays absent rather than gaining a window")
    void anUnverifiedPlaceIsNotGivenAWindow() {
        // Absence is the only way this vocabulary says "nobody established this", and the
        // feasibility layer spends HOURS_UNVERIFIED on it. An all-day window invented here would
        // silence that reason for every place the curator has not reached.
        DayPlan plan = assembled(DayPlanAssembler.assemble(DAY, List.of(
                item(ITEM_A, PLACE_A, DAY, 0, LocalTime.parse("09:00"), 60),
                item(ITEM_B, PLACE_B, DAY, 1, LocalTime.parse("13:00"), 45)),
                Map.of(PLACE_A, new CatalogOpeningWindow(CatalogOpeningWindow.State.CLOSED, null, null))));

        assertThat(plan.stops()).hasSize(2);
        // Not vacuous: the reading that does exist came through, so an assembler that dropped every
        // window would fail here rather than satisfy the absence below.
        assertThat(plan.windows()).containsOnlyKeys(PLACE_A);
        assertThat(plan.windows().get(PLACE_A)).isEqualTo(OpeningWindow.closed());
    }
}
