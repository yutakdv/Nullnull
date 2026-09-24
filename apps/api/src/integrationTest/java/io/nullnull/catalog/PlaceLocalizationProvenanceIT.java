package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-086: a localization row carries the source, reviewed revision, language and observation time of
 * its own text (V047), and the read path publishes it only while that revision is still the source's
 * current one and the source is still enabled.
 *
 * <p><strong>No source_registry row is created or edited here.</strong> The two conjuncts of the gate
 * are isolated with sources the migrations already ship: {@code KTO_KOR_SERVICE_2} is enabled and has
 * been bumped several times, so a superseded revision to point at exists without inventing one, and
 * {@code KTO_RELATED_PLACES} is disabled at its own current revision, so pinning to it satisfies the
 * revision conjunct and leaves {@code enabled} as the only thing that can withdraw the row. Both of
 * those properties are ASSERTED rather than assumed: a later migration that bumps or enables either
 * source would otherwise turn one of these tests into a duplicate of the other, silently.
 *
 * <p>Every case is measured at all three entry points that project a localized name - the detail
 * read, the embedded summary and the search match. They are three different SQL sites, and a gate
 * removed from one of them is exactly the drift a single-entry-point test would not see.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-086 localization provenance")
class PlaceLocalizationProvenanceIT {

    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    /** Enabled, and bumped by V008, V009 and V012 - so it has a superseded revision to point at. */
    private static final String ENABLED_SOURCE = "KTO_KOR_SERVICE_2";

    /** Shipped DISABLED by V007 and never bumped since. */
    private static final String DISABLED_SOURCE = "KTO_RELATED_PLACES";

    // Three distinct strings, so a fallback is distinguishable from a second fallback: when the
    // English row is withdrawn the answer must be the Korean LOCALIZATION, not the canonical name.
    private static final String CANONICAL_NAME = "널널 BA086 정본 이름";
    private static final String KOREAN_NAME = "널널 BA086 한국어 이름";
    private static final String ENGLISH_NAME = "Nullnull BA086 English Name";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CatalogPlaceQuery places;

    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void notePlacesAlreadyPresent() {
        placesBefore = OwnedRows.snapshot(jdbc, "places");
    }

