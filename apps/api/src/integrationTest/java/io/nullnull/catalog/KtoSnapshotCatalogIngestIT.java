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
import java.util.Map;
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

    @Test
    @DisplayName("BA-086-T12 a cached snapshot on a superseded source revision is refused rather than pinned")
    void refusesASnapshotCollectedUnderASupersededSourceRevision() {
        long current = currentKtoRevision();
        // Without a superseded revision to point at there is nothing to refuse and this test would
        // be measuring the absence of a case rather than the guard.
        assertThat(current).isGreaterThan(1L);

        assertThatThrownBy(() -> catalog.ingest(snapshot(current - 1, "A0101", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current reviewed revision");
        // Refused before anything was written: a snapshot promoted on a stale pin would produce a
        // row the read path withdraws on sight, and nothing anywhere would say why it vanished.
        assertThat(placesCreatedHere()).isEmpty();
        assertThat(rowsFor("place_external_refs")).isZero();

        // The identical snapshot at the current revision is accepted, so the refusal is about the
        // revision and not about this fixture being unfit for some other reason.
        CatalogPlace accepted = catalog.ingest(snapshot(current, "A0101", "1"));
        assertThat(placesCreatedHere()).containsExactly(accepted.id());
    }

    @Test
    @DisplayName("BA-086-T13 an ingested place's Korean text carries the provenance the read gate reads")
    void ingestStampsTheProvenanceTheReadGateReads() {
        long current = currentKtoRevision();
        CatalogPlace place = catalog.ingest(snapshot("A0101", "1"));

        // This is the only thing that measures the gate's PRODUCER. Every other BA-086 test works
        // from rows inserted by hand, so dropping the provenance argument from
        // KtoSnapshotCatalogIngest.create would leave all of them green while turning the read
        // gate into the guard-with-no-producer this card exists to avoid - the shape this
        // repository already met in place_hours and place_relations.
        Map<String, Object> row = jdbc.queryForMap("SELECT source_code, source_registry_version,"
                + " source_locale, observed_at FROM place_localizations WHERE place_id = ?", place.id());
        assertThat(row.get("source_code")).isEqualTo("KTO_KOR_SERVICE_2");
        assertThat(((Number) row.get("source_registry_version")).longValue()).isEqualTo(current);
        // KorService2 publishes Korean, so this row is not a translation: source_locale equals the
        // locale it is stored under, and that equality is what "translated" is derived from.
        assertThat(row.get("source_locale")).isEqualTo("ko-KR");
        // The provider timeline, not the row timeline - observed_at is when the source was read.
        assertThat(((java.sql.Timestamp) row.get("observed_at")).toInstant()).isEqualTo(FETCHED_AT);
    }

    @Test
    @DisplayName("BA-086-T14 re-ingesting an already mapped place with a superseded snapshot is a no-op, not a refusal")
    void reIngestingAnAlreadyMappedPlaceWithASupersededSnapshotIsStillANoOp() {
        long current = currentKtoRevision();
        assertThat(current).isGreaterThan(1L);
        CatalogPlace existing = catalog.ingest(snapshot(current, "A0101", "1"));
        Long pinBefore = pinOf(existing);

        // KtoDemoRefresh.detail() makes this call for EVERY place on its list and its comment says
        // it relies on the call being idempotent for ones already mapped. findFresh filters only on
        // staleness, so for a whole freshness window after a revision bump it hands over snapshots
        // pinned to the previous revision. Refusing here would report every already-known place as
        // failed for something that writes nothing - which is what the first version of the guard
        // did, because it ran before the external-reference lookup instead of inside create().
        CatalogPlace again = catalog.ingest(snapshot(current - 1, "A0101", "1"));

        assertThat(again.id()).isEqualTo(existing.id());
        assertThat(placesCreatedHere()).containsExactly(existing.id());
        // And it really wrote nothing: the pin is UNCHANGED, rather than equal to some value. The
        // difference was measured - asserting the value made this test fail whenever the producer
        // stopped stamping provenance at all, which is BA-086-T13's clause, not this one. A no-op
        // is a statement about change.
        assertThat(pinOf(existing)).isEqualTo(pinBefore);
    }

    private Long pinOf(CatalogPlace place) {
        return jdbc.queryForObject("SELECT source_registry_version FROM place_localizations"
                + " WHERE place_id = ?", Long.class, place.id());
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

    /**
     * The revision is READ rather than written down. It used to be the literal 3 while the
     * migrations had already moved KTO to 4, which was harmless only because nothing in this class
     * read the snapshot back through the projection - BA-086-T12 now refuses exactly that mismatch,
     * so a literal here would pin this class to whichever revision was current the day it was typed.
     */
    private KtoPlaceSnapshot snapshot(String category, String areaCode) {
        return snapshot(currentKtoRevision(), category, areaCode);
    }

    private static KtoPlaceSnapshot snapshot(long sourceRegistryVersion, String category, String areaCode) {
        return KtoPlaceSnapshot.accepted(sourceRegistryVersion, UUID.randomUUID(), "264432", "12",
                "서울 테스트 관광지", category, areaCode, "1", "서울특별시 종로구",
                new BigDecimal("37.566535"), new BigDecimal("126.978001"), FETCHED_AT, Duration.ofDays(7));
    }

    private long currentKtoRevision() {
        Long revision = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = 'KTO_KOR_SERVICE_2'", Long.class);
        assertThat(revision).isNotNull();
        return revision;
    }
}
