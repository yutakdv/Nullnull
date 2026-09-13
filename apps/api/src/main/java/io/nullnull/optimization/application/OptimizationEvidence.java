package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a run judged its answer against, frozen so the answer can be re-checked later.
 *
 * <p>Two things are frozen and they do different jobs. The snapshot set ids say WHICH observations
 * were in force, and they are stored as rows so a reader can go and look at them. The fingerprint
 * says that nothing about that evidence has changed since, and it is what APPLY revalidates: a
 * preview built on a forecast that has since been superseded must not be applicable, and comparing
 * one hash is how that stays cheap enough to do inside the decision transaction.
 *
 * <p>An empty evidence set is a real answer, not a failure to look. P0 has no live source and the
 * forecast provider covers only some places, so a run about an item with no forecast has nothing to
 * freeze - and the fingerprint of nothing is still a fingerprint: it pins "there was none", so a run
 * that later finds evidence where there was none is a run whose input changed.
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
        ZoneId zone = trip.get().range().timezone();
        Instant now = clock.instant();
        return trips.itemsOf(run.tripId()).stream()
                .filter(item -> item.id().equals(run.targetItemId()))
                .flatMap(item -> forecasts
                        .latestFresh(item.placeId(), startOfDay(item, zone), endOfDay(item, zone), now)
                        .stream())
                .map(CrowdForecastQuery.SnapshotSet::id)
                .distinct()
                .toList();
    }

    /**
     * A hash over the question and the evidence, in a fixed order.
     *
     * <p>The run's own identity is in it as well as the snapshot ids, so two runs that froze the same
     * evidence for different trips do not share a fingerprint - the value answers "is this preview
     * still about the same thing", and the thing includes which trip at which version.
     */
    public String fingerprint(OptimizationRun run, List<UUID> snapshotSetIds) {
        StringBuilder canonical = new StringBuilder()
                .append(run.tripId()).append('|')
                .append(run.inputTripVersion()).append('|')
                .append(run.scope()).append('|')
                .append(run.targetItemId()).append('|')
                .append(run.targetDate()).append('|');
        snapshotSetIds.stream().map(UUID::toString).sorted()
                .forEach(id -> canonical.append(id).append(','));
        return sha256Hex(canonical.toString());
    }

    private static Instant startOfDay(TripItem item, ZoneId zone) {
        return item.date().atStartOfDay(zone).toInstant();
    }

    private static Instant endOfDay(TripItem item, ZoneId zone) {
        LocalDate next = item.date().plusDays(1);
        return next.atStartOfDay(zone).toInstant();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
