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
import io.nullnull.testsupport.OwnedRows;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

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


    /**
     * The places that were already there when this test started. Everything that appears after it
     * is this test's, and only that is removed - a blanket DELETE takes other classes' rows or, more
     * often, fails on one of the foreign keys that deliberately do not cascade (AGENTS.md rule 6).
     */
    private List<UUID> placesBefore = List.of();
    private List<UUID> mediaBefore = List.of();
    private List<UUID> licensesBefore = List.of();


    @BeforeEach
    void notePlacesAlreadyPresent() {
        placesBefore = jdbc.queryForList("SELECT id FROM places", UUID.class);
        mediaBefore = OwnedRows.snapshot(jdbc, "media_assets");
        licensesBefore = OwnedRows.snapshot(jdbc, "asset_licenses");
    }

    @AfterEach
    void removeOnlyC3CatalogFixtures() {
        OwnedRows.remove(jdbc, "places", placesCreatedHere());
        // After the places, because their place_media_assets rows point at these. Assets and
        // licences this test created, not "every asset no post is using": that WHERE reads like a
        // scope and is not one - it matched every other class's place media too, and once those
        // classes stopped clearing the join table for everyone it started failing outright.
        OwnedRows.remove(jdbc, "media_assets", OwnedRows.appeared(jdbc, "media_assets", mediaBefore));
        OwnedRows.remove(jdbc, "asset_licenses", OwnedRows.appeared(jdbc, "asset_licenses", licensesBefore));
    }

    @Test
    @DisplayName("BA-022-T1 the same normalized KTO identity converges on one canonical POI")
    void mapsOneExternalIdentityOnlyOnce() {
        CatalogPlace first = catalog.ingest(snapshot("A0101", "1"));
        CatalogPlace repeated = catalog.ingest(snapshot("A0101", "1"));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.canonicalName()).isEqualTo("서울 테스트 관광지");
        // Scoped to the rows this test made. "the table holds one" was measuring every class that
        // ran before it in the gate's shared database, where the answer was 216.
        assertThat(placesCreatedHere()).hasSize(1);
        assertThat(rowsFor("place_localizations")).isOne();
        assertThat(rowsFor("place_external_refs")).isOne();
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
        assertThat(placesCreatedHere()).isEmpty();
        assertThat(rowsFor("place_external_refs")).isZero();
    }

    @Test
    @DisplayName("BA-022-T1 an incomplete refresh cannot be accepted merely because its canonical POI exists")
    void refusesIncompleteSnapshotAfterTheExternalIdentityWasAlreadyMapped() {
        CatalogPlace existing = catalog.ingest(snapshot("A0101", "1"));

        assertThatThrownBy(() -> catalog.ingest(snapshot(null, "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryCode");
        assertThat(placesCreatedHere()).containsExactly(existing.id());
    }

    /** The places that appeared while this test ran - the ones it is entitled to make claims about. */
    private List<UUID> placesCreatedHere() {
        List<UUID> mine = new ArrayList<>(jdbc.queryForList("SELECT id FROM places", UUID.class));
        mine.removeAll(placesBefore);
        return mine;
    }

    /** Rows of a place-owned table belonging to the places this test made. */
    private int rowsFor(String table) {
        List<UUID> mine = placesCreatedHere();
        if (mine.isEmpty()) {
            return 0;
        }
        String placeholders = mine.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        Integer found = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE place_id IN ("
                + placeholders + ")", Integer.class, mine.toArray());
        return found == null ? 0 : found;
    }

    private static KtoPlaceSnapshot snapshot(String category, String areaCode) {
        return KtoPlaceSnapshot.accepted(3, UUID.randomUUID(), "264432", "12", "서울 테스트 관광지", category,
                areaCode, "1", "서울특별시 종로구", new BigDecimal("37.566535"), new BigDecimal("126.978001"),
                FETCHED_AT, Duration.ofDays(7));
    }
}
