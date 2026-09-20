package io.nullnull.optimization.domain.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Pair;
import io.nullnull.optimization.domain.route.RouteInfeasibility.Reason;
import io.nullnull.trip.domain.ItemLock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-083 step 2: what a candidate day is checked against before anything proposes it.
 *
 * <p>Each test carries one clause, and the card now has one id per clause: {@code BA-083-T1} was
 * narrowed to the route gap alone and {@code T4}-{@code T21} took the rest. It read "불가능한 구간·
 * 비대칭·time window·모든 잠금 조합" - four clauses under one id - and the aggregator only checks that
 * an id appears in some testcase name, so tagging all nineteen with it would have let any one of the
 * four stand in for all four: the failure {@code BA-002-T3} recorded. The ids went on when the card
 * was split, not before.
 *
 * <p>{@code T1} and {@code T12} are why the split is worth its cost, and they are a contrasting pair.
 * A route gap stops the walk, so the day reports that one reason and no {@code completedAt}; an hours
 * violation does not, so a day with two closed places reports two reasons. Under one id either test
 * alone would satisfy it and the contrast - which is the actual design decision in
 * {@link RouteFeasibility} - would have nothing pinning it.
 *
 * <p>No test here derives a travel time from distance, because the code cannot: the only source of a
 * duration is the matrix. The tests that matter most are therefore the ones about its <em>absence</em>.
 */
