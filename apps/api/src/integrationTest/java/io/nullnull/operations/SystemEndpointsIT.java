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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** BA-003 health/readiness over the servlet path /api/v1 with a real database behind readiness. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
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
    void readinessReportsDatabaseReady() {
        MvcTestResult result = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("READY");
        assertThat(result).bodyJson().extractingPath("$.checks[0].name").isEqualTo("database");
        assertThat(result).bodyJson().extractingPath("$.checks[0].status").isEqualTo("READY");
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
