package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import nullnull.testsupport.http.HttpPolicyTestEndpoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * BA-003-T2 at the SHIPPED bound, and the second regime that bound has.
 *
 * <p>{@link RequestBodyLimitIT} runs with {@code max-request-body-bytes=8192}, 32x below what the
 * service ships, which is what proves the property is wired rather than hardcoded - and is also why
 * it structurally cannot see this: everything it sends is far inside Tomcat's swallow budget. This
 * class deliberately configures NO override, so it exercises the number in application.yaml.
 *
 * <p>A refused body has to be swallowed before the 413 can be written and the connection reused.
 * {@code server.tomcat.max-swallow-size} bounds that, so two regimes exist and both are deliberate:
 * an overshoot within the budget gets a clean 413 Problem, while a body far beyond it has its
 * connection dropped with no HTTP response at all. The second one is correct and safe - reading an
 * unbounded body that was already rejected is a denial-of-service path - but it is NOT a Problem
 * response, and docs/operations/ENVIRONMENT.md §3 documents both next to APP_MAX_REQUEST_BODY_BYTES.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "nullnull.ai.base-url=http://127.0.0.1:1")
@Import({TestcontainersConfiguration.class, HttpPolicyTestEndpoints.class})
@DisplayName("BA-003 request body bound at the shipped value")
class RequestBodySwallowBoundIT {

    /** APP_MAX_REQUEST_BODY_BYTES as application.yaml ships it. Nothing overrides it here. */
    private static final int SHIPPED_LIMIT_BYTES = 262_144;

    /** server.tomcat.max-swallow-size as application.yaml sets it: 8 x the accepted bound. */
    private static final int SWALLOW_BUDGET_BYTES = 8 * SHIPPED_LIMIT_BYTES;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    @Test
    @DisplayName("a chunked body just under the shipped bound is accepted")
    void aBodyJustUnderTheShippedBoundIsAccepted() throws Exception {
        // Pins the shipped number itself: lowering APP_MAX_REQUEST_BODY_BYTES breaks this.
        int valueLength = SHIPPED_LIMIT_BYTES - 1024;
        byte[] accepted = json(valueLength);
        assertThat(accepted.length).isLessThan(SHIPPED_LIMIT_BYTES);

        HttpResponse<String> response =
                send(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(accepted)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"length\":" + valueLength + "}");
    }

    @Test
    @DisplayName("BA-003-T2 an overshoot inside the swallow budget is a clean 413 Problem")
    void anOvershootInsideTheSwallowBudgetIsACleanProblem() throws Exception {
        byte[] oversized = json(SHIPPED_LIMIT_BYTES + 1024);
        assertThat(oversized.length).isGreaterThan(SHIPPED_LIMIT_BYTES)
                .isLessThan(SWALLOW_BUDGET_BYTES);

        HttpResponse<String> response =
                send(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type"))
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }

    @Test
    @DisplayName("a body far beyond the swallow budget loses its connection instead of getting a 413")
    void aBodyFarBeyondTheSwallowBudgetLosesItsConnection() {
        // Four times the budget. What the budget actually measures is the REMAINDER left unread when
        // the bound trips, so the boundary sits near (bound + budget); four times the budget is well
        // past it either way. Tomcat stops reading that remainder and closes the connection, so the
        // caller gets a transport failure and no Problem body. That is the documented second regime,
        // not a defect: the alternative is reading an unbounded body that has already been rejected.
        byte[] farTooLarge = json(4 * SWALLOW_BUDGET_BYTES);
        assertThat(farTooLarge.length).isGreaterThan(SWALLOW_BUDGET_BYTES);

        assertThatThrownBy(() -> send(
                BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(farTooLarge))))
                .as("no HTTP response is delivered once the connection is dropped")
                .isInstanceOf(IOException.class);
    }

    private HttpResponse<String> send(BodyPublisher body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + HttpPolicyTestEndpoints.ECHO))
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(body)
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static byte[] json(int valueLength) {
        return ("{\"value\":\"" + "x".repeat(valueLength) + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
