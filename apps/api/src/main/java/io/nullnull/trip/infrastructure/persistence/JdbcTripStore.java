package io.nullnull.trip.infrastructure.persistence;

import io.nullnull.trip.application.TripStore;
import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The trip module's own tables. No other module reads or writes them. */
@Repository
public class JdbcTripStore implements TripStore {

    private final JdbcClient jdbc;

    public JdbcTripStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(Trip trip, String snapshotSchemaVersion, String snapshotHash, String snapshot) {
        jdbc.sql("""
                INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,
                                   planning_level, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(trip.id(), trip.ownerId(), trip.title(),
                        java.sql.Date.valueOf(trip.range().startDate()),
                        java.sql.Date.valueOf(trip.range().endDate()), trip.range().timezone().getId(),
                        trip.planningLevel().name(), trip.status().name(), trip.version(),
                        Timestamp.from(trip.createdAt()), Timestamp.from(trip.updatedAt()))
                .update();
        for (TripInterest interest : trip.interests()) {
            jdbc.sql("INSERT INTO trip_interests (trip_id, interest_code, weight, created_at)"
                            + " VALUES (?, ?, ?, ?)")
                    .params(trip.id(), interest.code(), interest.weight(), Timestamp.from(trip.createdAt()))
                    .update();
        }
        // Revision 1 is written with the trip, in the same transaction: a trip whose first revision
        // is missing has no before-state for any later APPLY or REVERT to restore.
        jdbc.sql("""
                INSERT INTO trip_revisions (id, trip_id, version, snapshot_schema_version,
                                            snapshot_hash, aggregate_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """)
                .params(UUID.randomUUID(), trip.id(), trip.version(), snapshotSchemaVersion,
                        snapshotHash, snapshot, Timestamp.from(trip.createdAt()))
                .update();
    }

    @Override
    public Optional<Trip> find(UUID ownerId, UUID tripId) {
        // owner_id is in the WHERE clause, not checked afterwards: a trip that is not this owner's
        // must not be readable even for long enough to compare it.
        Optional<Trip> trip = jdbc.sql("""
                SELECT id, owner_id, title, start_date, end_date, timezone, planning_level, status,
                       version, created_at, updated_at, archived_at
                  FROM trips
                 WHERE id = ? AND owner_id = ?
                """)
                .params(tripId, ownerId)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .optional();
        return trip.map(found -> withInterests(found, interests(found.id())));
    }

    @Override
    public List<Trip> page(UUID ownerId, String status, long offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, owner_id, title, start_date, end_date, timezone, planning_level, status,
                       version, created_at, updated_at, archived_at
                  FROM trips
                 WHERE owner_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(ownerId);
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status);
        }
        // The ordering the (owner_id, status, start_date DESC) index serves. id breaks ties so the
        // sort is total: two trips starting the same day must not swap places between pages.
        sql.append(" ORDER BY start_date DESC, id DESC LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        List<Trip> found = jdbc.sql(sql.toString()).params(parameters)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .list();
        if (found.isEmpty()) {
            return found;
        }
        Map<UUID, List<TripInterest>> byTrip = interests(found.stream().map(Trip::id).toList());
        List<Trip> hydrated = new ArrayList<>(found.size());
        for (Trip trip : found) {
            hydrated.add(withInterests(trip, byTrip.getOrDefault(trip.id(), List.of())));
        }
        return List.copyOf(hydrated);
    }

    @Override
    public Map<UUID, Integer> candidateCounts(List<UUID> tripIds) {
        // trip_candidates belongs to BA-034 and does not exist yet. Returning an empty map means
        // every count is 0, which is true today: nothing can have created a candidate. It is NOT a
        // silent fallback - when the table lands, this method changes with it.
        return Map.of();
    }

    private List<TripInterest> interests(UUID tripId) {
        return interests(List.of(tripId)).getOrDefault(tripId, List.of());
    }

    private Map<UUID, List<TripInterest>> interests(List<UUID> tripIds) {
        if (tripIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<TripInterest>> byTrip = new HashMap<>();
        jdbc.sql("SELECT trip_id, interest_code, weight FROM trip_interests"
                        + " WHERE trip_id = ANY (?) ORDER BY interest_code")
                .param(tripIds.toArray(UUID[]::new))
                .query((ResultSet row, int index) -> {
                    byTrip.computeIfAbsent(row.getObject("trip_id", UUID.class), key -> new ArrayList<>())
                            .add(new TripInterest(row.getString("interest_code"), row.getInt("weight")));
                    return null;
                })
                .list();
        return byTrip;
    }

    private static Trip withInterests(Trip trip, List<TripInterest> interests) {
        return new Trip(trip.id(), trip.ownerId(), trip.title(), trip.range(), trip.planningLevel(),
                trip.status(), trip.version(), interests, trip.createdAt(), trip.updatedAt(),
                trip.archivedAt());
    }

    private static Trip map(ResultSet row, List<TripInterest> interests) throws SQLException {
        Timestamp archived = row.getTimestamp("archived_at");
        return new Trip(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getString("title"),
                new TripDateRange(row.getDate("start_date").toLocalDate(),
                        row.getDate("end_date").toLocalDate(),
                        java.time.ZoneId.of(row.getString("timezone"))),
                PlanningLevel.of(row.getString("planning_level")),
                TripStatus.of(row.getString("status")), row.getLong("version"), interests,
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")),
                archived == null ? null : archived.toInstant());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
