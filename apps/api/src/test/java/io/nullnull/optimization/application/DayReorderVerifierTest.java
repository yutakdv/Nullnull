package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.optimization.application.DayReorderVerifier.Cause;
import io.nullnull.optimization.application.DayReorderVerifier.Result;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Pair;
import io.nullnull.optimization.domain.route.OpeningWindow;
import io.nullnull.optimization.domain.route.PlannedStop;
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
 * BA-083 step 3: what a proposed order of one day has to survive before a traveller sees it.
 *
 * <p>The four refusals are the point. Three of them look alike from outside - nothing is proposed -
 * and they mean different things: a proposal that is not a permutation answered a different
 * question, a day whose legs are not all known was never judged, and an unimproved day was judged
 * and found equal. A run ends with a different failure code for the middle one.
 */
@DisplayName("BA-083 day reordering revalidation")
class DayReorderVerifierTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-21");
    private static final UUID PLACE_A = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93911");
    private static final UUID PLACE_B = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93912");
    private static final UUID PLACE_C = UUID.fromString("018f3f8e-9b67-7a21-8d31-31d315b93913");

    /** Open all day, so an hours rule never fires unless a case means it to. */
    private static Map<UUID, OpeningWindow> allOpen() {
        OpeningWindow window = OpeningWindow.open(LocalTime.parse("07:00"), LocalTime.parse("23:00"));
        return Map.of(PLACE_A, window, PLACE_B, window, PLACE_C, window);
    }

    private static DayPlan plan(ItemLock lockOnB) {
        PlannedStop b = lockOnB == null
                ? PlannedStop.of("b", PLACE_B, Duration.ofMinutes(30))
                : PlannedStop.locked("b", PLACE_B, Duration.ofMinutes(30), lockOnB);
        return new DayPlan(DAY, LocalTime.parse("09:00"),
                List.of(PlannedStop.of("a", PLACE_A, Duration.ofMinutes(30)), b,
                        PlannedStop.of("c", PLACE_C, Duration.ofMinutes(30))),
                allOpen());
    }

    /** a-&gt;b and b-&gt;c are the order stored; a-&gt;c and c-&gt;b are the order proposed. */
    private static DirectedRouteMatrix legs(long abSeconds, long bcSeconds,
            long acSeconds, long cbSeconds) {
        return DirectedRouteMatrix.of(Map.of(
                new Pair("a", "b"), Duration.ofSeconds(abSeconds),
                new Pair("b", "c"), Duration.ofSeconds(bcSeconds),
                new Pair("a", "c"), Duration.ofSeconds(acSeconds),
                new Pair("c", "b"), Duration.ofSeconds(cbSeconds)), Set.of());
    }

    private static DayReordering verified(Result result) {
        assertThat(result).isInstanceOf(Result.Verified.class);
        return ((Result.Verified) result).reordering();
    }

    private static Cause refused(Result result) {
        assertThat(result).isInstanceOf(Result.Refused.class);
        return ((Result.Refused) result).cause();
    }

    @Test
    @DisplayName("BA-083-T34 a feasible permutation that travels less is verified with its measured delta")
    void aShorterFeasibleOrderIsVerified() {
        Result result = DayReorderVerifier.verify(plan(null), List.of("a", "c", "b"),
                legs(600, 600, 300, 300));

        DayReordering reordering = verified(result);
        assertThat(reordering.order()).containsExactly("a", "c", "b");
        assertThat(reordering.currentTravel()).isEqualTo(Duration.ofMinutes(20));
        assertThat(reordering.proposedTravel()).isEqualTo(Duration.ofMinutes(10));
        // Proposed minus current, so an improvement is negative - the same direction the contract's
        // sibling column uses (its examples carry crowdDelta: -47 for an improvement).
        assertThat(reordering.travelMinutesDelta()).isEqualTo(-10);
        assertThat(reordering.saved()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("BA-083-T35 a proposal that is not a permutation of the day is refused as such")
    void aProposalThatChangesTheDayIsRefused() {
        DayPlan plan = plan(null);
        DirectedRouteMatrix matrix = legs(600, 600, 300, 300);

        // Dropping a stop is the dangerous one: it would answer a different question, and the stop
        // dropped could be one a MUST_VISIT lock says stays in the trip.
        assertThat(refused(DayReorderVerifier.verify(plan, List.of("a", "c"), matrix)))
                .isEqualTo(Cause.NOT_A_REORDERING);
        // A repeat has the right size and the right key set, so only counting distinct keys sees it.
        assertThat(refused(DayReorderVerifier.verify(plan, List.of("a", "c", "c"), matrix)))
                .isEqualTo(Cause.NOT_A_REORDERING);
        // A stop that is not in this day at all.
        assertThat(refused(DayReorderVerifier.verify(plan, List.of("a", "b", "z"), matrix)))
                .isEqualTo(Cause.NOT_A_REORDERING);
    }

    @Test
    @DisplayName("BA-083-T36 an order that travels the same or longer is refused as no improvement")
    void anEqualOrLongerOrderIsNoImprovement() {
        // Equal, not longer: that is where "strictly shorter" is decided. Proposing an equal day
        // spends a traveller's decision on a change that buys nothing.
        assertThat(refused(DayReorderVerifier.verify(plan(null), List.of("a", "c", "b"),
                legs(600, 600, 600, 600)))).isEqualTo(Cause.NO_IMPROVEMENT);
        assertThat(refused(DayReorderVerifier.verify(plan(null), List.of("a", "c", "b"),
                legs(600, 600, 900, 900)))).isEqualTo(Cause.NO_IMPROVEMENT);
    }

    @Test
    @DisplayName("BA-083-T37 an order that breaks a rule of the day is refused even when it travels less")
    void anInfeasibleOrderIsRefusedThoughItIsShorter() {
        // The same legs as T34, so this order IS shorter - the refusal is the lock, not the length.
        // b is pinned to 09:40 within five minutes: stored order puts it there exactly, the proposal
        // puts it at 10:10. Without the lock this input is verified, which is what makes the case
        // about feasibility rather than about anything else.
        assertThat(refused(DayReorderVerifier.verify(
                plan(new ItemLock.Time(LocalTime.parse("09:40"), 5)), List.of("a", "c", "b"),
                legs(600, 600, 300, 300)))).isEqualTo(Cause.INFEASIBLE);
    }

    @Test
    @DisplayName("BA-083-T38 a day with an unknown leg is refused as uncomparable, not as unimproved")
    void anIncompleteMatrixIsNotAnUnimprovedDay() {
        // The gap is in the STORED order, so the proposal itself is feasible and the walk completes.
        // What cannot be done is the comparison: the day's current cost is unknown, and a difference
        // against an unknown is a number nothing stands behind.
        DirectedRouteMatrix missingStoredLeg = DirectedRouteMatrix.of(Map.of(
                new Pair("a", "c"), Duration.ofSeconds(300),
                new Pair("c", "b"), Duration.ofSeconds(300)), Set.of());

        assertThat(refused(DayReorderVerifier.verify(plan(null), List.of("a", "c", "b"),
                missingStoredLeg))).isEqualTo(Cause.ROUTE_INCOMPLETE);
    }

    @Test
    @DisplayName("BA-083-T39 a saving shorter than the minute it is recorded in is still an improvement")
    void aSubMinuteSavingIsStillAnImprovement() {
        // 100s against 60s: a real forty-second saving that the minutes column cannot show. The
        // judgment is made on the full durations, so it is proposed; the column records 0, and the
        // card says beside it that 0 does not mean the itinerary did not move.
        //
        // No minimum is invented here. A threshold ("only propose savings over a minute") is a
        // policy value, and policy values in this system live in apps/ai's policy-v1.yaml with a
        // pin - not as a constant nobody measured, chosen in a revalidator.
        DayReordering reordering = verified(DayReorderVerifier.verify(plan(null),
                List.of("a", "c", "b"), legs(100, 0, 20, 40)));

        assertThat(reordering.saved()).isEqualTo(Duration.ofSeconds(40));
        assertThat(reordering.travelMinutesDelta()).isZero();
    }

    @Test
    @DisplayName("BA-083-T40 the recorded minutes round towards zero, never away from it")
    void theRecordedDeltaRoundsTowardsZero() {
        // 200s against 110s is a ninety-second saving: one minute recorded, not two. Rounding away
        // from zero would claim a larger change than was measured, and the column is read as
        // evidence of how much the day improved.
        assertThat(verified(DayReorderVerifier.verify(plan(null), List.of("a", "c", "b"),
                legs(200, 0, 50, 60))).travelMinutesDelta()).isEqualTo(-1);
    }
}
