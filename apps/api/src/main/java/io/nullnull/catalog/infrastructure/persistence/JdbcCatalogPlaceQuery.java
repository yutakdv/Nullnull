package io.nullnull.catalog.infrastructure.persistence;

import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogExternalReferenceView;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogMediaAsset;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceDetail;
import io.nullnull.catalog.application.CatalogPlaceQuery.CatalogPlaceSummary;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The public read path only joins active, coordinate-complete canonical rows. Provider snapshots,
 * origin-only media URLs and raw provider payloads remain outside this projection.
 */
@Repository
public class JdbcCatalogPlaceQuery implements CatalogPlaceQuery {

    private final JdbcTemplate jdbc;

    public JdbcCatalogPlaceQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The summary projection: name by locale fallback, the approved source credit from the
     * reviewed registry revision, and a redistributable thumbnail. Shared by the search and the
     * by-id read so an embedded place can never be projected differently from a searched one -
     * a second copy of this would drift and one of the two callers would lose its attribution.
     */
    private static final String SUMMARY_PROJECTION = """
SELECT p.id,
                       COALESCE(exact_locale.name, language_locale.name, ko_locale.name, p.canonical_name) AS name,
                       p.category_code, p.region_code,
                       COALESCE(exact_locale.address, language_locale.address, ko_locale.address) AS address,
                       source_credit.source_code AS credit_source_code,
                       source_credit.source_registry_version AS credit_source_version,
                       source_credit.source_display_name AS credit_display_name,
                       source_credit.attribution AS credit_attribution,
                       source_credit.official_url AS credit_official_url,
                       source_credit.license_url AS credit_license_url,
                       source_credit.license_name AS credit_license_name,
                       thumbnail.served_url AS thumbnail_url
                  FROM places p
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = ?
                     ORDER BY id
                     LIMIT 1
                  ) exact_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND split_part(lower(locale), '-', 1) = ?
                     ORDER BY lower(locale), id
                     LIMIT 1
                  ) language_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = 'ko-kr'
                     ORDER BY id
                     LIMIT 1
                  ) ko_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT ref.source_code,
                           ref.source_registry_version,
                           revision.canonical_contract->>'displayName' AS source_display_name,
                           revision.canonical_contract->>'attributionTemplate' AS attribution,
                           revision.canonical_contract->>'officialUrl' AS official_url,
                           revision.canonical_contract->'license'->>'url' AS license_url,
                           revision.canonical_contract->'license'->>'name' AS license_name
                      FROM place_external_refs ref
                      JOIN source_registry_revisions revision
                        ON revision.source_code = ref.source_code
                       AND revision.version = ref.source_registry_version
                     WHERE ref.place_id = p.id
                     ORDER BY ref.verified_at, ref.id
                     LIMIT 1
                  ) source_credit ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT asset.served_url
                      FROM place_media_assets assignment
                      JOIN media_assets asset ON asset.id = assignment.media_asset_id
                      JOIN asset_licenses license ON license.id = asset.asset_license_id
                     WHERE assignment.place_id = p.id
                       AND asset.served_url IS NOT NULL
                       AND license.redistribution_allowed
                       AND (asset.expires_at IS NULL OR asset.expires_at > ?)
                     ORDER BY assignment.position, asset.id
                     LIMIT 1
                  ) thumbnail ON TRUE
                """;

