package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.EngTextLinkImporter;
import io.nullnull.catalog.application.EngTextLinkImporter.Link;
import io.nullnull.catalog.application.EngTextLinkImporter.Plan;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "nullnull.env=test")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-086 owner-reviewed English record links")
class EngTextLinkImportIT {

    private static final String EVIDENCE = "https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=review";

    @Autowired EngTextLinkImporter importer;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> places = new ArrayList<>();
    private final Instant reviewed = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    @AfterEach
    void removeOnlyThisClassRows() {
        // Shared-database gate: name every row, never a whole table (AGENTS rule 6).
        for (UUID place : places) {
            jdbc.update("DELETE FROM place_localization_sources WHERE place_id = ?", place);
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", place);
            jdbc.update("DELETE FROM place_external_refs WHERE place_id = ?", place);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
    }

    @Test
    @DisplayName("BA-086-T22 an older review cannot replace a newer link")
    void olderReviewCannotReplaceNewerLink() {
        UUID place = koreanPlace();
        String newer = contentId();
        importer.importPlan(new Plan(List.of(link(place, newer, reviewed.plusSeconds(3600)))));

        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(link(place, contentId(), reviewed)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer review");
        assertThat(linkedRecord(place)).isEqualTo(newer);
    }

    @Test
    @DisplayName("BA-086-T23 relinking to a different English record removes the previous record's text in the same transaction")
    void relinkRemovesThePreviousRecordsText() {
        UUID place = koreanPlace();
        importer.importPlan(new Plan(List.of(link(place, contentId(), reviewed))));
        englishText(place);
        String replacement = contentId();

        importer.importPlan(new Plan(List.of(link(place, replacement, reviewed.plusSeconds(60)))));

        assertThat(linkedRecord(place)).isEqualTo(replacement);
        assertThat(englishRows(place)).isZero();
    }

    @Test
    @DisplayName("re-importing the same decision keeps the English text it already produced")
    void sameDecisionAgainKeepsItsText() {
        UUID place = koreanPlace();
        String record = contentId();
        importer.importPlan(new Plan(List.of(link(place, record, reviewed))));
        englishText(place);

        importer.importPlan(new Plan(List.of(link(place, record, reviewed))));

        assertThat(linkedRecord(place)).isEqualTo(record);
        assertThat(englishRows(place)).isOne();
    }

    @Test
    @DisplayName("a plan that cannot be an owner decision on a Korean KTO place is refused whole")
    void refusesWhatCannotBeLinked() {
        UUID place = koreanPlace();
        UUID noKoreanRecord = placeWithoutKoreanRecord();
        UUID linkedElsewhere = koreanPlace();
        String taken = contentId();
        importer.importPlan(new Plan(List.of(link(linkedElsewhere, taken, reviewed))));

        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(
                link(place, contentId(), Instant.now().plusSeconds(600))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future review");
        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(link(UUID.randomUUID(), contentId(), reviewed)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown or inactive place");
        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(link(noKoreanRecord, contentId(), reviewed)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Korean KTO record");
        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(link(place, taken, reviewed)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another place");
        assertThatThrownBy(() -> new Link(place, contentId(), "76", reviewed, "http://example.test/page"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS evidence URL");
        // One bad entry refuses the plan: the good entry before it is not left behind.
        assertThatThrownBy(() -> importer.importPlan(new Plan(List.of(
                link(place, contentId(), reviewed), link(noKoreanRecord, contentId(), reviewed)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_localization_sources WHERE place_id = ?",
                Long.class, place)).isZero();
    }

    private Link link(UUID place, String contentId, Instant reviewedAt) {
        return new Link(place, contentId, "76", reviewedAt, EVIDENCE);
    }

    private String linkedRecord(UUID place) {
        return jdbc.queryForObject("SELECT external_id FROM place_localization_sources WHERE place_id = ? AND locale = 'en'",
                String.class, place);
    }

    private long englishRows(UUID place) {
        return jdbc.queryForObject("SELECT count(*) FROM place_localizations WHERE place_id = ? AND locale = 'en'",
                Long.class, place);
    }

    /** The English row a refresh writes: KTO_ENG_SERVICE provenance under its current revision. */
    private void englishText(UUID place) {
        long revision = jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = 'KTO_ENG_SERVICE'",
                Long.class);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, address, updated_at, source_code, source_registry_version,
                     source_locale, observed_at)
                VALUES (?, ?, 'en', 'Gyeongbokgung Palace', '161, Sajik-ro', ?, 'KTO_ENG_SERVICE', ?, 'en', ?)
                """, UUID.randomUUID(), place, now, revision, now);
    }

    private UUID koreanPlace() {
        UUID place = placeWithoutKoreanRecord();
        long revision = jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = 'KTO_KOR_SERVICE_2'",
                Long.class);
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, 'KTO_KOR_SERVICE_2', ?, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, UUID.randomUUID(), place, revision, contentId(), Timestamp.from(Instant.now()));
        return place;
    }

    private UUID placeWithoutKoreanRecord() {
        UUID place = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code, status,
                                    created_at, updated_at)
                VALUES (?, '경복궁', 'HS', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, place, now, now);
        places.add(place);
        return place;
    }

    private static String contentId() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    }
}
