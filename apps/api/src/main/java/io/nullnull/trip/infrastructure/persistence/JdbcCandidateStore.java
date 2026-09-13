package io.nullnull.trip.infrastructure.persistence;

import io.nullnull.trip.application.CandidateStore;
import io.nullnull.trip.domain.CandidateSourceType;
import io.nullnull.trip.domain.CandidateStatus;
import io.nullnull.trip.domain.TripCandidate;
import io.nullnull.trip.domain.TripCandidate.CandidateSource;
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

/** The trip module's candidate tables. */
@Repository
public class JdbcCandidateStore implements CandidateStore {

    private final JdbcClient jdbc;

    public JdbcCandidateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Saved saveActive(UUID tripId, UUID placeId, String note, CandidateSource source,
            Instant now) {
        UUID id = UUID.randomUUID();
        // ON CONFLICT on the PARTIAL unique index: the same place saved twice converges on one
        // active row even when two requests race, because the database decides, not a prior read.
        int inserted = jdbc.sql("""
                INSERT INTO trip_candidates (id, trip_id, place_id, status, note, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT (trip_id, place_id) WHERE status <> 'DISMISSED' DO NOTHING
                """)
                .params(id, tripId, placeId, note, Timestamp.from(now), Timestamp.from(now))
                .update();
        UUID candidateId = inserted == 1 ? id : jdbc.sql(
                "SELECT id FROM trip_candidates WHERE trip_id = ? AND place_id = ? AND status <> 'DISMISSED'")
                .params(tripId, placeId)
                .query(UUID.class)
                .single();
        // The source is recorded either way. Saving the same POI from a second post adds a source to
        // the existing candidate rather than making a second candidate - which is what "different
        // posts converge on one active candidate" means for provenance.
        jdbc.sql("""
                INSERT INTO candidate_sources (id, candidate_id, source_type, post_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (candidate_id, source_type, post_id) DO NOTHING
                """)
                .params(UUID.randomUUID(), candidateId, source.type().name(), source.postId(),
                        Timestamp.from(now))
                .update();
        return new Saved(load(candidateId).orElseThrow(), inserted == 0);
    }

    @Override
    public Optional<TripCandidate> find(UUID ownerId, UUID tripId, UUID candidateId) {
        // The owner is in the join, not checked afterwards: a candidate in someone else's trip must
        // be unreadable rather than read and then rejected.
        Optional<UUID> id = jdbc.sql("""
                SELECT candidate.id
                  FROM trip_candidates candidate
                  JOIN trips trip ON trip.id = candidate.trip_id
                 WHERE candidate.id = ? AND candidate.trip_id = ? AND trip.owner_id = ?
                """)
                .params(candidateId, tripId, ownerId)
                .query(UUID.class)
                .optional();
        return id.flatMap(this::load);
    }

    @Override
    public boolean dismiss(UUID candidateId, Instant now) {
        // status = 'ACTIVE' in the WHERE clause, so a candidate that became SCHEDULED between the
        // read and this write is not dismissed out from under the schedule.
        return jdbc.sql("UPDATE trip_candidates SET status = 'DISMISSED', updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'")
                .params(Timestamp.from(now), candidateId)
                .update() == 1;
    }

    @Override
    public boolean schedule(UUID candidateId, UUID tripItemId, Instant now) {
        // Status and pointer move together because the table refuses any other combination:
        // trip_candidates_scheduled_shape_check makes SCHEDULED and a non-null item id one fact.
        return jdbc.sql("UPDATE trip_candidates SET status = 'SCHEDULED',"
                        + " scheduled_trip_item_id = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'ACTIVE'")
                .params(tripItemId, Timestamp.from(now), candidateId)
                .update() == 1;
    }

    @Override
    public Optional<UUID> restoreScheduledFor(UUID tripItemId, Instant now) {
        return moveScheduled(tripItemId, "ACTIVE", now);
    }

    @Override
    public Optional<UUID> dismissScheduledFor(UUID tripItemId, Instant now) {
        return moveScheduled(tripItemId, "DISMISSED", now);
    }

