package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.nullnull.catalog.application.KtoGatewayException;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.crowd.application.KtoCrowdForecastGateway;
import io.nullnull.crowd.application.KtoForecastRequest;
import io.nullnull.crowd.application.KtoForecastSnapshotSet;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in only: makes one real C2 detail call followed by one C4 forecast call against disposable
 * PostgreSQL. It leaves the developer's local database and every credential untouched.
 */
@SpringBootTest(properties = {
        "nullnull.env=local",
        "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]=apis.data.go.kr",
        "nullnull.sources.KTO_CONCENTRATION_FORECAST.allowed-hosts[0]=apis.data.go.kr",
        "nullnull.provider.request-timeout=PT20S"
})
@Import(TestcontainersConfiguration.class)
@Tag("actual-kto")
class KtoForecastActualSmokeIT {

    @DynamicPropertySource
    static void ktoSmokeProperties(DynamicPropertyRegistry registry) {
        KtoSmokeEnvironment.gatewayProperties(
                KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local")))
                .forEach((name, value) -> registry.add(name, () -> value));
    }

    @Autowired KtoPlaceDetailGateway detailGateway;
    @Autowired KtoCrowdForecastGateway forecastGateway;
    @Autowired KtoKorServiceProperties properties;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recordsOneActualKtoForecastAsAnImmutableNormalizedSnapshotAndRedactedAudit() throws Exception {
        KtoForecastSmokeMain.requireApproval(System.getenv());
        KtoSmokeMain.SmokeRequest request = KtoSmokeMain.SmokeRequest.from(System.getenv());
        request.requirePermittedEnvironment("local");
        assertThatCode(() -> properties.requireConfigured(false)).doesNotThrowAnyException();
        assertThatCode(() -> properties.requireForecastConfigured(false)).doesNotThrowAnyException();

        KtoPlaceSnapshot detail;
        KtoCrowdForecastGateway.RefreshResult result;
        try {
            detail = detailGateway.detail(request.contentId(), request.contentTypeId()).get(30, TimeUnit.SECONDS);
            result = forecastGateway.refresh(new KtoForecastRequest(actualCrowdFixturePlace(), detail.areaCode(), detail.sigunguCode(),
                    detail.title())).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            // The gateway can fail before any audit row exists (disabled source, quarantine, missing
            // key), and "audit=missing" alone cannot tell those apart. Only the stable gateway code is
            // reported: attaching the raw cause would risk a provider message reaching this output.
            throw new AssertionError("actual KTO forecast was rejected: " + compactAuditEvidence()
                    + " cause=" + gatewayCode(failure));
        }

        assertThat(result.hasCoverage()).isTrue();
        KtoForecastSnapshotSet set = result.snapshotSet().orElseThrow();
        assertThat(set.sourceRegistryVersion()).isEqualTo(2);
        assertThat(set.points()).isNotEmpty().hasSizeLessThanOrEqualTo(31);
        assertThat(set.points()).allSatisfy(point -> {
            assertThat(point.value()).isBetween(java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(100));
            assertThat(point.targetAt()).isAfterOrEqualTo(set.fetchedAt().minusSeconds(9 * 60 * 60));
        });

        Map<String, Object> ingest = jdbc.queryForMap("""
                SELECT outcome, http_status, response_count, payload_hash, validation_result
                  FROM api_ingest_logs WHERE collector_run_id = ?
                """, set.collectorRunId());
        Map<String, Object> run = jdbc.queryForMap("""
                SELECT status, records_received, records_accepted, records_rejected, error_code
                  FROM collector_runs WHERE id = ?
                """, set.collectorRunId());
        Integer forbiddenColumns = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('snapshot_sets', 'crowd_snapshots', 'api_ingest_logs', 'collector_runs')
                   AND column_name IN ('service_key', 'url', 'query', 'raw_body', 'provider_message')
                """, Integer.class);

        assertThat(ingest).containsEntry("outcome", "OK")
                .containsEntry("http_status", 200)
                .containsEntry("response_count", set.points().size())
                .containsEntry("payload_hash", set.payloadHash())
                .containsEntry("validation_result", "OK");
        assertThat(run).containsEntry("status", "COMPLETED")
                .containsEntry("records_received", set.points().size())
                .containsEntry("records_accepted", set.points().size())
                .containsEntry("records_rejected", 0)
                .containsEntry("error_code", null);
        assertThat(forbiddenColumns).isZero();
    }

    /** The gateway's own code is already provider-safe; nothing from the response body is used. */
    private static String gatewayCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof KtoGatewayException gateway) {
                return gateway.code().name();
            }
        }
        return "UNCLASSIFIED";
    }

    private String compactAuditEvidence() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.outcome, l.http_status, l.response_count, l.validation_result, r.status, r.error_code
                  FROM api_ingest_logs l
                  JOIN collector_runs r ON r.id = l.collector_run_id
                 WHERE l.endpoint_key = 'TATS_CNCTR_RATE_LIST'
                 ORDER BY l.created_at DESC
                 LIMIT 1
                """);
        if (rows.isEmpty()) {
            return "audit=missing";
        }
        Map<String, Object> row = rows.getFirst();
        return "outcome=" + row.get("outcome")
                + ",httpStatus=" + row.get("http_status")
                + ",responseCount=" + row.get("response_count")
                + ",validation=" + row.get("validation_result")
                + ",collectorStatus=" + row.get("status")
                + ",collectorError=" + row.get("error_code");
    }

    /**
     * The C4 proof needs a foreign-key target but does not claim that the current minimal C2 detail
     * response is sufficient to publish a C3 catalog record. The fixture is isolated in Testcontainers
     * and exists only to prove that an actual validated forecast reaches the immutable C4 model.
     */
    private UUID actualCrowdFixturePlace() {
        UUID place = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, 'actual-kto-c4-fixture', 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, place, now, now);
        return place;
    }
}
