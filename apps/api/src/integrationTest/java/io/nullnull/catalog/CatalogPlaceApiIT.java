package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-022's local route proof. The test enables the fail-closed projection flag only inside this
 * isolated PostgreSQL context; it performs no external provider call and does not publish anything.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-catalog-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, CatalogPlaceApiIT.Time.class})
@DisplayName("BA-022 canonical place HTTP projection")
class CatalogPlaceApiIT {

    private static final String SOURCE = "KTO_KOR_SERVICE_2";
    private static final String CANARY = "place-search-canary-8f2b1d6c4a09e7b5";

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock catalogClock() {
            return MutableClock.at(Instant.parse("2032-01-01T00:00:00Z"));
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    SessionService sessions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        rootLogger().addAppender(logs);
    }

    @AfterEach
    void removeOnlyC3CatalogFixtures() {
        rootLogger().detachAppender(logs);
        logs.stop();
        jdbc.update("DELETE FROM place_media_assets");
        jdbc.update("DELETE FROM media_assets");
        jdbc.update("DELETE FROM asset_licenses");
        jdbc.update("DELETE FROM place_external_refs");
        jdbc.update("DELETE FROM place_localizations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-022-T2 a signed cursor rejects tampering, another owner or another filter, and expires")
    void searchCursorIsOwnerAndFilterBound() throws Exception {
        SessionService.Bootstrap owner = owner("ko-KR");
        UUID first = activePlace("가 장소", true);
        UUID second = activePlace("나 장소", true);
        UUID hiddenWithoutCoordinates = activePlace("다 장소", false);
        localization(first, "ko-KR", "가 장소", "첫 번째 설명", "서울시 1");
        localization(second, "ko-KR", "나 장소", "두 번째 설명", "서울시 2");
        localization(hiddenWithoutCoordinates, "ko-KR", "다 장소", null, null);
        reference(first);
        reference(second);
        reference(hiddenWithoutCoordinates);

        MvcResult firstPage = search(owner, "{\"query\":\"장소\",\"limit\":1}")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn();
        String cursor = cursor(firstPage);
        assertThat(cursor).hasSizeLessThan(501).doesNotContain("장소", owner.owner.id().toString());

        search(owner, "{\"query\":\"장소\",\"limit\":1,\"cursor\":\"" + cursor + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.page.hasMore").value(false));

        SessionService.Bootstrap anotherOwner = owner("ko-KR");
        search(anotherOwner, "{\"query\":\"장소\",\"cursor\":\"" + cursor + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        search(owner, "{\"query\":\"다른 검색어\",\"cursor\":\"" + cursor + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        String tampered = cursor.substring(0, 8) + (cursor.charAt(8) == 'A' ? 'B' : 'A') + cursor.substring(9);
        search(owner, "{\"query\":\"장소\",\"cursor\":\"" + tampered + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));

        clock.advance(Duration.ofMinutes(15));
        MvcResult expired = search(owner, "{\"query\":\"장소\",\"cursor\":\"" + cursor + "\"}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"))
                .andReturn();
        assertThat(expired.getResponse().getContentAsString()).doesNotContain(cursor);
    }

    @Test
    @DisplayName("BA-022-T1 a deprecated ID resolves to canonical detail with locale fallback and only approved media")
    void canonicalDetailUsesOnlyPubliclyEligibleData() throws Exception {
        SessionService.Bootstrap englishOwner = owner("en-US");
        UUID canonical = activePlace("한국어 이름", true);
        UUID deprecated = deprecatedPlace(canonical);
        localization(canonical, "ko-KR", "한국어 이름", "한국어 설명", "서울시 종로구");
        localization(canonical, "en-US", "English name", "English description", "Jongno-gu, Seoul");
        reference(canonical);
        UUID originOnly = media(canonical, false, null, "origin-only.jpg");
        UUID approved = media(canonical, true, "https://cdn.example.test/approved.jpg", "approved.jpg");

        MvcResult result = mvc.perform(get("/api/v1/places/{placeId}", deprecated).cookie(cookie(englishOwner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.id").value(canonical.toString()))
                .andExpect(jsonPath("$.name").value("English name"))
                .andExpect(jsonPath("$.description").value("English description"))
                .andExpect(jsonPath("$.location.latitude").value(37.566535))
                .andExpect(jsonPath("$.externalRefs[0].source").value(SOURCE))
                .andExpect(jsonPath("$.thumbnailUrl").value("https://cdn.example.test/approved.jpg"))
                .andExpect(jsonPath("$.thumbnailAsset.id").value(approved.toString()))
                .andExpect(jsonPath("$.thumbnailAsset.redistributionAllowed").value(true))
                // The credit comes from the reviewed revision the reference points at, so the client
                // never decides which provider to name. Display names stay null until a reviewed
                // code-to-label mapping exists; null means "show nothing", not "unknown".
                .andExpect(jsonPath("$.sourceAttribution.source").value(SOURCE))
                .andExpect(jsonPath("$.sourceAttribution.sourceRegistryVersion").value(3))
                .andExpect(jsonPath("$.sourceAttribution.attribution").value("출처: ⓒ한국관광공사"))
                .andExpect(jsonPath("$.sourceAttribution.sourceDisplayName").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(originOnly.toString(), "origin-only.jpg", "https://origin.example.test")
                .contains("\"categoryName\":null", "\"regionName\":null");
    }

    /**
     * CMP-ATT-001 needs a credit on every KTO-sourced screen and CMP-ATT-003 forbids implying one that
     * was not given. A place with no reviewed external reference therefore projects no attribution at
     * all rather than a default or a guessed provider.
     */
    @Test
    @DisplayName("BA-022-T3 a place with no reviewed source projects no attribution instead of a default")
    void aPlaceWithoutAnExternalReferenceHasNoAttribution() throws Exception {
        SessionService.Bootstrap owner = owner("ko-KR");
        UUID credited = activePlace("출처 있는 장소", true);
        UUID uncredited = activePlace("출처 없는 장소", true);
        localization(credited, "ko-KR", "출처 있는 장소", null, null);
        localization(uncredited, "ko-KR", "출처 없는 장소", null, null);
        reference(credited);

        mvc.perform(get("/api/v1/places/{placeId}", credited).cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceAttribution.attribution").value("출처: ⓒ한국관광공사"));

        MvcResult result = mvc.perform(get("/api/v1/places/{placeId}", uncredited).cookie(cookie(owner)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("\"sourceAttribution\":null")
                .doesNotContain("한국관광공사");
    }

    @Test
    @DisplayName("BA-022-T3 a search canary stays out of responses and every captured application log")
    void searchCanaryIsNeverLoggedOrEchoed() throws Exception {
        SessionService.Bootstrap owner = owner("ko-KR");
        UUID place = activePlace("무관한 장소", true);
        localization(place, "ko-KR", "무관한 장소", null, null);
        reference(place);

        MvcResult result = search(owner, "{\"query\":\"" + CANARY + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(CANARY);
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(CANARY));
    }

    @Test
    @DisplayName("BA-022-T2 search treats SQL LIKE control characters as literal user input")
    void searchEscapesLikeControlCharacters() throws Exception {
        SessionService.Bootstrap owner = owner("ko-KR");
        UUID literal = activePlace("literal 100%_token", true);
        UUID wildcardOnly = activePlace("literal 100abtoken", true);
        reference(literal);
        reference(wildcardOnly);

        MvcResult result = search(owner, "{\"query\":\"100%_token\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains(literal.toString())
                .doesNotContain(wildcardOnly.toString());
    }

    /**
     * PM-010: a list card shows the image but the summary projection did not carry its credit, so a
     * client had no way to satisfy an attribution-required licence without an N+1 detail call.
     * Redistributable and creditless are different permissions - the summary already filtered on the
     * first and said nothing about the second.
     */
    @Test
    @DisplayName("BA-022-T1 a search summary carries the credit for the image it serves")
    void aSummaryCarriesTheCreditForItsThumbnail() throws Exception {
        SessionService.Bootstrap owner = owner("ko-KR");
        UUID credited = activePlace("가 크레딧 장소", true);
        UUID creditless = activePlace("나 크레딧 장소", true);
        localization(credited, "ko-KR", "가 크레딧 장소", null, null);
        localization(creditless, "ko-KR", "나 크레딧 장소", null, null);
        reference(credited);
        reference(creditless);
        media(credited, true, "https://cdn.example.test/credited.jpg", "credited.jpg", "사진: ⓒ촬영자");
        media(creditless, true, "https://cdn.example.test/creditless.jpg", "creditless.jpg", null);

        MvcResult result = search(owner, "{\"query\":\"크레딧\",\"limit\":10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://cdn.example.test/credited.jpg"))
                .andExpect(jsonPath("$.items[0].thumbnailAttribution").value("사진: ⓒ촬영자"))
                // A licence that requires no credit projects null, not an empty string and not the
                // place's sourceAttribution: crediting the record is not crediting the photograph.
                .andExpect(jsonPath("$.items[1].thumbnailUrl").value("https://cdn.example.test/creditless.jpg"))
                .andExpect(jsonPath("$.items[1].thumbnailAttribution").isEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"thumbnailAttribution\":null");
    }

    private org.springframework.test.web.servlet.ResultActions search(SessionService.Bootstrap owner, String body)
            throws Exception {
        return mvc.perform(post("/api/v1/places/search").cookie(cookie(owner))
                .header("Origin", "http://localhost:5173").contentType("application/json").content(body));
    }

    private SessionService.Bootstrap owner(String locale) {
        return sessions.bootstrap(null, locale, "Asia/Seoul");
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID activePlace(String name, boolean coordinates) {
        UUID id = UUID.randomUUID();
        if (coordinates) {
            jdbc.update("""
                    INSERT INTO places
                        (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                    VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                    """, id, name, timestamp(), timestamp());
        } else {
            jdbc.update("""
                    INSERT INTO places
                        (id, canonical_name, category_code, region_code, status, created_at, updated_at)
                    VALUES (?, ?, 'A0101', '1', 'ACTIVE', ?, ?)
                    """, id, name, timestamp(), timestamp());
        }
        return id;
    }

    private UUID deprecatedPlace(UUID canonical) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, region_code, status, created_at, updated_at)
                VALUES (?, ?, 'legacy duplicate', 'A0101', '1', 'DEPRECATED', ?, ?)
                """, id, canonical, timestamp(), timestamp());
        return id;
    }

    private void localization(UUID placeId, String locale, String name, String description, String address) {
        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, short_description, address, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), placeId, locale, name, description, address, timestamp());
    }

    private void reference(UUID placeId) {
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, 3, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, UUID.randomUUID(), placeId, SOURCE, "fixture-" + placeId, timestamp());
    }

    private UUID media(UUID placeId, boolean redistributable, String servedUrl, String externalId) {
        return media(placeId, redistributable, servedUrl, externalId, "출처: fixture");
    }

    private UUID media(UUID placeId, boolean redistributable, String servedUrl, String externalId,
            String attributionTemplate) {
        UUID license = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO asset_licenses
                    (id, source_code, source_registry_version, external_license_code, license_name, license_url,
                     attribution_template, redistribution_allowed, derivative_allowed, reviewed_at)
                VALUES (?, ?, 3, ?, 'fixture license', 'https://license.example.test/policy',
                        ?, ?, false, ?)
                """, license, SOURCE, "fixture-" + license, attributionTemplate, redistributable, timestamp());
        UUID asset = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media_assets
                    (id, asset_license_id, source_external_id, origin_url, served_url, checksum, media_type,
                     license_checked_at)
                VALUES (?, ?, ?, 'https://origin.example.test/', ?, ?, 'IMAGE', ?)
                """, asset, license, externalId, servedUrl, "a".repeat(64), timestamp());
        jdbc.update("INSERT INTO place_media_assets (place_id, media_asset_id, position) VALUES (?, ?, ?)", placeId,
                asset, redistributable ? 1 : 0);
        return asset;
    }

    private String cursor(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("page").path("nextCursor").asString();
    }

    private Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
