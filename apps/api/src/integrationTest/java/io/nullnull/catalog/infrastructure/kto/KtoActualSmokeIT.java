package io.nullnull.catalog.infrastructure.kto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.nullnull.catalog.application.CatalogIngest;
import io.nullnull.catalog.application.KtoPlaceDetailFetcher;
import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.catalog.domain.CatalogPlace;
import io.nullnull.catalog.domain.KtoPlaceSnapshot;
import io.nullnull.shared.provider.ProviderHttpClient.ProviderResponse;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in only: this test makes one real KTO request when {@code actualKtoSmoke} is explicitly selected.
 * It uses disposable PostgreSQL so a developer's local database password and data are never involved.
 */
@SpringBootTest(properties = {
        "nullnull.env=local",
        "nullnull.sources.KTO_KOR_SERVICE_2.allowed-hosts[0]=apis.data.go.kr",
        "nullnull.provider.request-timeout=PT20S"
})
@Import(TestcontainersConfiguration.class)
@Tag("actual-kto")
class KtoActualSmokeIT {

    @DynamicPropertySource
    static void ktoSmokeProperties(DynamicPropertyRegistry registry) {
        KtoSmokeEnvironment.gatewayProperties(
                KtoSmokeEnvironment.load(System.getenv(), java.nio.file.Path.of(".env.local")))
                .forEach((name, value) -> registry.add(name, () -> value));
    }

