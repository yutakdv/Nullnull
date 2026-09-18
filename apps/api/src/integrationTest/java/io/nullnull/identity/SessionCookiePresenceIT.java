package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * BA-010-T4 and BA-010-T8 on a real container: what Tomcat's own Cookie header parser does, which MockMvc cannot reach.
 *
 * <p>MockMvc's {@code .cookie()} bypasses that parser, and a raw MockMvc Cookie header never becomes a parsed cookie
 * at all, so only a real container shows the two places the parser and the raw header disagree. Measured on
 * tomcat-embed-core 11.0.24: a value holding a backslash, comma or space disappears from {@code getCookies()}, while
 * {@code name =value} and {@code name<TAB>=value} parse as ours. A cookie the client sent must never be reported as
 * missing in either direction - the Frontend answers that report by starting a new session, which is a different
 * owner.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "nullnull.ai.base-url=http://127.0.0.1:1")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-010 session cookie presence on a real server")
class SessionCookiePresenceIT {

    private static final String NAME = "__Host-nullnull_session";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @LocalServerPort
    int port;

    @Autowired
    SessionService sessions;

    @Test
    @DisplayName("BA-010-T4 a request that sends no session cookie gets missingCredential on a real server")
    void noSessionCookieIsNamed() throws Exception {
        for (String header : java.util.Arrays.asList(null, "other=1")) {
            HttpResponse<String> response = me(header);
            assertThat(response.statusCode()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(response.body()).as(String.valueOf(header)).contains("\"missingCredential\":\"SESSION_COOKIE\"");
        }
    }

    @Test
    @DisplayName("BA-010-T8 a cookie the container drops is still a sent cookie, not a missing one")
    void aDroppedCookieIsNotMissing() throws Exception {
        // The last one is two headers folded into one by an intermediary: Tomcat drops the whole folded pair, and a
        // raw scan that split only on ';' would find no pair named ours. The values are fake - Tomcat logs a dropped
        // pair verbatim, so a real token must not be the thing a test sends here.
        for (String header : List.of(NAME + "=bad\\value", NAME + "=a b", NAME + "=a,b", "other=1, " + NAME + "=x")) {
            HttpResponse<String> response = me(header);
            assertThat(response.statusCode()).as(header).isEqualTo(401);
            assertThat(response.body()).as(header).contains("\"code\":\"UNAUTHORIZED\"").doesNotContain("missingCredential");
        }
    }

    @Test
    @DisplayName("BA-010-T8 a cookie the container accepts is never reported missing")
    void anAcceptedSpellingAuthenticates() throws Exception {
        String token = sessions.bootstrap(null, null, null).cookie;
        for (String header : List.of(NAME + " =" + token, NAME + "\t=" + token)) {
            assertThat(me(header).statusCode()).as(header).isEqualTo(200);
        }
    }

    private HttpResponse<String> me(String cookieHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/me"))
                .timeout(TIMEOUT)
                .GET();
        if (cookieHeader != null) { builder.header("Cookie", cookieHeader); }
        HttpRequest request = builder.build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
