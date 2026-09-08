package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import io.nullnull.operations.application.ReadinessProbe.ProbeResult;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.infrastructure.DatabaseReadinessProbe;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * BA-003-T1: a failure reaches exactly one health scope.
 *
 * <p>The required database probe is replaced here rather than the DataSource itself: breaking the real
 * DataSource would also stop Flyway and JPA, so the context would never start and the test would prove
 * nothing about scopes. The optional side needs no substitution - the recommendation service is
 * genuinely out of reach at the configured address.
 */
@SpringBootTest(properties = "nullnull.ai.base-url=http://127.0.0.1:1")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-003 health scopes")
class HealthScopeIT {

    @MockitoBean
    DatabaseReadinessProbe database;

    @Autowired
    MockMvcTester mvc;

    private void databaseReports(ProbeStatus status, String detail) {
        given(database.name()).willReturn("database");
        given(database.required()).willReturn(true);
        given(database.probe(any())).willAnswer(call ->
                new ProbeResult(status, call.getArgument(0, Instant.class), detail));
    }

    @Test
    @DisplayName("BA-003-T1 a database failure takes readiness down and leaves liveness untouched")
    void aDatabaseFailureAffectsReadinessOnly() {
        databaseReports(ProbeStatus.UNAVAILABLE, "connection unavailable");

        MvcTestResult readiness = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(readiness).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readiness).bodyJson().extractingPath("$.code").isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(readiness).bodyJson().extractingPath("$.retryable").isEqualTo(true);
        assertThat(readiness.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5");

        // The process is healthy; only its dependency is not. Failing liveness here would make the
        // orchestrator restart a task that has nothing wrong with it.
        MvcTestResult liveness = mvc.get().uri("/api/v1/health/live").exchange();
        assertThat(liveness).hasStatus(HttpStatus.OK);
        assertThat(liveness).bodyJson().extractingPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("BA-003-T1 an optional source failure degrades readiness without making it NOT_READY")
    void anOptionalSourceFailureOnlyDegrades() {
        databaseReports(ProbeStatus.READY, null);

        MvcTestResult readiness = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(readiness).hasStatus(HttpStatus.OK);
        assertThat(readiness).bodyJson().extractingPath("$.status").isEqualTo("DEGRADED");
        assertThat(readiness).bodyJson().extractingPath("$.checks[?(@.name=='database')].status")
                .asArray().containsExactly("READY");
        assertThat(readiness).bodyJson().extractingPath("$.checks[?(@.name=='recommendation')].status")
                .asArray().containsExactly("UNAVAILABLE");
        assertThat(mvc.get().uri("/api/v1/health/live").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    @DisplayName("BA-003-T1 an infrastructure failure never changes a product capability's answer")
    void aDatabaseFailureDoesNotChangeTheDemoCapabilities() {
        databaseReports(ProbeStatus.UNAVAILABLE, "connection unavailable");

        // The two lists are separate namespaces: /health/ready is 503 above while this endpoint keeps
        // answering, because no demo capability claims to be backed by the database.
        MvcTestResult demo = mvc.get().uri("/api/v1/demo/readiness").exchange();
        assertThat(demo).hasStatus(HttpStatus.OK);
        assertThat(demo).bodyJson().extractingPath("$.overall").isEqualTo("NOT_READY");
        assertThat(demo).bodyJson().extractingPath("$.capabilities[*].name").asArray()
                .containsExactly("live", "replay", "optimization");
        assertThat(demo).bodyJson().extractingPath("$.capabilities[*].name").asArray()
                .doesNotContain("database", "jobs", "recommendation");
    }
}
