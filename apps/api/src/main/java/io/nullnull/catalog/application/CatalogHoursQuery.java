package io.nullnull.catalog.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

/**
 * Curated opening hours for a place across a trip's dates (V025, A-031/A-032).
 *
 * <p>The return type is catalog's own on purpose. {@code OpeningWindowIn} belongs to the
 * recommendation module, which is the gateway to apps/ai, and having a data source depend on its
 * consumer's DTO inverts the direction - the same split {@link CatalogPlaceQuery} keeps by returning
 * {@code CatalogPlaceSummary} rather than an API type. BA-042 converts.
 *
 * <p>No production caller yet; the BA-042 slot slice adds one. {@code ArchitectureRulesTest}'s
 * AWAITING_THEIR_SLICE register cannot hold this - it scans only
 * {@code io.nullnull.recommendation.application} and skips interfaces - so this sentence is the
 * register.
 *
 * <p><b>The place id is taken as canonical, and nothing resolves an alias for you.</b> That split is
 * safe only while no production path can produce a deprecated place, which is today's measured
 * state: the one writer of {@code places} passes a null {@code canonical_place_id}, and the only
 * DEPRECATED rows in this repository are written by tests. Resolving here now would be a guard no
 * test could fire, which is the same reason the outcome filter is absent below.
 *
 * <p>So the day a merge path lands, this call site owes canonicalisation - and it is not the only
 * one. BA-042's {@code CandidateMatchService.datesAlreadyHolding} compares place ids directly, and
 * an item holding a canonical id against a candidate holding an alias would report a day as free
 * when it is taken. That one is worse than an unverified hour, because it is silent. A merge slice
 * has to fix both call sites together, not whichever one it happens to touch.
 */
public interface CatalogHoursQuery {

    /**
     * The verified windows for this place on the dates in {@code [from, to]}, as of {@code now}.
     *
     * <p>A date with no entry is unverified. That is the whole contract: a caller must not read an
     * absent date as open, and must not read an empty map as a closed trip. {@code State} has no
     * UNKNOWN for the same reason - absence is the only way this type says "nobody established
     * this", so a caller cannot manufacture the distinction the storage refuses to flatten.
     *
     * <p>Evidence is skipped when it has been superseded or has passed its staleness threshold
     * (A-023; P30D for curated hours by A-032). Outcome is deliberately NOT filtered: a window can
     * only exist under an OBSERVED reading, because V025's trigger refuses to attach one to any
     * other outcome and its downgrade guard refuses to weaken an outcome while windows hang off it.
     * Filtering here would be a second guard over that same fact, and then neither would be provable
     * on its own. (The V025 header comment lists that filter as a hydration rule; it is redundant,
     * and the migration cannot be edited now that it has run.)
     *
     * <p>At most one entry per date: one current observation per place, and one window per date
     * within it, are both database constraints - so this map never has to choose between two
     * readings of the same day.
     *
     * <p>The caller bounds the range. {@code SlotEvaluateRequest} already refuses more than 30
     * entries, and duplicating that limit here would put the same rule in two places.
     */
    Map<LocalDate, CatalogOpeningWindow> windowsFor(UUID placeId, LocalDate from, LocalDate to, Instant now);

    /** A verified window or a verified closure. Never "unknown" - see {@link #windowsFor}. */
    record CatalogOpeningWindow(State state, LocalTime opensAt, LocalTime closesAt) {

        public enum State { OPEN, CLOSED }
    }
}
