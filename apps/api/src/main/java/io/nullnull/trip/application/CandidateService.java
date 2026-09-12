package io.nullnull.trip.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.domain.CandidateSourceType;
import io.nullnull.trip.domain.CandidateStatus;
import io.nullnull.trip.domain.TripCandidate;
import io.nullnull.trip.domain.TripCandidate.CandidateSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * listTripCandidates, addTripCandidate and removeTripCandidate.
 *
 * <p>Nothing here touches a trip row. Saving a candidate creates no TripItem and does not raise the
 * trip's schedule version (invariant 2) - a candidate is a place the traveller wants, not a place
 * on the plan, and the two are different resources (invariant 1).
 */
@Service
public class CandidateService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final String CONTEXT = "listTripCandidates";
    private static final String ADD_ROUTE = "POST /trips/{tripId}/candidates";

    private final CandidateStore candidates;
    private final TripCursorProperties cursors;
    private final IdempotencyGuard idempotency;
    private final Clock clock;
    private final ObjectMapper json;

    public CandidateService(CandidateStore candidates, TripCursorProperties cursors,
            IdempotencyGuard idempotency, Clock clock, ObjectMapper json) {
        this.candidates = candidates;
        this.cursors = cursors;
        this.idempotency = idempotency;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public CandidatePageView list(OwnerContext context, UUID tripId, String status, String cursor,
            Integer limit) {
        requireOwnedTrip(context, tripId);
        int size = pageSize(limit);
        String binding = cursors.ownerBinding(context.ownerId());
        long offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            offset = cursors.cursorCodec().decode(cursor, clock.instant(), binding, CONTEXT)
                    .nextOrdinal();
        }
        CandidateStatus filter = status == null || status.isBlank() ? null : CandidateStatus.of(status);
        List<TripCandidate> found = candidates.page(tripId, filter, offset, size + 1);
        boolean hasMore = found.size() > size;
        List<TripCandidate> page = hasMore ? found.subList(0, size) : found;
        String next = hasMore
                ? cursors.cursorCodec().encode(new CursorClaims(CONTEXT, offset + size, binding, CONTEXT,
                        TripCursorProperties.SORT_VERSION,
                        clock.instant().plus(cursors.cursorTtl()), cursors.keyId()))
                : null;
        return new CandidatePageView(page, next, hasMore);
    }

    /**
     * addTripCandidate. 201 the first time, 200 when the trip already holds a non-dismissed
     * candidate for that place - including one saved from a DIFFERENT post, which converges on the
     * same row rather than making a second (BA-034-T1).
     */
    public SaveResult add(OwnerContext context, UUID tripId, String idempotencyKey, UUID placeId,
            CandidateSourceType sourceType, UUID postId, String note) {
        requireOwnedTrip(context, tripId);
        if (placeId == null) {
            throw new io.nullnull.trip.domain.TripValidationException("placeId", "NotNull",
                    "placeId is required");
        }
        CandidateSource source = new CandidateSource(sourceType, postId, clock.instant());
        String fingerprint = RequestFingerprint.of("addTripCandidate",
                        Map.of("tripId", tripId.toString()),
                        placeId + "|" + sourceType + "|" + postId + "|" + (note == null ? "" : note))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), ADD_ROUTE,
                idempotencyKey, fingerprint, () -> {
                    CandidateStore.Saved saved = candidates.saveActive(tripId, placeId, note, source,
                            clock.instant());
                    return new CommandOutcome<>(saved.duplicate() ? 200 : 201,
                            Projection.of(saved.candidate(), saved.duplicate()));
                }, value -> value);
        Projection projection = json.readValue(guarded.body(), Projection.class);
        // The stored row is re-read rather than rebuilt from the projection: a replay must describe
        // the candidate as it is now, and its sources may have grown since.
        TripCandidate current = candidates.find(context.ownerId(), tripId, projection.id())
                .orElseThrow(CandidateService::notFound);
        return new SaveResult(current, projection.duplicate());
    }

    /**
     * removeTripCandidate: ACTIVE becomes DISMISSED. A SCHEDULED candidate is refused.
     *
     * <p>It is on the itinerary, and taking it off there is a schedule change that must move the
     * trip's version with it. Dismissing it here would remove the place from the plan while the
     * trip's version said nothing had changed, and every open tab would keep a stale ETag that
     * still validated.
     */
    @Transactional
    public void dismiss(OwnerContext context, UUID tripId, UUID candidateId) {
        requireOwnedTrip(context, tripId);
        TripCandidate candidate = candidates.find(context.ownerId(), tripId, candidateId)
                .orElseThrow(CandidateService::notFound);
        if (candidate.status() == CandidateStatus.DISMISSED) {
            // Already dismissed is success: the contract's 204 is "dismissed", and reporting a
            // conflict would make a double tap look like a failure.
            return;
        }
        if (!candidate.isDismissable()) {
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "A scheduled candidate is removed through its trip item, not dismissed.");
        }
        if (!candidates.dismiss(candidateId, clock.instant())) {
            // It stopped being ACTIVE between the read and the write - it was scheduled.
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "A scheduled candidate is removed through its trip item, not dismissed.");
        }
    }

    /** Non-dismissed candidate status per place, for callers that show it beside a place. */
    /** Whether this owner holds the trip, for callers that gate a read on it themselves. */
    @Transactional(readOnly = true)
    public boolean ownsTrip(UUID ownerId, UUID tripId) {
        return tripId != null && candidates.ownsTrip(ownerId, tripId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, CandidateStatus> statesByPlace(UUID tripId, List<UUID> placeIds) {
        return candidates.statesByPlace(tripId, placeIds);
    }

    @Transactional(readOnly = true)
    public int count(UUID tripId) {
        return candidates.count(tripId);
    }

    private void requireOwnedTrip(OwnerContext context, UUID tripId) {
        if (!candidates.ownsTrip(context.ownerId(), tripId)) {
            // Absent, not forbidden: distinguishing them would let a caller probe which trips exist.
            throw notFound();
        }
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested trip candidate does not exist.");
    }

    public record CandidatePageView(List<TripCandidate> items, String nextCursor, boolean hasMore) {
        public CandidatePageView {
            items = List.copyOf(items);
            if (!hasMore && nextCursor != null) {
                throw new IllegalArgumentException("a last page must not carry a next cursor");
            }
        }
    }

    public record SaveResult(TripCandidate candidate, boolean duplicate) { }

    /** What the idempotency guard stores. Only the identity and the outcome; the row is re-read. */
    public record Projection(UUID id, boolean duplicate) {
        static Projection of(TripCandidate candidate, boolean duplicate) {
            return new Projection(candidate.id(), duplicate);
        }
    }
}
