package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceSnapshotStore;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceClient;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceProperties;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.StubProviderServer;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "nullnull.env=test",
        "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]=127.0.0.1",
        "nullnull.provider.request-timeout=PT5S"
})
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-021 KTO read-through detail gateway")
class KtoPlaceDetailGatewayIT {

    private static final String CANARY = "fake-secret-key-never-retain";
    private static final String RAW_BODY_CANARY = "raw-provider-body-must-not-persist";

    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderHttpClient provider;
    @Autowired SourceRegistryQuery registry;
    @Autowired SourceRegistryStore registryStore;
    @Autowired IngestAudit audit;
    @Autowired SourceQuotaStore quotaStore;
    @Autowired KtoPlaceSnapshotStore snapshots;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void removeOnlyC2FixtureEvidence() {
        jdbc.update("DELETE FROM kto_place_snapshots");
        jdbc.execute("""
                WITH removed AS (
                    DELETE FROM api_ingest_logs WHERE request_id LIKE 'kto-detail-%'
                    RETURNING collector_run_id
                )
                DELETE FROM collector_runs WHERE id IN (SELECT collector_run_id FROM removed)
                """);
    }

    @Test
    @DisplayName("BA-021-T1 key and raw provider body remain outside snapshots and audit evidence")
    void canaryAndRawBodyNeverReachPersistedEvidence() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-10T00:00:00Z"));
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, response("126508", "서울 테스트 관광지")))) {
            KtoPlaceSnapshot snapshot = gateway(stub, clock).detail("126508", "12").join();

            String snapshotRow = jdbc.queryForObject("""
                    SELECT row_to_json(s)::text FROM kto_place_snapshots s WHERE s.collector_run_id = ?
                    """, String.class, snapshot.collectorRunId());
            String auditRow = jdbc.queryForObject("""
                    SELECT row_to_json(l)::text FROM api_ingest_logs l WHERE l.collector_run_id = ?
                    """, String.class, snapshot.collectorRunId());
            String runRow = jdbc.queryForObject("""
                    SELECT row_to_json(r)::text FROM collector_runs r WHERE r.id = ?
                    """, String.class, snapshot.collectorRunId());

            assertThat(stub.calls()).isEqualTo(1);
            assertThat(snapshot.sourceRegistryVersion()).isEqualTo(2);
            assertThat(snapshotRow).doesNotContain(CANARY, RAW_BODY_CANARY, "serviceKey", "overview");
            assertThat(auditRow).doesNotContain(CANARY, RAW_BODY_CANARY, "serviceKey", "overview");
            assertThat(runRow).doesNotContain(CANARY, RAW_BODY_CANARY, "serviceKey", "overview");
        }
    }

    @Test
    @DisplayName("BA-021-T2 concurrent cache misses coalesce and a stale entry performs one new refresh")
    void coalescesSameRequestAndRefreshesOnlyAfterExpiry() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-10T00:00:00Z"));
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, response("126509", "첫 번째"), Duration.ofMillis(250), java.util.Map.of()))
                .enqueue(new StubProviderServer.Response(200, response("126509", "두 번째")))) {
            KtoPlaceDetailGateway gateway = gateway(stub, clock);

            var first = gateway.detail("126509", "12");
            var concurrent = gateway.detail("126509", "12");
            assertThat(first.join().title()).isEqualTo("첫 번째");
            assertThat(concurrent.join().title()).isEqualTo("첫 번째");
            assertThat(stub.calls()).isEqualTo(1);

            assertThat(gateway.detail("126509", "12").join().title()).isEqualTo("첫 번째");
            assertThat(stub.calls()).isEqualTo(1);

            clock.advance(Duration.ofDays(8));
            assertThat(gateway.detail("126509", "12").join().title()).isEqualTo("두 번째");
            assertThat(stub.calls()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kto_place_snapshots", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("BA-021-T1 a transport failure is redacted, audited and closes its collector run")
    void providerFailureIsRedactedAndClosesTheRun() {
        MutableClock clock = MutableClock.at(Instant.parse("2026-09-10T00:00:00Z"));
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(500, "provider says " + CANARY))) {
            assertThatThrownBy(() -> gateway(stub, clock).detail("126510", "12").join())
                    .hasRootCauseInstanceOf(KtoGatewayException.class)
                    .rootCause().hasMessage("KTO_TRANSPORT_FAILED");

            String auditRow = jdbc.queryForObject("""
                    SELECT row_to_json(l)::text FROM api_ingest_logs l
                     WHERE l.request_id LIKE 'kto-detail-%'
                     ORDER BY l.created_at DESC
                     LIMIT 1
                    """, String.class);
            String runStatus = jdbc.queryForObject("""
                    SELECT r.status FROM collector_runs r
                    JOIN api_ingest_logs l ON l.collector_run_id = r.id
                   WHERE l.request_id LIKE 'kto-detail-%'
                   ORDER BY l.created_at DESC
                   LIMIT 1
                    """, String.class);

            assertThat(auditRow).contains("\"outcome\":\"HTTP_ERROR\"", "\"http_status\":500",
                    "\"validation_result\":\"PROVIDER_ERROR\"");
            assertThat(auditRow).doesNotContain(CANARY, "provider says", "serviceKey");
            assertThat(runStatus).isEqualTo("FAILED");
        }
    }

    private KtoPlaceDetailGateway gateway(StubProviderServer stub, MutableClock clock) {
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey(CANARY);
        properties.setBaseUrl(stub.uri(null).toString());
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        KtoKorServiceClient client = new KtoKorServiceClient(provider, properties, "test");
        CollectorRunRecorder collector = new CollectorRunRecorder(audit, new SourceQuotaGuard(quotaStore, clock));
        return new KtoPlaceDetailGateway(snapshots, registry, registryStore, collector, client, clock,
                transactionManager);
    }

    private static String response(String contentId, String title) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":{"contentid":"%s","contenttypeid":"12","title":"%s",
                  "cat1":"A0101","areacode":"1","sigungucode":"1","addr1":"서울특별시 종로구",
                  "mapy":"37.566535","mapx":"126.978001","overview":"%s"}},
                  "numOfRows":1,"pageNo":1,"totalCount":1}}}
                """.formatted(contentId, title, RAW_BODY_CANARY);
    }
}
