package io.nullnull.trip.application;

import io.nullnull.catalog.application.CatalogPlaceProjectionService;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripScheduleRules;
import io.nullnull.trip.domain.TripValidationException;
import io.nullnull.trip.domain.TripStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * createTrip, listTrips and getTrip.
 *
 * <p>Every read and write is keyed by the owner the session resolved to, never by an identifier the
 * caller sent (invariant 11). A trip that belongs to someone else is reported as absent rather than
 * forbidden, so a caller cannot probe which trip ids exist.
 */
@Service
public class TripService {

    /** Bumped when the persisted snapshot's shape changes, so a reader can tell which it has. */
    public static final String SNAPSHOT_SCHEMA_VERSION = "trip-aggregate-v1";
    private static final String CREATE_ROUTE = "POST /trips";
    private static final String DELETE_ROUTE = "DELETE /trips/{tripId}";
    private static final java.util.regex.Pattern IF_MATCH =
            java.util.regex.Pattern.compile("^\"[1-9][0-9]*\"$");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final TripStore trips;
    private final CatalogPlaceProjectionService places;
    private final IdempotencyGuard idempotency;
    private final TripCursorProperties cursors;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TripService(TripStore trips, CatalogPlaceProjectionService places,
            IdempotencyGuard idempotency, TripCursorProperties cursors,
            Clock clock, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.trips = trips;
        this.places = Objects.requireNonNull(places, "places");
        this.idempotency = idempotency;
        this.cursors = cursors;
        this.clock = clock;
        this.json = json;
    }

