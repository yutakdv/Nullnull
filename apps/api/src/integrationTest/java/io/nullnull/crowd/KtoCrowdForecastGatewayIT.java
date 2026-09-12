package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.infrastructure.kto.KtoConcentrationForecastClient;
import io.nullnull.catalog.infrastructure.kto.KtoKorServiceProperties;
import io.nullnull.crowd.application.CollectorRunRecorder;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastSnapshotStore;
import io.nullnull.crowd.application.SourceQuotaGuard;
import io.nullnull.crowd.application.SourceQuotaStore;
import io.nullnull.crowd.application.SourceRegistryQuery;
import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.operations.application.IngestAudit;
import io.nullnull.shared.provider.ProviderHttpClient;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.StubProviderServer;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        "nullnull.sources.KTO_CONCENTRATION_FORECAST.allowed-hosts[0]=127.0.0.1",
        "nullnull.provider.request-timeout=PT5S"
})
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-023 KTO concentration forecast collector")
class KtoCrowdForecastGatewayIT {

    private static final String SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final String CANARY = "raw-kto-forecast-body-must-not-persist";

    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderHttpClient provider;
    @Autowired SourceRegistryQuery registry;
    @Autowired SourceRegistryStore registryStore;
    @Autowired IngestAudit audit;
    @Autowired SourceQuotaStore quotaStore;
    @Autowired KtoForecastSnapshotStore snapshots;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<UUID> places = new ArrayList<>();

