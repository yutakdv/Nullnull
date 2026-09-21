package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.CrowdForecastQuery.Snapshot;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.application.LiveAreaCrowdQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the newest live-area snapshot per area, through the same row mapping the forecast read uses. */
@Repository
public class JdbcLiveAreaCrowdQuery implements LiveAreaCrowdQuery {

    private final JdbcTemplate jdbc;
    private final CrowdSnapshotRows rows = new CrowdSnapshotRows();
    private final CrowdProvenanceProjection provenance;

    public JdbcLiveAreaCrowdQuery(JdbcTemplate jdbc, CrowdProvenanceProjection provenance) {
        this.jdbc = jdbc;
        this.provenance = provenance;
    }

    @Override
    public List<AreaReading> latestFor(String sourceCode, List<UUID> liveAreaIds, Instant now) {
        if (liveAreaIds == null || liveAreaIds.isEmpty()) {
            return List.of();
        }
        // TWO STATEMENTS, AND NEITHER GROWS WITH THE ROWS BEHIND THEM. The first picks one snapshot
        // id per area - DISTINCT ON does that in the database rather than reading every reading an
        // area ever had and discarding all but one. The second is the shared point mapping, which
        // needs ids. A per-area loop would be one statement per area, which is what BA-070-T3 is
        // about.
        Map<UUID, UUID> areaBySnapshot = new HashMap<>();
        List<UUID> newest = new ArrayList<>();
        jdbc.query("""
                SELECT DISTINCT ON (point.live_area_id) point.live_area_id, point.id
                  FROM crowd_snapshots point
                 WHERE point.source_code = ?
                   AND point.live_area_id = ANY (?)
                 ORDER BY point.live_area_id, point.observed_at DESC, point.fetched_at DESC, point.id DESC
                """, result -> {
            UUID snapshotId = result.getObject("id", UUID.class);
            areaBySnapshot.put(snapshotId, result.getObject("live_area_id", UUID.class));
            newest.add(snapshotId);
        }, sourceCode, liveAreaIds.toArray(UUID[]::new));
        if (newest.isEmpty()) {
            return List.of();
        }
        List<Snapshot> points = jdbc.query(CrowdSnapshotRows.POINT + """
                 WHERE point.id = ANY (?)
                """, rows::map, (Object) newest.toArray(UUID[]::new));
        List<AreaReading> readings = new ArrayList<>(points.size());
        for (Snapshot point : points) {
            // staleFallback is false: nothing here fell back to an older set. The freshness question
            // - is this past its staleAt - is answered inside the projection against `now`.
            readings.add(new AreaReading(areaBySnapshot.get(point.id()), provenance.project(point, now, false)));
        }
        return List.copyOf(readings);
    }
}
