package io.nullnull.trip.infrastructure.persistence;

import io.nullnull.trip.application.TripStore;
import io.nullnull.trip.domain.ConstraintSource;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.PlanningLevel;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripDateRange;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripInterest;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    public void create(Trip trip, List<TripItem> items, String snapshotSchemaVersion,
            String snapshotHash, String snapshot) {
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
        writeItems(trip.id(), items, trip.createdAt());
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

    @Override
    public Optional<Trip> findForUpdate(UUID ownerId, UUID tripId) {
        // FOR UPDATE, not a read followed by a version comparison: two tabs that both read version 3
        // would both believe they may write version 4. ERD §11 requires one strategy, and this is it.
        Optional<Trip> trip = jdbc.sql("""
                SELECT id, owner_id, title, start_date, end_date, timezone, planning_level, status,
                       version, created_at, updated_at, archived_at
                  FROM trips
                 WHERE id = ? AND owner_id = ?
                 FOR UPDATE
                """)
                .params(tripId, ownerId)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .optional();
        return trip.map(found -> withInterests(found, interests(found.id())));
    }

    @Override
    public List<TripItem> items(UUID tripId) {
        Map<UUID, List<TripConstraint>> byItem = constraints(tripId);
        return jdbc.sql("""
                SELECT id, place_id, trip_date, position, start_time, duration_minutes, note
                  FROM trip_items
                 WHERE trip_id = ?
                 ORDER BY trip_date, position
                """)
                .param(tripId)
                .query((ResultSet row, int index) -> {
                    UUID id = row.getObject("id", UUID.class);
                    Time startTime = row.getTime("start_time");
                    // wasNull() reports on the LAST column read, so it is asked immediately after
                    // duration_minutes. Reading note first made it answer about note instead, and a
                    // null duration came back as 0 - which TripItem rejects as out of range.
                    int duration = row.getInt("duration_minutes");
                    Integer durationMinutes = row.wasNull() ? null : duration;
                    return new TripItem(id, row.getObject("place_id", UUID.class),
                            row.getDate("trip_date").toLocalDate(), row.getInt("position"),
                            startTime == null ? null : startTime.toLocalTime(),
                            durationMinutes, row.getString("note"),
                            byItem.getOrDefault(id, List.of()));
                })
                .list();
    }

    @Override
    public void updateMetadata(Trip trip, String snapshotSchemaVersion, String snapshotHash,
            String snapshot) {
        // The WHERE clause carries the PREVIOUS version. Combined with FOR UPDATE this is belt and
        // braces, but it is the half that survives if a future caller forgets the lock.
        int updated = jdbc.sql("""
                UPDATE trips
                   SET title = ?, start_date = ?, end_date = ?, timezone = ?, planning_level = ?,
                       status = ?, version = ?, updated_at = ?, archived_at = ?
                 WHERE id = ? AND owner_id = ? AND version = ?
                """)
                .params(trip.title(), java.sql.Date.valueOf(trip.range().startDate()),
                        java.sql.Date.valueOf(trip.range().endDate()), trip.range().timezone().getId(),
                        trip.planningLevel().name(), trip.status().name(), trip.version(),
                        Timestamp.from(trip.updatedAt()),
                        trip.archivedAt() == null ? null : Timestamp.from(trip.archivedAt()),
                        trip.id(), trip.ownerId(), trip.version() - 1)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("trip version moved under an update that held its lock");
        }
        jdbc.sql("DELETE FROM trip_interests WHERE trip_id = ?").param(trip.id()).update();
        for (TripInterest interest : trip.interests()) {
            jdbc.sql("INSERT INTO trip_interests (trip_id, interest_code, weight, created_at)"
                            + " VALUES (?, ?, ?, ?)")
                    .params(trip.id(), interest.code(), interest.weight(), Timestamp.from(trip.updatedAt()))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO trip_revisions (id, trip_id, version, snapshot_schema_version,
                                            snapshot_hash, aggregate_snapshot, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """)
                .params(UUID.randomUUID(), trip.id(), trip.version(), snapshotSchemaVersion,
                        snapshotHash, snapshot, Timestamp.from(trip.updatedAt()))
                .update();
    }

    @Override
    public void delete(UUID ownerId, UUID tripId) {
        // owner_id in the WHERE clause, not a check beforehand: the row a caller may not see is the
        // row they may not delete, and both are decided by the same predicate.
        jdbc.sql("DELETE FROM trips WHERE id = ? AND owner_id = ?").params(tripId, ownerId).update();
    }

    private void writeItems(UUID tripId, List<TripItem> items, Instant at) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TripItem item : items) {
            jdbc.sql("""
                    INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,
                                            duration_minutes, note, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(item.id(), tripId, item.placeId(),
                            java.sql.Date.valueOf(item.date()), item.position(),
                            item.startTime() == null ? null : Time.valueOf(item.startTime()),
                            item.durationMinutes(), item.note(), Timestamp.from(at), Timestamp.from(at))
                    .update();
            for (TripConstraint constraint : item.constraints()) {
                writeConstraint(tripId, item.id(), constraint, at);
            }
        }
    }

    private void writeConstraint(UUID tripId, UUID itemId, TripConstraint constraint, Instant at) {
        LocalDate dateValue = null;
        LocalTime startValue = null;
        LocalTime endValue = null;
        Integer tolerance = null;
        switch (constraint.lock()) {
            case ItemLock.MustVisit ignored -> { }
            case ItemLock.Date locked -> dateValue = locked.date();
            case ItemLock.Time locked -> {
                startValue = locked.startTime();
                tolerance = locked.toleranceMinutes();
            }
            case ItemLock.Reservation locked -> {
                dateValue = locked.date();
                startValue = locked.startTime();
                endValue = locked.endTime();
            }
        }
        jdbc.sql("""
                INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source, date_value,
                                              start_time_value, end_time_value, tolerance_minutes,
                                              created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(UUID.randomUUID(), tripId, itemId, constraint.type().name(),
                        constraint.source().name(),
                        dateValue == null ? null : java.sql.Date.valueOf(dateValue),
                        startValue == null ? null : Time.valueOf(startValue),
                        endValue == null ? null : Time.valueOf(endValue),
                        tolerance, Timestamp.from(at), Timestamp.from(at))
                .update();
    }

    private Map<UUID, List<TripConstraint>> constraints(UUID tripId) {
        Map<UUID, List<TripConstraint>> byItem = new HashMap<>();
        jdbc.sql("""
                SELECT trip_item_id, type, source, date_value, start_time_value, end_time_value,
                       tolerance_minutes
                  FROM trip_constraints
                 WHERE trip_id = ?
                 ORDER BY type
                """)
                .param(tripId)
                .query((ResultSet row, int index) -> {
                    byItem.computeIfAbsent(row.getObject("trip_item_id", UUID.class),
                            key -> new ArrayList<>()).add(readConstraint(row));
                    return null;
                })
                .list();
        return byItem;
    }

    private static TripConstraint readConstraint(ResultSet row) throws SQLException {
        LockType type = LockType.valueOf(row.getString("type"));
        ConstraintSource source = ConstraintSource.of(row.getString("source"));
        java.sql.Date date = row.getDate("date_value");
        Time start = row.getTime("start_time_value");
        Time end = row.getTime("end_time_value");
        ItemLock lock = switch (type) {
            case MUST_VISIT -> new ItemLock.MustVisit();
            case DATE -> new ItemLock.Date(date.toLocalDate());
            case TIME -> new ItemLock.Time(start.toLocalTime(), row.getInt("tolerance_minutes"));
            case RESERVATION -> new ItemLock.Reservation(date.toLocalDate(), start.toLocalTime(),
                    end == null ? null : end.toLocalTime());
        };
        return new TripConstraint(lock, source);
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
