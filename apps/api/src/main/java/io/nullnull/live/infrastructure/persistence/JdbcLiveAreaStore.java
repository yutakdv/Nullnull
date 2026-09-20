package io.nullnull.live.infrastructure.persistence;

import io.nullnull.live.application.LiveAreaStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stored view of a provider's area list.
 *
 * <p><strong>An area that leaves the provider's list is RETIRED, never deleted.</strong> The 2026-03
 * Seoul notice added, removed and renamed places, and snapshots outlive the list: deleting a removed
 * area would orphan every reading that measured it, and "this area existed and no longer reports" is
 * the honest record. V044's status CHECK is the other half of that decision.
 *
 * <p>Identity is {@code (source_code, external_id)}, not the name. The same notice renamed places, so
 * a name-keyed store would have read a rename as a delete plus an insert and lost the link to every
 * snapshot behind it.
 */
@Repository
public class JdbcLiveAreaStore implements LiveAreaStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcLiveAreaStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<StoredArea> replaceAreas(String sourceCode, List<AreaUpsert> published) {
        Instant now = clock.instant();
        List<AreaUpsert> areas = published == null ? List.of() : published;
        for (AreaUpsert area : areas) {
            // ON CONFLICT on the identity, so a rename updates the row the snapshots already point at.
            jdbc.update("""
                    INSERT INTO live_areas (id, source_code, external_id, name, boundary_geojson, status, updated_at)
                    VALUES (?, ?, ?, ?, NULL, 'ACTIVE', ?)
                    ON CONFLICT (source_code, external_id) DO UPDATE
                       SET name = EXCLUDED.name, status = 'ACTIVE', updated_at = EXCLUDED.updated_at
                    """, UUID.randomUUID(), sourceCode, area.externalId(), area.name(), java.sql.Timestamp.from(now));
        }
        // Everything this source used to publish and no longer does. Retired, not removed.
        if (areas.isEmpty()) {
            jdbc.update("UPDATE live_areas SET status = 'RETIRED', updated_at = ? "
                    + "WHERE source_code = ? AND status <> 'RETIRED'", java.sql.Timestamp.from(now), sourceCode);
        } else {
            List<Object> arguments = new ArrayList<>();
            arguments.add(java.sql.Timestamp.from(now));
            arguments.add(sourceCode);
            StringBuilder sql = new StringBuilder("UPDATE live_areas SET status = 'RETIRED', updated_at = ? "
                    + "WHERE source_code = ? AND status <> 'RETIRED' AND external_id NOT IN (");
            for (int index = 0; index < areas.size(); index++) {
                sql.append(index == 0 ? "?" : ", ?");
                arguments.add(areas.get(index).externalId());
            }
            jdbc.update(sql.append(")").toString(), arguments.toArray());
        }
        return activeAreas(sourceCode);
    }

    @Override
    public List<StoredArea> activeAreas(String sourceCode) {
        return jdbc.query("""
                SELECT id, external_id, name, status FROM live_areas
                 WHERE source_code = ? AND status = 'ACTIVE'
                 ORDER BY external_id
                """, JdbcLiveAreaStore::read, sourceCode);
    }

    private static StoredArea read(ResultSet row, int index) throws SQLException {
        return new StoredArea(row.getObject("id", UUID.class), row.getString("external_id"),
                row.getString("name"), row.getString("status"));
    }
}
