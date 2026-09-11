package io.nullnull.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RecommendationUnavailableException;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import nullnull.testsupport.http.HttpPolicyTestEndpoints;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * BA-003: unknown-field strictness belongs to the PUBLIC HTTP boundary, and only there.
 *
 * <p>{@code spring.jackson.deserialization.fail-on-unknown-properties} was set on the shared Boot
 * mapper, which also governs how {@code HttpRecommendationGateway} parses apps/ai - the gateway's
 * {@code RestClient} is built from the Boot-managed builder. apps/api and apps/ai are separate
 * services, so an ordinary rolling deploy runs a newer apps/ai carrying one additive response field
 * against an apps/api that has not been redeployed: strict parsing would fail conversion on every
 * call for the length of the rollout, with a fleet-wide fallback and only a {@code log.warn} to show
 * for it. Both directions are asserted here, in one place, because a fix to one is a risk to the
 * other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        HttpPolicyTestEndpoints.class})
@DisplayName("BA-003 JSON strictness at the public boundary and the internal gateway")
class RecommendationJsonBoundaryIT {

    private static final String POLICY_HASH = "a".repeat(64);

    /** A response from an apps/ai one deploy ahead: every declared field, plus one this build never saw. */
    private static final String POLICY_WITH_AN_ADDED_FIELD = """
            {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
             "serviceVersion":"0.1.0","fieldAddedByANewerService":"whatever"}
            """.formatted(POLICY_HASH);

    /** A response that DROPPED a declared field: still a contract break, still refused. */
    private static final String POLICY_WITHOUT_ITS_HASH = """
            {"policyVersion":"policy-v1","pipelineVersion":"nullnull-ai-pipeline-v1",
             "serviceVersion":"0.1.0"}
            """;

    private static final AtomicReference<String> POLICY_BODY = new AtomicReference<>();
    private static final HttpServer STUB = startStub();

    @Autowired
    RecommendationGateway gateway;

    @Autowired
    MockMvcTester mvc;

    @DynamicPropertySource
    static void recommendationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("nullnull.ai.base-url",
                () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetStub() {
        POLICY_BODY.set(POLICY_WITH_AN_ADDED_FIELD);
    }

    @Test
    @DisplayName("BA-003-T2 a request body field outside the schema is still refused with 400")
    void anUnknownFieldInAnInboundRequestIsStillRefused() {
        MvcTestResult result = mvc.post().uri(HttpPolicyTestEndpoints.ECHO)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"seoul\",\"unknownField\":\"x\"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_REQUEST");
    }

    @Test
    @DisplayName("an apps/ai response carrying an added field is parsed, not turned into a fallback")
    void anAddedFieldInAnAppsAiResponseIsTolerated() {
        PolicyDescriptor descriptor = gateway.policy();

        assertThat(descriptor.policyVersion()).isEqualTo("policy-v1");
        assertThat(descriptor.policyHash()).isEqualTo(POLICY_HASH);
        assertThat(descriptor.pipelineVersion()).isEqualTo("nullnull-ai-pipeline-v1");
    }

    @Test
    @DisplayName("an apps/ai response that dropped a declared field is still refused")
    void aMissingFieldInAnAppsAiResponseStillFails() {
        // Tolerating an ADDED field is not the same as accepting anything: the records still require
        // what the internal contract declares, so this direction must stay a failure.
        POLICY_BODY.set(POLICY_WITHOUT_ITS_HASH);

        assertThatThrownBy(() -> gateway.policy())
                .isInstanceOf(RecommendationUnavailableException.class);
    }

    /**
     * A minimal apps/ai stand-in on the JDK's own HTTP server: no dependency, and a real socket, so
     * the response really travels through the gateway's message converters.
     */
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", RecommendationJsonBoundaryIT::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot start the apps/ai stub", exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String body = exchange.getRequestURI().getPath().endsWith("/internal/v1/policy")
                ? POLICY_BODY.get()
                : "{}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
