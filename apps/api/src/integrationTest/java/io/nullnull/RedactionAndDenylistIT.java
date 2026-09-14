package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-070-T2, which is two clauses wearing one id.
 *
 * <p>"A redaction canary is zero" and "an API response carries nothing on the PII/secret denylist"
 * are different mechanisms with different failure modes: the first is about what the logging path
 * writes down, the second about what a response body hands to a client. A single test covering
 * either one would satisfy the aggregator while leaving the other unproven, so there are two cases
 * here and each carries the id. The card would be clearer split into T2 and T4 - flagged, not done,
 * because renumbering a card mid-flight is worse than a note.
 *
 * <p>Both sweep rather than enumerate. A check that named today's log statements, or today's secret
 * properties, would keep passing after somebody adds tomorrow's - and the one that leaks is the one
 * nobody thought to name.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=redaction-cursor-secret-that-is-long-enough-abc",
        "NULLNULL_DELETION_TOKEN_SECRET=redaction-deletion-secret-long-enough-xyz"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-070 redaction and response denylist")
class RedactionAndDenylistIT {

    private static final String ORIGIN = "http://localhost:5173";

    private String canary;
    private ListAppender<ILoggingEvent> logs;

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired ConfigurableEnvironment environment;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void planTheCanary() {
        canary = "REDACT" + UUID.randomUUID().toString().replace("-", "");
        logs = new ListAppender<>();
        logs.start();
        rootLogger().addAppender(logs);
    }

