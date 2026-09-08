package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.http.RequestIdFilter;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** BA-003 health/readiness over the servlet path /api/v1 with a real database behind readiness. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
// The recommendation service is deliberately out of reach here, so the optional probe is asserted on
// its own behaviour instead of on whether a developer happens to run apps/ai on the usual port.
@TestPropertySource(properties = "nullnull.ai.base-url=http://127.0.0.1:1")
@DisplayName("BA-003 system endpoints")
class SystemEndpointsIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void livenessReportsUpWithRequestId() {
        MvcTestResult result = mvc.get().uri("/api/v1/health/live").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UP");
        assertThat(result).bodyJson().extractingPath("$.time").asString().endsWith("Z");
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void readinessIsDegradedWhileOnlyTheOptionalRecommendationProbeFails() {
        // The database comes from Testcontainers and the recommendation probe cannot connect, so the
        // API degrades and stays 200: an optional capability never makes it NOT_READY.
        MvcTestResult result = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("DEGRADED");
        assertThat(result).bodyJson().extractingPath("$.checks[?(@.name=='database')].status")
                .asArray().containsExactly("READY");
        assertThat(result).bodyJson().extractingPath("$.checks[?(@.name=='recommendation')].status")
                .asArray().containsExactly("UNAVAILABLE");
    }

    @Test
    void readinessReportsTheJobRuntimeAsDegradedWhileItIsNotRunning() {
        // Every suite runs with nullnull.jobs.enabled=false. That used to publish jobs: READY while
        // nothing was claimed and neither retention sweep ran, because the probe only counted dead
        // letters and a queue that consumes nothing produces none.
        MvcTestResult result = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.checks[?(@.name=='jobs')].status")
                .asArray().containsExactly("DEGRADED");
        assertThat(result).bodyJson().extractingPath("$.checks[?(@.name=='jobs')].detail")
                .asArray().singleElement().asString().contains("nullnull.jobs.enabled");
    }

    @Test
    void demoReadinessPublishesProductCapabilitiesAndNotInfrastructureProbes() {
        // A capability with no source behind it is UNAVAILABLE, never READY (BA-003 safety line), and
        // the list never repeats a /health/ready probe name.
        MvcTestResult result = mvc.get().uri("/api/v1/demo/readiness").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.overall").isEqualTo("NOT_READY");
        assertThat(result).bodyJson().extractingPath("$.checkedAt").asString().endsWith("Z");
        assertThat(result).bodyJson().extractingPath("$.capabilities[*].name").asArray()
                .containsExactly("live", "replay", "optimization");
        assertThat(result).bodyJson().extractingPath("$.capabilities[*].status").asArray()
                .containsOnly("UNAVAILABLE");
        assertThat(result).bodyJson().extractingPath("$.capabilities[?(@.name=='live')].detail")
                .asArray().singleElement().asString().contains("FEATURE_LIVE_DATA");
    }

    @Test
    void unknownRouteIsProblemJsonWithMatchingRequestId() {
        MvcTestResult result = mvc.get().uri("/api/v1/does-not-exist")
                .header(RequestIdFilter.HEADER, "req_it-00000001").exchange();
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.requestId").isEqualTo("req_it-00000001");
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo("/api/v1/does-not-exist");
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isEqualTo("req_it-00000001");
    }
}
