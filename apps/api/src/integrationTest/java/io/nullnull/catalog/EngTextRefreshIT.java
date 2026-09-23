package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.catalog.application.EngTextLinkImporter;
import io.nullnull.catalog.application.EngTextStore;
import io.nullnull.catalog.application.KtoEngDetailFetcher;
import io.nullnull.catalog.application.KtoEngTextRefresh;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.infrastructure.kto.KtoEngServiceClient;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceProperties;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.identity.application.SessionService;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.StubProviderServer;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
        "nullnull.env=test",
        "nullnull.sources.KTO_ENG_SERVICE.allowed-hosts[0]=127.0.0.1",
        "nullnull.provider.request-timeout=PT5S",
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-catalog-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-086 English text from linked EngService records")
class EngTextRefreshIT {

    private static final String CANARY = "fake-secret-key-never-retain";
    private static final String EN_TITLE = "Gyeongbokgung Palace";
    private static final String EN_ADDRESS = "161, Sajik-ro, Jongno-gu, Seoul";
    private static final String KO_NAME = "경복궁";
    private static final String KO_ADDRESS = "서울특별시 종로구 사직로 161 (세종로)";
    private static final BigDecimal LATITUDE = new BigDecimal("37.579617");
    private static final BigDecimal LONGITUDE = new BigDecimal("126.977041");

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EngTextStore store;
    @Autowired EngTextLinkImporter importer;
    @Autowired ProviderHttpClient provider;
    @Autowired SourceRegistryQuery registry;
    @Autowired SourceRegistryStore registryStore;
    @Autowired IngestAudit audit;
    @Autowired SourceQuotaStore quotaStore;
    @Autowired PlatformTransactionManager transactions;

    private final ObjectMapper json = new ObjectMapper();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> koreanRuns = new ArrayList<>();

    @AfterEach
    void removeOnlyThisClassRows() {
        // The English runs carry the refresh's own request_id prefix. Removing them also keeps the
        // English source's LATEST run from being this class's, which decides quarantine for every other
        // class sharing the gate database.
        jdbc.execute("""
                WITH removed AS (
                    DELETE FROM api_ingest_logs WHERE request_id LIKE 'kto-eng-detail-%'
                    RETURNING collector_run_id
                )
                DELETE FROM collector_runs WHERE id IN (SELECT collector_run_id FROM removed)
                """);
        for (UUID place : places) {
            jdbc.update("DELETE FROM place_localization_sources WHERE place_id = ?", place);
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", place);
            jdbc.update("DELETE FROM place_external_refs WHERE place_id = ?", place);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
        for (UUID run : koreanRuns) {
            jdbc.update("DELETE FROM kto_place_snapshots WHERE collector_run_id = ?", run);
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
    }

    @Test
    @DisplayName("BA-086-T1 once English text is served, the same place answers in Korean and English with the same ids, coordinates, external refs and their verification times")
    void englishTextChangesOnlyText() throws Exception {
        UUID place = koreanPlace();
        String record = link(place);
        Map<String, Object> before = placeRow(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, EN_ADDRESS, 50))) {
            assertThat(refresh(stub).refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.UPDATED);
        }

        JsonNode english = getPlace(place, "en-US");
        JsonNode korean = getPlace(place, "ko-KR");

        // English is really served - without this the comparison below holds on code that writes nothing.
        assertThat(english.path("name").asString()).isEqualTo(EN_TITLE);
        assertThat(english.path("textProvenance").path("name").path("locale").asString()).isEqualTo("en");
        assertThat(english.path("textProvenance").path("name").path("sourceAttribution").path("source").asString())
                .isEqualTo("KTO_ENG_SERVICE");
        assertThat(korean.path("name").asString()).isEqualTo(KO_NAME);
        assertThat(withoutText(english)).isEqualTo(withoutText(korean));
        assertThat(english.path("id").asString()).isEqualTo(place.toString());
        assertThat(placeRow(place)).isEqualTo(before);
    }

