package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC projection of the normalized C2 KTO detail cache; there is no raw-body column. */
@Repository
public class JdbcKtoPlaceSnapshotStore implements KtoPlaceSnapshotStore {

    private final JdbcTemplate jdbc;

    public JdbcKtoPlaceSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<KtoPlaceSnapshot> findFresh(KtoPlaceRequest request, Instant at) {
        return jdbc.query("""
                SELECT id, source_registry_version, collector_run_id, content_id, content_type_id, title,
                       category_code, area_code, sigungu_code, address, latitude, longitude, payload_hash,
                       fetched_at, stale_at
                  FROM kto_place_snapshots
                 WHERE source_code = ? AND content_id = ? AND content_type_id = ? AND stale_at > ?
                 ORDER BY fetched_at DESC, id DESC
                 LIMIT 1
                """, JdbcKtoPlaceSnapshotStore::snapshot, KtoPlaceSnapshot.SOURCE_CODE, request.contentId(),
                request.contentTypeId(), java.sql.Timestamp.from(at)).stream().findFirst();
    }

    @Override
    public void save(KtoPlaceSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO kto_place_snapshots
                    (id, source_code, source_registry_version, collector_run_id, content_id, content_type_id,
                     title, category_code, area_code, sigungu_code, address, latitude, longitude, payload_hash,
                     fetched_at, stale_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshot.id(), KtoPlaceSnapshot.SOURCE_CODE, snapshot.sourceRegistryVersion(),
                snapshot.collectorRunId(), snapshot.contentId(), snapshot.contentTypeId(), snapshot.title(),
                snapshot.categoryCode(), snapshot.areaCode(), snapshot.sigunguCode(), snapshot.address(),
                snapshot.latitude(), snapshot.longitude(), snapshot.payloadHash(),
                java.sql.Timestamp.from(snapshot.fetchedAt()), java.sql.Timestamp.from(snapshot.staleAt()),
                java.sql.Timestamp.from(snapshot.fetchedAt()));
    }

    private static KtoPlaceSnapshot snapshot(ResultSet result, int row) throws SQLException {
        return new KtoPlaceSnapshot(result.getObject("id", UUID.class), result.getLong("source_registry_version"),
                result.getObject("collector_run_id", UUID.class), result.getString("content_id"),
                result.getString("content_type_id"), result.getString("title"), result.getString("category_code"),
                result.getString("area_code"), result.getString("sigungu_code"), result.getString("address"),
                result.getBigDecimal("latitude"), result.getBigDecimal("longitude"), result.getString("payload_hash"),
                result.getTimestamp("fetched_at").toInstant(), result.getTimestamp("stale_at").toInstant());
    }
}
