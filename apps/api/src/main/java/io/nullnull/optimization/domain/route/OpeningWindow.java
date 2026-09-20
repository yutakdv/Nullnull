package io.nullnull.optimization.domain.route;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A verified opening window, or a verified closure, for one place on one date. Never "unknown".
 *
 * <p><b>Absence is the only way this vocabulary says "nobody established this."</b> There is no
 * UNKNOWN state, so a caller holding a map of these cannot manufacture a distinction the storage
 * refuses to flatten: a date with no entry is unverified, and a caller must not read it as open.
 * {@link RouteFeasibility} spends a separate reason on that case for exactly this purpose.
 *
 * <p>This mirrors {@code CatalogHoursQuery.CatalogOpeningWindow}, which is the producer and holds the
 * same two states with the same meaning. It is copied rather than imported because that type lives in
 * {@code io.nullnull.catalog.application} and {@code ArchitectureRulesTest.domainLayerDependsOnNothingAbove}
 * forbids a domain class from depending on any application package - the same split
 * {@code CatalogHoursQuery}'s own javadoc describes when it says its consumer converts. The
 * conversion belongs in {@code io.nullnull.optimization.application} when step 3 wires a producer;
 * there is none today.
 */
public record OpeningWindow(State state, LocalTime opensAt, LocalTime closesAt) {

    /** The two things a curated reading can establish. There is no third - see the type javadoc. */
    public enum State { OPEN, CLOSED }

    public OpeningWindow {
        Objects.requireNonNull(state, "state");
        if (state == State.OPEN) {
            Objects.requireNonNull(opensAt, "opensAt is required for an OPEN window");
            Objects.requireNonNull(closesAt, "closesAt is required for an OPEN window");
            if (!opensAt.isBefore(closesAt)) {
                throw new IllegalArgumentException("an OPEN window must open before it closes");
            }
        } else if (opensAt != null || closesAt != null) {
            throw new IllegalArgumentException("a CLOSED day carries no times");
        }
    }

    public static OpeningWindow open(LocalTime opensAt, LocalTime closesAt) {
        return new OpeningWindow(State.OPEN, opensAt, closesAt);
    }

    public static OpeningWindow closed() {
        return new OpeningWindow(State.CLOSED, null, null);
    }
}