    /**
     * The two directions a scheduled candidate can leave the schedule, keyed by the item it points at.
     *
     * <p>RETURNING makes "did it move" and "which row" one statement: reading first and updating
     * after would let the row change in between, and the caller has no id to work with until the
     * update has actually happened.
     */
    private Optional<UUID> moveScheduled(UUID tripItemId, String status, Instant now) {
        return jdbc.sql("UPDATE trip_candidates SET status = ?, scheduled_trip_item_id = NULL,"
                        + " updated_at = ? WHERE scheduled_trip_item_id = ? AND status = 'SCHEDULED'"
                        + " RETURNING id")
                .params(status, Timestamp.from(now), tripItemId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public List<TripCandidate> page(UUID tripId, CandidateStatus status, long offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, trip_id, place_id, status, scheduled_trip_item_id, note, created_at, updated_at
                  FROM trip_candidates
                 WHERE trip_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(tripId);
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        // Most recent first, id breaking ties so the sort is total across pages.
        sql.append(" ORDER BY created_at DESC, id ASC LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        List<TripCandidate> found = jdbc.sql(sql.toString()).params(parameters)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .list();
        return withSources(found);
    }

    @Override
    public int count(UUID tripId) {
        // Every candidate the trip holds, whatever its status. Which statuses the DISPLAYED count
        // includes is the candidate slice's to decide and the contract deliberately does not fix it,
        // so this reports the whole set rather than inventing a filter.
        return jdbc.sql("SELECT count(*) FROM trip_candidates WHERE trip_id = ?")
                .param(tripId).query(Integer.class).single();
    }

    @Override
    public boolean ownsTrip(UUID ownerId, UUID tripId) {
        return jdbc.sql("SELECT count(*) FROM trips WHERE id = ? AND owner_id = ?")
                .params(tripId, ownerId).query(Integer.class).single() > 0;
    }

    @Override
    public Map<UUID, CandidateStatus> statesByPlace(UUID tripId, List<UUID> placeIds) {
        if (tripId == null || placeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CandidateStatus> states = new HashMap<>();
        jdbc.sql("""
                SELECT place_id, status
                  FROM trip_candidates
                 WHERE trip_id = ? AND place_id = ANY (?) AND status <> 'DISMISSED'
                """)
                .params(tripId, placeIds.toArray(UUID[]::new))
                .query((ResultSet row, int index) -> {
                    states.put(row.getObject("place_id", UUID.class),
                            CandidateStatus.of(row.getString("status")));
                    return null;
                })
                .list();
        return Map.copyOf(states);
    }

    private Optional<TripCandidate> load(UUID candidateId) {
        Optional<TripCandidate> found = jdbc.sql("""
                SELECT id, trip_id, place_id, status, scheduled_trip_item_id, note, created_at, updated_at
                  FROM trip_candidates WHERE id = ?
                """)
                .param(candidateId)
                .query((ResultSet row, int index) -> map(row, List.of()))
                .optional();
        return found.map(candidate -> withSources(List.of(candidate)).get(0));
    }

    private List<TripCandidate> withSources(List<TripCandidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Map<UUID, List<CandidateSource>> byCandidate = new HashMap<>();
        jdbc.sql("""
                SELECT candidate_id, source_type, post_id, created_at
                  FROM candidate_sources
                 WHERE candidate_id = ANY (?)
                 ORDER BY created_at, id
                """)
                .param(candidates.stream().map(TripCandidate::id).toArray(UUID[]::new))
                .query((ResultSet row, int index) -> {
                    byCandidate.computeIfAbsent(row.getObject("candidate_id", UUID.class),
                                    key -> new ArrayList<>())
                            .add(new CandidateSource(CandidateSourceType.of(row.getString("source_type")),
                                    row.getObject("post_id", UUID.class),
                                    row.getTimestamp("created_at").toInstant()));
                    return null;
                })
                .list();
        List<TripCandidate> hydrated = new ArrayList<>(candidates.size());
        for (TripCandidate candidate : candidates) {
            hydrated.add(new TripCandidate(candidate.id(), candidate.tripId(), candidate.placeId(),
                    candidate.status(), candidate.scheduledTripItemId(), candidate.note(),
                    byCandidate.getOrDefault(candidate.id(), List.of()), candidate.createdAt(),
                    candidate.updatedAt()));
        }
        return List.copyOf(hydrated);
    }

    private static TripCandidate map(ResultSet row, List<CandidateSource> sources) throws SQLException {
        return new TripCandidate(row.getObject("id", UUID.class), row.getObject("trip_id", UUID.class),
                row.getObject("place_id", UUID.class), CandidateStatus.of(row.getString("status")),
                row.getObject("scheduled_trip_item_id", UUID.class), row.getString("note"), sources,
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
