package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPlaceSearchRequest;
import io.nullnull.catalog.infrastructure.persistence.JdbcCatalogPlaceQuery;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-086 A-03 English palace identity correction")
class EngTextIdentityCorrectionMigrationIT {

    private static final String SCHEMA = "ba086_identity_correction";
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final UUID GYEONGBOKGUNG = UUID.fromString("01a0b825-4f15-7e7b-b30c-87cf71861c9c");
    private static final UUID DEOKSUGUNG = UUID.fromString("01a0b9f7-8020-74c6-bdca-dac05aadc82e");
    private static final UUID BUKCHON = UUID.fromString("01a0b9f7-8138-7051-bfbb-0a6420647c52");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V051 removes only the two disproven links and their KTO English text, then serves Korean with Korean credit")
    void badLinksWithdrawBeforeAnyProviderRefresh() throws SQLException {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        try {
            Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                    .createSchemas(true).locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("050")).load().migrate();
            try (Connection connection = dataSource.getConnection()) {
                connection.setSchema(SCHEMA);
                JdbcTemplate scoped = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertThat(scoped.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
                seed(scoped, GYEONGBOKGUNG, "경복궁", "264329", "Gwanghwamun Gate (광화문)");
                seed(scoped, DEOKSUGUNG, "덕수궁", "1942577",
                        "Deoksugung Palace's Daehanmun Gate (덕수궁 대한문)");
                seed(scoped, BUKCHON, "북촌한옥마을", "561382", "Bukchon Hanok Village");

                assertThat(count(scoped, "place_localization_sources", GYEONGBOKGUNG)
                        + count(scoped, "place_localization_sources", DEOKSUGUNG)
                        + count(scoped, "place_localization_sources", BUKCHON)).isEqualTo(3);
                CatalogPlaceQuery places = new JdbcCatalogPlaceQuery(scoped);
                assertThat(places.find(GYEONGBOKGUNG, "en-US", NOW).orElseThrow().name())
                        .isEqualTo("Gwanghwamun Gate (광화문)");

                Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                        .locations("classpath:db/migration").load().migrate();

                for (UUID place : List.of(GYEONGBOKGUNG, DEOKSUGUNG)) {
                    assertThat(count(scoped, "place_localization_sources", place)).isZero();
                    assertThat(count(scoped, "place_localizations", place, "en")).isZero();
                    CatalogPlaceQuery.CatalogPlaceDetail detail = places.find(place, "en-US", NOW).orElseThrow();
                    assertThat(detail.name()).isEqualTo(place.equals(GYEONGBOKGUNG) ? "경복궁" : "덕수궁");
                    assertThat(detail.textProvenance().name().locale()).isEqualTo("ko-KR");
                    assertThat(detail.textProvenance().name().sourceAttribution().source())
                            .isEqualTo("KTO_KOR_SERVICE_2");
                    assertThat(places.summaries(List.of(place), "en-US", NOW).getFirst().name())
                            .isEqualTo(detail.name());
                }
                assertThat(places.search(CatalogPlaceSearchRequest.of("Gwanghwamun Gate", "en-US", null, null, 20),
                        null, 20, NOW)).noneMatch(hit -> hit.summary().id().equals(GYEONGBOKGUNG));
                assertThat(count(scoped, "place_localization_sources", BUKCHON)).isOne();
                assertThat(count(scoped, "place_localizations", BUKCHON, "en")).isOne();
                assertThat(places.find(BUKCHON, "en-US", NOW).orElseThrow().name())
                        .isEqualTo("Bukchon Hanok Village");
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static long count(JdbcTemplate jdbc, String table, UUID place) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE place_id = ?", Long.class, place);
    }

    private static long count(JdbcTemplate jdbc, String table, UUID place, String locale) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE place_id = ? AND locale = ?",
                Long.class, place, locale);
    }

    private static void seed(JdbcTemplate jdbc, UUID place, String korean, String englishId, String english) {
        Timestamp now = Timestamp.from(NOW);
        long koreanRevision = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = 'KTO_KOR_SERVICE_2'", Long.class);
        long englishRevision = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = 'KTO_ENG_SERVICE'", Long.class);
        jdbc.update("""
                INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code, status,
                                    created_at, updated_at)
                VALUES (?, ?, 'HS', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, place, korean, now, now);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, updated_at, source_code,
                                                 source_registry_version, source_locale, observed_at)
                VALUES (?, ?, 'ko-KR', ?, ?, 'KTO_KOR_SERVICE_2', ?, 'ko-KR', ?)
                """, UUID.randomUUID(), place, korean, now, koreanRevision, now);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, updated_at, source_code,
                                                 source_registry_version, source_locale, observed_at)
                VALUES (?, ?, 'en', ?, ?, 'KTO_ENG_SERVICE', ?, 'en', ?)
                """, UUID.randomUUID(), place, english, now, englishRevision, now);
        jdbc.update("""
                INSERT INTO place_localization_sources
                    (id, place_id, locale, source_code, external_id, external_type, reviewed_at, created_at,
                     updated_at)
                VALUES (?, ?, 'en', 'KTO_ENG_SERVICE', ?, 'KTO_CONTENT_TYPE:76', ?, ?, ?)
                """, UUID.randomUUID(), place, englishId, now, now, now);
    }
}
