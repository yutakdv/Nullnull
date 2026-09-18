package io.nullnull.recommendation.infrastructure;

import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.draft.DraftComposeRequest;
import io.nullnull.recommendation.domain.draft.DraftComposeResponse;
import io.nullnull.recommendation.domain.draft.DraftPlaceIn;
import io.nullnull.recommendation.domain.draft.DraftStopOut;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderRequest;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderResponse;
import io.nullnull.recommendation.domain.feed.FeedCandidateIn;
import io.nullnull.recommendation.domain.feed.FeedRankRequest;
import io.nullnull.recommendation.domain.feed.FeedRankResponse;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.recommendation.domain.related.RelatedItemOut;
import io.nullnull.recommendation.domain.related.RelatedRankRequest;
import io.nullnull.recommendation.domain.related.RelatedRankResponse;
import io.nullnull.recommendation.domain.related.RelationCandidateIn;
import io.nullnull.recommendation.domain.related.RelationCandidateIn.RelationTier;
import io.nullnull.recommendation.domain.slot.SlotEvaluateRequest;
import io.nullnull.recommendation.domain.slot.SlotEvaluateResponse;
import io.nullnull.recommendation.domain.slot.SlotOut;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Internal contract v1 over HTTP. Called outside any DB transaction. Response identifiers are
 * checked against the request so the service can never introduce an id Spring did not hydrate.
 * Only the idempotent GET is retried, and only when apps/ai did not answer; {@code rankFeed},
 * {@code proposeItem}, {@code evaluateSlots}, {@code rankRelated}, {@code renderExplanation} and
 * {@code composeDraft} are POSTs and are attempted once. Which failures are retryable is decided in
 * {@link #exchange} for all seven.
 */
public class HttpRecommendationGateway implements RecommendationGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpRecommendationGateway.class);

    /** One bounded retry for the idempotent policy read; the caller's fallback covers the rest. */
    private static final int POLICY_ATTEMPTS = 2;

    /** policy-v1 candidateCaps.itemProposals: the service may never rank more than three previews. */
    private static final int MAX_PROPOSALS = PolicyPins.V1.caps().itemProposals();

    /** policy-v1 candidateCaps.slotDates: one answer per trip date, and a trip spans at most 30 (§4.1). */
    private static final int MAX_SLOT_DATES = PolicyPins.V1.caps().slotDates();

    /** policy-v1 candidateCaps.relatedMerged: the service merges to at most 300 canonical places. */
    private static final int MAX_RELATED_ITEMS = PolicyPins.V1.caps().relatedMerged();


    private final RestClient client;
    private final Supplier<String> requestId;

    /** {@code client} already carries the base URL and the connect/read timeouts applied by {@link RecommendationClientConfiguration}. */
    public HttpRecommendationGateway(RestClient client, Supplier<String> requestId) {
        this.client = client;
        this.requestId = requestId;
    }

    @Override
    public PolicyDescriptor policy() {
        RecommendationUnavailableException lastFailure = null;
        for (int attempt = 1; attempt <= POLICY_ATTEMPTS; attempt++) {
            try {
                return exchange("policy", () -> client.get().uri("/internal/v1/policy")
                        .header("X-Request-ID", requestId.get()).retrieve().body(PolicyDescriptor.class));
            } catch (RecommendationUnavailableException failure) {
                if (!failure.retryable()) {
                    // A rejected request and an answer outside the contract come back the same the
                    // second time. Only a service that did not answer is worth asking again.
                    throw failure;
                }
                log.warn("recommendation call failed operation=policy attempt={} of {}", attempt, POLICY_ATTEMPTS);
                lastFailure = failure;
            }
        }
        throw lastFailure;
    }

    private <T> T post(String operation, String path, Object request, Class<T> type) {
        return exchange(operation, () -> client.post().uri(path)
                .header("X-Request-ID", requestId.get())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(type));
    }

    /**
     * One exchange with apps/ai, and the only place its failures are sorted (#250).
     *
     * <p>Every method of this class calls through here, so the line between 503 and 500 cannot drift
     * between them: it drifted once already, when every method filed an unreadable body with the
     * outages and the edge answered a contract break with a retryable 503. A 4xx is this API's own
     * hydration bug, a failure to answer is {@link #unanswered}, and everything else - including an
     * empty body - is an answer outside the contract.
     */
    private <T> T exchange(String operation, Supplier<T> call) {
        T body;
        try {
            body = call.get();
        } catch (HttpClientErrorException exception) {
            throw rejected(operation, exception);
        } catch (RestClientException | HttpMessageConversionException exception) {
            if (unanswered(exception)) {
                throw new RecommendationUnavailableException("recommendation service unavailable", true, exception);
            }
            throw unreadable(operation, exception);
        }
        if (body == null) {
            throw unusable(operation + " response has no body");
        }
        return body;
    }

    /**
     * Whether apps/ai failed to ANSWER, as opposed to answering outside its contract. Only this is
     * retryable, and only this is what a caller may turn into 503 SOURCE_UNAVAILABLE.
     *
     * <p>Not answering is an I/O failure anywhere in the chain - refused, reset, timed out, or cut off
     * part way through the body - or a 5xx, which is apps/ai, or the load balancer in front of it during
     * a rollout, saying it cannot answer now.
     *
     * <p>The chain has to be walked. A body cut off part way arrives as {@code RestClientException},
     * then {@code HttpMessageNotReadableException}, then Jackson's {@code JacksonIOException}, then the
     * {@code IOException}: Jackson 3 wraps the I/O failure it meets while parsing, and Spring files it
     * with the parse errors. Sorting by the outer type would report an outage as a contract break.
     */
    private static boolean unanswered(Throwable failure) {
        if (failure instanceof HttpServerErrorException) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FeedRankResponse rankFeed(FeedRankRequest request) {
        FeedRankResponse response = post("feedRank", "/internal/v1/feed/rank", request, FeedRankResponse.class);
        verifyFeedOrder(request, response);
        return response;
    }

    /**
     * The order may only name posts this API hydrated, may name each of them once, and carries the
     * policy the run is fingerprinted with. A repeated id would render the same card twice and shift
     * every cursor after it. A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyFeedOrder(FeedRankRequest request, FeedRankResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("feed rank response carries no policy hash");
        }
        Set<UUID> requested = new HashSet<>();
        for (FeedCandidateIn candidate : request.candidates()) {
            requested.add(candidate.postId());
        }
        Set<UUID> seen = new HashSet<>();
        for (UUID postId : response.orderedPostIds()) {
            if (!requested.contains(postId)) {
                throw unusable("service returned a post id not in the request");
            }
            if (!seen.add(postId)) {
                throw unusable("a post is ordered once, never repeated");
            }
        }
    }

    @Override
    public ItemProposeResponse proposeItem(ItemProposeRequest request) {
        ItemProposeResponse response = post("itemPropose", "/internal/v1/items/propose", request, ItemProposeResponse.class);
        verifyResponse(request, response);
        return response;
    }

    @Override
    public SlotEvaluateResponse evaluateSlots(SlotEvaluateRequest request) {
        SlotEvaluateResponse response = post("slotEvaluate", "/internal/v1/slots/evaluate", request, SlotEvaluateResponse.class);
        verifySlots(request, response);
        return response;
    }

    @Override
    public RelatedRankResponse rankRelated(RelatedRankRequest request) {
        RelatedRankResponse response = post("relatedRank", "/internal/v1/related/rank", request, RelatedRankResponse.class);
        verifyRelated(request, response);
        return response;
    }

    @Override
    public ExplanationRenderResponse renderExplanation(ExplanationRenderRequest request) {
        ExplanationRenderResponse response = post("explanationRender", "/internal/v1/explanations/render", request, ExplanationRenderResponse.class);
        verifyExplanation(request, response);
        return response;
    }

    @Override
    public DraftComposeResponse composeDraft(DraftComposeRequest request) {
        DraftComposeResponse response = post("draftCompose", "/internal/v1/drafts/compose", request, DraftComposeResponse.class);
        verifyDraft(request, response);
        return response;
    }

    /**
     * A draft may only place a place this API put in the pool, once, on a trip date, within the per-day
     * cap, at positions 0..n-1 per date without a gap. It may not put a stop on a date the API sent as
     * verified CLOSED, and a stop says OPEN exactly when the API sent a verified OPEN window for that
     * place and date - the label repeats what catalog established and nothing else. The state has to
     * agree with the stops it summarises: EMPTY dressed as READY would render an empty itinerary as a
     * proposal. A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyDraft(DraftComposeRequest request, DraftComposeResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("draft compose response carries no policy hash");
        }
        if (!DraftComposeResponse.REASONS.containsAll(response.reasons())) {
            throw unusable("service returned a draft reason the contract does not declare");
        }
        Map<UUID, Map<LocalDate, OpeningWindowIn>> pool = new HashMap<>();
        for (DraftPlaceIn place : request.pool()) {
            pool.put(place.placeId(), place.openingHours());
        }
        Set<UUID> placed = new HashSet<>();
        Map<LocalDate, List<Integer>> positions = new HashMap<>();
        for (DraftStopOut stop : response.stops()) {
            Map<LocalDate, OpeningWindowIn> windows = pool.get(stop.placeId());
            if (windows == null) {
                throw unusable("service placed a place that was not in the pool");
            }
            if (!placed.add(stop.placeId())) {
                throw unusable("a place is placed once, never repeated");
            }
            if (stop.date().isBefore(request.tripStart()) || stop.date().isAfter(request.tripEnd())) {
                throw unusable("service placed a stop outside the trip range");
            }
            OpeningWindowIn window = windows.get(stop.date());
            OpeningWindowIn.OpeningState verified = window == null ? null : window.state();
            if (verified == OpeningWindowIn.OpeningState.CLOSED) {
                throw unusable("service placed a stop on a date verified as closed");
            }
            boolean verifiedOpen = verified == OpeningWindowIn.OpeningState.OPEN;
            if ((stop.hoursState() == DraftStopOut.HoursState.OPEN) != verifiedOpen) {
                throw unusable("a stop is OPEN exactly when its date carries a verified OPEN window");
            }
            positions.computeIfAbsent(stop.date(), date -> new ArrayList<>()).add(stop.position());
        }
        for (List<Integer> day : positions.values()) {
            if (day.size() > request.maxStopsPerDay()) {
                throw unusable("service placed more stops on one date than the cap allows");
            }
            List<Integer> sorted = day.stream().sorted().toList();
            for (int expected = 0; expected < sorted.size(); expected++) {
                if (sorted.get(expected) != expected) {
                    throw unusable("draft positions must run 0..n-1 per date without a gap");
                }
            }
        }
        boolean empty = response.stops().isEmpty();
        if (empty != (response.state() == DraftComposeResponse.State.EMPTY)) {
            throw unusable("a draft is EMPTY exactly when it places no stop");
        }
    }

    /**
     * An explanation is one line of text, within the length the FE renders, written by one of the two
     * published writers, and it still carries the attribution this API sent: a sentence that dropped
     * its source, grew a second line, came back empty or names an unknown writer is not displayable.
     * A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyExplanation(ExplanationRenderRequest request, ExplanationRenderResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("explanation response carries no policy hash");
        }
        if (!ExplanationRenderResponse.SOURCES.contains(response.source())) {
            throw unusable("service named a writer outside the two published values");
        }
        String summary = response.summary();
        if (summary.isBlank()) {
            throw unusable("an explanation is never an empty sentence");
        }
        if (summary.length() > ExplanationRenderResponse.MAX_SUMMARY_LENGTH) {
            throw unusable("service returned an explanation longer than the contract allows");
        }
        if (summary.indexOf('\n') >= 0) {
            throw unusable("an explanation is a single line of text");
        }
        if (!summary.contains(request.attribution())) {
            throw unusable("an explanation always carries the source attribution it was given");
        }
    }

    /**
     * A related place must be one of the targets this API hydrated, must not be the source, may appear
     * once, keeps the evidence it was ranked on, and names only channels the request carried. A
     * violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyRelated(RelatedRankRequest request, RelatedRankResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("related rank response carries no policy hash");
        }
        if (response.items().size() > MAX_RELATED_ITEMS) {
            throw unusable("service returned more related places than the policy allows");
        }
        Set<UUID> targets = new HashSet<>();
        Set<String> channels = new HashSet<>();
        for (RelationCandidateIn candidate : request.candidates()) {
            targets.add(candidate.targetPlaceId());
            channels.add(candidate.channel());
        }
        Set<UUID> seen = new HashSet<>();
        for (RelatedItemOut item : response.items()) {
            if (!targets.contains(item.placeId())) {
                throw unusable("service returned a related place that was not in the request");
            }
            if (item.placeId().equals(request.sourcePlaceId())) {
                throw unusable("a place is never related to itself");
            }
            if (!seen.add(item.placeId())) {
                throw unusable("related places must be merged, not repeated");
            }
            if (item.evidenceCount() < 1) {
                throw unusable("a ranked related place keeps at least one evidence row");
            }
            if (!channels.containsAll(item.channels())) {
                throw unusable("service returned a channel that was not in the request");
            }
        }
        verifyRelatedState(response);
    }

    /**
     * The state has to agree with the list it summarises. CHECKING and UNKNOWN describe the lookup, not
     * the places, so both may still carry what is already known.
     */
    private static void verifyRelatedState(RelatedRankResponse response) {
        RelationTier first = response.items().isEmpty() ? null : response.items().get(0).tier();
        switch (response.state()) {
            case EXACT -> {
                if (first != RelationTier.EXACT) {
                    throw unusable("EXACT needs an EXACT related place first");
                }
            }
            case SIMILAR -> {
                if (first != RelationTier.SIMILAR) {
                    throw unusable("SIMILAR needs a SIMILAR related place first");
                }
            }
            case NONE -> {
                if (first != null) {
                    throw unusable("NONE carries no related place");
                }
            }
            case CHECKING, UNKNOWN -> {
                // An unsettled lookup may still report the relations it already verified.
            }
        }
    }

    /**
     * A slot may only name a trip date, once, in order, and P0 never carries a suggested time. The state
     * has to agree with the slots it summarises, otherwise the candidate would render as bookable while
     * every date was refused. A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifySlots(SlotEvaluateRequest request, SlotEvaluateResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("slot evaluation response carries no policy hash");
        }
        if (response.slots().size() > MAX_SLOT_DATES) {
            throw unusable("service returned more slots than the policy allows");
        }
        LocalDate previous = null;
        boolean anyEligible = false;
        for (SlotOut slot : response.slots()) {
            if (slot.date().isBefore(request.tripStart()) || slot.date().isAfter(request.tripEnd())) {
                throw unusable("service returned a slot outside the trip range");
            }
            if (previous != null && !slot.date().isAfter(previous)) {
                throw unusable("slot dates must ascend without a repeat");
            }
            previous = slot.date();
            if (slot.suggestedTime() != null) {
                throw unusable("a P0 slot never carries a suggested time");
            }
            if (slot.eligible() == (slot.reasonCode() != null)) {
                throw unusable("a slot carries a reason code exactly when it is not eligible");
            }
            anyEligible = anyEligible || slot.eligible();
        }
        if (response.state() == SlotEvaluateResponse.State.EXACT && !anyEligible) {
            throw unusable("EXACT needs at least one eligible slot");
        }
        boolean settledWithoutSlot = response.state() == SlotEvaluateResponse.State.NONE
                || response.state() == SlotEvaluateResponse.State.UNKNOWN;
        if (settledWithoutSlot && anyEligible) {
            throw unusable("only EXACT or CHECKING may carry an eligible slot");
        }
    }

    /** One (date, startTime) slot; a DAY-resolution candidate keeps the start time the item already has. */
    private record Slot(LocalDate date, LocalTime startTime) {
    }

    private record SnapshotPair(UUID before, UUID after) {
    }

    /**
     * A proposal may only name a slot and a snapshot pair this service hydrated, and the ranks must be
     * 1..n without a gap. A violation is a contract break, not an outage: retrying cannot fix it.
     */
    private static void verifyResponse(ItemProposeRequest request, ItemProposeResponse response) {
        if (response.policyHash().isBlank()) {
            throw unusable("item proposal response carries no policy hash");
        }
        if (response.proposals().size() > MAX_PROPOSALS) {
            throw unusable("service returned more item proposals than the policy allows");
        }
        Set<Slot> slots = new HashSet<>();
        Set<SnapshotPair> pairs = new HashSet<>();
        for (TemporalCandidateIn candidate : request.candidates()) {
            slots.add(new Slot(candidate.date(), candidate.effectiveStartTime(request.target().startTime())));
            pairs.add(new SnapshotPair(candidate.beforeSnapshotId(), candidate.afterSnapshotId()));
        }
        int expectedRank = 1;
        for (ItemProposalOut proposal : response.proposals()) {
            if (proposal.rank() != expectedRank++) {
                throw unusable("item proposal ranks must run 1..n without a gap");
            }
            if (!slots.contains(new Slot(proposal.date(), proposal.startTime()))) {
                throw unusable("service proposed a slot that was not in the request");
            }
            if (!pairs.contains(new SnapshotPair(proposal.beforeSnapshotId(), proposal.afterSnapshotId()))) {
                throw unusable("service returned a snapshot pair that was not in the request");
            }
        }
    }

    /** The service answered, but the answer breaks the contract: never retry, never persist. */
    private static RecommendationUnavailableException unusable(String message) {
        log.error("recommendation response rejected — {}", message);
        return new RecommendationUnavailableException(message, false, null);
    }

    /**
     * apps/ai answered and the answer does not parse as the contract: a malformed body, a missing
     * field, a content type no converter reads. {@link #unusable} one layer earlier, and the same
     * verdict - the same request gets the same answer, so never retry.
     */
    private static RecommendationUnavailableException unreadable(String operation, RuntimeException exception) {
        // The type and not the message: a parse error quotes the body it could not read.
        log.error("recommendation response unreadable operation={} cause={} — retry will not help",
                operation, NestedExceptionUtils.getMostSpecificCause(exception).getClass().getName());
        return new RecommendationUnavailableException("recommendation response outside the contract", false,
                exception);
    }

    /** A 4xx means this service hydrated an invalid request: an alert, not a transient outage. */
    private RecommendationUnavailableException rejected(String operation, HttpClientErrorException exception) {
        log.error("recommendation request rejected operation={} status={} — hydration bug in the API, retry will not help",
                operation, exception.getStatusCode().value());
        return new RecommendationUnavailableException("recommendation request rejected", false, exception);
    }
}