@DisplayName("route feasibility for a candidate day")
class RouteFeasibilityTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final LocalDate OTHER_DAY = LocalDate.parse("2026-09-22");
    private static final UUID PLACE_A = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93911");
    private static final UUID PLACE_B = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93912");
    private static final UUID PLACE_C = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93913");

    private static PlannedStop stop(String key, UUID place, int dwellMinutes) {
        return PlannedStop.of(key, place, Duration.ofMinutes(dwellMinutes));
    }

    private static PlannedStop locked(String key, UUID place, int dwellMinutes, ItemLock lock) {
        return new PlannedStop(key, place, Duration.ofMinutes(dwellMinutes), lock);
    }

    private static DirectedRouteMatrix legs(Map<Pair, Duration> available) {
        return DirectedRouteMatrix.of(available, Set.of());
    }

    private static Pair pair(String from, String to) {
        return new Pair(from, to);
    }

    /** Open all day, so an hours rule never fires unless a test means it to. */
    private static Map<UUID, OpeningWindow> allOpen() {
        OpeningWindow window = OpeningWindow.open(LocalTime.parse("08:00"), LocalTime.parse("22:00"));
        return Map.of(PLACE_A, window, PLACE_B, window, PLACE_C, window);
    }

    private static List<Reason> reasonsOf(RouteFeasibility.Verdict verdict) {
        return verdict.reasons().stream().map(RouteInfeasibility::reason).toList();
    }

    @Test
    @DisplayName("BA-083-T7 a day whose legs and hours are all known finishes at the computed time")
    void aFullyKnownDayIsFeasible() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 60), stop("b", PLACE_B, 30)),
                legs(Map.of(pair("a", "b"), Duration.ofMinutes(20))), allOpen());

        assertThat(verdict.feasible()).isTrue();
        assertThat(verdict.completedAt()).isEqualTo(LocalTime.parse("10:50"));
    }

    @Test
    @DisplayName("BA-083-T4 a missing leg is not satisfied by the reverse leg")
    void theReverseLegDoesNotStandInForAMissingOne() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30), stop("b", PLACE_B, 30)),
                legs(Map.of(pair("b", "a"), Duration.ofMinutes(20))), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.ROUTE_ABSENT);
        assertThat(verdict.reasons().getFirst().fromStopKey()).isEqualTo("a");
        assertThat(verdict.reasons().getFirst().stopKey()).isEqualTo("b");
    }

    @Test
    @DisplayName("BA-083-T5 a pair the provider answered as unroutable is not the same reason as one nobody asked")
    void anUnroutablePairIsDistinctFromAnAbsentOne() {
        DirectedRouteMatrix matrix = DirectedRouteMatrix.of(Map.of(), Set.of(pair("a", "b")));

        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30), stop("b", PLACE_B, 30)), matrix, allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.ROUTE_UNAVAILABLE);
    }

    @Test
    @DisplayName("BA-083-T1 the walk stops at the first route gap rather than judging stops it cannot time")
    void stopsAfterARouteGapAreNotJudged() {
        // c is closed, but c's arrival time is unknowable once a->b is missing. A verdict about it
        // would be invented in either direction, so the only reason is the gap itself.
        Map<UUID, OpeningWindow> windows = Map.of(PLACE_A, OpeningWindow.open(LocalTime.parse("08:00"),
                LocalTime.parse("22:00")), PLACE_B, OpeningWindow.open(LocalTime.parse("08:00"),
                LocalTime.parse("22:00")), PLACE_C, OpeningWindow.closed());

        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30), stop("b", PLACE_B, 30), stop("c", PLACE_C, 30)),
                DirectedRouteMatrix.empty(), windows);

        assertThat(reasonsOf(verdict)).containsExactly(Reason.ROUTE_ABSENT);
        assertThat(verdict.completedAt()).isNull();
    }

    @Test
    @DisplayName("BA-083-T8 a date nobody verified is unverified, never open")
    void anAbsentWindowIsUnverified() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30)), DirectedRouteMatrix.empty(), Map.of());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.HOURS_UNVERIFIED);
    }

    @Test
    @DisplayName("BA-083-T9 a verified closure is reported as closed, not as unverified")
    void aVerifiedClosureIsItsOwnReason() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30)), DirectedRouteMatrix.empty(),
                Map.of(PLACE_A, OpeningWindow.closed()));

        assertThat(reasonsOf(verdict)).containsExactly(Reason.PLACE_CLOSED);
    }

    @Test
    @DisplayName("BA-083-T10 arriving before a place opens is outside its window")
    void arrivingBeforeOpeningIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("07:00"),
                List.of(stop("a", PLACE_A, 30)), DirectedRouteMatrix.empty(),
                Map.of(PLACE_A, OpeningWindow.open(LocalTime.parse("08:00"), LocalTime.parse("22:00"))));

        assertThat(reasonsOf(verdict)).containsExactly(Reason.OUTSIDE_OPENING_WINDOW);
    }

    @Test
    @DisplayName("BA-083-T11 a visit still running at closing time is outside the window")
    void aVisitOverrunningClosingIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("17:30"),
                List.of(stop("a", PLACE_A, 60)), DirectedRouteMatrix.empty(),
                Map.of(PLACE_A, OpeningWindow.open(LocalTime.parse("08:00"), LocalTime.parse("18:00"))));

        assertThat(reasonsOf(verdict)).containsExactly(Reason.OUTSIDE_OPENING_WINDOW);
    }

    @Test
    @DisplayName("BA-083-T12 every stop whose hours refuse it is reported, because their arrivals are known")
    void hoursViolationsDoNotStopTheWalk() {
        Map<UUID, OpeningWindow> windows = Map.of(PLACE_A, OpeningWindow.closed(),
                PLACE_B, OpeningWindow.closed());

        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(stop("a", PLACE_A, 30), stop("b", PLACE_B, 30)),
                legs(Map.of(pair("a", "b"), Duration.ofMinutes(20))), windows);

        assertThat(reasonsOf(verdict)).containsExactly(Reason.PLACE_CLOSED, Reason.PLACE_CLOSED);
    }

    @Test
    @DisplayName("BA-083-T13 a DATE lock pinning another date refuses the day")
    void aDateLockOnAnotherDateIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(locked("a", PLACE_A, 30, new ItemLock.Date(OTHER_DAY))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.DATE_LOCK_MISMATCH);
    }

    @Test
    @DisplayName("BA-083-T14 a TIME lock missed by more than its tolerance refuses the day")
    void aTimeLockMissedBeyondToleranceIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:31"),
                List.of(locked("a", PLACE_A, 30, new ItemLock.Time(LocalTime.parse("09:00"), 30))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.TIME_LOCK_MISSED);
    }

    @Test
    @DisplayName("BA-083-T15 a TIME lock met inside its tolerance does not refuse the day")
    void aTimeLockWithinToleranceIsAccepted() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:30"),
                List.of(locked("a", PLACE_A, 30, new ItemLock.Time(LocalTime.parse("09:00"), 30))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(verdict.feasible()).isTrue();
    }

    @Test
    @DisplayName("BA-083-T16 a RESERVATION held for another date refuses the day")
    void aReservationOnAnotherDateIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(locked("a", PLACE_A, 30,
                        new ItemLock.Reservation(OTHER_DAY, LocalTime.parse("10:00"), null))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.RESERVATION_DATE_MISMATCH);
    }

    @Test
    @DisplayName("BA-083-T17 arriving after the reserved start refuses the day")
    void arrivingAfterTheReservedStartIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("10:01"),
                List.of(locked("a", PLACE_A, 30,
                        new ItemLock.Reservation(DAY, LocalTime.parse("10:00"), null))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.RESERVATION_ARRIVAL_LATE);
    }

    @Test
    @DisplayName("BA-083-T18 arriving early for a booking waits for it instead of starting early")
    void arrivingEarlyForAReservationWaits() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(locked("a", PLACE_A, 30,
                        new ItemLock.Reservation(DAY, LocalTime.parse("10:00"), null))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(verdict.feasible()).isTrue();
        assertThat(verdict.completedAt()).isEqualTo(LocalTime.parse("10:30"));
    }

    @Test
    @DisplayName("BA-083-T19 a visit still running after the reserved end refuses the day")
    void overrunningTheReservedEndIsRefused() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("10:00"),
                List.of(locked("a", PLACE_A, 90,
                        new ItemLock.Reservation(DAY, LocalTime.parse("10:00"), LocalTime.parse("11:00")))),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(reasonsOf(verdict)).containsExactly(Reason.RESERVATION_DEPARTURE_OVERRUNS);
    }

    @Test
    @DisplayName("BA-083-T20 MUST_VISIT says nothing about time and refuses nothing here")
    void mustVisitIsInertForFeasibility() {
        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("09:00"),
                List.of(locked("a", PLACE_A, 30, new ItemLock.MustVisit())),
                DirectedRouteMatrix.empty(), allOpen());

        assertThat(verdict.feasible()).isTrue();
    }

    @Test
    @DisplayName("BA-083-T21 a day that would run past midnight overflows instead of wrapping to the morning")
    void aDayRunningPastMidnightOverflows() {
        // Open to the last minute of the day, so the overflow is the only rule left to fire: with the
        // usual 22:00 close, a's own window would refuse a 23:00 start and the reason under test would
        // arrive next to one this case is not about.
        OpeningWindow lateWindow = OpeningWindow.open(LocalTime.parse("08:00"), LocalTime.parse("23:59"));
        Map<UUID, OpeningWindow> windows = Map.of(PLACE_A, lateWindow, PLACE_B, lateWindow);

        RouteFeasibility.Verdict verdict = RouteFeasibility.verify(DAY, LocalTime.parse("23:00"),
                List.of(stop("a", PLACE_A, 30), stop("b", PLACE_B, 30)),
                legs(Map.of(pair("a", "b"), Duration.ofMinutes(120))), windows);

        assertThat(reasonsOf(verdict)).containsExactly(Reason.DAY_OVERFLOW);
        assertThat(verdict.reasons().getFirst().stopKey()).isEqualTo("b");
    }

    @Test
    @DisplayName("BA-083-T6 a pair declared both routable and unroutable is rejected rather than resolved")
    void aContradictoryPairIsRejected() {
        assertThatThrownBy(() -> DirectedRouteMatrix.of(
                Map.of(pair("a", "b"), Duration.ofMinutes(20)), Set.of(pair("a", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
