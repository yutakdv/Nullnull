package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Oversized input is rejected with 413. Beyond the swallow budget the connection is closed;
 * response timing determines whether the caller sees the 413 before that close or a transport error.
 * The raw-socket test continues uploading independently of the response and pipelines another
 * request, so unlimited swallowing cannot masquerade as a client-cancelled upload.
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
    @DisplayName("a body beyond the swallow budget closes the connection before a pipelined request")
    void aBodyFarBeyondTheSwallowBudgetLosesItsConnection() throws Exception {
        int valueLength = 32 * SWALLOW_BUDGET_BYTES;
        byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) 'x');
        // Unlike HttpClient, this writer does not stop uploading when a 413 header arrives.
        // That distinguishes a bounded swallow from a client-cancelled upload.
        try (var socket = new java.net.Socket();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            socket.setSendBufferSize(16 * 1024);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            var reader = executor.submit(() -> {
                var received = new java.io.ByteArrayOutputStream();
                try {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = socket.getInputStream().read(buffer)) != -1) {
                        received.write(buffer, 0, count);
                        assertThat(received.size()).isLessThan(64 * 1024);
                    }
                } catch (java.net.SocketException closed) {
                    // RST may arrive after the 413 was delivered, or before any response.
                }
                return received.toString(StandardCharsets.US_ASCII);
            });
            boolean bodySent = false;
            try {
                var output = socket.getOutputStream();
                String head = "POST " + HttpPolicyTestEndpoints.ECHO + " HTTP/1.1\r\n"
                        + "Host: localhost\r\nContent-Type: application/json\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n";
                output.write(head.getBytes(StandardCharsets.US_ASCII));
                output.write((Integer.toHexString(valueLength + 12) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write("{\"value\":\"".getBytes(StandardCharsets.US_ASCII));
                for (int sent = 0; sent < valueLength; sent += block.length) { output.write(block); }
                output.write("\"}".getBytes(StandardCharsets.US_ASCII));
                bodySent = true;
                output.write(("\r\n0\r\n\r\nGET /api/v1/health/live HTTP/1.1\r\n"
                        + "Host: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (java.net.SocketException closed) {
                // Bounded swallowing may close while this independent writer is still sending.
            }
            String response = reader.get(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            assertThat(bodySent).as("the server must stop the independent upload before swallowing the entire oversized body").isFalse();
            assertThat(response).doesNotContain("HTTP/1.1 200");
            if (!response.isEmpty()) { assertThat(response).startsWith("HTTP/1.1 413"); }
        }
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