    @AfterEach
    void removeOnlyThisClassesPlaces() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
    }

    @Test
    @DisplayName("BA-086-T6 a localization with no provenance still goes out when the place's own source "
            + "reference sits on a superseded revision")
    void unprovenancedTextIsNotResolvedThroughThePlacesExternalReference() {
        long current = currentRevision(ENABLED_SOURCE);
        assertThat(current).isGreaterThan(1L);

        UUID place = activePlace();
        insertLocalization(place, "ko-KR", KOREAN_NAME, null);
        insertLocalization(place, "en-US", ENGLISH_NAME, null);
        // The PLACE was collected under a revision the source has since moved past. That is a fact
        // about the place record, and it says nothing about who wrote the English sentence - so
        // reading the text's source out of this row is the fabrication the clause forbids, and the
        // English text must be unaffected by it.
        insertExternalReference(place, current - 1);

        assertServed(place, ENGLISH_NAME, true);
    }

    @Test
    @DisplayName("BA-086-T7 a localization stops going out once its source moves past the revision it was "
            + "collected under")
    void supersededRevisionWithdrawsTheLocalization() {
        long current = currentRevision(ENABLED_SOURCE);
        assertThat(current).isGreaterThan(1L);
        // Otherwise the other conjunct could be doing the work and this would not be a measurement.
        assertThat(isEnabled(ENABLED_SOURCE)).isTrue();

        UUID place = activePlace();
        // The fallback row carries provenance of its own, which is what an ingested Korean row
        // actually looks like. It also keeps the blast radius of a mutation honest: with a null
        // fallback row, deleting ANY conjunct of the gate would redden this test too.
        insertLocalization(place, "ko-KR", KOREAN_NAME, new Provenance(ENABLED_SOURCE, current, "ko-KR"));
        UUID english = insertLocalization(place, "en-US", ENGLISH_NAME,
                new Provenance(ENABLED_SOURCE, current, "en-US"));
        assertServed(place, ENGLISH_NAME, true);

        // The only thing that changes is the revision this one row is pinned to.
        jdbc.update("UPDATE place_localizations SET source_registry_version = ? WHERE id = ?",
                current - 1, english);

        assertServed(place, KOREAN_NAME, false);
    }

    @Test
    @DisplayName("BA-086-T8 a localization stops going out once its source is no longer enabled")
    void disabledSourceWithdrawsTheLocalization() {
        UUID place = activePlace();
        long current = currentRevision(ENABLED_SOURCE);
        insertLocalization(place, "ko-KR", KOREAN_NAME, new Provenance(ENABLED_SOURCE, current, "ko-KR"));
        UUID english = insertLocalization(place, "en-US", ENGLISH_NAME,
                new Provenance(ENABLED_SOURCE, current, "en-US"));
        assertServed(place, ENGLISH_NAME, true);

        // Pinned to the disabled source's OWN current revision, so the revision conjunct is
        // satisfied and `enabled` is the only thing left that can withdraw the row.
        long disabledCurrent = currentRevision(DISABLED_SOURCE);
        assertThat(isEnabled(DISABLED_SOURCE)).isFalse();
        jdbc.update("UPDATE place_localizations SET source_code = ?, source_registry_version = ? WHERE id = ?",
                DISABLED_SOURCE, disabledCurrent, english);

        assertServed(place, KOREAN_NAME, false);
    }

    @Test
    @DisplayName("BA-086-T19 a localization withdrawn by the read gate does not surface through textProvenance "
            + "either")
    void withdrawnLocalizationLeavesNoTextProvenance() {
        long disabledCurrent = currentRevision(DISABLED_SOURCE);
        assertThat(isEnabled(DISABLED_SOURCE)).isFalse();

        UUID place = activePlace();
        // The Korean row is the only row carrying an address and a description, and the gate withdraws
        // it. So address and description have no servable source at all: the values are null, and the
        // provenance must be null with them. A provenance chosen from the withdrawn row would publish
        // the locale and the credit of a source the gate had just refused - the credit of a disabled
        // source, beside a field that is empty.
        insertTextRow(place, "ko-KR", KOREAN_NAME, "널널 BA086 주소", "널널 BA086 설명",
                new Provenance(DISABLED_SOURCE, disabledCurrent, "ko-KR"));
        insertLocalization(place, "en-US", ENGLISH_NAME,
                new Provenance(ENABLED_SOURCE, currentRevision(ENABLED_SOURCE), "en-US"));

        CatalogPlaceQuery.CatalogPlaceDetail detail = places.find(place, "en-US", NOW).orElseThrow();
        assertThat(detail.address()).isNull();
        assertThat(detail.description()).isNull();
        assertThat(detail.textProvenance().name().locale()).isEqualTo("en-US");
        assertThat(detail.textProvenance().address()).isNull();
        assertThat(detail.textProvenance().description()).isNull();

        CatalogPlaceQuery.CatalogPlaceSummary summary =
                places.summaries(List.of(place), "en-US", NOW).getFirst();
        assertThat(summary.address()).isNull();
        assertThat(summary.textProvenance().address()).isNull();

        CatalogPlaceQuery.CatalogPlaceSummary hit = places
                .search(CatalogPlaceSearchRequest.of(ENGLISH_NAME, "en-US", null, null, 50), null, 50, NOW)
                .stream().map(CatalogPlaceQuery.CatalogPlaceSearchHit::summary)
                .filter(found -> found.id().equals(place)).findFirst().orElseThrow();
        assertThat(hit.address()).isNull();
        assertThat(hit.textProvenance().address()).isNull();
    }

    @Test
    @DisplayName("BA-086-T9 localization provenance is all four columns or none")
    void provenanceIsAllFourColumnsOrNone() {
        UUID place = activePlace();
        long current = currentRevision(ENABLED_SOURCE);

        assertThatCode(() -> insertLocalization(place, "ko-KR", KOREAN_NAME, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertLocalization(place, "en-US", ENGLISH_NAME,
                new Provenance(ENABLED_SOURCE, current, "en-US"))).doesNotThrowAnyException();

        // Each column missing on its own. A source claim with no reviewed contract behind it, a
        // revision referencing nothing, text with no language, and text with no observation time
        // are four different half-rows, and the composite foreign key catches none of them: it is
        // MATCH SIMPLE, so it is not enforced at all while any referencing column is null.
        assertThatThrownBy(() -> insertPartial(place, "fr-FR", null, current, "en-US", NOW))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertPartial(place, "de-DE", ENABLED_SOURCE, null, "en-US", NOW))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertPartial(place, "es-ES", ENABLED_SOURCE, current, null, NOW))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertPartial(place, "it-IT", ENABLED_SOURCE, current, "en-US", null))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * What every read entry point answers for this place under {@code en-US}: the detail name, the
     * embedded summary name, and whether searching for the English term finds it at all.
     *
     * <p>The search leaf is a different shape on purpose. A withdrawn row must not merely be absent
     * from the projection - it must stop MATCHING, or a search for the English term would return a
     * place whose name does not contain it and nothing in the response would explain why.
     */
    private void assertServed(UUID placeId, String expectedName, boolean expectedToMatchEnglishTerm) {
        assertThat(places.find(placeId, "en-US", NOW)).get()
                .extracting(CatalogPlaceQuery.CatalogPlaceDetail::name).isEqualTo(expectedName);
        assertThat(places.summaries(List.of(placeId), "en-US", NOW))
                .singleElement().extracting(CatalogPlaceQuery.CatalogPlaceSummary::name).isEqualTo(expectedName);
        assertThat(searchFindsIt(placeId)).isEqualTo(expectedToMatchEnglishTerm);
    }

    private boolean searchFindsIt(UUID placeId) {
        return places.search(CatalogPlaceSearchRequest.of(ENGLISH_NAME, "en-US", null, null, 50), null, 50, NOW)
                .stream().anyMatch(hit -> hit.summary().id().equals(placeId));
    }

    private long currentRevision(String sourceCode) {
        Long revision = jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = ?",
                Long.class, sourceCode);
        assertThat(revision).as("source %s must exist", sourceCode).isNotNull();
        return revision;
    }

    private boolean isEnabled(String sourceCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM source_registry WHERE code = ?",
                Boolean.class, sourceCode));
    }

    private record Provenance(String sourceCode, long sourceRegistryVersion, String sourceLocale) { }

    private UUID activePlace() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, id, CANONICAL_NAME, timestamp(NOW), timestamp(NOW));
        return id;
    }

    private UUID insertLocalization(UUID placeId, String locale, String name, Provenance provenance) {
        UUID id = UUID.randomUUID();
        insertRow(id, placeId, locale, name,
                provenance == null ? null : provenance.sourceCode(),
                provenance == null ? null : provenance.sourceRegistryVersion(),
                provenance == null ? null : provenance.sourceLocale(),
                provenance == null ? null : NOW);
        return id;
    }

    private void insertTextRow(UUID placeId, String locale, String name, String address, String description,
            Provenance provenance) {
        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, address, short_description, updated_at,
                     source_code, source_registry_version, source_locale, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), placeId, locale, name, address, description, timestamp(NOW),
                provenance.sourceCode(), provenance.sourceRegistryVersion(), provenance.sourceLocale(),
                timestamp(NOW));
    }

    private void insertPartial(UUID placeId, String locale, String sourceCode, Long version,
            String sourceLocale, Instant observedAt) {
        insertRow(UUID.randomUUID(), placeId, locale, ENGLISH_NAME, sourceCode, version, sourceLocale, observedAt);
    }

    private void insertRow(UUID id, UUID placeId, String locale, String name, String sourceCode,
            Long version, String sourceLocale, Instant observedAt) {
        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, updated_at,
                     source_code, source_registry_version, source_locale, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, placeId, locale, name, timestamp(NOW), sourceCode, version, sourceLocale,
                observedAt == null ? null : timestamp(observedAt));
    }

    private void insertExternalReference(UUID placeId, long sourceRegistryVersion) {
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, ?, ?, 'CONTENT:12', ?)
                """, UUID.randomUUID(), placeId, ENABLED_SOURCE, sourceRegistryVersion,
                "ba086-" + UUID.randomUUID(), timestamp(NOW));
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
