package io.nullnull.catalog.infrastructure.kto;

import io.nullnull.catalog.application.CanonicalCatalogStore;
import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.crowd.application.CrowdForecastQuery;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Keeps the staging demo's places comparable for as long as judging runs.
 *
 * <p>Two clocks decide whether an ITEM optimization has any evidence, and nothing in the service renews
 * either: the only caller of the forecast gateway is an operator command. A forecast set is stale
 * after PT24H, and a stale set is not evidence a proposal may rest on (OptimizationEvidence), so a run
 * a day after the last load ends DATA_INSUFFICIENT. The detailCommon2 snapshot a forecast request is
 * built from is stale after P7D, and after that even a forecast refresh has no mapping to ask with
 * (KtoForecastSnapshotStore.findFreshRequest). Each {@link Mode} renews one clock, for the places it is
 * given, before it lapses rather than after.
 *
 * <p>A mode renews only what would lapse before the next scheduled run and leaves the rest alone, so a
 * rerun costs no calls. Every call goes through the existing gateways, with their quota reservation,
 * collector audit and response validation; this class adds no provider path of its own. It prints
 * identifiers, counts and hashes only - never provider text (the smoke evidence rule).
 */
public final class KtoDemoRefresh {

    public enum Mode { FORECAST, DETAIL }

    /**
     * A detail snapshot that lapses within this of a run's start is renewed. A day longer than the
     * 5-day schedule (infra DETAIL_SCHEDULE_RATE_DAYS), and that day is the margin: each run renews the
     * snapshot the run before it fetched as long as a run's call comes less than a day later after its
     * tick than the previous run's did. Lateness is the scheduler's delivery, which its maxEventAge
     * bounds (1 h; an older invocation is dropped, not delivered late), then the Fargate start and the
     * places ahead of this one - minutes, and bounded by nothing here. Shorter than the P7D life, so a
     * rerun right after a fetch costs no call. Two days put that snapshot exactly on the next run's
     * boundary (#361).
     */
    static final Duration DETAIL_RENEW_BEFORE = Duration.ofDays(6);

    /**
     * A forecast set that lapses within this of a run's start is renewed. Six hours longer than the
     * 12-hour schedule: the same margin as above, six hours instead of a day. Shorter than the PT24H
     * life. Twelve hours, the schedule itself, was the same boundary (#361).
     */
    static final Duration FORECAST_RENEW_BEFORE = Duration.ofHours(18);

    /** The provider forecasts the 30 days after the query date (SOURCE_CATALOG.md); one day of margin. */
    private static final Duration FORECAST_HORIZON = Duration.ofDays(31);

    private static final String KTO_PLACE_SOURCE = KtoPlaceSnapshot.SOURCE_CODE;
    private static final String KTO_CONTENT_TYPE_PREFIX = "KTO_CONTENT_TYPE:";
    private static final Pattern ENTRY = Pattern.compile("[1-9][0-9]{0,29}:[1-9][0-9]{0,29}");

    private final KtoPlaceDetailGateway details;
    private final KtoPlaceSnapshotStore detailSnapshots;
    private final CatalogIngest ingest;
    private final CanonicalCatalogStore catalog;
    private final KtoCrowdForecastGateway forecasts;
    private final KtoForecastSnapshotStore forecastMappings;
    private final CrowdForecastQuery forecastSets;
    private final SourceRegistryQuery registry;
    private final Clock clock;

