package io.nullnull.trip;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-055: how an {@code apps/ai} that did not give a usable answer reaches the client, through the REAL
 * gateway over a real socket.
 *
 * <p>The distinction is the whole point. An unanswered service is 503 SOURCE_UNAVAILABLE and
 * retryable - never an EMPTY 200, which would tell the user their dates have no options. A service that
 * refused the request, or answered outside its contract, is 500 and not retryable, because the same
 * request would get the same answer. A mocked gateway could only show how the service maps an
 * exception it was handed; this shows which exception a real transport failure and a real 422 become.
 */
// The read timeout is shortened so the STALL case can outlast it without the suite waiting the
// production five seconds. It bounds the whole exchange, body included (JdkClientHttpRequest's
// TimeoutHandler closes the body stream), which is what that case relies on.
@SpringBootTest(properties = {"nullnull.catalog.public-enabled=true", "nullnull.ai.read-timeout=PT1S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-055 trip draft preview when apps/ai does not answer usably")
class TripDraftPreviewGatewayIT {

    enum Mode { DROP_CONNECTION, REJECT_422, OUTSIDE_THE_POOL, WRONG_FIELD_TYPE, CUT_OFF, STALL }

    private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.DROP_CONNECTION);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer STUB = startStub();

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void recommendationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("nullnull.ai.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetCalls() {
        CALLS.set(0);
    }

    @Test
    @DisplayName("BA-055-T5 an apps/ai that drops the connection is 503 SOURCE_UNAVAILABLE, not an EMPTY draft")
    void anUnansweredServiceIsAnOutageNotAnEmptyDraft() throws Exception {
        MODE.set(Mode.DROP_CONNECTION);

        preview().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.state").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    @Test
    @DisplayName("BA-055-T12 an apps/ai that rejects the request with a 4xx is 500 INTERNAL_ERROR, not 503")
    void aRejectedRequestIsNotAnOutage() throws Exception {
        MODE.set(Mode.REJECT_422);

        preview().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false));
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    @Test
    @DisplayName("BA-055-T20 an answer verifyDraft refuses reaches the client as 500, not as a draft")
    void anAnswerOutsideTheContractIsAnInternalError() throws Exception {
        MODE.set(Mode.OUTSIDE_THE_POOL);

        preview().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false));
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    /**
     * #250, the side of the line that moved. The body arrives whole and is valid JSON; one declared field
     * has a type it cannot have. Asking again gets the same body, so this is not the outage T5 is.
     */
    @Test
    @DisplayName("an apps/ai answer that does not parse as the contract is 500 INTERNAL_ERROR, not a retryable 503")
    void anUnreadableAnswerIsAnInternalErrorNotAnOutage() throws Exception {
        MODE.set(Mode.WRONG_FIELD_TYPE);

        preview().andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false));
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    /**
     * #250, the side that must NOT move. The body stops short of its Content-Length: the parse fails, but
     * because the read did. It reaches the gateway as a parse error with the I/O failure underneath, so
     * a line drawn on the outer type would call this outage a contract break.
     */
    @Test
    @DisplayName("an apps/ai body cut off part way is 503 SOURCE_UNAVAILABLE, not a contract break")
    void aBodyCutOffPartWayIsAnOutage() throws Exception {
        MODE.set(Mode.CUT_OFF);

        preview().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    /** #250, the same side: the body starts and then stops arriving, past the read timeout. */
    @Test
    @DisplayName("an apps/ai body that stops arriving past the read timeout is 503 SOURCE_UNAVAILABLE")
    void aBodyThatStallsPastTheTimeoutIsAnOutage() throws Exception {
        MODE.set(Mode.STALL);

        preview().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
        org.assertj.core.api.Assertions.assertThat(CALLS.get()).as("the stub was really reached").isEqualTo(1);
    }

    private ResultActions preview() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        return mvc.perform(post("/api/v1/trip-drafts/preview")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-05\",\"timezone\":\"Asia/Seoul\"}"));
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/drafts/compose", TripDraftPreviewGatewayIT::respond);
            // STALL holds its handler past the client's timeout; on the default single dispatcher thread
            // the next case's request would queue behind it and could time out itself.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot start the apps/ai stub", exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        CALLS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        switch (MODE.get()) {
            case DROP_CONNECTION -> {
                // Closing without a status line is what a crashed or restarting container looks like
                // from here: the socket answers and then nothing arrives.
                exchange.close();
            }
            case REJECT_422 -> send(exchange, 422, """
                    {"detail":[{"loc":["body","pool"],"msg":"rejected","type":"value_error"}]}""");
            case OUTSIDE_THE_POOL -> send(exchange, 200, """
                    {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
                     "state":"READY","stops":[{"placeId":"00000000-0000-7000-8000-0000000000ff","date":"2026-10-04",
                     "position":0,"hoursState":"UNKNOWN"}],"reasons":[],"evaluated":0,"rejectedByReason":{}}
                    """.formatted("a".repeat(64)));
            case WRONG_FIELD_TYPE -> send(exchange, 200, """
                    {"policyVersion":"policy-v1","policyHash":"%s","pipelineVersion":"nullnull-ai-pipeline-v1",
                     "state":"EMPTY","stops":[],"reasons":[],"evaluated":"many","rejectedByReason":{}}
                    """.formatted("a".repeat(64)));
            case CUT_OFF -> {
                // Promise the whole body and send half: the connection closes short of Content-Length.
                byte[] half = "{\"policyVersion\":\"policy-v1\",\"policyHash\":\"".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, half.length * 2L);
                OutputStream out = exchange.getResponseBody();
                out.write(half);
                out.flush();
                exchange.close();
            }
            case STALL -> {
                // Enough for the parser to start, then nothing until well after the one-second timeout.
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("{\"policyVersion\":\"policy-v1\",".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
