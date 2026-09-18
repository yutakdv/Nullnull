package io.nullnull.trip.application;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.draft.DraftComposeResponse;
import io.nullnull.recommendation.domain.draft.DraftPlaceIn;
import io.nullnull.recommendation.domain.draft.DraftStopOut;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * previewTripDraft (FR-TRC-10, REC-CON-04): a deterministic draft for a trip that does not exist yet.
 *
 * <p>The whole operation is a question. It reads the catalog, asks {@code apps/ai} where each place
 * could go, and writes nothing - no trip, no item, no candidate, no idempotency record. The user edits
 * the answer in the client and confirms it through createTrip, whose seed validation runs again as for
 * any other seed. Invariant 3 is therefore not something this class has to be careful about; there is
 * no write here to get wrong.
 *
 * <p>The order is the point:
 * <ol>
 * <li>the catalog publication gate first, so a closed catalog answers 503 without anything being read
 *     or {@code apps/ai} being called - this is the only gate on this path, because the pool is read
 *     from {@link CatalogPlaceQuery} directly;</li>
 * <li>the pool and its verified hours in one read-only transaction;</li>
 * <li>the {@code apps/ai} call OUTSIDE any transaction, so a slow service never holds a connection
 *     (CandidateMatchService calls inside one and is not the precedent here);</li>
 * <li>the gateway's post-condition, then the projection.</li>
 * </ol>
 *
 * <p>An unanswered {@code apps/ai} is 503, never an EMPTY 200: EMPTY means "nothing could be placed",
 * and saying it for an outage would tell the user their dates have no options. A service that refused
 * the request, or answered outside its contract, is 500 with {@code retryable: false}, because the
 * fault is this API's hydration or the service's contract and retrying the same request cannot help.
 */
@Service
public class TripDraftPreviewService {

    /**
     * How many stops one draft date holds. An initial design value, not a measurement: nobody has
     * measured how many places a visitor wants on one day of a draft. It is sent to {@code apps/ai}
     * rather than added to policy-v1, whose hash is pinned in three places. createTrip's own cap
     * ({@code TripItem.MAX_PER_DAY}) is the ceiling this may never exceed.
     */
    static final int MAX_STOPS_PER_DAY = 3;

    /** The service ranks at most this many places; one more row is read to learn whether it truncated. */
    static final int POOL_LIMIT = DraftComposeRequest.MAX_POOL;