    public KtoDemoRefresh(KtoPlaceDetailGateway details, KtoPlaceSnapshotStore detailSnapshots,
            CatalogIngest ingest, CanonicalCatalogStore catalog, KtoCrowdForecastGateway forecasts,
            KtoForecastSnapshotStore forecastMappings, CrowdForecastQuery forecastSets,
            SourceRegistryQuery registry, Clock clock) {
        this.details = Objects.requireNonNull(details, "details");
        this.detailSnapshots = Objects.requireNonNull(detailSnapshots, "detailSnapshots");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
        this.forecastMappings = Objects.requireNonNull(forecastMappings, "forecastMappings");
        this.forecastSets = Objects.requireNonNull(forecastSets, "forecastSets");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The places, as {@code contentId:contentTypeId} separated by commas.
     *
     * <p>The whole list is refused before anything runs - never an entry skipped - when it is empty, when
     * an entry is not two KTO identifiers, or when a contentId repeats. A skipped entry would be a place
     * the schedule silently stopped renewing, and it would surface days later as a demo that fails.
     */
    public static List<KtoPlaceRequest> places(String list) {
        if (list == null || list.isBlank()) {
            throw new IllegalArgumentException("the place list is empty");
        }
        List<KtoPlaceRequest> places = new ArrayList<>();
        Set<String> contentIds = new HashSet<>();
        for (String raw : list.split(",", -1)) {
            String entry = raw.strip();
            if (!ENTRY.matcher(entry).matches()) {
                throw new IllegalArgumentException("a place entry is not contentId:contentTypeId");
            }
            String[] parts = entry.split(":");
            if (!contentIds.add(parts[0])) {
                throw new IllegalArgumentException("a contentId appears more than once");
            }
            places.add(new KtoPlaceRequest(parts[0], parts[1]));
        }
        return List.copyOf(places);
    }

    /** Runs one mode over every place, printing one line per place and continuing past a failure. */
    public Report run(Mode mode, List<KtoPlaceRequest> places, Consumer<String> out) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(out, "out");
        if (places == null || places.isEmpty()) {
            throw new IllegalArgumentException("the place list is empty");
        }
        Instant now = clock.instant();
        String source = mode == Mode.FORECAST ? KtoForecastSnapshotSet.SOURCE_CODE : KTO_PLACE_SOURCE;
        Duration renewBefore = mode == Mode.FORECAST ? FORECAST_RENEW_BEFORE : DETAIL_RENEW_BEFORE;
        int planned = (int) places.stream().filter(place -> due(mode, place, now.plus(renewBefore))).count();
        int perDay = registry.find(source)
                .orElseThrow(() -> new IllegalStateException("source " + source + " is not registered"))
                .quotaPerDay();
        out.accept("KTO_DEMO_REFRESH_QUOTA mode=" + name(mode) + " source=" + source + " places=" + places.size()
                + " planned_calls=" + planned + " per_day=" + perDay
                + " planned_ratio=" + ratio(planned, perDay) + " renew_before=" + renewBefore);

        List<Outcome> outcomes = new ArrayList<>();
        for (KtoPlaceRequest place : places) {
            Outcome outcome;
            try {
                outcome = mode == Mode.FORECAST ? forecast(place, now) : detail(place, now);
            } catch (RuntimeException failure) {
                outcome = Outcome.failed(place, KtoSmokeEnvironment.failureCode(failure), true);
            }
            outcomes.add(outcome);
            outcome.lines(mode).forEach(out);
        }
        Report report = new Report(mode, source, perDay, List.copyOf(outcomes));
        out.accept(report.line());
        return report;
    }

    /** Whether this place needs a call: nothing fresh at the renewal horizon. */
    private boolean due(Mode mode, KtoPlaceRequest place, Instant horizon) {
        if (mode == Mode.DETAIL) {
            return detailSnapshots.findFresh(place, horizon).isEmpty();
        }
        return canonical(place).map(canonical -> freshForecast(canonical.id(), horizon).isEmpty()).orElse(false);
    }

    private Outcome detail(KtoPlaceRequest place, Instant now) {
        Instant horizon = now.plus(DETAIL_RENEW_BEFORE);
        Optional<KtoPlaceSnapshot> current = detailSnapshots.findFresh(place, horizon);
        KtoPlaceSnapshot snapshot = current.orElseGet(() -> details.detailFreshAt(place, horizon).join());
        // Idempotent: the canonical place is found by its external reference and created only once. It
        // runs for a current snapshot too, so a first run over a list the catalog never saw maps it.
        CatalogPlace canonical = ingest.ingest(snapshot);
        return new Outcome(place, current.isPresent() ? Status.CURRENT : Status.REFRESHED, canonical.id(),
                "snapshotId=" + snapshot.id() + " collectorRunId=" + snapshot.collectorRunId()
                        + " payloadHash=" + snapshot.payloadHash() + " fetchedAt=" + snapshot.fetchedAt()
                        + " staleAt=" + snapshot.staleAt(),
                null, current.isEmpty());
    }

