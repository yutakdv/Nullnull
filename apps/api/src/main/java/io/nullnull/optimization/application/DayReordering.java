package io.nullnull.optimization.application;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A verified reordering of one day, and what it costs against the order it replaces.
 *
 * <p>Both totals are the sum of the legs actually travelled, measured on the same matrix. Neither is
 * an estimate: a reordering only reaches this type when every leg of both orders came back
 * {@code Available}, because a total with a gap in it would compare a known number against a partly
 * guessed one and report the difference as if both were measured.
 *
 * <p><b>Nothing here is stored as evidence.</b> A-055 forbids keeping the route response these
 * durations came from, so {@link #travelMinutesDelta()} is a number computed at this moment, not a
 * measurement a later reader may cite. That is also why this type carries no timestamp: a freshness
 * it cannot honour would be worse than none.
 */
public record DayReordering(List<String> order, Duration currentTravel, Duration proposedTravel) {

    public DayReordering {
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        Objects.requireNonNull(currentTravel, "currentTravel");
        Objects.requireNonNull(proposedTravel, "proposedTravel");
        if (order.isEmpty()) {
            throw new IllegalArgumentException("a reordering names at least one stop");
        }
        if (currentTravel.isNegative() || proposedTravel.isNegative()) {
            throw new IllegalArgumentException("travel does not take negative time");
        }
    }

    /**
     * What {@code optimization_proposals.travel_minutes_delta} records: proposed minus current, so a
     * shorter day is negative. The contract's sibling column reads the same way - its examples carry
     * {@code crowdDelta: -47} for an improvement - and a second convention on the row beside it would
     * be read wrong by whoever looks at one and assumes the other.
     *
     * <p><b>The column is minutes and the provider answers in seconds, so this rounds.</b> It rounds
     * TOWARDS ZERO, which is Java's own integer division: a 90-second saving is reported as one
     * minute, never two. The direction is chosen so the record never claims a larger change than was
     * measured, in either direction. The consequence is that a saving under a minute is recorded as
     * {@code 0}, and {@code 0} therefore does NOT mean the itinerary did not move - the BA-083 card
     * says so beside this column, because the column cannot say it itself.
     *
     * <p>Judging whether a reordering is worth proposing does not use this value: {@link
     * DayReorderVerifier} compares the durations in full. Rounding decides what is written down, not
     * what is decided.
     */
    public int travelMinutesDelta() {
        return (int) (proposedTravel.minus(currentTravel).toSeconds() / 60);
    }

    /** How much shorter the proposed order is. Negative would mean it is longer. */
    public Duration saved() {
        return currentTravel.minus(proposedTravel);
    }
}
