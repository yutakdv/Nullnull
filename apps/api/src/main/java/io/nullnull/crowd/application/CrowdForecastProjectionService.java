package io.nullnull.crowd.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.crowd.application.CrowdProvenanceProjection.CrowdMetric;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.FieldError;
import io.nullnull.shared.problem.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C4 public read path: local immutable snapshots only. The collector is deliberately separate so a
 * public request can never treat an unvalidated provider response as a forecast.
 */
@Service
public class CrowdForecastProjectionService {

    private final CatalogPlaceProjectionService catalog;
    private final CrowdForecastQuery forecasts;
    private final CrowdProvenanceProjection provenance;
    private final CrowdForecastProperties properties;
    private final Clock clock;

    public CrowdForecastProjectionService(CatalogPlaceProjectionService catalog, CrowdForecastQuery forecasts,
            CrowdProvenanceProjection provenance, CrowdForecastProperties properties, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public CrowdSeries forecast(OwnerContext owner, UUID requestedPlaceId, Instant from, Instant to) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requestedPlaceId, "requestedPlaceId");
        if (!properties.accepts(from, to)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The requested forecast range is invalid.",
                    List.of(new FieldError("to", "INVALID_RANGE",
                            "to must not precede from or exceed the approved forecast range.")));
        }
        // Reuse the C3 canonical resolver. A deprecated identifier therefore converges to one active
        // place, and the fail-closed catalog publication gate also protects this nested route.
        UUID placeId = catalog.detail(owner, requestedPlaceId).id();
        Instant now = clock.instant();
        return forecasts.latestFresh(placeId, from, to, now)
                .map(set -> project(placeId, set, now, false))
                .or(() -> forecasts.latestStale(placeId, from, to, now)
                        .map(set -> project(placeId, set, now, true)))
                .orElseGet(() -> CrowdSeries.unavailable(placeId, "NO_COVERAGE"));
    }

    private CrowdSeries project(UUID placeId, CrowdForecastQuery.SnapshotSet set, Instant now, boolean staleFallback) {
        List<CrowdMetric> points = set.snapshots().stream()
                .map(snapshot -> provenance.project(snapshot, now, staleFallback)).toList();
        return new CrowdSeries(placeId, points.get(0).state(), points, null);
    }

    public record CrowdSeries(UUID placeId, SourceState state, List<CrowdMetric> points, String unavailableReason) {
        public CrowdSeries {
            points = List.copyOf(points);
        }

        static CrowdSeries unavailable(UUID placeId, String reason) {
            return new CrowdSeries(placeId, SourceState.UNAVAILABLE, List.of(), reason);
        }
    }
}