    @Override
    public List<CatalogPlaceSummary> search(CatalogPlaceSearchRequest request, long offset, int fetchLimit,
            Instant observedAt) {
        String pattern = "%" + escapeLike(request.query().toLowerCase(java.util.Locale.ROOT)) + "%";
        return jdbc.query("""
                SELECT p.id,
                       COALESCE(exact_locale.name, language_locale.name, ko_locale.name, p.canonical_name) AS name,
                       p.category_code, p.region_code,
                       COALESCE(exact_locale.address, language_locale.address, ko_locale.address) AS address,
                       source_credit.source_code AS credit_source_code,
                       source_credit.source_registry_version AS credit_source_version,
                       source_credit.source_display_name AS credit_display_name,
                       source_credit.attribution AS credit_attribution,
                       source_credit.official_url AS credit_official_url,
                       source_credit.license_url AS credit_license_url,
                       source_credit.license_name AS credit_license_name,
                       thumbnail.served_url AS thumbnail_url
                  FROM places p
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = ?
                     ORDER BY id
                     LIMIT 1
                  ) exact_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND split_part(lower(locale), '-', 1) = ?
                     ORDER BY lower(locale), id
                     LIMIT 1
                  ) language_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = 'ko-kr'
                     ORDER BY id
                     LIMIT 1
                  ) ko_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT ref.source_code,
                           ref.source_registry_version,
                           revision.canonical_contract->>'displayName' AS source_display_name,
                           revision.canonical_contract->>'attributionTemplate' AS attribution,
                           revision.canonical_contract->>'officialUrl' AS official_url,
                           revision.canonical_contract->'license'->>'url' AS license_url,
                           revision.canonical_contract->'license'->>'name' AS license_name
                      FROM place_external_refs ref
                      JOIN source_registry_revisions revision
                        ON revision.source_code = ref.source_code
                       AND revision.version = ref.source_registry_version
                     WHERE ref.place_id = p.id
                     ORDER BY ref.verified_at, ref.id
                     LIMIT 1
                  ) source_credit ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT asset.served_url
                      FROM place_media_assets assignment
                      JOIN media_assets asset ON asset.id = assignment.media_asset_id
                      JOIN asset_licenses license ON license.id = asset.asset_license_id
                     WHERE assignment.place_id = p.id
                       AND asset.served_url IS NOT NULL
                       AND license.redistribution_allowed
                       AND (asset.expires_at IS NULL OR asset.expires_at > ?)
                     ORDER BY assignment.position, asset.id
                     LIMIT 1
                  ) thumbnail ON TRUE
                 WHERE p.status = 'ACTIVE'
                   AND p.latitude IS NOT NULL
                   AND p.longitude IS NOT NULL
                   AND p.region_code = COALESCE(?, p.region_code)
                   AND (lower(p.canonical_name) LIKE ? ESCAPE '\\'
                        OR EXISTS (
                          SELECT 1
                            FROM place_localizations searchable
                           WHERE searchable.place_id = p.id
                             AND lower(searchable.name) LIKE ? ESCAPE '\\'
                        ))
                 ORDER BY lower(COALESCE(exact_locale.name, language_locale.name, ko_locale.name, p.canonical_name)),
                          p.id
                 LIMIT ? OFFSET ?
                """, JdbcCatalogPlaceQuery::summary, request.locale(), request.language(), Timestamp.from(observedAt),
                request.regionCode(), pattern, pattern, fetchLimit, offset);
    }

