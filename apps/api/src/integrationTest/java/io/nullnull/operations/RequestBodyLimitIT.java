package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.io.ByteArrayInputStream;
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
 * BA-003-T2, the half MockMvc cannot reach: a body with no declared length.
 *
 * <p>{@code MockHttpServletRequest.setContent} always sets a Content-Length, so a MockMvc call can
 * only exercise the declared-length refusal. A chunked body is refused by a different mechanism - the
 * bytes are counted as a converter reads them - and only a real container and a real client produce
 * one, so this runs against Tomcat on a random port with {@code java.net.http.HttpClient}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "nullnull.http.max-request-body-bytes=8192",
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@Import({TestcontainersConfiguration.class, HttpPolicyTestEndpoints.class})
@DisplayName("BA-003 request body bound on a real server")
class RequestBodyLimitIT {

    private static final int LIMIT_BYTES = 8192;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Test
    @DisplayName("BA-003-T2 a chunked body that passes the bound while streaming is refused with 413")
    void aChunkedBodyOverTheBoundIsRefused() throws Exception {
        byte[] oversized = json(LIMIT_BYTES * 2);
        BodyPublisher chunked = BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized));
        // An unknown length is exactly what makes the client send Transfer-Encoding: chunked, so the
        // server has no Content-Length to refuse and has to count the bytes it reads.
        assertThat(chunked.contentLength()).isEqualTo(-1);

        HttpResponse<String> response = send(chunked);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type"))
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }

    @Test
    @DisplayName("BA-003-T2 a declared Content-Length over the bound is refused with 413 by Tomcat too")
    void aDeclaredContentLengthOverTheBoundIsRefused() throws Exception {
        BodyPublisher declared = BodyPublishers.ofByteArray(json(LIMIT_BYTES * 2));
        assertThat(declared.contentLength()).isGreaterThan(LIMIT_BYTES);

        HttpResponse<String> response = send(declared);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @Test
    @DisplayName("a chunked body under the bound still reaches the handler")
    void aChunkedBodyUnderTheBoundIsAccepted() throws Exception {
        byte[] accepted = json(LIMIT_BYTES / 2);
        HttpResponse<String> response =
                send(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(accepted)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"length\":" + (LIMIT_BYTES / 2) + "}");
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