    @AfterEach
    void detach() {
        rootLogger().detachAppender(logs);
        jdbc.update("DELETE FROM place_localizations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-070-T2 nothing a request carried reaches a log line")
    void noChannelOfARequestReachesTheLog() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");

        // Every channel a request can carry a value in. The query string is the one the access log
        // can be configured to keep, and its default is off - which is exactly the setting that would
        // stop being exercised if this test drove only bodies.
        // The query goes in the URI, not through param(): MockMvc's param() fills the parameter map
        // and leaves getQueryString() null, so a test written that way never reaches the branch of
        // AccessLogFilter that can print a query at all. Measured - with include-query forced on, the
        // param() version stayed green.
        //
        // listTrips rather than a search: searchPlaces is a POST with its term in the body, precisely
        // so a search term never reaches a URL, so it is the wrong operation to prove a URL is not
        // logged. listTrips takes real query parameters.
        mvc.perform(get("/api/v1/trips?status=" + canary).cookie(cookie(owner.cookie)));
        mvc.perform(post("/api/v1/trips")
                .cookie(cookie(owner.cookie)).header("Origin", ORIGIN)
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", "redaction-key-" + canary)
                .contentType("application/json")
                .content("{\"title\":\"" + canary + "\",\"startDate\":\"2026-10-05\""
                        + ",\"endDate\":\"2026-10-06\",\"timezone\":\"Asia/Seoul\""
                        + ",\"planningLevel\":\"NOTHING\",\"interests\":[]}"));
        // A rejected credential: the value is a secret that was never valid, and a 401 is the branch
        // most likely to quote what it refused.
        mvc.perform(get("/api/v1/me").cookie(cookie(canary)));
        mvc.perform(get("/api/v1/trips/{id}", UUID.randomUUID()).cookie(cookie(owner.cookie)));

        // The suite ran and the appender caught something, or the sweep below proves nothing.
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(canary);
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(canary);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(canary);
        });
    }

    @Test
    @DisplayName("BA-070-T2 no response body carries a configured secret")
    void noResponseBodyCarriesAConfiguredSecret() throws Exception {
        Set<String> secrets = configuredSecrets();
        // Without this the sweep below is a loop over an empty list, which passes for any server.
        assertThat(secrets).as("the denylist must not be empty, or this proves nothing").isNotEmpty();

        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        List<MvcResult> answers = new ArrayList<>();
        answers.add(mvc.perform(post("/api/v1/demo/sessions").header("Origin", ORIGIN)).andReturn());
        answers.add(mvc.perform(get("/api/v1/me").cookie(cookie(owner.cookie))).andReturn());
        // A cursor is the one response field derived FROM a secret, so it is the field most likely to
        // carry one out. searchPlaces mints one only when there is a next page, so the fixture makes
        // one: two matching places and a limit of one. Without that this call returns nextCursor=null
        // and the whole cursor path goes unswept - measured, by putting the signing key into the
        // cursor payload and watching this case stay green.
        seedPlace("경복궁 하나");
        seedPlace("경복궁 둘");
        MvcResult paged = mvc.perform(post("/api/v1/places/search")
                .cookie(cookie(owner.cookie)).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"query\":\"경복궁\",\"locale\":\"ko-KR\",\"limit\":1}")).andReturn();
        assertThat(paged.getResponse().getContentAsString())
                .as("the cursor path must actually be exercised").contains("\"nextCursor\":\"");
        answers.add(paged);
        answers.add(mvc.perform(get("/api/v1/trips").cookie(cookie(owner.cookie))).andReturn());
        answers.add(mvc.perform(get("/api/v1/readiness")).andReturn());
        answers.add(mvc.perform(get("/api/v1/demo/readiness").cookie(cookie(owner.cookie))).andReturn());
        // And two refusals, because an error body is where a configuration value is most likely to be
        // quoted back by a handler that meant to be helpful.
        answers.add(mvc.perform(get("/api/v1/trips/{id}", UUID.randomUUID())
                .cookie(cookie(owner.cookie))).andReturn());
        answers.add(mvc.perform(post("/api/v1/places/search")
                .cookie(cookie(owner.cookie)).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"query\":\"경복궁\",\"cursor\":\"not-a-cursor\"}")).andReturn());

        for (MvcResult answer : answers) {
            String body = answer.getResponse().getContentAsString();
            String searchable = body + "\u001f" + decodedTokens(body);
            for (String secret : secrets) {
                assertThat(searchable)
                        .as("%s answered with a configured secret", answer.getRequest().getRequestURI())
                        .doesNotContain(secret);
            }
        }
    }

    /**
     * Every base64url blob in the body, decoded.
     *
     * <p>A plain substring sweep would miss the one leak most worth catching. A cursor is base64url
     * of its own payload, so a codec that put its signing key inside would produce a body in which
     * the secret does not appear as text - and the check would pass while handing the key out on
     * every page. Decoding first is what makes the sweep see through the encoding the leak would
     * hide behind.
     */
    private static String decodedTokens(String body) {
        StringBuilder decoded = new StringBuilder();
        java.util.regex.Matcher blobs =
                java.util.regex.Pattern.compile("[A-Za-z0-9_-]{20,}").matcher(body);
        while (blobs.find()) {
            try {
                decoded.append(new String(java.util.Base64.getUrlDecoder().decode(blobs.group()),
                        java.nio.charset.StandardCharsets.UTF_8)).append('\u001f');
            } catch (IllegalArgumentException notBase64) {
                // Ordinary text that happened to match the shape. Nothing to decode, nothing to hide.
            }
        }
        return decoded.toString();
    }

    /**
     * Every configured value that looks like a credential, swept out of the environment rather than
     * listed. A named list would be a list of the secrets somebody remembered.
     *
     * <p>Short values are dropped because they are not credentials and would produce false matches -
     * "true" or a port number appears in plenty of honest responses.
     */
    private Set<String> configuredSecrets() {
        Set<String> secrets = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.contains("secret") && !lower.contains("password")
                        && !lower.contains("api-key") && !lower.contains("api_key")) {
                    continue;
                }
                Object value = enumerable.getProperty(name);
                if (value instanceof String text && text.length() >= 16) {
                    secrets.add(text);
                }
            }
        }
        return secrets;
    }

    private void seedPlace(String name) {
        UUID id = UUID.randomUUID();
        java.sql.Timestamp at = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-14T00:00:00Z"));
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, at, at);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 종로구', ?)
                """, UUID.randomUUID(), id, name, at);
    }

    private static Cookie cookie(String value) {
        return new Cookie("__Host-nullnull_session", value);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
