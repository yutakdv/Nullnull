package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.ForecastDays;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.domain.Trip;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a run judged its answer against, frozen so the answer can be re-checked later.
 *
 * <p>What this slice can freeze is WHICH observations were in force, stored as rows so a reader can
 * go and look at them. What it deliberately does not write is {@code data_fingerprint}.
 *
 * <p>That column means one specific thing - the §8 fingerprint {@link
 * io.nullnull.recommendation.application.RunFingerprint} computes - and its inputs include the
 * policy version, the policy hash and the pipeline version, which only the recommendation service's
 * answer carries. BA-050 never calls it, so it cannot produce them. {@code RunFingerprint.Inputs}
 * says the same thing in its own constructor: it refuses an empty snapshot set and a null revision,
 * both of which a run in this slice can legitimately have. A hash computed from something else and
 * stored in that column would be the wrong value under the right name - APPLY would later revalidate
 * against a number that never described the evidence. The V024 CHECK that a READY run has a
 * fingerprint is what makes BA-051 fill it in rather than leaving it out.
 *
 * <p>An empty evidence set is a real answer, not a failure to look. P0 has no live source and the
 * forecast provider covers only some places, so a run about an item with no forecast has nothing to
 * freeze, and zero rows is what that looks like - not an error, and not a reason to withhold the
 * run.
 */
@Component
public class OptimizationEvidence {

    private final TripService trips;
    private final CrowdForecastQuery forecasts;
    private final Clock clock;

    public OptimizationEvidence(TripService trips, CrowdForecastQuery forecasts, Clock clock) {
        this.trips = trips;
        this.forecasts = forecasts;
        this.clock = clock;
    }

    /**
     * The snapshot sets in force for what this run is about.
     *
     * <p>Only fresh sets. A stale set is not evidence a proposal may rest on, and freezing one here
     * would hand the proposal slice something it would have to refuse anyway - while recording, in
     * the run's own evidence rows, that the run had looked at data it was not allowed to use.
     */
    public List<UUID> snapshotSetsFor(OptimizationRun run) {
        Optional<Trip> trip = trips.findForOwner(run.ownerId(), run.tripId());
        if (trip.isEmpty()) {
            // The trip is gone. There is nothing to freeze, and the gate that follows is what turns
            // that into the run's own answer rather than an exception from here.
            return List.of();
        }
        Instant now = clock.instant();
        // The item's date as the forecast source dates it (KST), not in the trip's timezone: see
        // ForecastDays. Read in the trip's zone, a date west of Seoul took the NEXT date's point.
        return trips.itemsOf(run.tripId()).stream()
                .filter(item -> item.id().equals(run.targetItemId()))
                .flatMap(item -> forecasts
                        .latestFresh(item.placeId(), ForecastDays.startOf(item.date()),
                                ForecastDays.endOf(item.date()), now)
                        .stream())
                .map(CrowdForecastQuery.SnapshotSet::id)
                .distinct()
                .toList();
    }
}