    /**
     * Creates the trip, guarded by Idempotency-Key. The guard replays the stored response for a
     * repeated key, so a double submit yields ONE trip - which is the whole point of the header on a
     * command that is not otherwise safe to repeat.
     *
     * <p>The fingerprint covers the command, so the same key with a different body is a reused key
     * (409) rather than a silent second answer to the first request.
     */
    public TripView create(OwnerContext context, String idempotencyKey, CreateTripCommand command) {
        if (!command.seedItems().isEmpty()) {
            // Before the write, not after. A trip created with seed items cannot be answered while
            // the catalog gate is closed - TripItem.place is required - and discovering that after
            // persisting would leave the trip created, the caller holding a 503, and the stored
            // idempotency response replaying that same 503 for as long as the key lives.
            places.requirePublicProjection();
        }
        String fingerprint = RequestFingerprint.of("createTrip", Map.of(), canonicalRequest(command))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), CREATE_ROUTE,
                idempotencyKey, fingerprint,
                () -> new CommandOutcome<>(201, persist(context.ownerId(), command)), value -> value);
        Projection projection = json.readValue(guarded.body(), Projection.class);
        Trip trip = rehydrate(context.ownerId(), projection);
        // The stored idempotency body carries the trip, not the day structure: a replay reads the
        // items back rather than keeping a second copy that could disagree with the table.
        return new TripView(trip, projection.candidateCount(), itemViews(context, trip.id()));
    }

    @Transactional(readOnly = true)
    public TripView get(OwnerContext context, UUID tripId) {
        Trip trip = trips.find(context.ownerId(), tripId).orElseThrow(TripService::notFound);
        return new TripView(trip, trips.candidateCounts(List.of(trip.id())).getOrDefault(trip.id(), 0),
                itemViews(context, trip.id()));
    }

    @Transactional(readOnly = true)
    public TripPageView list(OwnerContext context, String status, String cursor, Integer limit) {
        int size = pageSize(limit);
        String binding = cursors.ownerBinding(context.ownerId());
        long offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims = cursors.cursorCodec().decode(cursor, clock.instant(), binding,
                    TripCursorProperties.CONTEXT);
            offset = claims.nextOrdinal();
        }
        String normalizedStatus = status == null || status.isBlank() ? null : TripStatus.of(status).name();
        // One extra row: a page that is exactly full is otherwise indistinguishable from the last
        // page, and a cursor handed out for an empty next page is a wasted round trip.
        List<Trip> found = trips.page(context.ownerId(), normalizedStatus, offset, size + 1);
        boolean hasMore = found.size() > size;
        List<Trip> page = hasMore ? found.subList(0, size) : found;
        Map<UUID, Integer> counts = trips.candidateCounts(page.stream().map(Trip::id).toList());
        List<TripView> views = new ArrayList<>(page.size());
        for (Trip trip : page) {
            views.add(new TripView(trip, counts.getOrDefault(trip.id(), 0)));
        }
        String next = hasMore
                ? cursors.cursorCodec().encode(new CursorClaims(TripCursorProperties.CONTEXT,
                        offset + size, binding, TripCursorProperties.CONTEXT,
                        TripCursorProperties.SORT_VERSION,
                        clock.instant().plus(cursors.cursorTtl()), cursors.keyId()))
                : null;
        return new TripPageView(views, next, hasMore);
    }

    /**
     * The day structure a detail response renders, or nothing when the trip has no items.
     *
     * <p>The empty case returns before the gate is consulted, and that is the whole boundary: a trip
     * with no items needs no place from the catalog, so it can be answered honestly while the catalog
     * is unpublished. One item changes what the response has to contain, not how strict we feel -
     * {@code TripItem.place} is required, so a 503 there says "this answer cannot be built", which is
     * the same thing listFeed says for the same reason.
     *
     * <p>A missing summary for an item that exists is a loud failure rather than a hole in the array.
     * It is unreachable today - V010's trigger keeps a DEPRECATED place pointing at an ACTIVE
     * canonical one and the summary query resolves through that before filtering - and a response
     * that silently dropped the item would be a schedule the user cannot see, reported as success.
     */
    private List<TripItemView> itemViews(OwnerContext context, UUID tripId) {
        List<TripItem> items = trips.items(tripId);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, CatalogPlaceSummary> byId = new java.util.HashMap<>();
        for (CatalogPlaceSummary summary : places.embeddedSummaries(context,
                items.stream().map(TripItem::placeId).distinct().toList())) {
            byId.put(summary.id(), summary);
        }
        List<TripItemView> views = new ArrayList<>(items.size());
        for (TripItem item : items) {
            CatalogPlaceSummary place = byId.get(item.placeId());
            if (place == null) {
                throw new ApiException(ProblemCode.SOURCE_UNAVAILABLE,
                        "A scheduled place is not available.");
            }
            views.add(new TripItemView(item, place));
        }
        return List.copyOf(views);
    }

    private Projection persist(UUID ownerId, CreateTripCommand command) {
        Instant now = clock.instant();
        Trip trip = new Trip(UUID.randomUUID(), ownerId, command.title(), command.range(),
                command.planningLevel(), TripStatus.DRAFT, 1L, command.interests(), now, now, null);
        String snapshot = snapshot(trip, command.seedItems());
        trips.create(trip, command.seedItems(), SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot),
                snapshot);
        // A created trip holds no candidates: saving a candidate is a separate resource and is not
        // part of creation (invariant 1).
        return Projection.of(trip, 0);
    }

    /**
     * The canonical JSON recorded as revision 1. Field order is fixed and interests are sorted, so
     * the same trip always hashes the same way and the hash means "this content" rather than "this
     * serializer's field order today".
     */
    private String snapshot(Trip trip, List<TripItem> items) {
        return "{\"schemaVersion\":\"" + SNAPSHOT_SCHEMA_VERSION + "\""
                + ",\"tripId\":\"" + trip.id() + "\""
                + ",\"version\":" + trip.version()
                + ",\"title\":" + json.writeValueAsString(trip.title())
                + ",\"startDate\":\"" + trip.range().startDate() + "\""
                + ",\"endDate\":\"" + trip.range().endDate() + "\""
                + ",\"timezone\":" + json.writeValueAsString(trip.range().timezone().getId())
                + ",\"planningLevel\":\"" + trip.planningLevel() + "\""
                + ",\"status\":\"" + trip.status() + "\""
                + ",\"interests\":" + sortedInterests(trip.interests())
                + ",\"items\":" + canonicalItems(items)
                + ",\"candidates\":[]}";
    }

    private String sortedInterests(List<TripInterest> interests) {
        List<TripInterest> sorted = new ArrayList<>(interests);
        sorted.sort(Comparator.comparing(TripInterest::code));
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"code\":").append(json.writeValueAsString(sorted.get(index).code()))
                    .append(",\"weight\":").append(sorted.get(index).weight()).append('}');
        }
        return out.append(']').toString();
    }

    /**
     * What the idempotency guard hashes. The canonical snapshot shape is reused rather than the raw
     * body, so the same request with different whitespace or interest order is the same request -
     * and a genuinely different body is a reused key rather than a silent second answer.
     */
    private String canonicalRequest(CreateTripCommand command) {
        return "{\"title\":" + json.writeValueAsString(command.title())
                + ",\"startDate\":\"" + command.range().startDate() + "\""
                + ",\"endDate\":\"" + command.range().endDate() + "\""
                + ",\"timezone\":" + json.writeValueAsString(command.range().timezone().getId())
                + ",\"planningLevel\":\"" + command.planningLevel() + "\""
                + ",\"interests\":" + sortedInterests(command.interests()) + "}";
    }

    private Trip rehydrate(UUID ownerId, Projection projection) {
        return new Trip(projection.id(), ownerId, projection.title(),
                TripDateRange.of(LocalDate.parse(projection.startDate()),
                        LocalDate.parse(projection.endDate()), projection.timezone()),
                PlanningLevel.of(projection.planningLevel()), TripStatus.of(projection.status()),
                projection.version(),
                projection.interests().stream()
                        .map(entry -> new TripInterest(entry.code(), entry.weight())).toList(),
                projection.createdAt(), projection.updatedAt(), null);
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
        // Absent and not-yours are one answer on purpose: telling them apart would let a caller probe
        // which trip identifiers exist.
        return new ApiException(ProblemCode.NOT_FOUND, "The requested trip does not exist.");
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * updateTrip. Merge-patch: an absent field keeps its value, so only what the caller sent moves.
     *
     * <p>The version rises by EXACTLY ONE however many fields changed (invariant 6). A caller that
     * sent a patch equal to the current state still gets a new version, because the ETag it holds
     * was checked and a no-op that left the ETag alone would make "my If-Match succeeded" mean two
     * different things.
     */
    public TripView update(OwnerContext context, UUID tripId, String ifMatch, UpdateTripCommand patch) {
        long expected = parseIfMatch(ifMatch);
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId).orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            Trip updated = patch.applyTo(current, clock.instant());
            List<TripItem> items = trips.items(tripId);
            // A shrink is refused ENTIRELY while anything falls outside the new range. Nothing is
            // moved and no lock is released to make room (invariant 7): the user placed them.
            List<TripValidationException.FieldViolation> conflicts =
                    TripScheduleRules.shrinkConflicts(updated.range(), items);
            if (!conflicts.isEmpty()) {
                throw new TripValidationException(conflicts);
            }
            String snapshot = snapshot(updated, items);
            trips.updateMetadata(updated, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
            // Inside the transaction: if the catalog gate refuses, the mutation rolls back with it,
            // so the caller never holds a 503 for a change the database already accepted.
            return new TripView(updated,
                    trips.candidateCounts(List.of(tripId)).getOrDefault(tripId, 0),
                    itemViews(context, tripId));
        });
    }

    /**
     * replaceTripInterests. The whole set is replaced, and the version rises by exactly one.
     *
     * <p>Unlike saving a candidate, changing interests is a change to the trip itself: the contract
     * says so by requiring If-Match and returning the new ETag. It also makes any existing
     * optimization preview stale, which is why it cannot be a quiet side write.
     */
    public TripView replaceInterests(OwnerContext context, UUID tripId, String ifMatch,
            List<TripInterest> interests) {
        long expected = parseIfMatch(ifMatch);
        List<TripInterest> validated = TripInterest.validated(interests);
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId).orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            Instant now = clock.instant();
            Trip updated = new Trip(current.id(), current.ownerId(), current.title(), current.range(),
                    current.planningLevel(), current.status(), current.version() + 1, validated,
                    current.createdAt(), now, current.archivedAt());
            String snapshot = snapshot(updated, trips.items(tripId));
            trips.updateMetadata(updated, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
            // Inside the transaction: if the catalog gate refuses, the mutation rolls back with it,
            // so the caller never holds a 503 for a change the database already accepted.
            return new TripView(updated,
                    trips.candidateCounts(List.of(tripId)).getOrDefault(tripId, 0),
                    itemViews(context, tripId));
        });
    }

    /**
     * deleteTrip. Guarded by both If-Match and Idempotency-Key: the ETag says which trip state the
     * caller meant to delete, and the key makes a repeat safe once it is gone.
     */
    public void delete(OwnerContext context, UUID tripId, String ifMatch, String idempotencyKey) {
        long expected = parseIfMatch(ifMatch);
        String fingerprint = RequestFingerprint
                .of("deleteTrip", Map.of("tripId", tripId.toString()), Long.toString(expected))
                .sha256Hex();
        idempotency.execute(context.ownerId(), DELETE_ROUTE, idempotencyKey, fingerprint, () -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId).orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            // Items, constraints, interests and revisions follow through the foreign keys, and
            // owners.active_trip_id is cleared by its own ON DELETE SET NULL. One statement, one
            // transaction: a trip that half-disappeared would leave a pointer at nothing.
            trips.delete(context.ownerId(), tripId);
            return new CommandOutcome<>(204, "");
        }, value -> value);
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || !IF_MATCH.matcher(ifMatch).matches()) {
            // 400, not 412: a malformed header is a request the server cannot interpret, while a
            // well-formed one that lost the race is a conflict the caller can resolve by refetching.
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "If-Match must be the quoted trip version.");
        }
        return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
    }

    private static ApiException tripChanged(long currentVersion) {
        ApiException conflict = new ApiException(ProblemCode.TRIP_CHANGED,
                "The trip was modified elsewhere.");
        return conflict;
    }

    private String canonicalItems(List<TripItem> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        List<TripItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(TripItem::date).thenComparing(TripItem::position));
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < sorted.size(); index++) {
            TripItem item = sorted.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"id\":\"").append(item.id()).append('"')
                    .append(",\"placeId\":\"").append(item.placeId()).append('"')
                    .append(",\"date\":\"").append(item.date()).append('"')
                    .append(",\"position\":").append(item.position())
                    .append(",\"startTime\":").append(item.startTime() == null ? "null"
                            : '"' + item.startTime().toString() + '"')
                    .append(",\"constraints\":[");
            List<TripConstraint> constraints = new ArrayList<>(item.constraints());
            constraints.sort(Comparator.comparing(constraint -> constraint.type().name()));
            for (int at = 0; at < constraints.size(); at++) {
                if (at > 0) {
                    out.append(',');
                }
                out.append("{\"type\":\"").append(constraints.get(at).type()).append("\"}");
            }
            out.append("]}");
        }
        return out.append(']').toString();
    }

    /** What the idempotency guard stores for replay. Holds no owner identifier. */
    public record Projection(UUID id, String title, String startDate, String endDate, String timezone,
            String planningLevel, String status, long version, int candidateCount,
            List<InterestEntry> interests, Instant createdAt, Instant updatedAt) {

        public record InterestEntry(String code, int weight) { }

        static Projection of(Trip trip, int candidateCount) {
            Objects.requireNonNull(trip, "trip");
            return new Projection(trip.id(), trip.title(), trip.range().startDate().toString(),
                    trip.range().endDate().toString(), trip.range().timezone().getId(),
                    trip.planningLevel().name(), trip.status().name(), trip.version(), candidateCount,
                    trip.interests().stream().map(i -> new InterestEntry(i.code(), i.weight())).toList(),
                    trip.createdAt(), trip.updatedAt());
        }
    }
}
