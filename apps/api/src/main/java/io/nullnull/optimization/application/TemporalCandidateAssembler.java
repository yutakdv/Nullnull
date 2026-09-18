package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dates an item could move to, each carrying the pair of measurements that would justify it.
 *
 * <p>Every candidate is a comparison, not a suggestion: a date appears here only because there is a
 * stored forecast for it AND a stored forecast for where the item is now, both from the same frozen
 * snapshot set. That is what lets {@code apps/ai} decide anything at all - a candidate without both
 * halves is not a weaker option, it is a question nobody measured, and offering it would be the
 * invariant 8 violation this pipeline exists to prevent.
 *
 * <p><strong>Day resolution, and that is measured rather than chosen.</strong> The one forecast
 * source P0 has stores a point per date at Seoul midnight ({@code KtoForecastResponseValidator}
 * builds {@code targetDate.atStartOfDay(SEOUL)}), so there is no hour-level evidence to compare and
 * no honest way to propose a time. Candidates therefore carry a date and no time, which
 * {@code TemporalCandidateIn} maps to {@code DAY}. An hour-resolution source would change this, and
 * would change it here rather than anywhere downstream.
 *
 * <p>Eligibility is never decided here. {@code CrowdProvenanceProjection.compare} owns what "the
 * same series" means, and this asks it about each pair - including the pairs it expects to be
 * refused, because a refused pair with its reason is information the optimizer uses and a dropped
 * one is not.
 */
@Service
public class TemporalCandidateAssembler {

    private final CrowdForecastQuery forecasts;
    private final CrowdProvenanceProjection provenance;

    public TemporalCandidateAssembler(CrowdForecastQuery forecasts, CrowdProvenanceProjection provenance) {
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    /**
     * Every other date in the trip that the run's frozen forecast covers for this place, paired against
     * the date the item sits on now.
     *
     * <p>The set is the one the run froze, read by its id - never chosen again (#259). Choosing it here
     * with {@code latestFresh} was a second read in a second unit of work: a set stored in between, or
     * a newer one that leaves out the item's day, made the candidates - and so the pair a proposal
     * compared - come from a set the run never froze, which APPLY then revalidated instead.
     *
     * <p>An empty list is a real answer with two causes that the caller must not merge: no forecast
     * covers the trip at all, or none covers the day the item is on, so nothing can be compared
     * against it. Neither is "the itinerary is already optimal", and the run has to say so.
     *
     * @param frozenSetIds the sets {@code OptimizationEvidence.snapshotSetsFor} froze for this run
     */
    @Transactional(readOnly = true)
    public Candidates candidatesFor(List<UUID> frozenSetIds, UUID placeId, LocalDate currentDate,
            LocalDate tripStart, LocalDate tripEnd, ZoneId zone, Instant now) {
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(currentDate, "currentDate");
        Instant from = tripStart.atStartOfDay(zone).toInstant();
        Instant to = tripEnd.plusDays(1).atStartOfDay(zone).toInstant();

        // Fresh only. A stale forecast is readable elsewhere with its freshness shown to a human, but
        // a proposal is an instruction to change a plan, and a stale measurement is not evidence for
        // one. The frozen set was fresh when frozen; one that has gone stale since offers nothing.
        Optional<CrowdForecastQuery.SnapshotSet> found = frozenAndFresh(frozenSetIds, placeId, from, to, now);
        if (found.isEmpty()) {
            return Candidates.none();
        }
        List<CrowdForecastQuery.Snapshot> snapshots = found.get().snapshots();
        Instant currentTarget = currentDate.atStartOfDay(zone).toInstant();
        Optional<CrowdForecastQuery.Snapshot> before = snapshots.stream()
                .filter(snapshot -> snapshot.targetAt().equals(currentTarget))
                .findFirst();
        if (before.isEmpty()) {
            return Candidates.none();
        }

        List<TemporalCandidateIn> candidates = new ArrayList<>();
        java.util.Set<UUID> pinned = new java.util.LinkedHashSet<>();
        for (CrowdForecastQuery.Snapshot after : snapshots) {
            if (after.targetAt().equals(currentTarget)) {
                continue;
            }
            LocalDate date = LocalDate.ofInstant(after.targetAt(), zone);
            if (date.isBefore(tripStart) || date.isAfter(tripEnd)) {
                // The set is asked for by window, but a window in instants and a trip in local dates
                // do not have the same edges. Filtering by the date the traveller would see keeps a
                // candidate from appearing on a day the trip does not contain.
                continue;
            }
            CrowdProvenanceProjection.PairVerdict verdict =
                    provenance.compare(before.get(), after, now);
            candidates.add(new TemporalCandidateIn(placeId, date, null,
                    TemporalCandidateIn.ForecastResolution.DAY, before.get().value(), after.value(),
                    after.metricCode(), verdict.eligible(), verdict.reasonCode(), before.get().id(),
                    after.id()));
            pinned.add(before.get().id());
            pinned.add(after.id());
        }
        if (candidates.isEmpty()) {
            return Candidates.none();
        }
        CrowdForecastQuery.Snapshot evidence = before.get();
        return new Candidates(List.copyOf(candidates), Set.copyOf(pinned),
                Map.of(evidence.source().code(), Math.toIntExact(evidence.source().registryVersion())),
                evidence.normalizationVersion(), evidence.source().attribution(),
                evidence.forecastIssueId(), evidence.metricCode());
    }

    /**
     * The first frozen set that still holds points for this place in the window and is fresh now -
     * the same test {@code latestFresh} made when the run froze it (a set's {@code stale_at} after
     * now), read off the points, which carry their set's {@code stale_at}.
     */
    private Optional<CrowdForecastQuery.SnapshotSet> frozenAndFresh(List<UUID> frozenSetIds, UUID placeId,
            Instant from, Instant to, Instant now) {
        for (UUID setId : frozenSetIds) {
            Optional<CrowdForecastQuery.SnapshotSet> set = forecasts.frozenSet(setId, placeId, from, to)
                    .filter(frozen -> frozen.snapshots().stream()
                            .allMatch(point -> point.staleAt() != null && now.isBefore(point.staleAt())));
            if (set.isPresent()) {
                return set;
            }
        }
        return Optional.empty();
    }

    /**
     * The candidates and everything else that came from the same frozen set.
     *
     * <p>The extra fields are not decoration: the run's fingerprint pins the snapshot ids, the source
     * revision and the normalization version, and the explanation names the metric and the source
     * line. All of them describe ONE set, so they are read once here rather than by a caller going
     * back to the store for values it already had in its hands - a second read is a second moment,
     * and the whole point of freezing evidence is that there is only one.
     */
    public record Candidates(List<TemporalCandidateIn> items, Set<UUID> snapshotIds,
            Map<String, Integer> sourceRegistryVersions, String normalizationVersion,
            String attribution, String forecastIssueId, String metricCode) {

        public static Candidates none() {
            return new Candidates(List.of(), Set.of(), Map.of(), null, null, null, null);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }
}
