package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CanonicalCatalogStore;
import io.nullnull.catalog.domain.CatalogExternalReference;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.CatalogPlaceLocalization;
import io.nullnull.catalog.domain.CatalogPlaceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC catalog persistence keeps the external-ID uniqueness rule visible in SQL. If two workers
 * ingest the same normalized KTO snapshot, the unique reference row elects one canonical place and
 * the loser removes its otherwise-unreferenced candidate inside the surrounding transaction.
 */
@Repository
public class JdbcCanonicalCatalogStore implements CanonicalCatalogStore {

    private final JdbcTemplate jdbc;

    public JdbcCanonicalCatalogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CatalogPlace> findByExternalReference(String sourceCode, String externalId, String externalType) {
        return jdbc.query("""
                SELECT p.id, p.canonical_place_id, p.canonical_name, p.category_code, p.latitude, p.longitude,
                       p.region_code, p.status, p.created_at, p.updated_at
                  FROM place_external_refs external_ref
                  JOIN places p ON p.id = external_ref.place_id
                 WHERE external_ref.source_code = ?
                   AND external_ref.external_id = ?
                   AND external_ref.external_type = ?
                """, JdbcCanonicalCatalogStore::place, sourceCode, externalId, externalType).stream().findFirst();
    }

    @Override
    public CatalogPlace createIfAbsent(CatalogPlace place, CatalogPlaceLocalization localization,
            CatalogExternalReference externalReference) {
        requireSamePlace(place, localization, externalReference);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, latitude, longitude, region_code,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, place.id(), place.canonicalPlaceId(), place.canonicalName(), place.categoryCode(),
                place.latitude(), place.longitude(), place.regionCode(), place.status().name(),
                Timestamp.from(place.createdAt()), Timestamp.from(place.updatedAt()));

        int claimed = jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_code, external_id, external_type) DO NOTHING
                """, externalReference.id(), externalReference.placeId(), externalReference.sourceCode(),
                externalReference.sourceRegistryVersion(), externalReference.externalId(), externalReference.externalType(),
                Timestamp.from(externalReference.verifiedAt()));
        if (claimed == 0) {
            jdbc.update("DELETE FROM places WHERE id = ?", place.id());
            return findByExternalReference(externalReference.sourceCode(), externalReference.externalId(),
                    externalReference.externalType()).orElseThrow(() ->
                            new IllegalStateException("external reference winner disappeared"));
        }

        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, short_description, address, search_tokens, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '[]'::jsonb, ?)
                """, localization.id(), localization.placeId(), localization.locale(), localization.name(),
                localization.shortDescription(), localization.address(), Timestamp.from(localization.updatedAt()));
        return place;
    }

    private static CatalogPlace place(ResultSet result, int row) throws SQLException {
        return new CatalogPlace(result.getObject("id", UUID.class), result.getObject("canonical_place_id", UUID.class),
                result.getString("canonical_name"), result.getString("category_code"), result.getBigDecimal("latitude"),
                result.getBigDecimal("longitude"), result.getString("region_code"),
                CatalogPlaceStatus.valueOf(result.getString("status")), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }

    private static void requireSamePlace(CatalogPlace place, CatalogPlaceLocalization localization,
            CatalogExternalReference externalReference) {
        if (!place.id().equals(localization.placeId()) || !place.id().equals(externalReference.placeId())) {
            throw new IllegalArgumentException("catalog rows must share one place id");
        }
        if (!place.isCanonical()) {
            throw new IllegalArgumentException("external references may only claim an active canonical place");
        }
    }
}
