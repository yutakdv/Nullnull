package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.testsupport.TripRows;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** BA-085 reading notifications: read-all, the cutoff, owner isolation and a vanished target. */
@SpringBootTest(properties = "nullnull.notifications.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-085 notification reading")
class NotificationReadIT {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> seededNotifications = new ArrayList<>();
    private final List<UUID> seededTrips = new ArrayList<>();

    @AfterEach
    void removeTheRowsThisClassCreated() {
        // By id. The gate runs every context against one database, so a statement that does not
        // name its own rows is a statement about every test that ran before it (AGENTS rule 6).
        for (UUID id : seededNotifications) {
            jdbc.update("DELETE FROM notifications WHERE id = ?", id);
        }
        for (UUID id : seededTrips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", id);
        }
        seededNotifications.clear();
        seededTrips.clear();
    }

    @Test
    @DisplayName("BA-085-T1 marking all read again reports nothing left to mark")
    void markAllReadConvergesOnRepeat() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        notification(owner.owner.id(), Instant.now().minusSeconds(60));
        notification(owner.owner.id(), Instant.now().minusSeconds(30));

        JsonNode first = body(readAll(owner, "read-all-" + UUID.randomUUID()));
        assertThat(first.get("updatedCount").asInt()).isEqualTo(2);
        assertThat(first.get("unreadCount").asInt()).isZero();