    private Outcome forecast(KtoPlaceRequest place, Instant now) {
        Optional<CatalogPlace> canonical = canonical(place);
        if (canonical.isEmpty()) {
            // Mapped by the detail mode; a place it never saw has nothing to ask the forecast with.
            return Outcome.failed(place, "NO_CANONICAL_PLACE", false);
        }
        UUID placeId = canonical.get().id();
        Optional<CrowdForecastQuery.SnapshotSet> current = freshForecast(placeId, now.plus(FORECAST_RENEW_BEFORE));
        if (current.isPresent()) {
            return new Outcome(place, Status.CURRENT, placeId, "snapshotSetId=" + current.get().id(), null, false);
        }
        Optional<KtoForecastRequest> mapping = forecastMappings.findFreshRequest(placeId, now);
        if (mapping.isEmpty()) {
            // The detail snapshot lapsed: the detail mode has not run within P7D.
            return new Outcome(place, Status.FAILED, placeId, null, "NO_VERIFIED_KTO_MAPPING", false);
        }
        KtoCrowdForecastGateway.RefreshResult result = forecasts.refresh(mapping.get()).join();
        String evidence = result.snapshotSet()
                .map(set -> "coverage=" + set.points().size() + " snapshotSetId=" + set.id()
                        + " collectorRunId=" + set.collectorRunId() + " forecastIssueId=" + set.forecastIssueId()
                        + " payloadHash=" + set.payloadHash() + " fetchedAt=" + set.fetchedAt()
                        + " staleAt=" + set.staleAt())
                .orElse("coverage=0");
        return new Outcome(place, Status.REFRESHED, placeId, evidence, null, true);
    }

    private Optional<CatalogPlace> canonical(KtoPlaceRequest place) {
        return catalog.findByExternalReference(KTO_PLACE_SOURCE, place.contentId(),
                KTO_CONTENT_TYPE_PREFIX + place.contentTypeId());
    }

    /** A stored set that is still fresh at {@code at}, over the provider's forecast horizon. */
    private Optional<CrowdForecastQuery.SnapshotSet> freshForecast(UUID placeId, Instant at) {
        Instant now = clock.instant();
        return forecastSets.latestFresh(placeId, now, now.plus(FORECAST_HORIZON), at);
    }

    private static String name(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    /** Four decimals, no percent sign: the operator log allowlist has none. */
    static String ratio(int calls, int perDay) {
        return String.format(Locale.ROOT, "%.4f", perDay <= 0 ? 0.0 : (double) calls / perDay);
    }

    public enum Status { REFRESHED, CURRENT, FAILED }

    /**
     * One place's result. {@code called} is whether a provider call was made or attempted, which is what
     * counts against the quota.
     */
    public record Outcome(KtoPlaceRequest place, Status status, UUID placeId, String evidence, String failureCode,
            boolean called) {

        static Outcome failed(KtoPlaceRequest place, String code, boolean called) {
            return new Outcome(place, Status.FAILED, null, null, code, called);
        }

        /**
         * The result, and the evidence on a line of its own: the operator log allowlist passes a line of at
         * most 400 characters after its tag, and a forecast's identifiers and hashes alone come close.
         */
        List<String> lines(Mode mode) {
            StringBuilder line = new StringBuilder("KTO_DEMO_REFRESH_PLACE mode=").append(name(mode))
                    .append(" contentId=").append(place.contentId())
                    .append(" contentTypeId=").append(place.contentTypeId())
                    .append(" status=").append(status);
            if (placeId != null) {
                line.append(" placeId=").append(placeId);
            }
            if (failureCode != null) {
                line.append(" failure=").append(failureCode);
            }
            return evidence == null ? List.of(line.toString())
                    : List.of(line.toString(), "KTO_DEMO_REFRESH_EVIDENCE contentId=" + place.contentId() + " " + evidence);
        }
    }

    /** The run as a whole; {@link #failed()} is what the command turns into exit status 1. */
    public record Report(Mode mode, String source, int perDay, List<Outcome> outcomes) {

        public long count(Status status) {
            return outcomes.stream().filter(outcome -> outcome.status() == status).count();
        }

        public int calls() {
            return (int) outcomes.stream().filter(Outcome::called).count();
        }

        public boolean failed() {
            return count(Status.FAILED) > 0;
        }

        String line() {
            return "KTO_DEMO_REFRESH_DONE mode=" + name(mode) + " source=" + source
                    + " places=" + outcomes.size() + " refreshed=" + count(Status.REFRESHED)
                    + " current=" + count(Status.CURRENT) + " failed=" + count(Status.FAILED)
                    + " calls=" + calls() + " per_day=" + perDay + " calls_ratio=" + ratio(calls(), perDay);
        }
    }
}
