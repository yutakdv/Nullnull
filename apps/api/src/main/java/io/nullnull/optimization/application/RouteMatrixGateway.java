package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.route.DirectedRouteMatrix;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/**
 * Travel times for the ordered pairs a caller asks about (BA-083 step 1).
 *
 * <p><b>It answers for the pairs it is given and no others.</b> There is no N&times;N matrix API
 * behind this - a directed matrix costs one call per pair - so asking for a pair is what buys it, and
 * a search that never considers a pair never pays for it. Pairs the caller did not ask about come
 * back {@link DirectedRouteMatrix.Leg.Absent}, which is the same state as one that could not be
 * answered: the matrix's own vocabulary already says "nobody asked, or the answer never arrived".
 *
 * <p><b>Nothing it returns may be stored.</b> A-055 forbids persisting a route response, so the
 * matrix is built per request, consumed, and dropped. That is why this port returns the domain value
 * rather than anything with an id: there is no row to point at.
 *
 * <p><b>Coordinates come from the caller, not from here.</b> This module does not read the catalog;
 * the application service that knows which stops a day holds resolves their coordinates and hands
 * them over. A place with no coordinates cannot become a {@link RouteWaypoint} at all, so it cannot
 * be asked about - and the leg is {@code Absent} by construction rather than by a check further in.
 */
public interface RouteMatrixGateway {

    /**
     * What is known about travelling each leg, in the direction given.
     *
     * <p>Never throws for a leg it could not answer. A refused, failed or unparseable leg is
     * {@code Absent}, because a caller cannot tell those apart from the provider's own silence and
     * {@link io.nullnull.optimization.domain.route.RouteFeasibility} already refuses to time a day
     * with a gap in it. Misconfiguration is different and does throw: a gateway with no credential
     * would otherwise report every leg as "no answer", which reads as a quiet outage rather than the
     * deployment error it is.
     */
    DirectedRouteMatrix legsFor(Collection<RouteLeg> legs);

    /**
     * One end of a leg: the key the matrix will address it by, and where it is.
     *
     * <p>{@code key} is the caller's identity for the stop within one verification - the same string
     * {@link io.nullnull.optimization.domain.route.PlannedStop#key()} carries - and it never leaves
     * this process. The wire carries coordinates only: a 1-&gt;1 directions request has no field for
     * a caller-chosen identifier, so the pair a response belongs to is fixed by which call was made
     * rather than by anything the provider echoes back.
     */
    record RouteWaypoint(String key, BigDecimal latitude, BigDecimal longitude) {

        public RouteWaypoint {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(latitude, "latitude");
            Objects.requireNonNull(longitude, "longitude");
            if (key.isBlank()) {
                throw new IllegalArgumentException("a waypoint key must not be blank");
            }
            // The same bounds CatalogPlace enforces on the columns these values come from. A place
            // may hold no coordinates at all, and such a place cannot be built into a waypoint -
            // which is the point: it is unroutable, and the leg stays Absent.
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                    || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new IllegalArgumentException("a waypoint must be on the earth");
            }
        }
    }

    /** An ordered pair to ask about. {@code from} to {@code to}, and not the other way. */
    record RouteLeg(RouteWaypoint from, RouteWaypoint to) {

        public RouteLeg {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.key().equals(to.key())) {
                throw new IllegalArgumentException("a leg must join two different stops");
            }
        }
    }
}
