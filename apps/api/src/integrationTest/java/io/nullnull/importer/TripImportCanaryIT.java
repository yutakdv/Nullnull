package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-060's privacy boundary, checked the only way a schema cannot check it: put a string in the paste
 * that exists nowhere else, then look for it everywhere it must not be.
 *
 * <p>The canary is a free-memo line - the kind #223 established has no safe representation - carrying
 * something shaped like a contact detail. The card names free memos and contact details specifically,
 * and they are what a parser is most likely to carry along by accident: they survive no pattern, so
 * anything that stored one stored the line itself.
 *
 * <p>{@code to_jsonb(row)::text} rather than a column list on purpose. A check that named today's
 * columns would keep passing after somebody adds tomorrow's, and the column that leaks is the one
 * nobody thought to name.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-canary-cursor-secret-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-060 the paste does not survive parsing")
class TripImportCanaryIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final String ORIGIN = "http://localhost:5173";

    /** Unique per run: another class's 경복궁 would make this one ambiguous and never resolve. */
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private String canary;
    private ListAppender<ILoggingEvent> logs;

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    /** Ids this class created, so teardown touches nothing another class is still using. */
    private final java.util.List<UUID> seededPlaces = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededOwners = new java.util.ArrayList<>();

    @BeforeEach
    void planTheCanary() {
        canary = "CANARY" + UUID.randomUUID().toString().replace("-", "");
        logs = new ListAppender<>();
        logs.start();
        rootLogger().addAppender(logs);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        rootLogger().detachAppender(logs);
        // Only what this class created. A blanket delete is the wrong shape under the gate,
        // which shares ONE database across every context while TestcontainersConfiguration gives
        // each distinct @SpringBootTest its own container locally - so the failure exists only
        // where running the classes in order cannot show it. places is deliberately not
        // cascaded and ten tables reference it, so the class that tries to clear the table is
        // the one that dies on somebody else's rows, and when it succeeds it takes their
        // fixtures with it. Owner-scoped first, because trip_items cascade from trips.
        seededOwners.forEach(owner -> {
            jdbc.update("DELETE FROM itinerary_import_drafts WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM trips WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", owner);
        });
        seededPlaces.forEach(place -> {
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", place);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        });
        seededOwners.clear();
        seededPlaces.clear();
    }

    @Test
    @DisplayName("BA-060-T1 no column of the stored draft holds the pasted line")
    void theStoredDraftHoldsNoPartOfThePaste() throws Exception {
        SessionService.Bootstrap owner = owner();
        place();

        parse(owner, "key-canary-stored-01").andExpect(status().isOk());

        // Every column, including the two jsonb ones the application fills itself.
        List<String> rows = jdbc.queryForList(
                "SELECT to_jsonb(d)::text FROM itinerary_import_drafts d", String.class);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain(canary));
    }

    @Test
    @DisplayName("BA-060-T2 none of the three responses echoes the pasted line")
    void noResponseEchoesThePaste() throws Exception {
        SessionService.Bootstrap owner = owner();
        place();

        String parsed = body(parse(owner, "key-canary-response-1").andExpect(status().isOk()));
        UUID draft = draftId();

        // The memo line left an unresolved token, so the draft is NEEDS_REVIEW. Dismissing it is what
        // #223 added for exactly this dead end, and it takes the draft to READY - which is also how
        // this case reaches remap and confirm at all.
        String remapped = body(mvc.perform(patch("/api/v1/trip-imports/{id}", draft)
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"updates\":[{\"clientKey\":\"t3\",\"dismissed\":true}]}"))
                .andExpect(status().isOk()));
        String confirmed = body(mvc.perform(post("/api/v1/trip-imports/{id}/confirm", draft)
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "key-canary-confirmed-1")
                        .contentType("application/json")
                        .content("{\"title\":\"붙여넣은 일정\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated()));

        assertThat(parsed).doesNotContain(canary);
        assertThat(remapped).doesNotContain(canary);
        assertThat(confirmed).doesNotContain(canary);
    }

    @Test
    @DisplayName("BA-060-T3 no log line and no error message carries the pasted line")
    void noLogOrErrorCarriesThePaste() throws Exception {
        SessionService.Bootstrap owner = owner();
        place();

        parse(owner, "key-canary-logged-01").andExpect(status().isOk());
        // And the refusal path, which is where a message is most tempted to quote its input: a paste
        // over the bound, and a correction naming an entry that is not there.
        String tooLong = body(mvc.perform(post("/api/v1/trip-imports/parse")
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "key-canary-toolong-01")
                        .contentType("application/json")
                        .content(json("rawText", canary + "가".repeat(20001), "locale", "ko-KR",
                                "timezone", "Asia/Seoul")))
                .andExpect(status().isUnprocessableContent()));
        String unknownKey = body(mvc.perform(patch("/api/v1/trip-imports/{id}", draftId())
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"updates\":[{\"clientKey\":\"" + canary + "\"}]}"))
                .andExpect(status().isUnprocessableContent()));

        assertThat(tooLong).doesNotContain(canary);
        assertThat(unknownKey).doesNotContain(canary);
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(canary);
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain(canary);
        });
    }

    @Test
    @DisplayName("BA-060-T5 the stored idempotency replay holds no part of the paste")
    void theStoredReplayHoldsNoPartOfThePaste() throws Exception {
        SessionService.Bootstrap owner = owner();
        place();

        parse(owner, "key-canary-replay-01").andExpect(status().isOk());

        // The request hash covers the paste and is a one-way digest, which is the point: the guard can
        // tell a retry from a different request without keeping what it compared. What must not be
        // here is the response body, and parse stores the draft's id rather than the draft.
        List<String> records = jdbc.queryForList(
                "SELECT to_jsonb(r)::text FROM idempotency_records r", String.class);
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(row -> assertThat(row).doesNotContain(canary));
    }

    private org.springframework.test.web.servlet.ResultActions parse(SessionService.Bootstrap owner,
            String key) throws Exception {
        return mvc.perform(post("/api/v1/trip-imports/parse")
                .cookie(cookie(owner)).header("Origin", ORIGIN)
                .header("X-CSRF-Token", owner.csrf.token).header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json("rawText", "2026-10-05\n경복궁" + tag + "\n" + canary + " 엄마한테 전화\n",
                        "locale", "ko-KR", "timezone", "Asia/Seoul")));
    }

    private static String json(String... pairs) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            body.put(pairs[at], pairs[at + 1]);
        }
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body);
    }

    private static String body(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        MvcResult result = actions.andReturn();
        return result.getResponse().getContentAsString();
    }

    private UUID draftId() {
        return jdbc.queryForObject("SELECT id FROM itinerary_import_drafts ORDER BY created_at LIMIT 1",
                UUID.class);
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private SessionService.Bootstrap owner() {
        return bootstrapped(null, "ko-KR", "Asia/Seoul");
    }

    private void place() {
        UUID id = UUID.randomUUID();
        seededPlaces.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, "경복궁" + tag, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 종로구', ?)
                """, UUID.randomUUID(), id, "경복궁" + tag, Timestamp.from(NOW));
    }

    /** Bootstraps a session and remembers whose rows this class is about to create. */
    private SessionService.Bootstrap bootstrapped(String cookie, String locale, String zone) {
        SessionService.Bootstrap owner = sessions.bootstrap(cookie, locale, zone);
        seededOwners.add(sessions.resolve(owner.cookie, false).ownerId());
        return owner;
    }
}
