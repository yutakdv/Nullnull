package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.ReplayManifestReader;
import io.nullnull.crowd.application.ReplayManifestReader.ReplayBatch;
import io.nullnull.operations.application.DemoCapabilityQuery;
import io.nullnull.operations.application.DemoCapabilityQuery.DemoReadinessReport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * packages/contracts/fixtures/system/demo-readiness-not-ready.json is what Frontend mocks the
 * capability screen against, and the submission flow depends on that screen telling the truth.
 *
 * <p>A fixture transcribed by hand from reading the service is a claim about the server, not
 * evidence of it - the per-capability {@code detail} strings are built in Java from
 * {@code DemoCapabilities.FLAG_VARIABLES}, and nothing would notice if one drifted. So this builds
 * the real query and asserts it produces exactly that document.
 */
class DemoReadinessContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant CHECKED = Instant.parse("2026-09-10T00:00:00Z");
    private static final ReplayManifestReader UNUSED_REPLAY = new ReplayManifestReader() {
        @Override
        public Optional<ReplayBatch> read(UUID manifestId, Instant now) {
            throw new AssertionError("replay is disabled");
        }

        @Override
        public Optional<ReplayBatch> latestFor(String sourceCode, Instant now) {
            throw new AssertionError("replay is disabled");
        }
    };

    private static Path fixture() {
        String property = System.getProperty("nullnull.fixtures.path");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("system property nullnull.fixtures.path is required");
        }
        return Path.of(property).resolve("system/demo-readiness-not-ready.json");
    }

    /** The controller's projection, kept here rather than reached through MVC: this test is about
     *  the values, and DemoReadinessController only renames them. */
    private static Map<String, Object> project(DemoReadinessReport report) {
        List<Object> capabilities = new ArrayList<>();
        for (var capability : report.capabilities()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", capability.name());
            entry.put("status", capability.status().name());
            entry.put("checkedAt", report.checkedAt().toString());
            entry.put("detail", capability.detail());
            capabilities.add(entry);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("overall", report.overall().name());
        document.put("capabilities", capabilities);
        document.put("checkedAt", report.checkedAt().toString());
        return document;
    }

    @Test
    @DisplayName("BA-003-T12 the demo readiness fixture is what DemoCapabilityQuery actually answers")
    void fixtureMatchesTheService() throws Exception {
        // Disabled capabilities never read their sources or claim readiness.
        DemoCapabilityQuery query = new DemoCapabilityQuery(false, false, false,
                UNUSED_REPLAY, Clock.fixed(CHECKED, ZoneOffset.UTC));

        JsonNode produced = JSON.valueToTree(project(query.readiness()));
        JsonNode onDisk = JSON.readTree(Files.readString(fixture(), StandardCharsets.UTF_8));

        assertThat(produced).isEqualTo(onDisk);
    }

    @Test
    @DisplayName("BA-003-T11 disabled capabilities are never advertised as ready")
    void disabledCapabilitiesAreNotReady() {
        DemoReadinessReport report = new DemoCapabilityQuery(false, false, false,
                UNUSED_REPLAY, Clock.fixed(CHECKED, ZoneOffset.UTC)).readiness();
        assertThat(report.capabilities()).isNotEmpty();
        assertThat(report.capabilities())
                .as("no capability may report READY while nothing answers it")
                .noneMatch(capability -> capability.status().name().equals("READY"));
        assertThat(report.overall().name()).isEqualTo("NOT_READY");
    }
}
