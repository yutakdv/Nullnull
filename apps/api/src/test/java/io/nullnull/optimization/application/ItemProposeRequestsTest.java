package io.nullnull.optimization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.trip.domain.ConstraintSource;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The shape of the question an ITEM run asks, which is where privacy and completeness are decided. */
@DisplayName("item propose request assembly")
class ItemProposeRequestsTest {

    private static final UUID TRIP = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final LocalDate END = LocalDate.of(2026, 10, 8);
    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");

    @Test
    @DisplayName("the target is the target and every other item is a neighbour, across the whole trip")
    void theTargetIsNotAlsoItsOwnNeighbour() {
        ItemProposeRequest request = build(List.of(target(), other(LocalDate.of(2026, 10, 7))));

        assertThat(request.target().itemId()).isEqualTo(TARGET);
        assertThat(request.neighbours()).extracting(NeighbourItemIn::itemId).containsExactly(OTHER);
        // The neighbour sits on a different day and is still carried: a move puts the item on another
        // date, and whether it fits there cannot be judged without knowing what is already there.
        assertThat(request.neighbours().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(request.tripStart()).isEqualTo(START);
        assertThat(request.tripEnd()).isEqualTo(END);
        assertThat(request.tripVersion()).isEqualTo(3);
        // P0 has no route provider, so the evaluator is told so rather than left to read silence.
        assertThat(request.routeEvidence()).isEqualTo(ItemProposeRequest.RouteEvidence.NONE);
    }

    @Test
    @DisplayName("all four lock types travel, including the one that never blocks a move")
    void everyLockOnTheTargetIsCarried() {
        TripItem locked = new TripItem(TARGET, UUID.randomUUID(), START, 0, LocalTime.of(9, 0), 60, null,
                List.of(new TripConstraint(new ItemLock.MustVisit(), ConstraintSource.USER),
                        new TripConstraint(new ItemLock.Date(START), ConstraintSource.USER),
                        new TripConstraint(new ItemLock.Time(LocalTime.of(9, 0), 30), ConstraintSource.USER),
                        new TripConstraint(new ItemLock.Reservation(START, LocalTime.of(9, 0),
                                LocalTime.of(10, 0)), ConstraintSource.IMPORT)));

        ItemProposeRequest request = build(List.of(locked));

        // MUST_VISIT does not block a temporal move, and dropping it here would hide from the answer
        // why the move was allowed. Which locks matter is the evaluator's decision.
        assertThat(request.locks()).extracting(lock -> lock.type())
                .containsExactly(LockType.MUST_VISIT, LockType.DATE, LockType.TIME, LockType.RESERVATION);
        assertThat(request.locks().get(2).toleranceMinutes()).isEqualTo(30);
        assertThat(request.locks().get(3).endTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("a target that is no longer in the trip fails loudly rather than asking about nothing")
    void aMissingTargetIsNotAskedAbout() {
        // The run froze a version and the gate checked it still holds, so the two disagreeing here is
        // a contradiction rather than a user action - and a request built around a missing target
        // would ask the optimizer about an item nobody has.
        assertThatThrownBy(() -> build(List.of(other(START))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer in the trip");
    }

    private static ItemProposeRequest build(List<TripItem> items) {
        return ItemProposeRequests.of(run(), trip(), items, Map.of(), List.of(), AT);
    }

    private static TripItem target() {
        return new TripItem(TARGET, UUID.randomUUID(), START, 0, LocalTime.of(9, 0), 60, "개인 메모", List.of());
    }

    private static TripItem other(LocalDate date) {
        return new TripItem(OTHER, UUID.randomUUID(), date, 1, LocalTime.of(13, 0), 90, null, List.of());
    }

    private static Trip trip() {
        return new Trip(TRIP, UUID.randomUUID(), "여행", TripDateRange.of(START, END, "Asia/Seoul"),
                PlanningLevel.NOTHING, TripStatus.DRAFT, 3L, List.of(), AT, AT, null);
    }

    private static OptimizationRun run() {
        return new OptimizationRun(UUID.randomUUID(), TRIP, UUID.randomUUID(), OptimizationScope.ITEM,
                TARGET, null, false, OptimizationStatus.RUNNING, 3L, null, null, null, null, null, AT,
                AT, null, null, List.of());
    }
}
