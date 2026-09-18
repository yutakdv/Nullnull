package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-060's two draft operations over HTTP and a real PostgreSQL.
 *
 * <p>Drafts are seeded directly rather than parsed. That is not a shortcut around a missing parser -
 * it is what lets these clauses be settled at all: every one of them is about what happens to a draft
 * that already exists, and the operation that makes one is blocked on #223, where what an unresolved
 * token may carry is still being negotiated. Seeding is also how BA-052 reaches a proposal and how
 * FeedIT reaches a post.
 *
 * <p>{@code parseTripImport} is therefore absent, and with it BA-060-T15, T16 and T19, and T18's
 * third operation.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-import-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        TripImportIT.Time.class})
@DisplayName("BA-060 import draft remap and confirm")
class TripImportIT {

    private static final Instant NOW = Instant.parse("2032-03-01T00:00:00Z");
    private static final String ORIGIN = "http://localhost:5173";
    private static final String DAY = "2032-03-15";

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock importClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    /** Ids this class created, so teardown touches nothing another class is still using. */
    private final java.util.List<UUID> seededPlaces = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededOwners = new java.util.ArrayList<>();
    /**
     * Fixed, and never advanced. A draft's window closing is a property of the row, so these tests
     * close it by seeding an {@code expires_at} that has already passed rather than by moving time.
     * Moving it would expire the caller's own session first - the request would be refused before it
     * ever reached the draft - and MutableClock only moves forward by design, so one test that
     * advanced it would expire every draft seeded by the tests after it.
     *
     * <p><strong>Why that is true HERE:</strong> this card's draft window and the idempotency key's
     * retention are both 24 hours, so there is no amount of time that closes one without closing the
     * other, and the key is what the caller needs to reach the draft at all. A card whose window does
     * not collide with a credential's lifetime can move the clock instead - {@code OptimizeRevertIT}
     * advances 25 hours to close the 24-hour revert window, because sessions idle at P30D. Read this
     * paragraph as a fact about these two durations rather than as a rule about clocks: a constraint
     * with no stated source is either ignored or obeyed everywhere, and both are wrong.
     *
     * <p>The credential to check is not only the session. {@code APP_CSRF_TOKEN_TTL} is PT2H, so any
     * advance past two hours needs a freshly issued token even where the session is fine.
     */
    @Autowired MutableClock clock;

    @AfterEach
    void removeOnlyOwnFixtures() {
        // Trips before places: a confirmed draft leaves trip_items pointing at the place it mapped,
        // and that foreign key has no cascade. Drafts go first because an unconfirmed one holds no
        // trip and would otherwise survive the trip delete that cascades the confirmed ones.
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
    @DisplayName("a remap corrects the mapping and hands back the next version")
    void remapCorrectsAnEntryAndRaisesTheVersion() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID wrong = place("잘못 잡힌 장소");
        UUID right = place("고쳐 넣을 장소");
        UUID draft = draft(ownerId(owner), 1L, item("e1", wrong), NOW.plus(Duration.ofHours(24)));

        remap(owner, draft, "\"1\"", update(right))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.items[0].place.id").value(right.toString()));
    }

    @Test
    @DisplayName("BA-060-T13 another owner's draftId is not found, on either operation")
    void aDraftIsOnlyEverVisibleToItsOwner() throws Exception {
        SessionService.Bootstrap mine = owner();
        SessionService.Bootstrap theirs = owner();
        UUID place = place("장소");
        UUID draft = draft(ownerId(mine), 1L, item("e1", place), NOW.plus(Duration.ofHours(24)));

        // 404 and not 403: a 403 would confirm that this id names somebody's draft (invariant 11).
        remap(theirs, draft, "\"1\"", update(place))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        confirm(theirs, draft, "\"1\"", "key-other-owner-0001")
                .andExpect(status().isNotFound());

        assertThat(trips(ownerId(theirs))).isZero();
        assertThat(draftVersion(draft)).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-060-T7 an expired draft answers 410 on either operation and makes no trip")
    void anExpiredDraftIsGoneForBothOperations() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = expiredDraft(ownerId(owner), 1L, item("e1", place));

        remap(owner, draft, "\"1\"", update(place))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_EXPIRED"));
        confirm(owner, draft, "\"1\"", "key-expired-draft-01")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_EXPIRED"));

        assertThat(trips(ownerId(owner))).isZero();
        assertThat(draftVersion(draft)).isEqualTo(1L);
        assertThat(confirmedTrip(draft)).isNull();
    }

    @Test
    @DisplayName("BA-060-T6 a stale If-Match changes neither the draft nor any trip")
    void aStalePreconditionChangesNothing() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = draft(ownerId(owner), 4L, item("e1", place), NOW.plus(Duration.ofHours(24)));

        remap(owner, draft, "\"3\"", update(place))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_CHANGED"));
        confirm(owner, draft, "\"3\"", "key-stale-precondition")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_CHANGED"));

