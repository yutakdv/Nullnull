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

    /**
     * Every declared-but-unenforced security requirement in this slice, asserted so none can be
     * forgotten. {@code getDemoReadiness} declares {@code sessionCookie} while no session layer
     * exists (BA-010 brings it), and this test pins BOTH halves of that for EACH entry: the contract
     * really does ask for a scheme, and the route really does still answer an unauthenticated call.
     *
     * <p>The loop is the point. Reading one hardcoded operationId enforced the registry's honesty
     * invariant for exactly that entry, so any id added later - including one that declares
     * {@code security: []} and therefore deviates from nothing - joined the set with the suite green.
     *
     * <p>It is written to fail on the fix, not on the deviation. The day a session layer makes one of
     * these routes a 401, the {@code hasStatus(OK)} below goes red and whoever wrote that layer
     * removes the entry from {@code SECURITY_NOT_YET_ENFORCED} in the same change.
     */
    @Test
    void declaredSecurityIsNotEnforcedYet() {
        assertThat(ImplementedOperationsRegistry.SECURITY_NOT_YET_ENFORCED)
                .as("an unenforced deviation must name an operation this service actually serves")
                .isSubsetOf(ImplementedOperationsRegistry.IMPLEMENTED)
                .as("the deviation list is not empty while BA-010 has not landed")
                .isNotEmpty();

        for (String operationId : ImplementedOperationsRegistry.SECURITY_NOT_YET_ENFORCED) {
            assertThat(openApi.declaredSecuritySchemes(operationId))
                    .as("%s is only a deviation while the contract declares a scheme for it",
                            operationId)
                    .isNotEmpty();

            OpenApiDocument.Route route = openApi.routeOf(operationId);
            MvcTestResult result = mvc.method(HttpMethod.valueOf(route.method()))
                    .uri(route.path()).exchange();

            assertThat(result)
                    .as("%s: no cookie is required yet, and this must go red when one is", operationId)
                    .hasStatus(HttpStatus.OK);
        }
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
        MvcTestResult result = mvc.get().uri("/api/v1/demo/readiness").exchange();
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
