package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * C3 schema safety only. No controller is registered here: BA-021-T3 still gates all public place
 * projection until the final staging deployment establishes actual KTO provenance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-022 canonical catalog foundation")
class CatalogFoundationIT {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyC3CatalogFixtures() {
        jdbc.update("DELETE FROM place_media_assets");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM asset_licenses");
        jdbc.update("DELETE FROM place_external_refs");
        jdbc.update("DELETE FROM place_localizations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-022-T1 one provider identity maps to one active canonical POI and bad redirects are rejected")
    void externalIdentityAndCanonicalTargetAreDatabaseEnforced() {
        UUID canonical = activePlace();
        UUID duplicate = activePlace();
        UUID deprecated = UUID.randomUUID();

        insertLocalization(canonical, "ko-KR", "서울 테스트 관광지");
        insertExternalReference(canonical, "264432", "CONTENT:12", 3);
        assertThatCode(() -> insertDeprecated(deprecated, canonical)).doesNotThrowAnyException();

        assertThatThrownBy(() -> insertExternalReference(duplicate, "264432", "CONTENT:12", 3))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertExternalReference(duplicate, "different", "CONTENT:12", 999))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertDeprecated(UUID.randomUUID(), deprecated))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, region_code, status, created_at, updated_at)
                VALUES (?, ?, 'invalid active redirect', 'A0101', '1', 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), canonical, timestamp(), timestamp()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, region_code, status, created_at, updated_at)
                VALUES (?, 'partial coordinate', 'A0101', 37.566535, '1', 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), timestamp(), timestamp()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLocalization(deprecated, "ko-KR", "old duplicate"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLocalization(canonical, "ko-KR", "duplicate locale"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("BA-022-T3 media cannot be served without a reviewed redistributable license")
    void mediaRightsBoundaryFailsClosed() {
        UUID place = activePlace();
        UUID nonRedistributableLicense = license(false);
        UUID originOnlyAsset = UUID.randomUUID();

        assertThatThrownBy(() -> media(UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> media(UUID.randomUUID(), nonRedistributableLicense,
                "https://cdn.example.test/forbidden.jpg"))
                .isInstanceOf(DataAccessException.class);
        assertThatCode(() -> media(originOnlyAsset, nonRedistributableLicense, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update("""
                INSERT INTO place_media_assets (place_id, media_asset_id, position) VALUES (?, ?, 0)
                """, place, originOnlyAsset)).doesNotThrowAnyException();

        UUID redistributableLicense = license(true);
        UUID servedAsset = UUID.randomUUID();
        assertThatCode(() -> media(servedAsset, redistributableLicense,
                "https://cdn.example.test/approved.jpg")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE asset_licenses SET redistribution_allowed = false WHERE id = ?
                """, redistributableLicense)).isInstanceOf(DataAccessException.class);
    }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, '서울 테스트 관광지', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, id, timestamp(), timestamp());
        return id;
    }

    private void insertDeprecated(UUID id, UUID canonicalPlaceId) {
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, region_code, status, created_at, updated_at)
                VALUES (?, ?, '폐기된 테스트 관광지', 'A0101', '1', 'DEPRECATED', ?, ?)
                """, id, canonicalPlaceId, timestamp(), timestamp());
    }

    private void insertLocalization(UUID placeId, String locale, String name) {
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), placeId, locale, name, timestamp());
    }

    private void insertExternalReference(UUID placeId, String externalId, String externalType,
            long sourceRegistryVersion) {
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, 'KTO_KOR_SERVICE_2', ?, ?, ?, ?)
                """, UUID.randomUUID(), placeId, sourceRegistryVersion, externalId, externalType, timestamp());
    }

    private UUID license(boolean redistributionAllowed) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asset_licenses
                    (id, source_code, source_registry_version, external_license_code, license_name, license_url,
                     attribution_template,
                     redistribution_allowed, derivative_allowed, reviewed_at)
                VALUES (?, 'KTO_KOR_SERVICE_2', 3, ?, 'fixture license', 'https://license.example.test/policy',
                        '출처: fixture', ?, false, ?)
                """, id, "fixture-" + id, redistributionAllowed, timestamp());
        return id;
    }

    private void media(UUID id, UUID licenseId, String servedUrl) {
        jdbc.update("""
                INSERT INTO media_assets
                    (id, asset_license_id, source_external_id, origin_url, served_url, checksum, media_type,
                     license_checked_at)
                VALUES (?, ?, 'fixture-media', 'https://origin.example.test/image.jpg', ?, ?, 'IMAGE', ?)
                """, id, licenseId, servedUrl, "a".repeat(64), timestamp());
    }

    private static java.sql.Timestamp timestamp() {
        return java.sql.Timestamp.from(NOW);
    }
}