        assertThat(draftVersion(draft)).isEqualTo(4L);
        assertThat(trips(ownerId(owner))).isZero();
        assertThat(confirmedTrip(draft)).isNull();
    }

    @Test
    @DisplayName("BA-060-T12 a draft that has already become a trip cannot be remapped")
    void aConfirmedDraftIsNoLongerEditable() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = draft(ownerId(owner), 1L, item("e1", place), NOW.plus(Duration.ofHours(24)));
        confirm(owner, draft, "\"1\"", "key-first-confirm-01").andExpect(status().isCreated());

        // The ETag is the current one: this is refused for being confirmed, not for being stale.
        remap(owner, draft, "\"1\"", update(place))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_CHANGED"));

        assertThat(draftVersion(draft)).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-060-T9 the same Idempotency-Key twice creates one trip")
    void retryingWithTheSameKeyCreatesOneTrip() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = draft(ownerId(owner), 1L, item("e1", place), NOW.plus(Duration.ofHours(24)));

        String first = confirm(owner, draft, "\"1\"", "key-retried-confirm-1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replayed = confirm(owner, draft, "\"1\"", "key-retried-confirm-1")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The second answer is the same trip, not a second one - and it has to be an answer at all:
        // by the time it arrives the draft is CONFIRMED, which for any other caller is a 409.
        assertThat(tripId(replayed)).isEqualTo(tripId(first));
        assertThat(trips(ownerId(owner))).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-060-T10 two concurrent confirms under different keys create one trip")
    void concurrentConfirmsUnderDifferentKeysCreateOneTrip() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = draft(ownerId(owner), 1L, item("e1", place), NOW.plus(Duration.ofHours(24)));

        // Different keys on purpose: the idempotency guard cannot help here, because two keys are two
        // slots and each would run its own command. What makes this one trip is the row lock inside
        // the guard's transaction plus the status the winner writes.
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> attempt = () -> {
                start.await(5, TimeUnit.SECONDS);
                return confirm(owner, draft, "\"1\"", "key-" + UUID.randomUUID()).andReturn()
                        .getResponse().getStatus();
            };
            Future<Integer> one = pool.submit(attempt);
            Future<Integer> two = pool.submit(attempt);
            start.countDown();
            List<Integer> answers = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

            assertThat(answers).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(trips(ownerId(owner))).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-060-T11 replaying a key after the draft expired answers 410, not the stored 201")
    void anExpiredDraftIsNotReplayedFromTheStoredResponse() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        // Created two hours ago so that the window can be closed below without breaking V028's
        // expires_at > created_at.
        UUID draft = draft(ownerId(owner), 1L, item("e1", place), NOW.minus(Duration.ofHours(2)),
                NOW.plus(Duration.ofHours(24)));

        confirm(owner, draft, "\"1\"", "key-confirm-then-replay").andExpect(status().isCreated());
        // The window closes and nothing else does. The idempotency record's own retention is measured
        // against the clock, which has not moved, so the guard still holds a replayable 201 - that is
        // the whole point: if the draft's expiry and the key's retention ran out together, both
        // answers would be "gone" and the test could not see which of the two decided.
        jdbc.update("UPDATE itinerary_import_drafts SET expires_at = ? WHERE id = ?",
                Timestamp.from(NOW.minus(Duration.ofHours(1))), draft);

        confirm(owner, draft, "\"1\"", "key-confirm-then-replay")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("IMPORT_DRAFT_EXPIRED"));

        assertThat(trips(ownerId(owner))).isEqualTo(1L);
    }

    @Test
    @DisplayName("BA-060-T8 a draft still holding a question cannot be confirmed, and makes no trip")
    void anUnansweredDraftIsNotConfirmed() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        UUID draft = draftWithQuestion(ownerId(owner), item("e1", place));

        confirm(owner, draft, "\"1\"", "key-still-unanswered-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(trips(ownerId(owner))).isZero();
        assertThat(confirmedTrip(draft)).isNull();
    }

    @Test
    @DisplayName("BA-060-T17 a day with too many entries is refused by name, not quietly trimmed")
    void anOverfullDayIsNamedRatherThanTruncated() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");
        StringBuilder entries = new StringBuilder();
        for (int at = 0; at <= 20; at++) {
            entries.append(at == 0 ? "" : ",").append(item("e" + at, place, at));
        }
        UUID draft = draft(ownerId(owner), 1L, entries.toString(), NOW.plus(Duration.ofHours(24)));

        // The named date lives in fieldErrors rather than in `detail`, which stays the generic
        // sentence for every validation failure.
        confirm(owner, draft, "\"1\"", "key-overfull-day-001")
                .andExpect(status().isUnprocessableContent())
                // The date, not just the rule. A paste can fill a fortnight, and a refusal that says
                // only "a day is too full" leaves the person counting entries by hand to find which.
                .andExpect(jsonPath("$.fieldErrors[*].message")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString(DAY))))
                .andExpect(jsonPath("$.fieldErrors[*].message")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("at most 20"))));

        // And nothing was trimmed to make it fit: no trip, and the draft still holds all 21.
        assertThat(trips(ownerId(owner))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(structured_draft -> 'items')
                  FROM itinerary_import_drafts WHERE id = ?
                """, Integer.class, draft)).isEqualTo(21);
    }

    /** A draft whose every item resolved but which still holds one question, so it is NEEDS_REVIEW. */
    private UUID draftWithQuestion(UUID ownerId, String itemJson) {
        UUID id = UUID.randomUUID();
        String content = "{\"title\":null,\"startDate\":\"" + DAY + "\",\"endDate\":\"" + DAY + "\""
                + ",\"timezone\":\"Asia/Seoul\",\"items\":[" + itemJson + "]}";
        String tokens = "[{\"clientKey\":\"t2\",\"kind\":\"DATE\",\"line\":2,\"label\":\"3/15\""
                + ",\"suggestionPlaceIds\":[]}]";
        jdbc.update("""
                INSERT INTO itinerary_import_drafts
                    (id, owner_id, status, version, structured_draft, unresolved_tokens,
                     confirmed_trip_id, confirmed_at, expires_at, created_at)
                VALUES (?, ?, 'NEEDS_REVIEW', 1, ?::jsonb, ?::jsonb, NULL, NULL, ?, ?)
                """, id, ownerId, content, tokens,
                Timestamp.from(NOW.plus(Duration.ofHours(24))), Timestamp.from(NOW));
        return id;
    }

    private ResultActions remap(SessionService.Bootstrap owner, UUID draftId, String ifMatch, String body)
            throws Exception {
        return mvc.perform(patch("/api/v1/trip-imports/{id}", draftId)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch)
                .contentType("application/json").content(body));
    }

    private ResultActions confirm(SessionService.Bootstrap owner, UUID draftId, String ifMatch, String key)
            throws Exception {
        return mvc.perform(post("/api/v1/trip-imports/{id}/confirm", draftId)
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("If-Match", ifMatch).header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"title\":\"붙여넣은 일정\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"));
    }

    private static String update(UUID placeId) {
        return "{\"updates\":[{\"clientKey\":\"e1\",\"placeId\":\"" + placeId + "\"}]}";
    }

    private static String item(String clientKey, UUID placeId) {
        return item(clientKey, placeId, 0);
    }

    private static String item(String clientKey, UUID placeId, int position) {
        return "{\"clientKey\":\"" + clientKey + "\",\"placeId\":\"" + placeId + "\",\"originalLabel\":\"경복궁\""
                + ",\"date\":\"" + DAY + "\",\"startTime\":null,\"position\":" + position
                + ",\"confidence\":0.9}";
    }

    private static String tripId(String body) {
        int at = body.indexOf("\"id\":\"");
        return body.substring(at + 6, body.indexOf('"', at + 6));
    }

    private SessionService.Bootstrap owner() {
        return bootstrapped(null, "ko-KR", "Asia/Seoul");
    }

    private UUID ownerId(SessionService.Bootstrap owner) {
        return sessions.resolve(owner.cookie, false).ownerId();
    }

    /** A draft whose 24-hour window closed an hour ago; V028 still requires expires_at > created_at. */
    private UUID expiredDraft(UUID ownerId, long version, String itemJson) {
        return draft(ownerId, version, itemJson, NOW.minus(Duration.ofHours(25)),
                NOW.minus(Duration.ofHours(1)));
    }

    private UUID draft(UUID ownerId, long version, String itemJson, Instant expiresAt) {
        return draft(ownerId, version, itemJson, NOW, expiresAt);
    }

    private UUID draft(UUID ownerId, long version, String itemJson, Instant createdAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String content = "{\"title\":null,\"startDate\":\"" + DAY + "\",\"endDate\":\"" + DAY + "\""
                + ",\"timezone\":\"Asia/Seoul\",\"items\":[" + itemJson + "]}";
        jdbc.update("""
                INSERT INTO itinerary_import_drafts
                    (id, owner_id, status, version, structured_draft, unresolved_tokens,
                     confirmed_trip_id, confirmed_at, expires_at, created_at)
                VALUES (?, ?, 'READY', ?, ?::jsonb, '[]'::jsonb, NULL, NULL, ?, ?)
                """, id, ownerId, version, content, Timestamp.from(expiresAt),
                Timestamp.from(createdAt));
        return id;
    }

    private long draftVersion(UUID draftId) {
        return jdbc.queryForObject("SELECT version FROM itinerary_import_drafts WHERE id = ?", Long.class,
                draftId);
    }

    private UUID confirmedTrip(UUID draftId) {
        return jdbc.queryForObject("SELECT confirmed_trip_id FROM itinerary_import_drafts WHERE id = ?",
                UUID.class, draftId);
    }

    private long trips(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM trips WHERE owner_id = ?", Long.class, ownerId);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        seededPlaces.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, name, Timestamp.from(NOW));
        return id;
    }

    /** Bootstraps a session and remembers whose rows this class is about to create. */
    private SessionService.Bootstrap bootstrapped(String cookie, String locale, String zone) {
        SessionService.Bootstrap owner = sessions.bootstrap(cookie, locale, zone);
        seededOwners.add(sessions.resolve(owner.cookie, false).ownerId());
        return owner;
    }
}