    @Override
    public List<CatalogPlaceSummary> summaries(List<UUID> placeIds, String locale, Instant observedAt) {
        if (placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        CatalogPlaceSearchRequest normalized = CatalogPlaceSearchRequest.of("x", locale, null, null, 1);
        // A requested id is resolved through canonical_place_id before the ACTIVE filter, so a
        // deprecated id still yields its canonical row. Filtering first would drop the place from
        // the embedding entirely, and FeedCard.primaryPlace is required - a post whose place was
        // later merged would stop producing a valid card at all.
        return jdbc.query(SUMMARY_PROJECTION + """
                 WHERE p.status = 'ACTIVE'
                   AND p.id IN (SELECT COALESCE(requested.canonical_place_id, requested.id)
                                  FROM places requested
                                 WHERE requested.id = ANY (?))
                 ORDER BY p.id
                """, JdbcCatalogPlaceQuery::summary, normalized.locale(), normalized.language(),
                Timestamp.from(observedAt), placeIds.toArray(UUID[]::new));
    }

    @Override
    public Optional<CatalogPlaceDetail> find(UUID requestedPlaceId, String locale, Instant observedAt) {
        String normalizedLocale = CatalogPlaceSearchRequest.of("x", locale, null, null, 1).locale();
        String language = normalizedLocale.substring(0, normalizedLocale.indexOf('-') < 0
                ? normalizedLocale.length() : normalizedLocale.indexOf('-'));
        List<DetailFields> fields = jdbc.query("""
                SELECT p.id,
                       COALESCE(exact_locale.name, language_locale.name, ko_locale.name, p.canonical_name) AS name,
                       p.category_code, p.region_code,
                       COALESCE(exact_locale.address, language_locale.address, ko_locale.address) AS address,
                       COALESCE(exact_locale.short_description, language_locale.short_description,
                                ko_locale.short_description) AS description,
                       p.latitude, p.longitude,
                       source_credit.source_code AS credit_source_code,
                       source_credit.source_registry_version AS credit_source_version,
                       source_credit.source_display_name AS credit_display_name,
                       source_credit.attribution AS credit_attribution,
                       source_credit.official_url AS credit_official_url,
                       source_credit.license_url AS credit_license_url,
                       source_credit.license_name AS credit_license_name,
                       thumbnail.served_url AS thumbnail_url
                  FROM places requested
                  JOIN places p ON p.id = COALESCE(requested.canonical_place_id, requested.id)
                  LEFT JOIN LATERAL (
                    SELECT name, address, short_description
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = ?
                     ORDER BY id
                     LIMIT 1
                  ) exact_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address, short_description
                      FROM place_localizations
                     WHERE place_id = p.id AND split_part(lower(locale), '-', 1) = ?
                     ORDER BY lower(locale), id
                     LIMIT 1
                  ) language_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT name, address, short_description
                      FROM place_localizations
                     WHERE place_id = p.id AND lower(locale) = 'ko-kr'
                     ORDER BY id
                     LIMIT 1
                  ) ko_locale ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT ref.source_code,
                           ref.source_registry_version,
                           revision.canonical_contract->>'displayName' AS source_display_name,
                           revision.canonical_contract->>'attributionTemplate' AS attribution,
                           revision.canonical_contract->>'officialUrl' AS official_url,
                           revision.canonical_contract->'license'->>'url' AS license_url,
                           revision.canonical_contract->'license'->>'name' AS license_name
                      FROM place_external_refs ref
                      JOIN source_registry_revisions revision
                        ON revision.source_code = ref.source_code
                       AND revision.version = ref.source_registry_version
                     WHERE ref.place_id = p.id
                     ORDER BY ref.verified_at, ref.id
                     LIMIT 1
                  ) source_credit ON TRUE
                  LEFT JOIN LATERAL (
                    SELECT asset.served_url
                      FROM place_media_assets assignment
                      JOIN media_assets asset ON asset.id = assignment.media_asset_id
                      JOIN asset_licenses license ON license.id = asset.asset_license_id
                     WHERE assignment.place_id = p.id
                       AND asset.served_url IS NOT NULL
                       AND license.redistribution_allowed
                       AND (asset.expires_at IS NULL OR asset.expires_at > ?)
                     ORDER BY assignment.position, asset.id
                     LIMIT 1
                  ) thumbnail ON TRUE
                 WHERE requested.id = ?
                   AND p.status = 'ACTIVE'
                   AND p.latitude IS NOT NULL
                   AND p.longitude IS NOT NULL
                """, JdbcCatalogPlaceQuery::detailFields, normalizedLocale, language, Timestamp.from(observedAt),
                requestedPlaceId);
        return fields.stream().findFirst().map(detail -> new CatalogPlaceDetail(detail.id, detail.name,
                detail.categoryCode, detail.regionCode, detail.categoryName, detail.regionName, detail.thumbnailUrl,
                detail.address, detail.description, detail.latitude, detail.longitude, externalReferences(detail.id),
                media(detail.id, observedAt).orElse(null), detail.sourceAttribution));
    }

    private List<CatalogExternalReferenceView> externalReferences(UUID placeId) {
        return jdbc.query("""
                SELECT source_code, external_id, verified_at
                  FROM place_external_refs
                 WHERE place_id = ?
                 ORDER BY source_code, external_type, external_id
                 LIMIT 20
                """, (result, row) -> new CatalogExternalReferenceView(result.getString("source_code"),
                result.getString("external_id"), result.getTimestamp("verified_at").toInstant()), placeId);
    }

    private Optional<CatalogMediaAsset> media(UUID placeId, Instant observedAt) {
        return jdbc.query("""
                SELECT asset.id, asset.served_url, asset.media_type, asset.alt_text, asset.expires_at,
                       license.source_code, license.license_name, license.license_url, license.reviewed_at,
                       license.attribution_template, license.redistribution_allowed
                  FROM place_media_assets assignment
                  JOIN media_assets asset ON asset.id = assignment.media_asset_id
                  JOIN asset_licenses license ON license.id = asset.asset_license_id
                 WHERE assignment.place_id = ?
                   AND asset.served_url IS NOT NULL
                   AND license.redistribution_allowed
                   AND (asset.expires_at IS NULL OR asset.expires_at > ?)
                 ORDER BY assignment.position, asset.id
                 LIMIT 1
                """, (result, row) -> new CatalogMediaAsset(result.getObject("id", UUID.class),
                result.getString("served_url"), result.getString("media_type"), result.getString("alt_text"),
                result.getString("source_code"), result.getString("license_name"), result.getString("license_url"),
                result.getTimestamp("reviewed_at").toInstant(), result.getString("attribution_template") != null,
                result.getString("attribution_template"), result.getBoolean("redistribution_allowed"),
                timestamp(result, "expires_at")), placeId, Timestamp.from(observedAt)).stream().findFirst();
    }

    private static CatalogPlaceSummary summary(ResultSet result, int row) throws SQLException {
        return new CatalogPlaceSummary(result.getObject("id", UUID.class), result.getString("name"),
                result.getString("category_code"), result.getString("region_code"), categoryName(result),
                regionName(result), result.getString("thumbnail_url"), result.getString("address"),
                sourceAttribution(result));
    }

    /**
     * Display names stay null until a reviewed code-to-label mapping exists for the provider's
     * classification and region systems. Null tells the client to show nothing; it never means
     * "unknown category", and no label is guessed from the raw provider code.
     */
    private static String categoryName(ResultSet result) throws SQLException {
        return null;
    }

    private static String regionName(ResultSet result) throws SQLException {
        return null;
    }

    /** A partial credit is worse than none: both the source and its approved text must be present. */
    private static CatalogPlaceQuery.CatalogSourceAttribution sourceAttribution(ResultSet result) throws SQLException {
        String source = result.getString("credit_source_code");
        String attribution = result.getString("credit_attribution");
        String displayName = result.getString("credit_display_name");
        long version = result.getLong("credit_source_version");
        if (source == null || attribution == null || displayName == null || version < 1) {
            return null;
        }
        return new CatalogPlaceQuery.CatalogSourceAttribution(source, displayName, version, attribution,
                result.getString("credit_official_url"), result.getString("credit_license_url"),
                result.getString("credit_license_name"));
    }

    private static DetailFields detailFields(ResultSet result, int row) throws SQLException {
        return new DetailFields(result.getObject("id", UUID.class), result.getString("name"),
                result.getString("category_code"), result.getString("region_code"), categoryName(result),
                regionName(result), result.getString("thumbnail_url"), result.getString("address"),
                result.getString("description"), result.getBigDecimal("latitude"), result.getBigDecimal("longitude"),
                sourceAttribution(result));
    }

    private static Instant timestamp(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record DetailFields(UUID id, String name, String categoryCode, String regionCode, String categoryName,
            String regionName, String thumbnailUrl, String address, String description, BigDecimal latitude,
            BigDecimal longitude, CatalogPlaceQuery.CatalogSourceAttribution sourceAttribution) {
    }
}
