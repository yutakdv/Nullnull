package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.CrowdForecastQuery;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Reads one immutable set at a time. The query joins the revision captured by the snapshot instead
 * of the mutable registry row, so old previews retain their original license and metric meaning.
 */
@Repository
public class JdbcCrowdForecastQuery implements CrowdForecastQuery {

    private final JdbcTemplate jdbc;
    private final CrowdSnapshotRows rows = new CrowdSnapshotRows();

    public JdbcCrowdForecastQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SnapshotSet> latestFresh(UUID placeId, Instant from, Instant to, Instant now) {
        return latest(placeId, from, to, now, true);
    }

    @Override
    public Optional<SnapshotSet> latestStale(UUID placeId, Instant from, Instant to, Instant now) {
        return latest(placeId, from, to, now, false);
    }

    private Optional<SnapshotSet> latest(UUID placeId, Instant from, Instant to, Instant now, boolean fresh) {
        // The operator is selected internally, never from a request value. Keeping the placeholder
        // solely for the timestamp avoids the malformed `?AND` SQL which text-block concatenation
        // can produce when this query is reformatted.
        String freshnessOperator = fresh ? ">" : "<=";
        String latestSetSql = """
                SELECT ss.id
                  FROM snapshot_sets ss
                 WHERE ss.source_state = 'FORECAST'
                   AND ss.stale_at %s ?
                   AND EXISTS (
                       SELECT 1
                         FROM crowd_snapshots point
                        WHERE point.snapshot_set_id = ss.id
                          AND point.place_id = ?
                          AND point.target_at >= ?
                          AND point.target_at <= ?
                   )
                 ORDER BY ss.fetched_at DESC, ss.id DESC
                 LIMIT 1
                """.formatted(freshnessOperator);
        List<UUID> ids = jdbc.query(latestSetSql,
                new Object[] {Timestamp.from(now), placeId, Timestamp.from(from), Timestamp.from(to)},
                (result, row) -> result.getObject("id", UUID.class));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return frozenSet(ids.get(0), placeId, from, to);
    }

    @Override
    public Map<UUID, UUID> latestFreshSetIds(Collection<UUID> placeIds, Instant from, Instant to, Instant now) {
        return latestSetIds(placeIds, from, to, now, true);
    }

    @Override
    public Map<UUID, UUID> latestStaleSetIds(Collection<UUID> placeIds, Instant from, Instant to, Instant now) {
        return latestSetIds(placeIds, from, to, now, false);
    }

    /**
     * {@link #latest}'s question for many places in one statement: per place, the newest FORECAST set
     * on this side of {@code stale_at} that holds a point for it in the window, ties broken by id.
     * DISTINCT ON keeps each place's first row in exactly latest's ORDER BY, so the two choose the same
     * set. Fresh and stale stay two statements, as there: folding them into one ORDER BY on
     * {@code stale_at > now} would put a set whose {@code stale_at} is NULL first under DESC, a set
     * both of latest's comparisons refuse today.
     */
    private Map<UUID, UUID> latestSetIds(Collection<UUID> placeIds, Instant from, Instant to, Instant now,
            boolean fresh) {
        if (placeIds.isEmpty()) {
            return Map.of();
        }
        String freshnessOperator = fresh ? ">" : "<=";
        String latestSetsSql = """
                SELECT DISTINCT ON (point.place_id) point.place_id, ss.id AS set_id
                  FROM snapshot_sets ss
                  JOIN crowd_snapshots point ON point.snapshot_set_id = ss.id
                 WHERE ss.source_state = 'FORECAST'
                   AND ss.stale_at %s ?
                   AND point.place_id = ANY (?)
                   AND point.target_at >= ?
                   AND point.target_at <= ?
                 ORDER BY point.place_id, ss.fetched_at DESC, ss.id DESC
                """.formatted(freshnessOperator);
        Map<UUID, UUID> chosen = new HashMap<>();
        jdbc.query(latestSetsSql, (RowCallbackHandler) result -> chosen.put(
                        result.getObject("place_id", UUID.class), result.getObject("set_id", UUID.class)),
                Timestamp.from(now), placeIds.toArray(UUID[]::new), Timestamp.from(from), Timestamp.from(to));
        return Map.copyOf(chosen);
    }

    @Override
    public Map<UUID, SnapshotSet> sets(Map<UUID, UUID> setIdByPlace, Instant from, Instant to) {
        if (setIdByPlace.isEmpty()) {
            return Map.of();
        }
        List<UUID> places = List.copyOf(setIdByPlace.keySet());
        // Pairs, not two independent lists: snapshot_sets has no place column, so nothing ties a set
        // to one place, and a place's points in the set chosen for ANOTHER place are not its answer.
        List<Snapshot> points = jdbc.query(CrowdSnapshotRows.POINT + """
                 WHERE (point.snapshot_set_id, point.place_id) IN (SELECT * FROM unnest(?::uuid[], ?::uuid[]))
                   AND point.target_at >= ?
                   AND point.target_at <= ?
                 ORDER BY point.place_id, point.target_at ASC, point.id ASC
                """, rows::map, places.stream().map(setIdByPlace::get).toArray(UUID[]::new),
                places.toArray(UUID[]::new), Timestamp.from(from), Timestamp.from(to));
        Map<UUID, List<Snapshot>> byPlace = new LinkedHashMap<>();
        for (Snapshot point : points) {
            byPlace.computeIfAbsent(point.placeId(), place -> new ArrayList<>()).add(point);
        }
        Map<UUID, SnapshotSet> sets = new HashMap<>();
        byPlace.forEach((place, held) -> sets.put(place, new SnapshotSet(setIdByPlace.get(place), held)));
        return Map.copyOf(sets);
    }


    @Override
    public Optional<SnapshotSet> frozenSet(UUID setId, UUID placeId, Instant from, Instant to) {
        List<Snapshot> points = jdbc.query(CrowdSnapshotRows.POINT + """
                 WHERE point.snapshot_set_id = ?
                   AND point.place_id = ?
                   AND point.target_at >= ?
                   AND point.target_at <= ?
                 ORDER BY point.target_at ASC, point.id ASC
                """, new Object[] {setId, placeId, Timestamp.from(from), Timestamp.from(to)}, rows::map);
        return points.isEmpty() ? Optional.empty() : Optional.of(new SnapshotSet(setId, points));
    }

    @Override
    public List<Snapshot> points(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(CrowdSnapshotRows.POINT + """
                 WHERE point.id = ANY (?)
                 ORDER BY point.target_at ASC, point.id ASC
                """, new Object[] {ids.toArray(UUID[]::new)}, rows::map);
    }
}
