package io.nullnull.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.shared.http.RequestIdFilter;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Controller responses are validated against docs/api/openapi.yaml component schemas with a JSON
 * Schema 2020-12 evaluator, and every implemented operationId must exist in the contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("OpenAPI contract: System tag")
class SystemContractTest {

    static OpenApiDocument openApi;
    static JsonSchemaCheck schemaCheck;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    io.nullnull.identity.application.SessionService sessions;

    @BeforeAll
    static void loadContract() {
        openApi = OpenApiDocument.load();
        schemaCheck = new JsonSchemaCheck(openApi);
    }

    @Test
    void contractIsTheZeroPointTwoLine() {
        // docs/api/openapi.yaml is edited by the docs owner; only the contract line is pinned here.
        assertThat(openApi.version()).matches("^0\\.2\\.\\d+(-[0-9A-Za-z.]+)?$");
    }

    @Test
    void implementedOperationsExistInContract() {
        TreeSet<String> declared = openApi.operationIds();
        assertThat(declared).hasSizeGreaterThanOrEqualTo(48);
        assertThat(declared).containsAll(ImplementedOperationsRegistry.IMPLEMENTED);
    }

    @Test
    void demoReadinessRequiresSession() {
        assertThat(mvc.get().uri("/api/v1/demo/readiness").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void livenessMatchesHealthStatusSchema() {
        MvcTestResult result = mvc.get().uri("/api/v1/health/live").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(schemaCheck.validate("HealthStatus", body(result))).isEmpty();
    }

    @Test
    void readinessMatchesReadinessStatusSchema() {
        MvcTestResult result = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(schemaCheck.validate("ReadinessStatus", body(result))).isEmpty();
    }

    @Test
    void demoReadinessMatchesDemoReadinessSchema() {
        var bootstrap = sessions.bootstrap(null, null, null);
        MvcTestResult result = mvc.get().uri("/api/v1/demo/readiness")
                .cookie(new jakarta.servlet.http.Cookie("__Host-nullnull_session", bootstrap.cookie)).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(schemaCheck.validate("DemoReadiness", body(result))).isEmpty();
        // The capability vocabulary is a server decision (CapabilityStatus.name has no enum in the
        // contract), so the contract check alone cannot pin it; DemoCapabilityQueryTest does.
        assertThat(result).bodyJson().extractingPath("$.capabilities[*].name").asArray()
                .containsExactly("live", "replay", "optimization");
    }

    @Test
    void notFoundMatchesProblemSchema() {
        MvcTestResult result = mvc.get().uri("/api/v1/missing").exchange();
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith("application/problem+json");
        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(schemaCheck.validate("Problem", body(result))).isEmpty();
    }

    @Test
    void wrongMethodMatchesProblemSchema() {
        MvcTestResult result = mvc.post().uri("/api/v1/health/live").exchange();
        assertThat(result).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(schemaCheck.validate("Problem", body(result))).isEmpty();
    }

    @Test
    void invalidExampleIsRejectedByTheEvaluator() {
        assertThat(schemaCheck.validate("HealthStatus", "{\"status\":\"DOWN\",\"time\":\"not-a-date\"}"))
                .as("evaluator must reject const/format violations, otherwise the contract check is vacuous")
                .isNotEmpty();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