        // A DIFFERENT key, so this is a second request rather than a replay of the first. A replay
        // is the other half of "재시도해도 결과 동일" and it is asserted separately below.
        JsonNode again = body(readAll(owner, "read-all-" + UUID.randomUUID()));
        assertThat(again.get("updatedCount").asInt())
                .as("the second request finds nothing still unread").isZero();
        assertThat(again.get("unreadCount").asInt()).isZero();
    }

    @Test
    @DisplayName("BA-085 a retried mark-all replays the first result rather than reporting zero")
    void markAllReadReplaysUnderTheSameKey() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        notification(owner.owner.id(), Instant.now().minusSeconds(60));
        String key = "read-all-retry-" + UUID.randomUUID();

        JsonNode first = body(readAll(owner, key));
        JsonNode retry = body(readAll(owner, key));
        // FR-NOT-02 asks for "재시도해도 결과 동일" - the same RESULT, not merely the same end
        // state. Converging on its own would answer 0 here, which is a different sentence.
        assertThat(retry.get("updatedCount").asInt()).isEqualTo(first.get("updatedCount").asInt());
        assertThat(retry.get("cutoffAt").asText()).isEqualTo(first.get("cutoffAt").asText());
    }

    /**
     * Two mark-all requests at once, asserted on the result rather than on what ordered them.
     *
     * <p>Written because the clause was missing, not because a defect was suspected: BA-052 had this
     * exact shape wrong once. There the claim "the guard's owner lock means two requests cannot both
     * be live" was <em>read off the lock order</em> and published, and it was false because
     * {@code decide()} read the run BEFORE the guard - so both requests passed a status check on a
     * snapshot that was already stale. A test that tried to reach the race is what overturned it.
     *
     * <p>So this tries to reach it. Measured with the {@code notifications} table held in ACCESS
     * EXCLUSIVE while both requests were in flight: exactly ONE of the two reached the table and
     * blocked there ({@code blocked_on_notifications=1}), and their cutoffs were 3 seconds apart -
     * the second request computed its cutoff only after the first had finished. The structural
     * reason is the difference from BA-052: {@code markAllRead} reads no mutable state before the
     * guard, because the cutoff is taken INSIDE the command.
     *
     * <p><strong>That mechanism is deliberately not asserted here.</strong> Naming the line that
     * orders them would let the next person delete it, see green, and read the other guard as dead
     * code. What is asserted is the property that must survive whichever line does the ordering: no
     * notification is counted twice.
     */
    @Test
    @DisplayName("BA-085 two mark-all requests sent at once mark each notification exactly once")
    void concurrentMarkAllReadCountsEachNotificationOnce() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        int seeded = 4;
        for (int index = 0; index < seeded; index++) {
            notification(owner.owner.id(), Instant.now().minusSeconds(120L - index));
        }

        // Different Idempotency-Keys on purpose: with the same key the guard replays and there is no
        // race to have. Two distinct keys are two commands, and both are allowed to run.
        java.util.concurrent.ExecutorService callers =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<MvcResult>> sent = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = "concurrent-read-all-" + UUID.randomUUID();
                sent.add(callers.submit(() -> {
                    go.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    return readAll(owner, key);
                }));
            }
            go.countDown();
            MvcResult first = sent.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS);
            MvcResult second = sent.get(1).get(60, java.util.concurrent.TimeUnit.SECONDS);

            // The result, not the line that produced it. Whether the two are ordered by the guard's
            // owner lock or by the UPDATE's row locks is an implementation detail that a later change
            // is allowed to move; what may not move is that no notification is counted twice.
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            int counted = body(first).get("updatedCount").asInt()
                    + body(second).get("updatedCount").asInt();
            assertThat(counted)
                    .as("each unread notification is marked once across both requests, not once per request")
                    .isEqualTo(seeded);
            assertThat(body(first).get("unreadCount").asInt()).isZero();
            assertThat(body(second).get("unreadCount").asInt()).isZero();
            assertThat(unreadRowsOf(owner.owner.id()))
                    .as("and the table agrees with what both callers were told").isZero();
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("BA-085-T4 a notification committed after the request cutoff stays unread")
    void theCutoffLeavesNewerNotificationsUnread() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        notification(owner.owner.id(), Instant.now().minusSeconds(60));
        // Committed "after" the request: its created_at is later than the cutoff the server will
        // take. Seeding the row ahead of the clock models the race deterministically - the real one
        // is a notification landing between the request arriving and its UPDATE running.
        UUID newer = notification(owner.owner.id(), Instant.now().plusSeconds(600));

        JsonNode result = body(readAll(owner, "cutoff-" + UUID.randomUUID()));
        assertThat(result.get("updatedCount").asInt()).isEqualTo(1);
        assertThat(result.get("unreadCount").asInt())
                .as("the newer one is still unread").isEqualTo(1);
        assertThat(readAtOf(newer)).as("and it was not marked").isNull();
    }

    @Test
    @DisplayName("BA-085-T5 another owner's notification is indistinguishable from one that never existed")
    void anotherOwnersNotificationIsIndistinguishableFromNothing() throws Exception {
        SessionService.Bootstrap mine = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        SessionService.Bootstrap theirs = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        UUID theirNotification = notification(theirs.owner.id(), Instant.now().minusSeconds(60));
        UUID neverExisted = UUID.randomUUID();

        MvcResult onTheirs = markRead(mine, theirNotification);
        MvcResult onNothing = markRead(mine, neverExisted);

        // Not "both are refused" - 403 refuses while still answering the question the caller was
        // really asking, which is whether that id is real. The test is that the two answers cannot
        // be told apart (invariant 11, the shape BA-070-T1 fixes).
        assertThat(onTheirs.getResponse().getStatus()).isEqualTo(404);
        assertThat(verdict(onTheirs)).isEqualTo(verdict(onNothing));

        // The third call is what stops this being vacuous: two requests that die before reaching the
        // ownership decision also match each other. The same call against my OWN notification must
        // differ, or this pair proves nothing about isolation.
        UUID myNotification = notification(mine.owner.id(), Instant.now().minusSeconds(60));
        assertThat(markRead(mine, myNotification).getResponse().getStatus()).isEqualTo(204);
        // And theirs really was left alone, rather than quietly marked by a request that 404'd.
        assertThat(readAtOf(theirNotification)).isNull();
    }

    @Test
    @DisplayName("BA-085-T6 BA-085-T10 a notification whose target is gone stays in the list"
            + " and its link is not rewritten")
    void aVanishedTargetLeavesTheNotificationAndItsLinkAlone() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        UUID tripId = TripRows.insert(jdbc, owner.owner.id(), Instant.now());
        seededTrips.add(tripId);
        String link = "/trip/" + tripId;
        UUID id = notification(owner.owner.id(), Instant.now().minusSeconds(60), link);

        jdbc.update("DELETE FROM trips WHERE id = ?", tripId);
        seededTrips.remove(tripId);

        JsonNode page = body(mvc.perform(get("/api/v1/notifications")
                .cookie(cookie(owner))).andReturn());
        JsonNode item = itemWithId(page, id);
        // #310's FE ruling: the client does not open a raw href it cannot route and explains at
        // /notifications instead. So the server's obligation here is the negative one - it does not
        // drop the notification, and it does not rewrite the link into one that points somewhere
        // else. A rewritten link would be a server quietly telling the traveller a different story
        // about what happened.
        // T6 and T10 are two clauses and they break separately: hiding the notification
        // reddens the first, rewriting the link to /notifications reddens only the second.
        // One testcase carries both ids because one fixture measures both.
        assertThat(item).as("the notification is still listed").isNotNull();
        assertThat(item.get("deepLink").asText()).isEqualTo(link);
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult readAll(SessionService.Bootstrap owner, String key) throws Exception {
        return mvc.perform(put("/api/v1/notifications/read-all")
                .cookie(cookie(owner)).header("Origin", ORIGIN)
                .header("X-CSRF-Token", owner.csrf.token).header("Idempotency-Key", key)).andReturn();
    }

    private MvcResult markRead(SessionService.Bootstrap owner, UUID id) throws Exception {
        return mvc.perform(put("/api/v1/notifications/{id}/read", id)
                .cookie(cookie(owner)).header("Origin", ORIGIN)
                .header("X-CSRF-Token", owner.csrf.token)).andReturn();
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    /**
     * The whole Problem body minus the two fields that are per-request by construction.
     *
     * <p>{@code instance} is the path the caller themselves asked for, so it carries the id they
     * already hold, and {@code requestId} is unique per request by design and is a declared header.
     * Everything else - status, code, title, type and <strong>detail</strong> - has to match, which
     * is stricter than the {@code status/code} pair {@code OwnerIsolationMatrixIT} compares: a
     * sentence like "that notification belongs to someone else" would pass the pair and fail here.
     */
    private static String verdict(MvcResult result) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode)
                JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        node.remove("instance");
        node.remove("requestId");
        return result.getResponse().getStatus() + " " + node;
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode itemWithId(JsonNode page, UUID id) {
        for (JsonNode item : page.get("items")) {
            if (item.get("id").asText().equals(id.toString())) {
                return item;
            }
        }
        return null;
    }

    private UUID notification(UUID ownerId, Instant createdAt) {
        return notification(ownerId, createdAt, "/notifications");
    }

    private UUID notification(UUID ownerId, Instant createdAt, String deepLink) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, owner_id, type, title, body, deep_link,"
                        + " created_at, read_at, expires_at)"
                        + " VALUES (?, ?, 'TRIP_REMINDER', 'title', 'body', ?, ?, NULL, ?)",
                id, ownerId, deepLink, Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(java.time.Duration.ofDays(90))));
        seededNotifications.add(id);
        return id;
    }

    private int unreadRowsOf(UUID ownerId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE owner_id = ? AND read_at IS NULL",
                Integer.class, ownerId);
        return count == null ? 0 : count;
    }

    private Instant readAtOf(UUID id) {
        Timestamp value = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?",
                Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }
}
