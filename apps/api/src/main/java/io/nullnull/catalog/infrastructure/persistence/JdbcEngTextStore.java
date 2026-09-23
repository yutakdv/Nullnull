package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.EngLinkRule;
import io.nullnull.catalog.application.EngTextStore;
import io.nullnull.catalog.application.KtoEngRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEngTextStore implements EngTextStore {

    private static final String SOURCE = KtoEngRecord.SOURCE_CODE;
    private static final String CONTENT_TYPE_PREFIX = "KTO_CONTENT_TYPE:";
    /**
     * The Korean snapshot revision from which lDongSignguCd is the code in sigungu_code. V012 (revision 4)
     * switched the Korean reader from the retired areacode/sigungucode fields to lDongRegnCd/lDongSignguCd,
     * and earlier snapshots keep the meaning they were collected under (SOURCE_CATALOG KTO_KOR_SERVICE_2).
     * A later Korean revision is accepted: requiring the CURRENT one would withdraw every English text at
     * the next Korean bump, until the Korean refresh caught up.
     */
    private static final long KOREAN_LEGAL_DONG_REVISION = 4;

    private final JdbcTemplate jdbc;

    public JdbcEngTextStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Link> links() {
        return jdbc.query("""
                SELECT place_id, external_id, external_type, reviewed_at
                  FROM place_localization_sources
                 WHERE source_code = ? AND locale = ?
                 ORDER BY place_id
                """, JdbcEngTextStore::link, SOURCE, LOCALE);
    }

    @Override
    public Optional<Link> lockLink(UUID placeId) {
        return jdbc.query("""
                SELECT place_id, external_id, external_type, reviewed_at
                  FROM place_localization_sources
                 WHERE place_id = ? AND locale = ? AND source_code = ?
                   FOR UPDATE
                """, JdbcEngTextStore::link, placeId, LOCALE, SOURCE).stream().findFirst();
    }

    @Override
    public Optional<UUID> linkedPlace(String externalId, String contentTypeId) {
        return jdbc.query("""
                SELECT place_id FROM place_localization_sources
                 WHERE source_code = ? AND external_id = ? AND external_type = ?
                """, (row, ignored) -> row.getObject(1, UUID.class), SOURCE, externalId,
                CONTENT_TYPE_PREFIX + contentTypeId).stream().findFirst();
    }

    @Override
    public Optional<PlaceSide> lockActivePlace(UUID placeId) {
        return jdbc.query("""
                SELECT p.latitude, p.longitude, p.category_code, p.region_code,
                       (SELECT snapshot.sigungu_code
                          FROM place_external_refs ref
                          JOIN kto_place_snapshots snapshot
                            ON snapshot.source_code = ref.source_code
                           AND snapshot.content_id = ref.external_id
                           AND ref.external_type = 'KTO_CONTENT_TYPE:' || snapshot.content_type_id
                         WHERE ref.place_id = p.id
                           AND ref.source_code = 'KTO_KOR_SERVICE_2'
                           AND snapshot.source_registry_version >= ?
                         ORDER BY snapshot.fetched_at DESC, snapshot.id DESC
                         LIMIT 1) AS sigungu_code,
                       EXISTS (SELECT 1 FROM place_external_refs korean
                                WHERE korean.place_id = p.id
                                  AND korean.source_code = 'KTO_KOR_SERVICE_2') AS has_korean_record
                  FROM places p
                 WHERE p.id = ? AND p.status = 'ACTIVE' AND p.canonical_place_id IS NULL
                   FOR UPDATE OF p
                """, (row, ignored) -> new PlaceSide(new EngLinkRule.PlaceFacts(row.getBigDecimal("latitude"),
                        row.getBigDecimal("longitude"), row.getString("category_code"), row.getString("region_code"),
                        row.getString("sigungu_code")), row.getBoolean("has_korean_record")),
                KOREAN_LEGAL_DONG_REVISION, placeId).stream().findFirst();
    }

    @Override
    public void saveLink(Link link, Instant now) {
        jdbc.update("""
                INSERT INTO place_localization_sources
                    (id, place_id, locale, source_code, external_id, external_type, reviewed_at, created_at,
                     updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (place_id, locale) DO UPDATE
                   SET source_code = EXCLUDED.source_code,
                       external_id = EXCLUDED.external_id,
                       external_type = EXCLUDED.external_type,
                       reviewed_at = EXCLUDED.reviewed_at,
                       updated_at = EXCLUDED.updated_at
                """, UUID.randomUUID(), link.placeId(), LOCALE, SOURCE, link.externalId(),
                CONTENT_TYPE_PREFIX + link.contentTypeId(), Timestamp.from(link.reviewedAt()), Timestamp.from(now),
                Timestamp.from(now));
    }

    @Override
    public boolean writeText(UUID placeId, KtoEngRecord record, long sourceRegistryVersion, Instant observedAt,
            Instant now) {
        // short_description is never written: the Korean side carries none, and an English-only
        // description would be facts the canonical record does not have (BA-086-T1).
        return jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, short_description, address, search_tokens, updated_at,
                     source_code, source_registry_version, source_locale, observed_at)
                VALUES (?, ?, ?, ?, NULL, ?, '[]'::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (place_id, locale) DO UPDATE
                   SET name = EXCLUDED.name,
                       short_description = NULL,
                       address = EXCLUDED.address,
                       updated_at = EXCLUDED.updated_at,
                       source_code = EXCLUDED.source_code,
                       source_registry_version = EXCLUDED.source_registry_version,
                       source_locale = EXCLUDED.source_locale,
                       observed_at = EXCLUDED.observed_at
                 WHERE place_localizations.source_code = ?
                """, UUID.randomUUID(), placeId, LOCALE, record.title(), record.address(), Timestamp.from(now),
                SOURCE, sourceRegistryVersion, LOCALE, Timestamp.from(observedAt), SOURCE) == 1;
    }

    @Override
    public boolean removeText(UUID placeId) {
        return jdbc.update("DELETE FROM place_localizations WHERE place_id = ? AND locale = ? AND source_code = ?",
                placeId, LOCALE, SOURCE) > 0;
    }

    private static Link link(ResultSet row, int ignored) throws SQLException {
        String type = row.getString("external_type");
        if (!type.startsWith(CONTENT_TYPE_PREFIX)) {
            throw new IllegalStateException("an English link carries an external type this reader does not know");
        }
        return new Link(row.getObject("place_id", UUID.class), row.getString("external_id"),
                type.substring(CONTENT_TYPE_PREFIX.length()), row.getTimestamp("reviewed_at").toInstant());
    }
}
