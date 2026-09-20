package io.nullnull.optimization.domain.route;

import java.util.Objects;

/**
 * One named reason a candidate day could not be verified, and the stop it is about.
 *
 * <p>Every reason names a stop, and the two route reasons also name the stop travelled from, because
 * "there is no way to get there" is a fact about a pair and not about either end of it.
 *
 * <p>There is no reason that means "we assumed it was fine". A rule that cannot be checked produces a
 * reason here, never silence - that is the card's safety boundary read from the other side.
 */
public record RouteInfeasibility(Reason reason, String stopKey, String fromStopKey) {

    /**
     * Why a stop could not be verified.
     *
     * <p>The two route reasons are separate because they are different facts about the world: nobody
     * asked, versus the provider answered and said there is no route. See {@link DirectedRouteMatrix}.
     *
     * <p>{@code MUST_VISIT} has no reason here on purpose. That lock says the place stays in the trip;
     * it constrains what an optimizer may drop, not whether a given order is walkable in time, and
     * {@code ItemLock.MustVisit}'s own javadoc says a temporal move keeps it. A reason for it in this
     * type could never fire, and an assertion that cannot fire is worse than a missing one. The
     * optimizer owns it in step 3.
     */
    public enum Reason {

        /** Nobody asked for this ordered pair, or the answer never arrived. */
        ROUTE_ABSENT,

        /** The provider answered for this ordered pair and reported no route. */
        ROUTE_UNAVAILABLE,

        /** Arriving here would land on a later date than the day being verified. */
        DAY_OVERFLOW,

        /** No curated reading establishes this place's hours on this date. Absence is not "open". */
        HOURS_UNVERIFIED,

        /** A curated reading establishes that this place is closed on this date. */
        PLACE_CLOSED,

        /** The visit does not fit inside the verified opening window. */
        OUTSIDE_OPENING_WINDOW,

        /** A DATE lock pins this item to another date. */
        DATE_LOCK_MISMATCH,

        /** A TIME lock's start time is missed by more than its tolerance. */
        TIME_LOCK_MISSED,

        /** A RESERVATION lock is on another date. */
        RESERVATION_DATE_MISMATCH,

        /** Arrival is after the reserved start time. */
        RESERVATION_ARRIVAL_LATE,

        /** The visit would still be running after the reserved end time. */
        RESERVATION_DEPARTURE_OVERRUNS
    }

    public RouteInfeasibility {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(stopKey, "stopKey");
    }

    /** A reason about one stop, with no pair involved. */
    public static RouteInfeasibility at(Reason reason, String stopKey) {
        return new RouteInfeasibility(reason, stopKey, null);
    }

    /** A reason about travelling {@code fromStopKey} to {@code stopKey}, in that direction. */
    public static RouteInfeasibility between(Reason reason, String fromStopKey, String stopKey) {
        return new RouteInfeasibility(reason, stopKey, Objects.requireNonNull(fromStopKey, "fromStopKey"));
    }
}