    private final CatalogPlaceProjectionService publication;
    private final CatalogPlaceQuery catalog;
    private final CatalogHoursQuery hours;
    private final OwnerPreferencesService preferences;
    private final RecommendationGateway recommendations;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public TripDraftPreviewService(CatalogPlaceProjectionService publication, CatalogPlaceQuery catalog,
            CatalogHoursQuery hours, OwnerPreferencesService preferences, RecommendationGateway recommendations,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.publication = Objects.requireNonNull(publication, "publication");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.hours = Objects.requireNonNull(hours, "hours");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations");
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TripDraftPreviewView preview(OwnerContext owner, LocalDate startDate, LocalDate endDate, String timezone) {
        Objects.requireNonNull(owner, "owner");
        publication.requirePublicProjection();
        if (startDate == null) {
            throw new TripValidationException("startDate", "NotNull", "startDate is required");
        }
        if (endDate == null) {
            throw new TripValidationException("endDate", "NotNull", "endDate is required");
        }
        TripDateRange range = TripDateRange.of(startDate, endDate, timezone);
        Instant now = clock.instant();
        Hydrated hydrated = readOnly.execute(status -> hydrate(owner, range, now));

        DraftComposeResponse answer;
        try {
            answer = recommendations.composeDraft(hydrated.request());
        } catch (RecommendationUnavailableException unavailable) {
            if (unavailable.retryable()) {
                throw new ApiException(ProblemCode.SOURCE_UNAVAILABLE,
                        "The draft service is not available right now.");
            }
            throw new ApiException(ProblemCode.INTERNAL_ERROR, "The draft could not be composed.");
        }
        return project(range, hydrated, answer, now);
    }

    private Hydrated hydrate(OwnerContext owner, TripDateRange range, Instant now) {
        String locale = preferences.get(owner).locale();
        List<CatalogPlaceSummary> fetched = catalog.activePool(POOL_LIMIT + 1, locale, now);
        boolean truncated = fetched.size() > POOL_LIMIT;
        List<CatalogPlaceSummary> pool = truncated ? fetched.subList(0, POOL_LIMIT) : fetched;
        List<UUID> ids = pool.stream().map(CatalogPlaceSummary::id).toList();
        Map<UUID, Map<LocalDate, CatalogOpeningWindow>> verified =
                hours.windowsForAll(ids, range.startDate(), range.endDate(), now);
        List<DraftPlaceIn> places = new ArrayList<>(pool.size());
        Map<UUID, CatalogPlaceSummary> summaries = new HashMap<>();
        for (CatalogPlaceSummary summary : pool) {
            summaries.put(summary.id(), summary);
            places.add(new DraftPlaceIn(summary.id(), openingHours(verified.getOrDefault(summary.id(), Map.of()))));
        }
        DraftComposeRequest request = new DraftComposeRequest(now, range.startDate(), range.endDate(),
                range.timezone().getId(), MAX_STOPS_PER_DAY, places);
        return new Hydrated(request, summaries, truncated);
    }

    /**
     * Catalog's verified windows in the shape the service reads - the conversion
     * CandidateMatchService.openingHours makes. A date catalog does not know about stays out; an
     * UNKNOWN window is never constructed, because absence is how this data says "nobody established
     * this".
     */
    private static Map<LocalDate, OpeningWindowIn> openingHours(Map<LocalDate, CatalogOpeningWindow> verified) {
        Map<LocalDate, OpeningWindowIn> converted = new LinkedHashMap<>(verified.size());
        verified.forEach((date, window) -> converted.put(date,
                window.state() == CatalogOpeningWindow.State.OPEN
                        ? OpeningWindowIn.open(window.opensAt(), window.closesAt())
                        : OpeningWindowIn.closed()));
        return converted;
    }

    /**
     * Every trip date becomes a day, including the ones with no stop: a date the service left empty is
     * part of the answer, and dropping it would make the draft look shorter than the trip. Nothing is
     * padded - an EMPTY draft is every date with no stop.
     */
    private static TripDraftPreviewView project(TripDateRange range, Hydrated hydrated, DraftComposeResponse answer,
            Instant now) {
        Map<LocalDate, List<DraftStopOut>> byDate = new HashMap<>();
        for (DraftStopOut stop : answer.stops()) {
            byDate.computeIfAbsent(stop.date(), date -> new ArrayList<>()).add(stop);
        }
        boolean anyOpen = false;
        List<TripDraftPreviewView.Day> days = new ArrayList<>(range.dayCount());
        for (LocalDate date : range.days()) {
            List<TripDraftPreviewView.Stop> stops = new ArrayList<>();
            for (DraftStopOut stop : byDate.getOrDefault(date, List.of()).stream()
                    .sorted(Comparator.comparingInt(DraftStopOut::position)).toList()) {
                anyOpen = anyOpen || stop.hoursState() == DraftStopOut.HoursState.OPEN;
                stops.add(new TripDraftPreviewView.Stop(hydrated.summaries().get(stop.placeId()), date,
                        stop.position(), stop.hoursState().name()));
            }
            days.add(new TripDraftPreviewView.Day(date, List.copyOf(stops)));
        }
        // A basis is named only when it decided at least one stop: every draft is shaped by its dates,
        // and verified hours count only when some stop actually sits on a verified OPEN date.
        List<String> basis = new ArrayList<>(List.of(TripDraftPreviewView.BASIS_DATE_RANGE));
        if (anyOpen) {
            basis.add(TripDraftPreviewView.BASIS_OPENING_HOURS_VERIFIED);
        }
        List<String> reasons = new ArrayList<>(answer.reasons());
        if (hydrated.truncated()) {
            reasons.add(TripDraftPreviewView.REASON_POOL_TRUNCATED);
        }
        return new TripDraftPreviewView(answer.state().name(), now, answer.policyVersion(), List.copyOf(basis),
                List.copyOf(days), List.copyOf(reasons));
    }

    private record Hydrated(DraftComposeRequest request, Map<UUID, CatalogPlaceSummary> summaries,
            boolean truncated) {
    }
}
