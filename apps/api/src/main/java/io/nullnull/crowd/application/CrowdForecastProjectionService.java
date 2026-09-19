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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C4 public read path: local immutable snapshots only. The collector is deliberately separate so a
 * public request can never treat an unvalidated provider response as a forecast.
 */
@Service
public class CrowdForecastProjectionService {

    /** queryPlaceCrowdForecasts' maxItems: one search page, PlaceSearchRequest.limit's maximum. */
    static final int MAX_PLACES = 50;

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
                            "to must not precede from or exceed the approved forecast range, and both must fall in the"
                            + " years 0001-9999.")));
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

    /**
     * queryPlaceCrowdForecasts: {@link #forecast}'s answer for many places and one window, in the
     * request's order and in a number of statements that does not grow with the list (BA-023-T14).
     *
     * <p>{@code items[i]} answers {@code requestedPlaceIds[i]}, assembled by walking the request -
     * never in the order a query returned rows. An id {@link #forecast} would answer 404 for answers
     * its own item UNAVAILABLE with {@code PLACE_UNAVAILABLE} instead of failing the call, because one
     * card whose place went away must not blank the other forty-nine.
     *
     * <p>Validation comes before the publication gate, as it does in {@link #forecast}, and a null is
     * refused here rather than reaching {@link CrowdForecastProperties#accepts}, whose null check would
     * otherwise answer 500.
     */
    @Transactional(readOnly = true)
    public List<CrowdSeries> forecastMany(OwnerContext owner, List<UUID> requestedPlaceIds, Instant from,
            Instant to) {
        Objects.requireNonNull(owner, "owner");
        List<FieldError> invalid = invalid(requestedPlaceIds, from, to);
        if (!invalid.isEmpty()) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "The forecast query is invalid.", invalid);
        }
        Map<UUID, UUID> readable = catalog.readableCanonicalIds(requestedPlaceIds);
        // One instant for the whole response, or two items could be judged fresh and stale on either
        // side of the same staleAt.
        Instant now = clock.instant();
        Set<UUID> places = new LinkedHashSet<>(readable.values());
        Map<UUID, UUID> fresh = forecasts.latestFreshSetIds(places, from, to, now);
        Set<UUID> withoutFresh = new LinkedHashSet<>(places);
        withoutFresh.removeAll(fresh.keySet());
        // Asked once for every place the fresh read left out, however many that is.
        Map<UUID, UUID> stale = forecasts.latestStaleSetIds(withoutFresh, from, to, now);
        Map<UUID, UUID> chosen = new HashMap<>(fresh);
        chosen.putAll(stale);
        // A chosen set always has a point for its place in the window - that is how it was chosen, and
        // no production path deletes a snapshot - so a place missing here means it had no set at all.
        Map<UUID, CrowdForecastQuery.SnapshotSet> sets = forecasts.sets(chosen, from, to);

        List<CrowdSeries> items = new ArrayList<>(requestedPlaceIds.size());
        for (UUID requested : requestedPlaceIds) {
            UUID placeId = readable.get(requested);
            if (placeId == null) {
                items.add(CrowdSeries.unavailable(requested, "PLACE_UNAVAILABLE"));
                continue;
            }
            CrowdForecastQuery.SnapshotSet set = sets.get(placeId);
            items.add(set == null
                    ? CrowdSeries.unavailable(placeId, "NO_COVERAGE")
                    : project(placeId, set, now, stale.containsKey(placeId)));
        }
        return List.copyOf(items);
    }

    /** Every field error in the query at once, so a client fixes its request in one round trip. */
    private List<FieldError> invalid(List<UUID> placeIds, Instant from, Instant to) {
        List<FieldError> errors = new ArrayList<>();
        if (placeIds == null) {
            errors.add(new FieldError("placeIds", "NotNull", "placeIds is required"));
        } else if (placeIds.isEmpty() || placeIds.size() > MAX_PLACES) {
            errors.add(new FieldError("placeIds", "Size",
                    "placeIds must hold between 1 and " + MAX_PLACES + " place ids"));
        } else if (placeIds.stream().anyMatch(Objects::isNull)) {
            errors.add(new FieldError("placeIds", "NotNull", "placeIds must not contain null"));
        } else if (new HashSet<>(placeIds).size() != placeIds.size()) {
            errors.add(new FieldError("placeIds", "Duplicate", "placeIds must not repeat a place id"));
        }
        if (from == null) {
            errors.add(new FieldError("from", "NotNull", "from is required"));
        }
        if (to == null) {
            errors.add(new FieldError("to", "NotNull", "to is required"));
        }
        if (from != null && to != null && !properties.accepts(from, to)) {
            errors.add(new FieldError("to", "INVALID_RANGE",
                    "to must not precede from or exceed the approved forecast range, and both must fall in the"
                            + " years 0001-9999."));
        }
        return errors;
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
