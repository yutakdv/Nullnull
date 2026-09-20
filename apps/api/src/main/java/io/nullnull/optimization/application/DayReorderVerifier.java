package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.route.DirectedRouteMatrix;
import io.nullnull.optimization.domain.route.DirectedRouteMatrix.Leg;
import io.nullnull.optimization.domain.route.PlannedStop;
import io.nullnull.optimization.domain.route.RouteFeasibility;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a proposed order of one day may be shown to a traveller (BA-083 step 3, revalidation).
 *
 * <p>This is the layer ADR-0006 puts on the Spring side: the order is computed elsewhere and
 * <em>re-checked here against the day this run froze</em>, and only a reordering that survives is
 * stored. Nothing it returns has been applied - invariant 3 keeps a preview out of the trip until a
 * person approves it, and this class has no mutation to call.
 *
 * <p><b>It is pure.</b> The plan, the proposed order and the matrix arrive as arguments; there is no
 * clock, no database and no randomness, so the same inputs always give the same verdict.
 *
 * <p><b>Four refusals, and they are not interchangeable.</b> A proposal that moves a stop out of the
 * day is not a worse reordering, it is not a reordering; a day whose legs are not all known is not
 * an unimproved day, it is an unjudged one. The distinction is the same one {@code OptimizeItemHandler}
 * already keeps between {@code NO_IMPROVEMENT} and {@code DATA_INSUFFICIENT}, and it decides which
 * failure code a run ends with.
 */
public final class DayReorderVerifier {

    /** Why a proposed order was not accepted. Diagnostic; the run stores a failure code, not this. */
    public enum Cause {

        /** The proposal is not a permutation of the day: it adds, drops or repeats a stop. */
        NOT_A_REORDERING,

        /** The proposed order breaks a rule the day is judged by - hours, a lock, or a route gap. */
        INFEASIBLE,

        /** A leg of one of the two orders is unknown, so the two cannot be compared at all. */
        ROUTE_INCOMPLETE,

        /** The proposal is feasible and takes at least as long as the order it would replace. */
        NO_IMPROVEMENT
    }

    /** Either the reordering to propose, or the reason there is nothing to propose. */
    public sealed interface Result {

        record Verified(DayReordering reordering) implements Result { }

        record Refused(Cause cause) implements Result { }
    }

    private DayReorderVerifier() {
    }

    /**
     * @param proposedOrder the stop keys in the order being proposed - the same keys
     *     {@link PlannedStop#key()} carries, which never leave this process
     */
    public static Result verify(DayPlan plan, List<String> proposedOrder, DirectedRouteMatrix matrix) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(proposedOrder, "proposedOrder");
        Objects.requireNonNull(matrix, "matrix");

        Map<String, PlannedStop> byKey = new HashMap<>();
        plan.stops().forEach(stop -> byKey.put(stop.key(), stop));
        // A permutation, checked as one: same size, same keys, no repeats. Dropping a stop would
        // silently answer a different question - and could drop one a MUST_VISIT lock says stays in
        // the trip, which this layer is not the place to decide (invariant 7).
        Set<String> proposedKeys = new HashSet<>(proposedOrder);
        if (proposedOrder.size() != plan.stops().size() || !proposedKeys.equals(byKey.keySet())
                || proposedKeys.size() != proposedOrder.size()) {
            return new Result.Refused(Cause.NOT_A_REORDERING);
        }

        List<PlannedStop> proposed = new ArrayList<>(proposedOrder.size());
        proposedOrder.forEach(key -> proposed.add(byKey.get(key)));

        RouteFeasibility.Verdict verdict =
                RouteFeasibility.verify(plan.date(), plan.anchoredAt(), proposed, matrix, plan.windows());
        if (!verdict.feasible()) {
            return new Result.Refused(Cause.INFEASIBLE);
        }

        // Both totals, or neither. Comparing a known sum against a partly unknown one and reporting
        // the difference would be the invented number the card's failure boundary is about.
        Optional<Duration> current = totalTravel(plan.stops(), matrix);
        Optional<Duration> proposedTravel = totalTravel(proposed, matrix);
        if (current.isEmpty() || proposedTravel.isEmpty()) {
            return new Result.Refused(Cause.ROUTE_INCOMPLETE);
        }
        // Strictly shorter. An equal day is not an improvement, and proposing it would spend a
        // traveller's decision on a change that buys nothing. The comparison is on the full
        // durations, not on the rounded minutes the proposal records - see DayReordering.
        if (proposedTravel.orElseThrow().compareTo(current.orElseThrow()) >= 0) {
            return new Result.Refused(Cause.NO_IMPROVEMENT);
        }
        return new Result.Verified(new DayReordering(List.copyOf(proposedOrder),
                current.orElseThrow(), proposedTravel.orElseThrow()));
    }

    /** The sum of the legs walked in this order, or empty if any of them is not a known duration. */
    private static Optional<Duration> totalTravel(List<PlannedStop> stops, DirectedRouteMatrix matrix) {
        Duration total = Duration.ZERO;
        for (int index = 1; index < stops.size(); index++) {
            Leg leg = matrix.leg(stops.get(index - 1).key(), stops.get(index).key());
            if (!(leg instanceof Leg.Available available)) {
                return Optional.empty();
            }
            total = total.plus(available.travelTime());
        }
        return Optional.of(total);
    }
}
