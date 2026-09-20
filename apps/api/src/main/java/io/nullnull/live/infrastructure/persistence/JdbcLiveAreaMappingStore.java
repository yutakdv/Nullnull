package io.nullnull.live.infrastructure.persistence;

import io.nullnull.live.application.LiveAreaMappingStore;
import io.nullnull.live.domain.LiveAreaMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The stored reviewer decisions in {@code seoul_live_area_maps}, read back.
 *
 * <p><strong>This class only reads.</strong> V044's rows are review decisions about where one of our
 * places sits, and no collector run may write them - so there is no insert here for a scheduled job
 * to reach for. The rows arrive through the curation path, the same way {@code place_hours} and
 * {@code place_relations} do.
 *
 * <p>Both queries order by {@code place_id} so that the answer is a function of the rows and not of
 * a plan. {@code LivePlace} has no rank and the contract gives the array no sort, so without this the
 * order would be whatever PostgreSQL produced that day and a reader comparing two loads of the same
 * area could see them differ with nothing having changed.
 */
@Repository
public class JdbcLiveAreaMappingStore implements LiveAreaMappingStore {

    private final JdbcTemplate jdbc;

    public JdbcLiveAreaMappingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, LiveAreaMapping> forArea(UUID liveAreaId) {
        if (liveAreaId == null) {
            return Map.of();
        }
        return index(jdbc.query("""
                SELECT place_id, live_area_id, mapping_type, confidence, fallback_used
                  FROM seoul_live_area_maps
                 WHERE live_area_id = ?
                 ORDER BY place_id
                """, JdbcLiveAreaMappingStore::read, liveAreaId));
    }

    @Override
    public Map<UUID, LiveAreaMapping> forPlaces(List<UUID> placeIds) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Map.of();
        }
        // One statement for the whole list rather than one per place: BA-070-T3's budget is about
        // work that grows with the rows read, and a per-place loop here is exactly the N+1 shape it
        // refuses.
        List<UUID> ids = placeIds.stream().distinct().toList();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
        return index(jdbc.query("""
                SELECT place_id, live_area_id, mapping_type, confidence, fallback_used
                  FROM seoul_live_area_maps
                 WHERE place_id IN (%s)
                 ORDER BY place_id
                """.formatted(placeholders), JdbcLiveAreaMappingStore::read, ids.toArray()));
    }

    /**
     * A place has at most one mapping - V044's {@code UNIQUE (place_id, live_area_id)} permits a
     * second area for the same place, and nothing today writes one. If one ever appears this keeps
     * the lowest area id rather than whichever row arrived first, so two loads agree; the caller is
     * not the place to discover that two areas claim one place.
     *
     * <p>The map is a {@link LinkedHashMap} behind an unmodifiable view and NOT {@code Map.copyOf}:
     * that factory's iteration order is unspecified, so copying through it would throw away the
     * {@code ORDER BY} above and hand the caller a different array order on different runs. The
     * ordering is the point of the query.
     */
    private static Map<UUID, LiveAreaMapping> index(List<LiveAreaMapping> rows) {
        Map<UUID, LiveAreaMapping> byPlace = new LinkedHashMap<>();
        for (LiveAreaMapping row : rows) {
            byPlace.merge(row.placeId(), row, (kept, other) ->
                    kept.liveAreaId().toString().compareTo(other.liveAreaId().toString()) <= 0 ? kept : other);
        }
        return Collections.unmodifiableMap(byPlace);
    }

    private static LiveAreaMapping read(ResultSet row, int index) throws SQLException {
        return new LiveAreaMapping(row.getObject("place_id", UUID.class),
                row.getObject("live_area_id", UUID.class), row.getString("mapping_type"),
                row.getBigDecimal("confidence"), row.getBoolean("fallback_used"));
    }
}