    @Autowired KtoPlaceDetailGateway gateway;
    @Autowired KtoPlaceDetailFetcher fetcher;
    @Autowired KtoKorServiceProperties properties;
    @Autowired CatalogIngest catalogIngest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void recordsOneActualApprovedKtoCallAsRedactedSnapshotAndAudit() throws Exception {
        KtoSmokeMain.SmokeRequest request = KtoSmokeMain.SmokeRequest.from(System.getenv());
        request.requirePermittedEnvironment("local");
        assertThatCode(() -> properties.requireConfigured(false)).doesNotThrowAnyException();

        KtoPlaceSnapshot snapshot;
        try {
            snapshot = gateway.detail(request.contentId(), request.contentTypeId()).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            throw new AssertionError("actual KTO result was rejected: " + compactAuditEvidence()
                    + "," + safeResponseShape(request));
        }

        assertThat(snapshot.sourceRegistryVersion()).isEqualTo(4);
        assertThat(snapshot.contentId()).isEqualTo(request.contentId());
        assertThat(snapshot.contentTypeId()).isEqualTo(request.contentTypeId());
        assertThat(snapshot.collectorRunId()).isNotNull();
        assertThat(snapshot.payloadHash()).matches("[0-9a-f]{64}");
        assertThat(snapshot.staleAt()).isAfter(snapshot.fetchedAt());

        Map<String, Object> ingest = jdbc.queryForMap("""
                SELECT outcome, http_status, response_count, payload_hash, validation_result
                  FROM api_ingest_logs
                 WHERE collector_run_id = ?
                """, snapshot.collectorRunId());
        Map<String, Object> run = jdbc.queryForMap("""
                SELECT status, records_received, records_accepted, records_rejected, error_code
                  FROM collector_runs
                 WHERE id = ?
                """, snapshot.collectorRunId());
        Integer forbiddenColumns = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name IN ('kto_place_snapshots', 'api_ingest_logs', 'collector_runs')
                   AND column_name IN ('service_key', 'url', 'query', 'raw_body', 'overview', 'first_image')
                """, Integer.class);

        assertThat(ingest).containsEntry("outcome", "OK")
                .containsEntry("http_status", 200)
                .containsEntry("response_count", 1)
                .containsEntry("payload_hash", snapshot.payloadHash())
                .containsEntry("validation_result", "OK");
        assertThat(run).containsEntry("status", "COMPLETED")
                .containsEntry("records_received", 1)
                .containsEntry("records_accepted", 1)
                .containsEntry("records_rejected", 0)
                .containsEntry("error_code", null);
        assertThat(forbiddenColumns).isZero();

        // A validated snapshot is not yet a usable place. Under registry revision 3 this smoke was
        // green while every real KTO place was rejected one stage later, because the validator read
        // cat1/areacode - which detailCommon2 returns as empty strings - and KtoSnapshotCatalogIngest
        // requires categoryCode and areaCode to be present. The green half hid the broken half, so
        // the smoke now carries the actual response through the canonical mapping as well.
        CatalogPlace place = catalogIngest.ingest(snapshot);
        assertThat(place.categoryCode()).isNotBlank();
        assertThat(place.regionCode()).isNotBlank();
        assertThat(place.canonicalName()).isEqualTo(snapshot.title());

        Map<String, Object> canonical = jdbc.queryForMap("""
                SELECT p.category_code, p.region_code, p.status, r.source_registry_version
                  FROM places p
                  JOIN place_external_refs r ON r.place_id = p.id
                 WHERE p.id = ?
                """, place.id());
        assertThat(canonical).containsEntry("status", "ACTIVE")
                .containsEntry("source_registry_version", 4L);
        assertThat((String) canonical.get("category_code")).isNotBlank();
        assertThat((String) canonical.get("region_code")).isNotBlank();
    }

    private String compactAuditEvidence() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.outcome, l.http_status, l.response_count, l.validation_result, r.status, r.error_code
                  FROM api_ingest_logs l
                  JOIN collector_runs r ON r.id = l.collector_run_id
                 WHERE l.endpoint_key = 'KOR_SERVICE_2_DETAIL_COMMON_2'
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

    private String safeResponseShape(KtoSmokeMain.SmokeRequest request) {
        try {
            ProviderResponse response = fetcher.fetch(new KtoPlaceRequest(request.contentId(), request.contentTypeId()))
                    .get(30, TimeUnit.SECONDS);
            JsonNode root = JsonMapper.builder().build().readTree(new String(response.body(), StandardCharsets.UTF_8));
            return "directHttpStatus=" + response.status() + ",providerResult=" + providerResult(root)
                    + ",semantics=" + semantics(root, request) + ",shape=" + shape(root, 0);
        } catch (Exception failure) {
            return "directProbe=unavailable";
        }
    }

    private static String shape(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isValueNode() || depth == 6) {
            return node.getNodeType().name().toLowerCase();
        }
        if (node.isArray()) {
            return node.isEmpty() ? "[]" : "[" + shape(node.get(0), depth + 1) + "]";
        }
        List<String> fields = new ArrayList<>();
        node.properties().stream()
                .sorted(Comparator.comparing((Map.Entry<String, JsonNode> entry) -> entry.getKey()))
                .limit(20)
                .forEach(entry -> fields.add(entry.getKey() + ":" + shape(entry.getValue(), depth + 1)));
        return "{" + String.join(",", fields) + (node.size() > fields.size() ? ",…" : "") + "}";
    }

    private static String semantics(JsonNode root, KtoSmokeMain.SmokeRequest expected) {
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            item = item.size() == 1 ? item.get(0) : item;
        }
        if (!item.isObject()) {
            return "item=absent";
        }
        return "contentId=" + relation(item.path("contentid").asString(), expected.contentId())
                + ",contentType=" + relation(item.path("contenttypeid").asString(), expected.contentTypeId())
                + ",title=" + (item.path("title").asString().isBlank() ? "missing" : "present");
    }

    private static String relation(String actual, String expected) {
        return actual.isBlank() ? "missing" : actual.equals(expected) ? "match" : "mismatch";
    }

    private static String providerResult(JsonNode root) {
        if (!root.isObject() || root.has("response") || !root.has("resultCode")) {
            return "NOT_TOP_LEVEL_ERROR";
        }
        return switch (root.path("resultCode").asString()) {
            case "10" -> "INVALID_REQUEST";
            case "12" -> "SERVICE_UNAVAILABLE";
            case "20" -> "KEY_OR_PERMISSION";
            case "22" -> "DAILY_QUOTA";
            case "23" -> "RATE_LIMIT";
            case "29" -> "IP_BLOCKED";
            case "30" -> "KEY_NOT_REGISTERED";
            case "31" -> "KEY_EXPIRED";
            default -> "OTHER_PROVIDER_ERROR";
        };
    }
}