    @Test
    @DisplayName("BA-086-T2 a field the English record lacks falls back to the Korean text, and each field carries its own source's credit and licence")
    void missingEnglishFieldFallsBackWithItsOwnCredit() throws Exception {
        UUID place = koreanPlace();
        String record = link(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, "", 50))) {
            assertThat(refresh(stub).refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.UPDATED);
        }

        JsonNode english = getPlace(place, "en-US");
        JsonNode name = english.path("textProvenance").path("name");
        JsonNode address = english.path("textProvenance").path("address");

        assertThat(english.path("name").asString()).isEqualTo(EN_TITLE);
        assertThat(name.path("locale").asString()).isEqualTo("en");
        assertThat(name.path("sourceAttribution").path("source").asString()).isEqualTo("KTO_ENG_SERVICE");
        assertThat(name.path("sourceAttribution").path("attribution").asString()).isEqualTo("출처: ⓒ한국관광공사");
        assertThat(name.path("sourceAttribution").path("license").asString())
                .isEqualTo("이용허락범위 제한 없음 (관광정보 텍스트; 이미지 별도 심사)");
        assertThat(name.path("sourceAttribution").path("officialUrl").asString())
                .isEqualTo("https://www.data.go.kr/data/15101753/openapi.do");
        assertThat(english.path("address").asString()).isEqualTo(KO_ADDRESS);
        assertThat(address.path("locale").asString()).isEqualTo("ko-KR");
        assertThat(address.path("sourceAttribution").path("source").asString()).isEqualTo("KTO_KOR_SERVICE_2");
        assertThat(address.path("sourceAttribution").path("officialUrl").asString())
                .isEqualTo("https://www.data.go.kr/data/15101578/openapi.do");
        assertThat(address.path("sourceAttribution").path("licenseUrl").asString()).isNotBlank();
        assertThat(english.path("description").isNull()).isTrue();
    }

    @Test
    @DisplayName("BA-086-T3 when the linked English record is gone, its text stops being served")
    void goneRecordWithdrawsItsText() throws Exception {
        UUID place = koreanPlace();
        String record = link(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, EN_ADDRESS, 50))
                .enqueue(gone())) {
            KtoEngTextRefresh refresh = refresh(stub);
            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.UPDATED);
            assertThat(getPlace(place, "en-US").path("name").asString()).isEqualTo(EN_TITLE);

            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.WITHDRAWN_GONE);
        }

        JsonNode english = getPlace(place, "en-US");
        assertThat(english.path("name").asString()).isEqualTo(KO_NAME);
        assertThat(english.path("textProvenance").path("name").path("locale").asString()).isEqualTo("ko-KR");
    }

    @Test
    @DisplayName("BA-086-T24 an answer that the English record is gone does not quarantine the English source")
    void goneAnswerLeavesTheSourceCollectable() {
        UUID first = koreanPlace();
        UUID second = koreanPlace();
        link(first);
        String secondRecord = link(second);
        try (StubProviderServer stub = new StubProviderServer().enqueue(gone())
                .enqueue(found(secondRecord, EN_TITLE, EN_ADDRESS, 50))) {
            KtoEngTextRefresh refresh = refresh(stub);
            assertThat(refresh.refresh(linkOf(first))).isEqualTo(KtoEngTextRefresh.Outcome.WITHDRAWN_GONE);

            assertThat(refresh.refresh(linkOf(second))).isEqualTo(KtoEngTextRefresh.Outcome.UPDATED);
        }
    }

    @Test
    @DisplayName("BA-086-T25 a linked record that no longer satisfies the owner's link rule stops its English text being served")
    void recordFailingTheRuleWithdrawsItsText() throws Exception {
        UUID place = koreanPlace();
        String record = link(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, EN_ADDRESS, 50))
                .enqueue(found(record, EN_TITLE, EN_ADDRESS, 150))) {
            KtoEngTextRefresh refresh = refresh(stub);
            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.UPDATED);
            assertThat(getPlace(place, "en-US").path("name").asString()).isEqualTo(EN_TITLE);

            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.WITHDRAWN_RULE);
        }

        assertThat(getPlace(place, "en-US").path("name").asString()).isEqualTo(KO_NAME);
    }

    @Test
    @DisplayName("BA-086-T26 a record fetched under a revision that is no longer current is not written")
    void recordFromASupersededRevisionIsNotWritten() {
        UUID place = koreanPlace();
        String record = link(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, EN_ADDRESS, 50))) {
            KtoEngDetailFetcher bumpingDuringTheCall = new BumpDuringCall(client(stub));
            KtoEngTextRefresh refresh = new KtoEngTextRefresh(store, registry, registryStore, collector(),
                    bumpingDuringTheCall, Clock.systemUTC(), transactions);

            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.DISCARDED_REVISION_CHANGED);
        } finally {
            restoreRevision();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_localizations WHERE place_id = ? AND locale = 'en'",
                Long.class, place)).isZero();
    }

    @Test
    @DisplayName("a link the owner replaced while the call was out is not overwritten by the old record's text")
    void replacedLinkIsNotWrittenFromTheOldRecord() {
        UUID place = koreanPlace();
        String record = link(place);
        try (StubProviderServer stub = new StubProviderServer().enqueue(found(record, EN_TITLE, EN_ADDRESS, 50))) {
            KtoEngDetailFetcher relinkDuringTheCall = new KtoEngDetailFetcher() {
                private final KtoEngDetailFetcher delegate = client(stub);

                @Override
                public void requireConfigured() {
                    delegate.requireConfigured();
                }

                @Override
                public CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request) {
                    importer.importPlan(new EngTextLinkImporter.Plan(List.of(new EngTextLinkImporter.Link(place,
                            contentId(), "76", Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS),
                            "https://korean.visitkorea.or.kr/review"))));
                    return delegate.fetch(request);
                }

                @Override
                public String releaseVersion() {
                    return delegate.releaseVersion();
                }
            };
            KtoEngTextRefresh refresh = new KtoEngTextRefresh(store, registry, registryStore, collector(),
                    relinkDuringTheCall, Clock.systemUTC(), transactions);

            assertThat(refresh.refresh(linkOf(place))).isEqualTo(KtoEngTextRefresh.Outcome.DISCARDED_LINK_CHANGED);
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM place_localizations WHERE place_id = ? AND locale = 'en'",
                Long.class, place)).isZero();
    }

    /** Moves the English source to a second reviewed revision while the provider call is out. */
    private final class BumpDuringCall implements KtoEngDetailFetcher {

        private final KtoEngDetailFetcher delegate;

        BumpDuringCall(KtoEngDetailFetcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void requireConfigured() {
            delegate.requireConfigured();
        }

        @Override
        public CompletableFuture<ProviderResponse> fetch(KtoPlaceRequest request) {
            jdbc.update("""
                    INSERT INTO source_registry_revisions
                        (source_code, version, canonical_contract, contract_hash, reviewed_at, created_at)
                    SELECT source_code, 2, canonical_contract, contract_hash, reviewed_at, now()
                      FROM source_registry_revisions WHERE source_code = 'KTO_ENG_SERVICE' AND version = 1
                    """);
            jdbc.update("UPDATE source_registry SET current_revision = 2 WHERE code = 'KTO_ENG_SERVICE'");
            return delegate.fetch(request);
        }

        @Override
        public String releaseVersion() {
            return delegate.releaseVersion();
        }
    }

    /** Puts the English source back on revision 1: the gate database is shared with every other class. */
    private void restoreRevision() {
        jdbc.update("UPDATE source_registry SET current_revision = 1 WHERE code = 'KTO_ENG_SERVICE'");
        jdbc.update("DELETE FROM source_registry_revisions WHERE source_code = 'KTO_ENG_SERVICE' AND version = 2");
    }

    private KtoEngTextRefresh refresh(StubProviderServer stub) {
        return new KtoEngTextRefresh(store, registry, registryStore, collector(), client(stub), Clock.systemUTC(),
                transactions);
    }

    private KtoEngDetailFetcher client(StubProviderServer stub) {
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey(CANARY);
        properties.setEngBaseUrl(stub.uri(null).toString());
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        return new KtoEngServiceClient(provider, properties, "test");
    }

    private CollectorRunRecorder collector() {
        return new CollectorRunRecorder(audit, new SourceQuotaGuard(quotaStore, Clock.systemUTC()));
    }

    private EngTextStore.Link linkOf(UUID place) {
        return store.links().stream().filter(link -> link.placeId().equals(place)).findFirst().orElseThrow();
    }

    private String link(UUID place) {
        String record = contentId();
        importer.importPlan(new EngTextLinkImporter.Plan(List.of(new EngTextLinkImporter.Link(place, record, "76",
                Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS),
                "https://korean.visitkorea.or.kr/review"))));
        return record;
    }

    private JsonNode getPlace(UUID place, String locale) throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, locale, "Asia/Seoul");
        String body = mvc.perform(get("/api/v1/places/{placeId}", place)
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private static JsonNode withoutText(JsonNode place) {
        ObjectNode copy = (ObjectNode) place.deepCopy();
        copy.remove(List.of("name", "address", "description", "textProvenance"));
        return copy;
    }

    private Map<String, Object> placeRow(UUID place) {
        return jdbc.queryForMap("SELECT canonical_name, category_code, latitude, longitude, region_code, status,"
                + " updated_at FROM places WHERE id = ?", place);
    }

    /** A Korean KTO place as the canonical ingest writes one, with the snapshot the link rule reads. */
    private UUID koreanPlace() {
        UUID place = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code, status,
                                    created_at, updated_at)
                VALUES (?, ?, 'HS', ?, ?, '11', 'ACTIVE', ?, ?)
                """, place, KO_NAME, LATITUDE, LONGITUDE, now, now);
        places.add(place);
        long revision = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = 'KTO_KOR_SERVICE_2'", Long.class);
        String koreanId = contentId();
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, 'KTO_KOR_SERVICE_2', ?, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, UUID.randomUUID(), place, revision, koreanId, now);
        jdbc.update("""
                INSERT INTO place_localizations
                    (id, place_id, locale, name, address, updated_at, source_code, source_registry_version,
                     source_locale, observed_at)
                VALUES (?, ?, 'ko-KR', ?, ?, ?, 'KTO_KOR_SERVICE_2', ?, 'ko-KR', ?)
                """, UUID.randomUUID(), place, KO_NAME, KO_ADDRESS, now, revision, now);
        UUID run = UUID.randomUUID();
        koreanRuns.add(run);
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted, records_rejected,
                     schema_version, started_at, finished_at)
                VALUES (?, 'KTO_KOR_SERVICE_2', 'COMPLETED', 'MANUAL', 1, 1, 0, 'test-kor-v3', ?, ?)
                """, run, now, now);
        jdbc.update("""
                INSERT INTO kto_place_snapshots
                    (id, source_code, source_registry_version, collector_run_id, content_id, content_type_id, title,
                     category_code, area_code, sigungu_code, address, latitude, longitude, payload_hash, fetched_at,
                     stale_at, created_at)
                VALUES (?, 'KTO_KOR_SERVICE_2', ?, ?, ?, '12', ?, 'HS', '11', '110', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), revision, run, koreanId, KO_NAME, KO_ADDRESS, LATITUDE, LONGITUDE,
                "a".repeat(64), now, Timestamp.from(now.toInstant().plus(7, ChronoUnit.DAYS)), now);
        return place;
    }

    private static StubProviderServer.Response found(String contentId, String title, String address, int metresNorth) {
        String latitude = LATITUDE.add(BigDecimal.valueOf(Math.toDegrees(metresNorth / 6_371_008.8)))
                .setScale(6, RoundingMode.HALF_UP).toPlainString();
        return new StubProviderServer.Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[{
                  "contentid":"%s","contenttypeid":"76","title":"%s","addr1":"%s","addr2":"","zipcode":"03045",
                  "mapy":"%s","mapx":"%s","mlevel":"6","lclsSystm1":"HS","lclsSystm2":"HS01",
                  "lclsSystm3":"HS010100","lDongRegnCd":"11","lDongSignguCd":"110","cat1":"","cat2":"","cat3":"",
                  "areacode":"","sigungucode":"","overview":"An English-only overview that must not be stored.",
                  "tel":"","telname":"","homepage":"","firstimage":"","firstimage2":"","cpyrhtDivCd":"",
                  "createdtime":"20200101000000","modifiedtime":"20250101000000"}]},
                  "numOfRows":1,"pageNo":1,"totalCount":1}}}
                """.formatted(contentId, title, address, latitude, LONGITUDE.toPlainString()));
    }

    private static StubProviderServer.Response gone() {
        return new StubProviderServer.Response(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},
                 "body":{"items":"","numOfRows":10,"pageNo":1,"totalCount":0}}}
                """);
    }

    private static String contentId() {
        return Long.toString(ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
    }
}