    @AfterEach
    void removeOnlyC4CollectorFixtures() {
        jdbc.update("DELETE FROM crowd_snapshots WHERE source_code = ?", SOURCE);
        jdbc.update("DELETE FROM snapshot_sets WHERE source_code = ?", SOURCE);
        jdbc.execute("""
                WITH removed AS (
                    DELETE FROM api_ingest_logs WHERE request_id LIKE 'kto-forecast-%'
                    RETURNING collector_run_id
                )
                DELETE FROM collector_runs WHERE id IN (SELECT collector_run_id FROM removed)
                """);
        for (UUID place : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
    }

    @Test
    @DisplayName("BA-023-T1 normalized daily forecast preserves provenance and redacts raw provider content")
    void validatedForecastWritesOnlyNormalizedRowsAndSafeAuditEvidence() {
        MutableClock clock = MutableClock.at(Instant.parse("2032-01-01T00:00:00Z"));
        UUID place = place(clock);
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, response("""
                        {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320102","cnctrRate":"42.5","ignored":"%s"},
                        {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320103","cnctrRate":"58"}
                        """.formatted(CANARY))))) {
            KtoCrowdForecastGateway.RefreshResult result = gateway(stub, clock)
                    .refresh(new KtoForecastRequest(place, "11", "110", "테스트 관광지")).join();

            assertThat(stub.calls()).isEqualTo(1);
            // #109: the adapter is given the RAW stored pair (11, 110) and must send the JOINED
            // region code. Sending 110 is not an error the provider reports - it answers
            // resultCode 0000 with totalCount 0 - so nothing downstream would ever go red, and the
            // collector would record "no coverage" forever. This is the only assertion in the
            // codebase that looks at what actually left the process.
            assertThat(stub.observedQuery(0))
                    .containsEntry("areaCd", "11")
                    .containsEntry("signguCd", "11110")
                    .containsEntry("tAtsNm", "테스트 관광지")
                    .doesNotContainKey("serviceKey");
            assertThat(result.hasCoverage()).isTrue();
            assertThat(result.snapshotSet().orElseThrow().sourceRegistryVersion()).isEqualTo(2);
            assertThat(result.snapshotSet().orElseThrow().points()).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowd_snapshots WHERE source_code = ?", Integer.class,
                    SOURCE)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT value FROM crowd_snapshots WHERE source_code = ? ORDER BY target_at LIMIT 1
                    """, BigDecimal.class, SOURCE)).isEqualByComparingTo("42.5");

            // PM-013: a KTO relative concentration rate is a DAILY index whose 100 is "the busiest
            // period", not the Seoul four-level or the common five-level scale. The frontend's
            // CrowdLevel renders a 1..4 bar from ordinalLevel, so the moment this collector derives
            // a level from cnctrRate the card starts claiming a scale the source does not have -
            // and it would look like data rather than like a bug. unit stays the relative index and
            // ordinal_level stays absent until a reviewed vocabulary exists (FCR-029).
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM crowd_snapshots
                     WHERE source_code = ? AND (ordinal_level IS NOT NULL OR unit <> 'relative-index')
                    """, Integer.class, SOURCE))
                    .as("a daily relative index must not be projected onto a level scale")
                    .isZero();

            String snapshotRows = jdbc.queryForObject("""
                    SELECT string_agg(row_to_json(s)::text, E'\\n')
                      FROM crowd_snapshots s WHERE s.source_code = ?
                    """, String.class, SOURCE);
            String auditRow = jdbc.queryForObject("""
                    SELECT row_to_json(l)::text FROM api_ingest_logs l
                     WHERE l.request_id LIKE 'kto-forecast-%' ORDER BY l.created_at DESC LIMIT 1
                    """, String.class);
            String runRow = jdbc.queryForObject("""
                    SELECT row_to_json(r)::text FROM collector_runs r
                     WHERE r.source_code = ? ORDER BY r.started_at DESC LIMIT 1
                    """, String.class, SOURCE);
            assertThat(snapshotRows).doesNotContain(CANARY, "ignored", "serviceKey", "tAtsNm");
            assertThat(auditRow).doesNotContain(CANARY, "ignored", "serviceKey", "tAtsNm");
            assertThat(runRow).doesNotContain(CANARY, "ignored", "serviceKey", "tAtsNm");
            assertThat(auditRow).contains("\"outcome\":\"OK\"", "\"validation_result\":\"OK\"");
            assertThat(runRow).contains("\"status\":\"COMPLETED\"", "\"records_received\":2",
                    "\"records_accepted\":2");
        }
    }

    @Test
    @DisplayName("BA-023-T1 explicit no coverage is audited but creates no invented forecast row")
    void noCoverageDoesNotWriteAPlaceholderForecast() {
        MutableClock clock = MutableClock.at(Instant.parse("2032-01-01T00:00:00Z"));
        UUID place = place(clock);
        try (StubProviderServer stub = new StubProviderServer().enqueue(new StubProviderServer.Response(200, """
                {"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[]},"totalCount":0}}}
                """))) {
            KtoCrowdForecastGateway.RefreshResult result = gateway(stub, clock)
                    .refresh(new KtoForecastRequest(place, "11", "110", "테스트 관광지")).join();

            assertThat(result.hasCoverage()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowd_snapshots WHERE source_code = ?", Integer.class,
                    SOURCE)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM snapshot_sets WHERE source_code = ?", Integer.class,
                    SOURCE)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM collector_runs WHERE source_code = ?", String.class,
                    SOURCE)).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("BA-023-T1 out-of-range provider values quarantine the run and never write a snapshot")
    void rangeDriftIsQuarantinedBeforePersistence() {
        MutableClock clock = MutableClock.at(Instant.parse("2032-01-01T00:00:00Z"));
        UUID place = place(clock);
        try (StubProviderServer stub = new StubProviderServer()
                .enqueue(new StubProviderServer.Response(200, response("""
                        {"areaCd":"11","signguCd":"11110","tAtsNm":"테스트 관광지","baseYmd":"20320102","cnctrRate":"101"}
                        """)))) {
            assertThatThrownBy(() -> gateway(stub, clock)
                    .refresh(new KtoForecastRequest(place, "11", "110", "테스트 관광지")).join())
                    .hasRootCauseInstanceOf(KtoGatewayException.class)
                    .rootCause().hasMessage("KTO_RESPONSE_REJECTED");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM crowd_snapshots WHERE source_code = ?", Integer.class,
                    SOURCE)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM collector_runs WHERE source_code = ?", String.class,
                    SOURCE)).isEqualTo("QUARANTINED");
            assertThat(jdbc.queryForObject("SELECT validation_result FROM api_ingest_logs WHERE request_id LIKE 'kto-forecast-%'",
                    String.class)).isEqualTo("RANGE");
        }
    }

    private KtoCrowdForecastGateway gateway(StubProviderServer stub, MutableClock clock) {
        KtoKorServiceProperties properties = new KtoKorServiceProperties();
        properties.setServiceKey(CANARY);
        properties.setForecastBaseUrl(stub.uri(null).toString());
        properties.setMobileApp("Nullnull");
        properties.setMobileOs("ETC");
        properties.setReleaseVersion("test-release");
        KtoConcentrationForecastClient client = new KtoConcentrationForecastClient(provider, properties, "test");
        CollectorRunRecorder collector = new CollectorRunRecorder(audit, new SourceQuotaGuard(quotaStore, clock));
        return new KtoCrowdForecastGateway(snapshots, registry, registryStore, collector, client, clock,
                transactionManager);
    }

    private UUID place(MutableClock clock) {
        UUID place = UUID.randomUUID();
        places.add(place);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, 'C4 collector fixture', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, place, now, now);
        return place;
    }

    private static String response(String items) {
        int count = (int) items.lines().filter(line -> line.contains("baseYmd")).count();
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":[%s]},"totalCount":%d}}}
                """.formatted(items, count);
    }
}
