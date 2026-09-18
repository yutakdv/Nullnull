package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * BA-070-T2 on the one channel MockMvc cannot reach: Tomcat's own Cookie header parser.
 *
 * <p>RedactionAndDenylistIT sweeps every channel a request carries, but through MockMvc, which never runs Tomcat's
 * parser - so it could not see that Tomcat writes a Cookie pair it cannot parse to the log verbatim (tomcat-embed-core
 * 11.0.24, {@code http/parser/Cookie.logInvalidHeader}: INFO the first time, DEBUG after). A pair such as
 * {@code name=<token> junk}, or a folded {@code a=1, name=<token>}, carries a live session token into that line.
 * application.yaml sets the parser's logger to WARN, which silences both levels.
 *
 * <p>Tomcat logs at INFO only once per suppression window, per JVM, and an earlier test that sends a malformed
 * cookie spends that one chance - after which even an unconfigured logger stays quiet at INFO, and this sweep would
 * pass for the wrong reason. So the test gives the chance back before each request. If Tomcat renames the field,
 * the reflection fails loudly instead of the test going vacuous.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "nullnull.ai.base-url=http://127.0.0.1:1")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-070 redaction on a real server")
class CookieParserLogRedactionIT {

    private static final String NAME = "__Host-nullnull_session";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void listen() {
        logs = new ListAppender<>();
        logs.start();
        rootLogger().addAppender(logs);
    }

    @AfterEach
    void stopListening() {
        rootLogger().detachAppender(logs);
    }

    @Test
    @DisplayName("BA-070-T2 a cookie the container cannot parse never reaches a log line")
    void aDroppedCookieIsNotLogged() throws Exception {
        // Fake, but shaped like the thing that must never be logged: it rides in exactly where a session token would.
        String canary = "REDACT" + UUID.randomUUID().toString().replace("-", "");
        for (String header : List.of(NAME + "=" + canary + " junk", "other=1, " + NAME + "=" + canary)) {
            giveTomcatItsInfoChanceBack();
            assertThat(me(header).statusCode()).as(header).isEqualTo(401);
        }
        // The server logged something for these requests, or the sweep below proves nothing.
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(canary);
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(canary);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(canary);
        });
    }

    /** Resets the per-JVM "last logged at INFO" time of the parser's UserDataHelper, so its next drop tries INFO. */
    private static void giveTomcatItsInfoChanceBack() throws ReflectiveOperationException {
        Field helperField = org.apache.tomcat.util.http.parser.Cookie.class.getDeclaredField("invalidCookieLog");
        helperField.setAccessible(true);
        Object helper = helperField.get(null);
        Field lastInfo = helper.getClass().getDeclaredField("lastInfoTime");
        lastInfo.setAccessible(true);
        lastInfo.setLong(helper, 0L);
    }

    private HttpResponse<String> me(String cookieHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .header("Cookie", cookieHeader)
                .timeout(TIMEOUT)
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
