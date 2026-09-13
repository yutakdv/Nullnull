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
import io.nullnull.trip.domain.CandidateSourceType;
import io.nullnull.trip.domain.ConstraintSource;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockChecks;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripCandidate;
import io.nullnull.trip.domain.TripCandidate.CandidateSource;
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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
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
    private static final String ADD_ITEM_ROUTE = "POST /trips/{tripId}/items";
    private static final String REORDER_ROUTE = "POST /trips/{tripId}/items/reorder";
    private static final String REPLACE_ROUTE = "POST /trips/{tripId}/items/{itemId}/replace";
    private static final java.util.regex.Pattern IF_MATCH =
            java.util.regex.Pattern.compile("^\"[1-9][0-9]*\"$");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final TripStore trips;
    private final CandidateStore candidates;
    private final CatalogPlaceProjectionService places;
    private final IdempotencyGuard idempotency;
    private final TripCursorProperties cursors;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public TripService(TripStore trips, CandidateStore candidates,
            CatalogPlaceProjectionService places,
            IdempotencyGuard idempotency, TripCursorProperties cursors,
            Clock clock, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.trips = trips;
        this.candidates = Objects.requireNonNull(candidates, "candidates");
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
     * <p>The fingerprint covers the command INCLUDING its seeded items, so the same key with a
     * different body is a reused key (409) rather than a silent second answer to the first request.
     * seedItems was missing from it, which made exactly that silent second answer reachable: the same
     * key with a different set of seeded places replayed the first trip and dropped the second set
     * without saying so.
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

    /**
     * The create path for a caller that is already inside its own command's idempotency guard.
     *
     * <p>{@link #create} is the whole command: it takes the Idempotency-Key, opens the guard, and the
     * guard opens the transaction. A caller whose command is <em>not</em> createTrip - confirming an
     * import draft, which has to mark the draft and create the trip as one act - cannot reach that
     * method, because doing so would open a second guard under a second key inside the first one's
     * transaction, and the inner guard's retry would be gone (it detects the active transaction and
     * drops to a single attempt) without anything saying so.
     *
     * <p>So this is the persist half on its own, and {@code Propagation.MANDATORY} is what keeps it
     * that way: called outside a transaction it throws rather than quietly creating a trip with no
     * guard around it. The name says the same thing to a reader; the annotation says it to the
     * runtime, which is the half that still holds when somebody adds a caller in a hurry.
     *
     * <p>The gate is checked here for the same reason {@link #create} checks it: a trip with seeded
     * items cannot be answered while the catalog is closed, and finding that out after the write
     * would leave the trip created and the caller holding a 503.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TripView createWithinCallersGuard(OwnerContext context, CreateTripCommand command) {
        if (!command.seedItems().isEmpty()) {
            places.requirePublicProjection();
        }
        Projection projection = persist(context.ownerId(), command);
        Trip trip = rehydrate(context.ownerId(), projection);
        return new TripView(trip, projection.candidateCount(), itemViews(context, trip.id()));
    }

    @Transactional(readOnly = true)
    public TripView get(OwnerContext context, UUID tripId) {
        Trip trip = trips.find(context.ownerId(), tripId).orElseThrow(TripService::notFound);
        return new TripView(trip, trips.candidateCounts(List.of(trip.id())).getOrDefault(trip.id(), 0),
                itemViews(context, trip.id()));
    }

    /**
     * The trip row itself, owner-checked, with no place hydration.
     *
     * <p>{@link #get} is the detail projection: it reads every item and asks the catalog for each
     * place, which a caller that only needs the trip's identity and version pays for and - because
     * that hydration is behind the publication gate - can be refused by. The optimization preflight is
     * such a caller, so it gets the row.
     */
    @Transactional(readOnly = true)
    public Optional<Trip> findForOwner(OwnerContext context, UUID tripId) {
        return findForOwner(context.ownerId(), tripId);
    }

    /** The same read for a caller that holds an owner id rather than a request's context. */
    @Transactional(readOnly = true)
    public Optional<Trip> findForOwner(UUID ownerId, UUID tripId) {
        return trips.find(ownerId, tripId);
    }

    /**
     * A trip's items with their locks, for a caller that has already established the owner.
     *
     * <p>There is no owner check here, and that is why the parameter list makes it impossible to
     * call without having read the trip first: the only callers pass an id they just received from
     * {@link #findForOwner} or {@link #get}. It returns the trip's own rows, never another module's
     * projection, so it cannot leak a place a closed catalog would have withheld.
     */
    @Transactional(readOnly = true)
    public List<TripItem> itemsOf(UUID tripId) {
        return trips.items(tripId);
    }

    /**
     * The current version of a trip, for background work deciding whether its frozen input still
     * holds.
     *
     * <p>It takes the owner rather than trusting the caller to have checked one, even though the
     * caller is a worker rather than a request: the worker holds a run row that recorded the owner at
     * preflight, so it has the id, and a method that did not ask for it would be the one place in
     * this service where a trip can be read without naming whose it is. Empty means the trip is gone,
     * which is the answer the worker needs, not an error.
     */
    @Transactional(readOnly = true)
    public java.util.OptionalLong versionFor(UUID ownerId, UUID tripId) {
        return findForOwner(ownerId, tripId)
                .map(trip -> java.util.OptionalLong.of(trip.version()))
                .orElseGet(java.util.OptionalLong::empty);
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
                + ",\"interests\":" + sortedInterests(command.interests())
                + ",\"seedItems\":" + canonicalSeedItems(command.seedItems()) + "}";
    }

    /**
     * The seeded items as the REQUEST expressed them, for the fingerprint only.
     *
     * <p>Separate from {@link #canonicalItems}, which serialises the stored aggregate and therefore
     * carries each item's id. Ids are minted per request, so hashing them would make two byte-identical
     * submissions look like two different commands and turn every retry into a reused key - the exact
     * opposite of what Idempotency-Key is for. What is hashed here is what the client actually sent.
     *
     * <p>Sorted by date and position because those are explicit in the request: the same seed set in a
     * different array order is the same trip, and treating it as a different one would reject a retry
     * whose client rebuilt the array.
     */
    private String canonicalSeedItems(List<TripItem> items) {
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
            List<TripConstraint> constraints = new ArrayList<>(item.constraints());
            constraints.sort(Comparator.comparing(constraint -> constraint.type().name()));
            out.append("{\"placeId\":\"").append(item.placeId()).append('"')
                    .append(",\"date\":\"").append(item.date()).append('"')
                    .append(",\"position\":").append(item.position())
                    .append(",\"startTime\":").append(item.startTime() == null ? "null"
                            : '"' + item.startTime().toString() + '"')
                    .append(",\"constraints\":[");
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
     * addTripItem. If-Match says which trip state the caller meant to change; Idempotency-Key makes
     * a repeat safe, because adding an item twice would be two items rather than one.
     *
     * <p>The candidate transition happens INSIDE the guarded command, which is the only placement
     * that works. The guard replays a stored response without invoking the command again, so a
     * transition performed by the caller around {@code execute} would run on every retry - the
     * candidate would be scheduled again, against the item the FIRST attempt created.
     *
     * <p>What the guard stores is {@link ItemProjection}: the id of the item that was added, and
     * nothing else. It does not store the response, which carries every day of the trip with a
     * PlaceSummary per item. Measured on a full 100-item trip whose places hold only a short name,
     * that response is about 41,000 bytes against the record column's 65,536 - so it would fit there,
     * and fit is exactly the problem: the address, thumbnail, attribution and licence a real catalog
     * place publishes are all absent from that measurement. {@code requireStorable} runs AFTER the
     * command, so the first trip whose places carry their real content would answer 500 to a request
     * that had already succeeded, and keep answering 500 on every retry of that key.
     */
    public TripMutationView addItem(OwnerContext context, UUID tripId, String ifMatch,
            String idempotencyKey, AddTripItemCommand command) {
        long expected = parseIfMatch(ifMatch);
        // Before the guard reserves a slot, not after the command has run: this response must carry
        // the item's place, so a closed catalog cannot answer it at all. Finding that out later
        // would leave the item written, the caller holding a 503, and the stored response replaying
        // that 503 for the life of the key - the same trap createTrip's seedItems check avoids.
        places.requirePublicProjection();
        // The expected version travels as the precondition, not folded into the body: it is what
        // docs/api/README.md section 5 calls the command precondition, and a caller that retries the
        // same key against a DIFFERENT version is reusing the key rather than retrying.
        String fingerprint = RequestFingerprint.of("addTripItem",
                        Map.of("tripId", tripId.toString()), canonicalAddition(command),
                        Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(),
                ADD_ITEM_ROUTE, idempotencyKey, fingerprint,
                () -> new CommandOutcome<>(201, insertItem(context.ownerId(), tripId, expected, command)),
                value -> value);
        ItemProjection projection = json.readValue(guarded.body(), ItemProjection.class);
        // Re-read rather than rebuilt from the projection: a replay has to describe the trip as it
        // is now, and the day structure is the table's to answer, not a second copy's.
        return mutationView(context, tripId, List.of(projection.itemId()));
    }

    private ItemProjection insertItem(UUID ownerId, UUID tripId, long expected,
            AddTripItemCommand command) {
        Trip current = trips.findForUpdate(ownerId, tripId).orElseThrow(TripService::notFound);
        if (current.version() != expected) {
            throw tripChanged(current.version());
        }
        Instant now = clock.instant();
        TripItem item = command.toItem(UUID.randomUUID());
        List<TripItem> after = new ArrayList<>(trips.items(tripId));
        after.add(item);
        // Checked against what the trip holds NOW, read under the lock this transaction took. The
        // caps and the position rule are facts about the whole day, so the new item alone cannot
        // answer them.
        TripScheduleRules.requireInsideRange(current.range(), List.of(item));
        TripScheduleRules.requireWithinCaps(after);
        TripScheduleRules.requireDistinctPositions(after);
        trips.insertItem(tripId, item, now);
        scheduleCandidate(ownerId, tripId, command, item, now);
        Trip bumped = raiseVersion(current, now);
        String snapshot = snapshot(bumped, after);
        trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
        return new ItemProjection(item.id());
    }

    /**
     * Marks the named candidate SCHEDULED against the new item, when the caller named one.
     *
     * <p>The candidate has to be for the same place. Scheduling a candidate against an item for a
     * DIFFERENT place would leave the plan saying the traveller's saved place is on the schedule
     * when some other place is, and nothing downstream could tell the two apart afterwards.
     */
    private void scheduleCandidate(UUID ownerId, UUID tripId, AddTripItemCommand command,
            TripItem item, Instant now) {
        if (command.candidateId() == null) {
            return;
        }
        TripCandidate candidate = candidates.find(ownerId, tripId, command.candidateId())
                .orElseThrow(() -> new TripValidationException("candidateId", "NotFound",
                        "this trip has no such candidate"));
        if (!candidate.placeId().equals(command.placeId())) {
            throw new TripValidationException("candidateId", "PlaceMismatch",
                    "the candidate is for a different place than the item");
        }
        if (candidate.mustVisit()) {
            // Where the intention becomes a lock. The traveller said this place has to be in the
            // trip on the screen where they saved it, and until now there was nothing on the item to
            // carry that - the answer was collected and dropped (#185). Same transaction as the
            // insert and the transition (invariant 5), so there is no moment where the item exists
            // without the lock the candidate asked for.
            //
            // MUST_VISIT is the only lock a candidate can become: V014's typed check requires DATE,
            // TIME and RESERVATION to carry a date or a clock time, and a candidate has neither.
            trips.putConstraint(tripId, item.id(),
                    new TripConstraint(new ItemLock.MustVisit(), ConstraintSource.USER), now);
        }
        if (!candidates.schedule(candidate.id(), item.id(), now)) {
            // It stopped being ACTIVE between the read and the write: dismissed, or already put on
            // the schedule by the other tab that is racing this one.
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "The candidate is no longer available to schedule.");
        }
    }

    /**
     * reorderTripItems. Every named item moves, or none does.
     *
     * <p>Atomic in the database's sense, not only the service's: the moves run in one transaction with
     * {@code trip_items_slot_unique} deferred, so a swap - which necessarily passes through two items
     * on one slot - is judged on the result rather than on each row as it goes by. V020 made the
     * constraint deferrable for this; every other writer still fails on its own statement.
     *
     * <p>Locks are consulted here, and this is the first trip mutation that consults them - until now
     * only the optimizer's re-validation did, through the same {@link LockChecks}. Leaving them out
     * would make a reorder move a DATE-locked item without a word, which is what invariant 7 forbids.
     * A lock the request does not name refuses the move; a lock it names is released, because that is
     * the user's answer to the question the screen already asked.
     */
    public TripMutationView reorder(OwnerContext context, UUID tripId, String ifMatch,
            String idempotencyKey, ReorderTripItemsCommand command) {
        long expected = parseIfMatch(ifMatch);
        // Before the guard reserves a slot: the response carries every item's place, so a closed
        // catalog cannot answer this - the same order addTripItem uses, for the same reason.
        places.requirePublicProjection();
        String fingerprint = RequestFingerprint.of("reorderTripItems",
                        Map.of("tripId", tripId.toString()), canonicalReorder(command),
                        Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), REORDER_ROUTE,
                idempotencyKey, fingerprint,
                () -> new CommandOutcome<>(200, applyReorder(context.ownerId(), tripId, expected, command)),
                value -> value);
        MovedProjection projection = json.readValue(guarded.body(), MovedProjection.class);
        return mutationView(context, tripId, projection.itemIds());
    }

    private MovedProjection applyReorder(UUID ownerId, UUID tripId, long expected,
            ReorderTripItemsCommand command) {
        Trip current = trips.findForUpdate(ownerId, tripId).orElseThrow(TripService::notFound);
        if (current.version() != expected) {
            throw tripChanged(current.version());
        }
        Instant now = clock.instant();
        Map<UUID, TripItem> byId = new java.util.LinkedHashMap<>();
        for (TripItem item : trips.items(tripId)) {
            byId.put(item.id(), item);
        }
        List<TripItem> after = new ArrayList<>();
        List<UUID> moved = new ArrayList<>(command.entries().size());
        for (ReorderTripItemsCommand.Entry entry : command.entries()) {
            TripItem item = byId.get(entry.itemId());
            if (item == null) {
                // Not 404: the trip exists and the caller may read it, so this is a body that names
                // something the trip does not hold - the same reading addTripItem gives a candidateId
                // from another trip.
                throw new TripValidationException("items[].itemId", "NotFound",
                        "this trip has no such item");
            }
            requireLocksAllow(item, entry, now);
            moved.add(item.id());
            after.add(new TripItem(item.id(), item.placeId(), entry.date(), entry.position(),
                    item.startTime(), item.durationMinutes(), item.note(), item.constraints()));
        }
        for (TripItem item : byId.values()) {
            if (!moved.contains(item.id())) {
                // Items the request did not name are still part of the day the rules judge.
                after.add(item);
            }
        }
        TripScheduleRules.requireInsideRange(current.range(), after);
        TripScheduleRules.requireWithinCaps(after);
        TripScheduleRules.requireDistinctPositions(after);
        // After the rules, before the writes: deferring earlier would only widen the window in which
        // a half-applied order is visible to this transaction's own later statements.
        trips.deferSlotUniqueness();
        for (ReorderTripItemsCommand.Entry entry : command.entries()) {
            trips.moveItem(tripId, entry.itemId(), entry.date(), entry.position(), now);
        }
        Trip bumped = raiseVersion(current, now);
        String snapshot = snapshot(bumped, after);
        trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
        return new MovedProjection(List.copyOf(moved));
    }

    /**
     * Refuses the move when a lock the request did not name would be broken by it, and releases the
     * locks it did name.
     *
     * <p>A named lock is deleted whether or not it was the one in the way. The field says the user
     * chose to release it, and a server that released only the blocking ones would be deciding which
     * part of an explicit instruction to honour.
     */
    private void requireLocksAllow(TripItem item, ReorderTripItemsCommand.Entry entry, Instant now) {
        applyTemporalLockRules(item, entry.date(), item.startTime(), item.durationMinutes(),
                entry.releaseConstraints(), "The move is refused by locks this request did not release: ");
    }

    /** What reorderTripItems hashes: the moves it asked for, in a fixed order. */
    private String canonicalReorder(ReorderTripItemsCommand command) {
        List<ReorderTripItemsCommand.Entry> sorted = new ArrayList<>(command.entries());
        sorted.sort(Comparator.comparing(entry -> entry.itemId().toString()));
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < sorted.size(); index++) {
            ReorderTripItemsCommand.Entry entry = sorted.get(index);
            if (index > 0) {
                out.append(',');
            }
            List<String> released = entry.releaseConstraints().stream().map(Enum::name).sorted().toList();
            out.append("{\"itemId\":\"").append(entry.itemId()).append('"')
                    .append(",\"date\":\"").append(entry.date()).append('"')
                    .append(",\"position\":").append(entry.position())
                    .append(",\"releaseConstraints\":[");
            for (int at = 0; at < released.size(); at++) {
                if (at > 0) {
                    out.append(',');
                }
                out.append('"').append(released.get(at)).append('"');
            }
            out.append("]}");
        }
        return out.append(']').toString();
    }

    /**
     * replaceTripItem. The schedule stays, the place changes, and the outgoing place becomes a
     * candidate again.
     *
     * <p>Locks are why this is not simply a place swap. MUST_VISIT pins the place and RESERVATION
     * pins a booking made for it, so a replacement breaks both; either refuses the request unless
     * {@code releaseConstraints} names it. DATE and TIME pin the schedule, which is kept, so they
     * travel across to nothing - the item keeps its own rows and they are never consulted.
     *
     * <p>The outgoing place returns as an ACTIVE candidate with no parameter to choose otherwise
     * (contract): replacing a place is not saying to forget it. A candidate carries no date, so
     * restoring one changes no schedule, which is why this stays inside invariant 2.
     */
    public TripMutationView replaceItem(OwnerContext context, UUID tripId, UUID itemId, String ifMatch,
            String idempotencyKey, ReplaceTripItemCommand command) {
        long expected = parseIfMatch(ifMatch);
        places.requirePublicProjection();
        String fingerprint = RequestFingerprint.of("replaceTripItem",
                        Map.of("tripId", tripId.toString(), "itemId", itemId.toString()),
                        canonicalReplacement(command), Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(),
                REPLACE_ROUTE, idempotencyKey, fingerprint,
                () -> new CommandOutcome<>(200,
                        applyReplacement(context.ownerId(), tripId, itemId, expected, command)),
                value -> value);
        ItemProjection projection = json.readValue(guarded.body(), ItemProjection.class);
        return mutationView(context, tripId, List.of(projection.itemId()));
    }

    private ItemProjection applyReplacement(UUID ownerId, UUID tripId, UUID itemId, long expected,
            ReplaceTripItemCommand command) {
        Trip current = trips.findForUpdate(ownerId, tripId).orElseThrow(TripService::notFound);
        if (current.version() != expected) {
            throw tripChanged(current.version());
        }
        List<TripItem> existing = trips.items(tripId);
        TripItem item = existing.stream().filter(each -> each.id().equals(itemId)).findFirst()
                .orElseThrow(TripService::itemNotFound);
        if (item.placeId().equals(command.replacementPlaceId())) {
            throw new TripValidationException("replacementPlaceId", "NoChange",
                    "the item already holds this place");
        }
        Instant now = clock.instant();
        requirePlaceLocksReleased(item, command);
        // The outgoing place first, while the item still names it: after the update this row would
        // say nothing about where the candidate came from.
        UUID outgoing = item.placeId();
        // Whatever candidate was scheduled onto this item goes back to ACTIVE; an item that never
        // came from one still leaves a candidate behind, because the contract promises the outgoing
        // place comes back either way, and TRIP_SEED is the only true thing to say about its origin.
        // false whichever branch runs, and not by omission. A replacement cannot proceed while a
        // MUST_VISIT lock is unreleased (#199), so by here the traveller has named it and chosen to
        // let the place go; carrying the intention back would restore what they just released. This
        // was written on the fallback branch alone at first, which is the branch that almost never
        // runs - a candidate-backed item always takes the restore above, and that one was returning
        // the flag the candidate was saved with.
        if (candidates.restoreScheduledFor(itemId, false, now).isEmpty()) {
            candidates.saveActive(tripId, outgoing, item.note(), false,
                    new CandidateSource(CandidateSourceType.TRIP_SEED, null, now), now);
        }
        trips.replaceItemPlace(tripId, itemId, command.replacementPlaceId(), now);
        List<TripItem> after = existing.stream()
                .map(each -> each.id().equals(itemId)
                        ? new TripItem(each.id(), command.replacementPlaceId(), each.date(),
                                each.position(), each.startTime(), each.durationMinutes(), each.note(),
                                each.constraints().stream()
                                        .filter(constraint -> !command.releaseConstraints()
                                                .contains(constraint.type()))
                                        .toList())
                        : each)
                .toList();
        Trip bumped = raiseVersion(current, now);
        String snapshot = snapshot(bumped, after);
        trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
        return new ItemProjection(itemId);
    }

    /**
     * Refuses the replacement when a lock it breaks is not named, and deletes the ones that are.
     *
     * <p>Only MUST_VISIT and RESERVATION can be broken by it, so only those are looked at. The list
     * is not {@code LockChecks}' because that evaluates a TEMPORAL move: it passes MUST_VISIT by
     * definition ("a temporal move keeps the place") and judges RESERVATION against a proposed date
     * and time, neither of which changes here.
     */
    private void requirePlaceLocksReleased(TripItem item, ReplaceTripItemCommand command) {
        List<String> unreleased = new ArrayList<>();
        for (TripConstraint constraint : item.constraints()) {
            LockType type = constraint.type();
            if (type != LockType.MUST_VISIT && type != LockType.RESERVATION) {
                continue;
            }
            if (command.releaseConstraints().contains(type)) {
                trips.deleteConstraint(item.id(), type);
            } else {
                unreleased.add(type.name());
            }
        }
        if (!unreleased.isEmpty()) {
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "The replacement is refused by locks this request did not release: "
                            + String.join(", ", unreleased) + ".");
        }
    }

    /** What replaceTripItem hashes. The refused fields are absent because they cannot be sent. */
    private String canonicalReplacement(ReplaceTripItemCommand command) {
        List<String> released = command.releaseConstraints().stream().map(Enum::name).sorted().toList();
        return "{\"replacementPlaceId\":\"" + command.replacementPlaceId() + "\""
                + ",\"releaseConstraints\":[" + String.join(",",
                        released.stream().map(name -> '"' + name + '"').toList()) + "]}";
    }

    /**
     * updateTripItem. If-Match only, like removeTripItem and for the same reason: the contract gives
     * it no Idempotency-Key, and a repeat of the same patch against the same version is refused by
     * the ETag rather than needing a key to absorb it.
     *
     * <p>Locks are evaluated against the item the patch WOULD produce, not against the fields it
     * happens to mention. Changing only the start time can break a TIME lock, and changing only the
     * duration can push a stay past the end of a RESERVATION window - neither touches the date, so a
     * check that looked at what the caller sent would miss both.
     */
    public TripMutationView updateItem(OwnerContext context, UUID tripId, UUID itemId, String ifMatch,
            UpdateTripItemCommand patch) {
        long expected = parseIfMatch(ifMatch);
        if (patch.touchesNothing()) {
            // minProperties: 1 in the contract. An empty patch that still raised the version would
            // make "my If-Match succeeded" mean two different things.
            throw new TripValidationException("body", "Size", "the patch changes nothing");
        }
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId)
                    .orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            List<TripItem> existing = trips.items(tripId);
            TripItem stored = existing.stream().filter(each -> each.id().equals(itemId)).findFirst()
                    .orElseThrow(TripService::itemNotFound);
            TripItem patched = patch.applyTo(stored);
            requireLocksAllowUpdate(stored, patched, patch);
            List<TripItem> after = existing.stream()
                    .map(each -> each.id().equals(itemId) ? patched : each).toList();
            TripScheduleRules.requireInsideRange(current.range(), List.of(patched));
            TripScheduleRules.requireWithinCaps(after);
            TripScheduleRules.requireDistinctPositions(after);
            Instant now = clock.instant();
            trips.updateItem(tripId, patched, now);
            Trip bumped = raiseVersion(current, now);
            String snapshot = snapshot(bumped, after);
            trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
            return mutationView(context, tripId, List.of(itemId));
        });
    }

    /**
     * The same rule reorder follows, judged on the patched item: a lock the request does not name
     * refuses the edit, and a lock it names is released.
     *
     * <p>The locks read are the STORED ones. Reading the patched item's would ask whether the edit
     * is allowed by the locks it already dropped, which is every edit.
     */
    private void requireLocksAllowUpdate(TripItem stored, TripItem patched,
            UpdateTripItemCommand patch) {
        applyTemporalLockRules(stored, patched.date(), patched.startTime(), patched.durationMinutes(),
                patch.releaseConstraints(), "The edit is refused by locks this request did not release: ");
    }

    /**
     * The one place a temporal edit decides what its locks permit, for every command that makes one.
     *
     * <p>{@code LockChecks} was already shared - reorder, updateTripItem, setTripItemConstraint and
     * the optimizer's re-validation all call the same evaluator. What was not shared is this: read
     * the verdict, refuse the types the request did not name, delete the ones it did. Two copies of
     * that were a rule with two spellings, and #199 is only true where both agree.
     *
     * <p>The locks judged are the item's STORED ones, and the proposal is passed separately. Judging
     * the patched item's locks would ask whether an edit is allowed by the locks it has already
     * dropped, which is every edit.
     *
     * <p>replaceTripItem does NOT come through here, and that is not an omission. This evaluator
     * answers a question about a temporal move: it passes MUST_VISIT by definition, because such a
     * move keeps the place, and judges RESERVATION against a proposed date and time that a
     * replacement does not change. Routing replace through it would pass the exact lock #199 requires
     * it to refuse.
     */
    private void applyTemporalLockRules(TripItem stored, LocalDate date,
            LocalTime startTime, Integer durationMinutes, Set<LockType> released,
            String refusal) {
        if (stored.constraints().isEmpty() && released.isEmpty()) {
            return;
        }
        LockChecks.Result verdict = LockChecks.evaluate(
                stored.constraints().stream().map(TripConstraint::lock).toList(),
                date, startTime, durationMinutes);
        List<String> unreleased = new ArrayList<>();
        verdict.passed().forEach((type, satisfied) -> {
            if (!satisfied && !released.contains(type)) {
                unreleased.add(LockChecks.reasonCodeOf(type));
            }
        });
        if (!unreleased.isEmpty()) {
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    refusal + String.join(", ", unreleased) + ".");
        }
        for (LockType type : released) {
            trips.deleteConstraint(stored.id(), type);
        }
    }

    /**
     * setTripItemConstraint. One lock, written without touching the other three.
     *
     * <p>Independence is invariant 7 and it is storage's, not this method's care: the locks are
     * separate rows under a unique {@code (trip_item_id, type)} index, so writing DATE cannot
     * disturb TIME. What this adds is the other half - the lock must be TRUE of the item as it
     * stands. Setting a DATE lock for a day the item is not on would store a lock that is already
     * broken, and every later edit would be refused by a constraint the user could not have met.
     *
     * <p>The source is always USER (contract). An IMPORT-sourced lock arrives with an import, not
     * through an endpoint a person calls.
     */
    public TripMutationView setConstraint(OwnerContext context, UUID tripId, UUID itemId,
            String constraintType, String ifMatch, TripConstraint constraint) {
        long expected = parseIfMatch(ifMatch);
        LockType path = lockTypeOf(constraintType);
        if (constraint.type() != path) {
            // Two names for the thing being set, and no rule about which wins. Refused rather than
            // resolved: the caller meant one of them and the server cannot know which.
            throw new TripValidationException("type", "Mismatch",
                    "the body's type must equal the constraintType in the path");
        }
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId)
                    .orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            List<TripItem> existing = trips.items(tripId);
            TripItem item = existing.stream().filter(each -> each.id().equals(itemId)).findFirst()
                    .orElseThrow(TripService::itemNotFound);
            requireLockHoldsNow(item, constraint);
            Instant now = clock.instant();
            trips.putConstraint(tripId, itemId, constraint, now);
            return commitConstraintChange(context, current, existing, itemId, constraint, true, now);
        });
    }

    /**
     * removeTripItemConstraint. Releasing a lock is deleting its row - the ERD stores only
     * {@code locked=true} rows, so there is no released state to write.
     */
    public TripMutationView removeConstraint(OwnerContext context, UUID tripId, UUID itemId,
            String constraintType, String ifMatch) {
        long expected = parseIfMatch(ifMatch);
        LockType type = lockTypeOf(constraintType);
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId)
                    .orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            List<TripItem> existing = trips.items(tripId);
            TripItem item = existing.stream().filter(each -> each.id().equals(itemId)).findFirst()
                    .orElseThrow(TripService::itemNotFound);
            if (item.constraints().stream().noneMatch(each -> each.type() == type)) {
                // Absent is not "already released": a lock that was never set is a path parameter
                // naming nothing, and answering 200 would tell the caller a release happened.
                throw notFoundConstraint();
            }
            Instant now = clock.instant();
            trips.deleteConstraint(itemId, type);
            return commitConstraintChange(context, current, existing, itemId,
                    new TripConstraint(new ItemLock.MustVisit(), ConstraintSource.USER), false, now);
        });
    }

    /**
     * Raises the version once and writes the revision, with the item list the change produced.
     *
     * <p>{@code added} distinguishes the two callers only in how the item is rebuilt; both raise the
     * version exactly once, because a lock is part of the trip a client holds an ETag for.
     */
    private TripMutationView commitConstraintChange(OwnerContext context, Trip current,
            List<TripItem> existing, UUID itemId, TripConstraint constraint, boolean added,
            Instant now) {
        List<TripItem> after = existing.stream()
                .map(each -> each.id().equals(itemId) ? withConstraint(each, constraint, added) : each)
                .toList();
        Trip bumped = raiseVersion(current, now);
        String snapshot = snapshot(bumped, after);
        trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
        return mutationView(context, current.id(), List.of(itemId));
    }

    private static TripItem withConstraint(TripItem item, TripConstraint constraint, boolean added) {
        List<TripConstraint> kept = item.constraints().stream()
                .filter(each -> each.type() != constraint.type()).toList();
        List<TripConstraint> next = new ArrayList<>(kept);
        if (added) {
            next.add(constraint);
        }
        return new TripItem(item.id(), item.placeId(), item.date(), item.position(), item.startTime(),
                item.durationMinutes(), item.note(), next);
    }

    /**
     * Refuses a lock the item already breaks.
     *
     * <p>MUST_VISIT is never broken by the item's own schedule - it pins the place - so LockChecks
     * passes it by definition and this only ever judges the three temporal ones.
     */
    private static void requireLockHoldsNow(TripItem item, TripConstraint constraint) {
        LockChecks.Result verdict = LockChecks.evaluate(List.of(constraint.lock()), item.date(),
                item.startTime(), item.durationMinutes());
        if (!verdict.satisfied()) {
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "The item does not satisfy this lock: " + String.join(", ", verdict.reasonCodes())
                            + ". Move the item first, or set a lock it already meets.");
        }
    }

    private static LockType lockTypeOf(String value) {
        for (LockType type : LockType.values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new TripValidationException("constraintType", "Enum",
                "constraintType must name a lock type");
    }

    private static ApiException notFoundConstraint() {
        return new ApiException(ProblemCode.NOT_FOUND, "The item does not carry that constraint.");
    }

    /**
     * removeTripItem. If-Match only - the contract gives this no Idempotency-Key, and it does not
     * need one: removing an item that is already gone is a 404, not a second removal.
     *
     * <p>{@code disposition} is the user's answer to a question the server must not guess. RESTORE
     * puts the place back among the candidates, so taking it off the plan is not the same as
     * deciding against it; REMOVE dismisses it. Choosing a default here would silently discard
     * places the traveller only meant to reschedule.
     */
    public TripMutationView removeItem(OwnerContext context, UUID tripId, UUID itemId, String ifMatch,
            String disposition) {
        long expected = parseIfMatch(ifMatch);
        ItemDisposition wanted = ItemDisposition.of(disposition);
        return transactions.execute(status -> {
            Trip current = trips.findForUpdate(context.ownerId(), tripId)
                    .orElseThrow(TripService::notFound);
            if (current.version() != expected) {
                throw tripChanged(current.version());
            }
            List<TripItem> existing = trips.items(tripId);
            TripItem removed = existing.stream().filter(item -> item.id().equals(itemId)).findFirst()
                    .orElseThrow(TripService::itemNotFound);
            Instant now = clock.instant();
            disposeCandidate(tripId, removed, wanted, now);
            trips.deleteItem(tripId, itemId);
            List<TripItem> after = existing.stream()
                    .filter(item -> !item.id().equals(itemId)).toList();
            // Positions are not closed up. The contract types position as an ordinal rather than an
            // index, so a gap is a valid order; renumbering would move items the user did not touch.
            Trip bumped = raiseVersion(current, now);
            String snapshot = snapshot(bumped, after);
            trips.updateMetadata(bumped, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
            return mutationView(context, tripId, List.of(itemId));
        });
    }

    /**
     * Moves whatever candidate pointed at this item, BEFORE the item is deleted.
     *
     * <p>The order is the database's, not a preference: {@code scheduled_trip_item_id} is ON DELETE
     * SET NULL while {@code trip_candidates_scheduled_shape_check} requires a SCHEDULED candidate to
     * name an item, so deleting the item first makes the referential action violate the check.
     *
     * <p>An item that never came from a candidate is the seedItems case, and RESTORE still has to
     * produce one. Its source is TRIP_SEED because that is the only true answer: the place arrived
     * with the trip, and SEARCH or POST would record a provenance the traveller never produced.
     */
    private void disposeCandidate(UUID tripId, TripItem removed, ItemDisposition wanted, Instant now) {
        if (wanted == ItemDisposition.REMOVE) {
            candidates.dismissScheduledFor(removed.id(), now);
            return;
        }
        // The item's lock is the same thing the candidate flag records, so it travels back on both
        // branches: taking a place off the schedule is not changing your mind about whether it
        // belongs in the trip. Reading the ITEM rather than leaving the candidate's old value is what
        // makes a lock added after scheduling survive - the restore used to return whatever the
        // candidate was saved with, so exactly that lock was the one being dropped.
        boolean mustVisit = removed.constraints().stream()
                .anyMatch(constraint -> constraint.type() == LockType.MUST_VISIT);
        if (candidates.restoreScheduledFor(removed.id(), mustVisit, now).isEmpty()) {
            candidates.saveActive(tripId, removed.placeId(), removed.note(), mustVisit,
                    new CandidateSource(CandidateSourceType.TRIP_SEED, null, now), now);
        }
    }

    /** The trip as it stands, plus which items this command changed. */
    private TripMutationView mutationView(OwnerContext context, UUID tripId, List<UUID> changed) {
        Trip trip = trips.find(context.ownerId(), tripId).orElseThrow(TripService::notFound);
        return new TripMutationView(new TripView(trip,
                trips.candidateCounts(List.of(tripId)).getOrDefault(tripId, 0),
                itemViews(context, tripId)), changed);
    }

    /** The same trip one version later. Exactly one, however much the command changed. */
    private static Trip raiseVersion(Trip current, Instant now) {
        return new Trip(current.id(), current.ownerId(), current.title(), current.range(),
                current.planningLevel(), current.status(), current.version() + 1, current.interests(),
                current.createdAt(), now, current.archivedAt());
    }

    /**
     * What addTripItem hashes for its Idempotency-Key, without the item id: the id is minted inside
     * the command, so including it would make two identical requests look different and defeat the
     * replay the header exists for.
     */
    private String canonicalAddition(AddTripItemCommand command) {
        List<TripConstraint> sorted = new ArrayList<>(command.constraints());
        sorted.sort(Comparator.comparing(constraint -> constraint.type().name()));
        StringBuilder out = new StringBuilder("{\"placeId\":\"").append(command.placeId()).append('"')
                .append(",\"candidateId\":").append(command.candidateId() == null ? "null"
                        : '"' + command.candidateId().toString() + '"')
                .append(",\"date\":\"").append(command.date()).append('"')
                .append(",\"position\":").append(command.position())
                .append(",\"startTime\":").append(command.startTime() == null ? "null"
                        : '"' + command.startTime().toString() + '"')
                .append(",\"durationMinutes\":").append(command.durationMinutes())
                .append(",\"note\":").append(json.writeValueAsString(command.note()))
                .append(",\"constraints\":[");
        for (int at = 0; at < sorted.size(); at++) {
            if (at > 0) {
                out.append(',');
            }
            out.append("{\"type\":\"").append(sorted.get(at).type()).append("\"}");
        }
        return out.append("]}").toString();
    }

    private static ApiException itemNotFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested trip item does not exist.");
    }

    /**
     * deleteTrip. Guarded by both If-Match and Idempotency-Key: the ETag says which trip state the
     * caller meant to delete, and the key makes a repeat safe once it is gone.
     */
    public void delete(OwnerContext context, UUID tripId, String ifMatch, String idempotencyKey) {
        long expected = parseIfMatch(ifMatch);
        // deleteTrip has no request body at all, so the version was standing in as one. It is the
        // precondition; the body is empty because that is the truth about this request.
        String fingerprint = RequestFingerprint
                .of("deleteTrip", Map.of("tripId", tripId.toString()), "", Long.toString(expected))
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

    /**
     * What the guard stores for addTripItem. One id, deliberately.
     *
     * <p>The response this command returns is a whole TripDetail - every day, every item, a
     * PlaceSummary each - and it grows with the trip while the record column stays at 65,536 bytes.
     * A full trip of name-only places measures around 41,000 of them, which fits; the same trip with
     * the addresses, thumbnails and attributions a real place carries does not. requireStorable runs
     * AFTER the command, so crossing that line turns a request that succeeded into a 500 that repeats
     * on every retry of the key. The trip is read back instead, which is also what makes a replay
     * describe the trip as it is now.
     */
    public record ItemProjection(UUID itemId) { }

    /** What the guard stores for reorderTripItems: which items moved, never the trip they moved in. */
    public record MovedProjection(List<UUID> itemIds) {
        public MovedProjection {
            itemIds = List.copyOf(itemIds);
        }
    }

    /** A mutation's answer: the trip after it, and which items it changed. */
    public record TripMutationView(TripView trip, List<UUID> changedItemIds) {
        public TripMutationView {
            Objects.requireNonNull(trip, "trip");
            changedItemIds = List.copyOf(changedItemIds);
        }
    }

    /**
     * What removeTripItem does with the place it takes off the schedule.
     *
     * <p>Required, with no default. The contract makes it a required query parameter for the same
     * reason: the two answers differ in whether the traveller keeps the place, and guessing would
     * either resurrect places they rejected or discard places they only meant to move.
     */
    public enum ItemDisposition {
        RESTORE_CANDIDATE,
        REMOVE;

        static ItemDisposition of(String value) {
            for (ItemDisposition disposition : values()) {
                if (disposition.name().equals(value)) {
                    return disposition;
                }
            }
            // 400 rather than a field error: disposition is a query parameter, so a missing or
            // unknown value is a request line the server cannot interpret - the same reading
            // parseIfMatch applies to a malformed header.
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "disposition must be one of " + java.util.Arrays.toString(values()) + ".");
        }
    }
}
