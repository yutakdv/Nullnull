package io.nullnull.optimization.domain.route;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Travel times between stops, in the direction they were measured (BA-083 step 2).
 *
 * <p><b>Nothing here is stored.</b> A-055 forbids persisting a Kakao route response, so this type is
 * built from a live response, consumed, and dropped. It deliberately carries no {@code fetchedAt} and
 * no provenance: AGENTS principle 9 wants an external record's freshness preserved, and a value that
 * may not be kept cannot honour it. The consequence for a caller is the one the decision records -
 * these durations are "computed just now", never evidence to cite or compare against later.
 *
 * <p><b>Direction is load-bearing.</b> Road routing is asymmetric: one-way streets, turn restrictions
 * and time-of-day controls all make A-&gt;B and B-&gt;A different journeys. {@link #leg} keys on the
 * ordered pair and no reader falls back to the reverse, because a reverse duration is a measurement
 * of a different road.
 *
 * <p>A pair is in exactly one of three states and they are kept apart on purpose:
 * <ul>
 *   <li>{@link Leg.Available} - the provider returned a route and its duration;
 *   <li>{@link Leg.Unavailable} - the provider answered for this pair and said there is no route.
 *       Kakao's multi-destination response carries a per-destination {@code result_code}, so one
 *       destination can fail inside an otherwise successful call;
 *   <li>{@link Leg.Absent} - nobody asked, or the answer never arrived.
 * </ul>
 * The last two both make a plan unverifiable, but they are different facts about the world. Collapsing
 * them would let a transport failure read as "no such road"; the card's safety boundary is that a
 * travel time is never invented, not that every gap has the same cause. {@link RouteFeasibility} names
 * them with separate reasons.
 */
public final class DirectedRouteMatrix {

    /** What is known about one ordered pair. */
    public sealed interface Leg {

        /** The provider returned a route, and this is how long it takes. */
        record Available(Duration travelTime) implements Leg {
            public Available {
                Objects.requireNonNull(travelTime, "travelTime");
                if (travelTime.isNegative()) {
                    throw new IllegalArgumentException("a leg cannot take negative time");
                }
            }
        }

        /** The provider answered for this pair and reported no route. */
        record Unavailable() implements Leg { }

        /** Nobody asked, or the answer never arrived. */
        record Absent() implements Leg { }
    }

    /**
     * An ordered pair of stop keys. {@code new Pair("a", "b")} is never equal to
     * {@code new Pair("b", "a")}, and that inequality is what keeps the matrix directed.
     */
    public record Pair(String from, String to) {
        public Pair {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("a leg must join two different stops");
            }
        }
    }

    private static final Leg ABSENT = new Leg.Absent();
    private static final Leg UNAVAILABLE = new Leg.Unavailable();

    private final Map<Pair, Duration> available;
    private final Set<Pair> unavailable;

    private DirectedRouteMatrix(Map<Pair, Duration> available, Set<Pair> unavailable) {
        this.available = Map.copyOf(available);
        this.unavailable = Set.copyOf(unavailable);
    }

    /**
     * A matrix over the pairs the provider actually answered for.
     *
     * <p>A pair given in both arguments is rejected rather than resolved. The two arguments are
     * separate statements about the same pair - "it takes this long" and "there is no route" - and a
     * precedence rule here would quietly pick a winner where the caller has a contradiction to fix.
     */
    public static DirectedRouteMatrix of(Map<Pair, Duration> available, Set<Pair> unavailable) {
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(unavailable, "unavailable");
        for (Pair pair : unavailable) {
            if (available.containsKey(pair)) {
                throw new IllegalArgumentException("a pair cannot be both routable and unroutable");
            }
        }
        return new DirectedRouteMatrix(available, unavailable);
    }

    /** A matrix that answered for nothing. Every lookup is {@link Leg.Absent}. */
    public static DirectedRouteMatrix empty() {
        return new DirectedRouteMatrix(Map.of(), Set.of());
    }

    /** What is known about travelling {@code from} to {@code to}, in that direction only. */
    public Leg leg(String from, String to) {
        Pair pair = new Pair(from, to);
        Duration travelTime = available.get(pair);
        if (travelTime != null) {
            return new Leg.Available(travelTime);
        }
        return unavailable.contains(pair) ? UNAVAILABLE : ABSENT;
    }
}
