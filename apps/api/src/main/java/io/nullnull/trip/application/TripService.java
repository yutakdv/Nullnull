package io.nullnull.trip.application;

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
import io.nullnull.trip.domain.TripInterest;
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
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final TripStore trips;
    private final IdempotencyGuard idempotency;
    private final TripCursorProperties cursors;
    private final Clock clock;
    private final ObjectMapper json;

    public TripService(TripStore trips, IdempotencyGuard idempotency, TripCursorProperties cursors,
            Clock clock, ObjectMapper json) {
        this.trips = trips;
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
        String fingerprint = RequestFingerprint.of("createTrip", Map.of(), canonicalRequest(command))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), CREATE_ROUTE,
                idempotencyKey, fingerprint,
                () -> new CommandOutcome<>(201, persist(context.ownerId(), command)), value -> value);
        Projection projection = json.readValue(guarded.body(), Projection.class);
        return new TripView(rehydrate(context.ownerId(), projection), projection.candidateCount());
    }

    @Transactional(readOnly = true)
    public TripView get(OwnerContext context, UUID tripId) {
        Trip trip = trips.find(context.ownerId(), tripId).orElseThrow(TripService::notFound);
        return new TripView(trip, trips.candidateCounts(List.of(trip.id())).getOrDefault(trip.id(), 0));
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

    private Projection persist(UUID ownerId, CreateTripCommand command) {
        Instant now = clock.instant();
        Trip trip = new Trip(UUID.randomUUID(), ownerId, command.title(), command.range(),
                command.planningLevel(), TripStatus.DRAFT, 1L, command.interests(), now, now, null);
        String snapshot = snapshot(trip);
        trips.create(trip, SNAPSHOT_SCHEMA_VERSION, sha256Hex(snapshot), snapshot);
        // A created trip holds no candidates: saving a candidate is a separate resource and is not
        // part of creation (invariant 1).
        return Projection.of(trip, 0);
    }

    /**
     * The canonical JSON recorded as revision 1. Field order is fixed and interests are sorted, so
     * the same trip always hashes the same way and the hash means "this content" rather than "this
     * serializer's field order today".
     */
    private String snapshot(Trip trip) {
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
                + ",\"items\":[]"
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
