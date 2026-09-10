package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal C3 mapping test; it uses normalized C2 evidence and performs no KTO HTTP call. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-022 normalized KTO snapshot to canonical catalog mapping")
class KtoSnapshotCatalogIngestIT {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-10T00:00:00Z");

    @Autowired
    CatalogIngest catalog;

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
    @DisplayName("BA-022-T1 the same normalized KTO identity converges on one canonical POI")
    void mapsOneExternalIdentityOnlyOnce() {
        CatalogPlace first = catalog.ingest(snapshot("A0101", "1"));
        CatalogPlace repeated = catalog.ingest(snapshot("A0101", "1"));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.canonicalName()).isEqualTo("서울 테스트 관광지");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_localizations", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_external_refs", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT external_type FROM place_external_refs WHERE external_id = '264432'
                """, String.class)).isEqualTo("KTO_CONTENT_TYPE:12");
    }

    @Test
    @DisplayName("BA-022-T1 unknown category or region is not invented into a canonical POI")
    void refusesIncompleteSnapshotWithoutWritingCatalogRows() {
        assertThatThrownBy(() -> catalog.ingest(snapshot(null, "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryCode");
        assertThatThrownBy(() -> catalog.ingest(snapshot("A0101", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("areaCode");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_external_refs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("BA-022-T1 an incomplete refresh cannot be accepted merely because its canonical POI exists")
    void refusesIncompleteSnapshotAfterTheExternalIdentityWasAlreadyMapped() {
        CatalogPlace existing = catalog.ingest(snapshot("A0101", "1"));

        assertThatThrownBy(() -> catalog.ingest(snapshot(null, "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryCode");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM places", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT id FROM places", java.util.UUID.class)).isEqualTo(existing.id());
    }

    private static KtoPlaceSnapshot snapshot(String category, String areaCode) {
        return KtoPlaceSnapshot.accepted(3, UUID.randomUUID(), "264432", "12", "서울 테스트 관광지", category,
                areaCode, "1", "서울특별시 종로구", new BigDecimal("37.566535"), new BigDecimal("126.978001"),
                FETCHED_AT, Duration.ofDays(7));
    }
}
