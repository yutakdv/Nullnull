package io.nullnull.trip.application;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.domain.CandidateStatus;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripCandidate;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * getCandidateTripMatches: where a saved candidate could go, without putting it anywhere.
 *
 * <p>The whole operation is a question. Invariant 1 keeps SavedPost, TripCandidate and TripItem
 * distinct, and invariant 2 says saving a candidate does not change a schedule - so this reads the
 * trip, asks {@code apps/ai} which dates are eligible, and writes nothing. There is no mutation in
 * this class to get wrong.
 *
 * <p>It is not behind the catalog publication gate, and that is a decision with a reason rather than
 * an oversight. {@code CandidateMatchResult} carries {@code candidateId}, a {@code state} and a list
 * of {@code {date, suggestedTime, eligible, reasonCode}} - no place name, no address, no thumbnail.
 * The gate exists to stop provider-derived place CONTENT reaching a client before the projection is
 * approved; a date and a boolean are the trip's own facts. {@code replaceTripItem}, which does
 * project places, is behind it.
 *
 * <p>Evidence absent is not evidence of absence. Opening hours arrive per date and only for dates
 * somebody verified ({@link CatalogHoursQuery}); the rest are simply not there, and the evaluator is
 * told nothing about them rather than being told they are closed. P0 has no route provider at all, so
 * {@code routeEvidence} is NONE for every request - which is a fact about our data, not about the
 * itinerary.
 */
@Service
public class CandidateMatchService {

    private final CandidateStore candidates;
    private final TripStore trips;
    private final CatalogHoursQuery hours;
    private final RecommendationGateway recommendations;
    private final Clock clock;

    public CandidateMatchService(CandidateStore candidates, TripStore trips, CatalogHoursQuery hours,
            RecommendationGateway recommendations, Clock clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.trips = Objects.requireNonNull(trips, "trips");
        this.hours = Objects.requireNonNull(hours, "hours");
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional(readOnly = true)
    public CandidateMatchView matches(OwnerContext context, UUID tripId, UUID candidateId) {
        TripCandidate candidate = candidates.find(context.ownerId(), tripId, candidateId)
                .orElseThrow(CandidateMatchService::notFound);
        if (candidate.status() != CandidateStatus.ACTIVE) {
            // A SCHEDULED candidate is already somewhere and a DISMISSED one is a choice the owner
            // took back. Answering either with an empty slot list would say "nowhere fits", which is
            // a different sentence from "this is not a candidate waiting to be placed".
            throw new TripValidationException("candidateId", "Unsupported",
                    "only an ACTIVE candidate has slots to offer");
        }
        Trip trip = trips.find(context.ownerId(), tripId).orElseThrow(CandidateMatchService::notFound);
        List<TripItem> items = trips.items(tripId);
        Instant now = clock.instant();

        SlotEvaluateRequest request = new SlotEvaluateRequest(now, tripId, candidateId,
                candidate.placeId(), trip.range().startDate(), trip.range().endDate(),
                trip.range().timezone().getId(), null, neighbours(items),
                openingHours(candidate.placeId(), trip.range(), now),
                datesAlreadyHolding(items, candidate.placeId()),
                // P0 confirms no route, so the evaluator is told it has none rather than being left
                // to read silence as a verified one.
                ItemProposeRequest.RouteEvidence.NONE, TripItem.MAX_PER_DAY,
                // CHECKING means a verification job is running for this candidate. There is no such
                // job in P0, so this is false on every request and the state is unreachable - which
                // CandidateMatchStateCoverageTest records rather than leaving to be discovered.
                false);
        try {
            return view(candidateId, recommendations.evaluateSlots(request));
        } catch (RecommendationUnavailableException unavailable) {
            // UNKNOWN is the honest answer when the evaluator did not answer: not NONE, which would
            // claim every date was checked and rejected. Spring does hydration and this fallback; it
            // does not compute slots of its own (ADR-0006).
            return new CandidateMatchView(candidateId, "UNKNOWN", List.of());
        }
    }

    private static CandidateMatchView view(UUID candidateId, SlotEvaluateResponse response) {
        return new CandidateMatchView(candidateId, response.state().name(),
                response.slots().stream()
                        .map(slot -> new SlotView(slot.date(), slot.suggestedTime(), slot.eligible(),
                                slot.reasonCode()))
                        .toList());
    }

    /**
     * Catalog's verified windows, in the shape the evaluator reads.
     *
     * <p>A conversion and nothing more: every date catalog knows about keeps its verdict, and every
     * date it does not know about stays out. {@code OpeningWindowIn.UNKNOWN} is never constructed
     * here, because {@code CatalogOpeningWindow.State} cannot express it - absence is how this data
     * says "nobody established this", and inventing an UNKNOWN entry would turn a date nobody looked
     * at into a date somebody looked at and could not resolve.
     */
    private Map<LocalDate, OpeningWindowIn> openingHours(UUID placeId,
            io.nullnull.trip.domain.TripDateRange range, Instant now) {
        Map<LocalDate, CatalogOpeningWindow> verified =
                hours.windowsFor(placeId, range.startDate(), range.endDate(), now);
        Map<LocalDate, OpeningWindowIn> converted = new LinkedHashMap<>(verified.size());
        verified.forEach((date, window) -> converted.put(date,
                window.state() == CatalogOpeningWindow.State.OPEN
                        ? OpeningWindowIn.open(window.opensAt(), window.closesAt())
                        : OpeningWindowIn.closed()));
        return converted;
    }

    /** Every item in the trip, as the neighbours a day's shape is judged against. */
    private static List<NeighbourItemIn> neighbours(List<TripItem> items) {
        return items.stream()
                .map(item -> new NeighbourItemIn(item.id(), item.date(), item.position(),
                        item.startTime(), item.durationMinutes()))
                .toList();
    }

    /**
     * The dates that already schedule this place.
     *
     * <p>Distinct, because two items of the same place on one day is one date to the evaluator and
     * the request caps the list at one entry per trip date.
     *
     * <p>Ids are compared as stored, with no canonical resolution, and that is correct only while no
     * place can be deprecated. Measured: {@code KtoSnapshotCatalogIngest} is the one production
     * writer of {@code places} and always passes a null {@code canonical_place_id}, so a DEPRECATED
     * row exists in tests and nowhere else. Resolving here today would be a guard nothing could
     * make fire.
     *
     * <p>It stops being correct the day a merge path exists. A candidate stores whatever place id it
     * was saved with, so after a merge an item could hold the canonical id while a candidate holds
     * the alias - and this comparison would then tell the evaluator the place is free on a day it is
     * already scheduled, quietly. The same applies to the place id sent for opening hours. The slice
     * that introduces merging owes canonicalisation to both call sites; this sentence is where it is
     * recorded, because nothing else would notice.
     */
    private static List<LocalDate> datesAlreadyHolding(List<TripItem> items, UUID placeId) {
        return items.stream()
                .filter(item -> item.placeId().equals(placeId))
                .map(TripItem::date)
                .distinct()
                .sorted()
                .toList();
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested candidate is unavailable.");
    }

    /** CandidateMatchResult, as the contract spells it. */
    public record CandidateMatchView(UUID candidateId, String state, List<SlotView> slots) { }

    public record SlotView(LocalDate date, java.time.LocalTime suggestedTime, boolean eligible,
            String reasonCode) { }
}
