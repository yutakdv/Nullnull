package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The question an ITEM run asks {@code apps/ai}, assembled from what the trip says right now.
 *
 * <p>Pure and static on purpose: every value here was read by the caller inside its own unit of
 * work, and assembling the request is not another chance to read. That also makes the shape of the
 * question testable without a database, a job or a running service - which matters, because the
 * shape is where privacy is decided.
 *
 * <p><strong>Nothing identifying travels.</strong> {@code ItemProposeRequest} has no owner or
 * session component and none is invented here; notes are not carried either, because an item's note
 * is the traveller's own words and no scoring decision reads it. What goes out is ids, dates, times,
 * durations, positions, locks and the crowd pairs - the facts an optimizer needs to rank days.
 *
 * <p>{@code routeEvidence} is NONE on every request, which is a statement about our data and not
 * about the itinerary: P0 confirms no route provider, so the evaluator is told it has none rather
 * than being left to read silence as a verified route.
 */
final class ItemProposeRequests {

    private ItemProposeRequests() {
    }

    /**
     * @throws IllegalStateException when the run's target item is not in the trip any more. The
     *     caller froze a version and checked it still holds, so reaching this means the two
     *     disagree - and a request built around a missing target would ask about an item nobody has.
     */
    static ItemProposeRequest of(OptimizationRun run, Trip trip, List<TripItem> items,
            Map<LocalDate, OpeningWindowIn> openingHours, List<TemporalCandidateIn> candidates,
            Instant evaluatedAt) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(trip, "trip");
        UUID targetId = Objects.requireNonNull(run.targetItemId(), "an ITEM run has a target item");
        TripItem target = items.stream()
                .filter(item -> item.id().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the run's target item is no longer in the trip"));

        return new ItemProposeRequest(evaluatedAt, trip.id(), Math.toIntExact(trip.version()),
                trip.range().startDate(), trip.range().endDate(), trip.range().timezone().getId(),
                target(target), locks(target), neighbours(items, targetId), openingHours,
                ItemProposeRequest.RouteEvidence.NONE, candidates);
    }

    private static TargetItemIn target(TripItem item) {
        return new TargetItemIn(item.id(), item.placeId(), item.date(), item.startTime(),
                item.durationMinutes(), item.position());
    }

    /**
     * The target's own locks, all four types included.
     *
     * <p>A MUST_VISIT does not block a temporal move and the evaluator knows that, so filtering it
     * out here would only hide from the answer why a move was allowed. Invariant 7 keeps the types
     * independent; deciding which ones matter is the evaluator's job, not the caller's.
     */
    private static List<LockIn> locks(TripItem target) {
        return target.constraints().stream().map(TripConstraint::lock).map(LockIn::from).toList();
    }

    /**
     * Every other item in the trip, as the shape a proposed day is judged against.
     *
     * <p>The whole trip rather than the target's day alone: a move puts the item on a different date,
     * and the evaluator cannot tell whether it fits there without knowing what is already there.
     */
    private static List<NeighbourItemIn> neighbours(List<TripItem> items, UUID targetId) {
        return items.stream()
                .filter(item -> !item.id().equals(targetId))
                .map(item -> new NeighbourItemIn(item.id(), item.date(), item.position(),
                        item.startTime(), item.durationMinutes()))
                .toList();
    }
}
