package io.nullnull.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BA-033 ingestEventBatch over HTTP and a real PostgreSQL.
 *
 * <p>What is actually being tested here is a refusal. An analytics endpoint is the one place in the
 * product where a client can post arbitrary JSON, and the privacy invariants say the server must not
 * end up holding free text, a coordinate or an identifier a caller supplied. So most of these cases
 * send something that looks plausible and assert that it does not land.
 */
@SpringBootTest(properties = {
        "NULLNULL_CURSOR_SECRET=test-analytics-secret-that-is-long-enough",
        // The deletion job is asynchronous and off by default; the erasure case below is about what
        // it leaves behind, so it has to actually run.
        "nullnull.jobs.enabled=true", "nullnull.jobs.poll-interval=PT0.02S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-033 analytics event ingest")
class AnalyticsIngestIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.nullnull.analytics.application.ExpiredAnalyticsEventEraser eraser;

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private ResultActions send(SessionService.Bootstrap owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/events/batch")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .contentType("application/json").content(body));
    }

    private static String batch(String... events) {
        return "{\"schemaVersion\":\"1.1.0\",\"events\":[" + String.join(",", events) + "]}";
    }

    private static String tripCreated(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"name\":\"trip_created\","
                + "\"occurredAt\":\"2026-09-04T09:00:00+09:00\",\"context\":{\"locale\":\"ko-KR\","
                + "\"timezone\":\"Asia/Seoul\",\"route\":\"/start\",\"appVersion\":\"3546086\"},"
                + "\"properties\":{\"tripId\":\"018f3f8e-9b67-7a21-8d31-31d315b938f3\","
                + "\"method\":\"MANUAL\",\"planningLevel\":\"MUST_VISIT_ONLY\",\"dayCount\":3,"
                + "\"itemCount\":2}}";
    }

    private int storedFor(SessionService.Bootstrap owner) {
        return jdbc.queryForObject("SELECT count(*) FROM analytics_events WHERE owner_id = ?",
                Integer.class, owner.owner.id());
    }

    @Test
    @DisplayName("BA-033-T2 a valid batch is accepted once and a resend is counted as duplicate")
    void aResendConvergesOnOneRow() throws Exception {
        var owner = owner();
        String id = UUID.randomUUID().toString();
        String body = batch(tripCreated(id));

        send(owner, body)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.rejected").value(0));
        // The client cannot know whether the first attempt landed, so a resend is the normal case
        // and gets an honest count rather than a second row or a 409.
        send(owner, body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));
        assertThat(storedFor(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-033-T1 an unknown property is refused and nothing in the batch lands")
    void anUnknownPropertyRefusesTheWholeBatch() throws Exception {
        var owner = owner();
        String withExtra = tripCreated(UUID.randomUUID().toString())
                .replace("\"itemCount\":2}}", "\"itemCount\":2,\"note\":\"9시 경복궁 11시 인사동\"}}");
        send(owner, batch(tripCreated(UUID.randomUUID().toString()), withExtra))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // The valid event in the same batch does not land either: a half-accepted batch leaves the
        // client unable to say what to resend, and the sender is not the client this contract
        // describes.
        assertThat(storedFor(owner)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 the refusal is one fixed English sentence, not the validator's message")
    void theRefusalIsTheContractsSentence() throws Exception {
        var owner = owner();
        String canary = "폐렴환자경복궁9시";
        String withText = tripCreated(UUID.randomUUID().toString())
                .replace("\"itemCount\":2}}", "\"itemCount\":2,\"note\":\"" + canary + "\"}}");
        send(owner, batch(withText))
                .andExpect(status().isUnprocessableEntity())
                // Exact, because the alternative is forwarding the library's text. That text does
                // not quote the rejected value (EventBatchValidatorTest measures this), but it does
                // carry the JSON path and the client's own property names, and it is rendered in
                // the JVM's default locale - so the same failure would read differently on two
                // servers and docs/api/README.md's English, markup-free detail would not hold.
                .andExpect(jsonPath("$.detail")
                        .value("The event batch does not match the published event schema."))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String problem = send(owner, batch(withText))
                .andReturn().getResponse().getContentAsString();
        assertThat(problem).doesNotContain(canary);
        // The path into the payload does not travel either.
        assertThat(problem).doesNotContain("/events/0");
    }

    /**
     * The guard here is the SCHEMA, not the service. {@code context} is
     * {@code additionalProperties: false}, so an owner or session field never reaches the code that
     * binds the owner - which is why removing the service's cookie binding does not turn this red.
     * That is the intended shape (refuse at the edge), and saying so is more useful than a test
     * that appears to cover a line it cannot reach. {@code theOwnerComesFromTheCookie} covers the
     * binding itself.
     */
    @Test
    @DisplayName("BA-033-T1 a client-supplied owner or session identifier is refused by the schema")
    void aForgedOwnerIsRefused() throws Exception {
        var mine = owner();
        var theirs = owner();
        for (String forged : new String[] {
                "\"ownerId\":\"" + theirs.owner.id() + "\"",
                "\"sessionId\":\"" + theirs.owner.id() + "\"",
        }) {
            String event = tripCreated(UUID.randomUUID().toString())
                    .replace("\"context\":{", "\"context\":{" + forged + ",");
            send(mine, batch(event)).andExpect(status().isUnprocessableEntity());
        }
        assertThat(storedFor(mine)).isZero();
        assertThat(storedFor(theirs)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 a coordinate cannot arrive in any event property")
    void aCoordinateIsRefused() throws Exception {
        var owner = owner();
        String event = tripCreated(UUID.randomUUID().toString())
                .replace("\"itemCount\":2}}", "\"itemCount\":2,\"lat\":37.5796,\"lng\":126.9770}}");
        send(owner, batch(event)).andExpect(status().isUnprocessableEntity());
        assertThat(storedFor(owner)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 a concrete route is refused, so no identifier reaches the route column")
    void aConcreteRouteIsRefused() throws Exception {
        var owner = owner();
        for (String route : new String[] {
                "/trip/018f3f8e-9b67-7a21-8d31-31d315b938f3",
                "/search?q=%EC%84%9C%EC%9A%B8",
                "/feed#top",
        }) {
            String event = tripCreated(UUID.randomUUID().toString())
                    .replace("\"route\":\"/start\"", "\"route\":\"" + route + "\"");
            send(owner, batch(event)).andExpect(status().isUnprocessableEntity());
        }
        assertThat(storedFor(owner)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 the product caps are enforced on the event too (PM-016)")
    void theProductCapsAreEnforced() throws Exception {
        var owner = owner();
        // 31 days and 101 items are trips the server cannot create, so an event claiming them is
        // either forged or a client that has drifted from the product.
        send(owner, batch(tripCreated(UUID.randomUUID().toString())
                .replace("\"dayCount\":3", "\"dayCount\":31")))
                .andExpect(status().isUnprocessableEntity());
        send(owner, batch(tripCreated(UUID.randomUUID().toString())
                .replace("\"itemCount\":2", "\"itemCount\":101")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(storedFor(owner)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 the owner is bound from the cookie, not from anything the caller sent")
    void theOwnerComesFromTheCookie() throws Exception {
        var mine = owner();
        var theirs = owner();
        send(mine, batch(tripCreated(UUID.randomUUID().toString())))
                .andExpect(status().isAccepted());
        assertThat(storedFor(mine)).isEqualTo(1);
        assertThat(storedFor(theirs)).isZero();
    }

    @Test
    @DisplayName("BA-033-T2 a batch over fifty events is refused whole")
    void anOversizedBatchIsRefused() throws Exception {
        var owner = owner();
        String[] events = new String[51];
        for (int index = 0; index < events.length; index++) {
            events[index] = tripCreated(UUID.randomUUID().toString());
        }
        send(owner, batch(events)).andExpect(status().isUnprocessableEntity());
        assertThat(storedFor(owner)).isZero();
        // Fifty exactly is fine, so the bound is the contract's and not an accident of the test.
        send(owner, batch(java.util.Arrays.copyOf(events, 50)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(50));
    }

    @Test
    @DisplayName("BA-033-T3 deleting the owner removes their events at the soft-delete stage")
    void ownerDeletionRemovesTheEvents() throws Exception {
        var owner = owner();
        send(owner, batch(tripCreated(UUID.randomUUID().toString())))
                .andExpect(status().isAccepted());
        assertThat(storedFor(owner)).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/session")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "analytics-erase-" + UUID.randomUUID()))
                .andExpect(status().isAccepted());
        awaitCompleted(owner.owner.id());

        // The owner row survives a SOFT delete, so the ON DELETE CASCADE on owner_id does not fire:
        // at this stage AnalyticsOwnerDataEraser is the only thing that removes these rows.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class,
                owner.owner.id())).as("the owner row survives a soft delete").isOne();
        assertThat(storedFor(owner)).isZero();
    }

    @Test
    @DisplayName("BA-033-T1 the session is bound from the cookie and survives as NULL when revoked")
    void theSessionIsBoundAndNulledOnSessionDeletion() throws Exception {
        var owner = owner();
        send(owner, batch(tripCreated(UUID.randomUUID().toString())))
                .andExpect(status().isAccepted());
        // Bound server-side, like owner_id: the contract's ClientEvent has no session field.
        assertThat(jdbc.queryForObject(
                "SELECT session_id IS NOT NULL FROM analytics_events WHERE owner_id = ?",
                Boolean.class, owner.owner.id())).isTrue();

        // ERD: a hard-deleted session nulls the pointer rather than taking the event with it. The
        // event still happened, and the owner-level measurement is not the session's to withdraw -
        // while the deleted session stops being identifiable.
        // The CSRF tokens cascade from the session; deleting the session row is the hard delete.
        jdbc.update("DELETE FROM demo_sessions WHERE owner_id = ?", owner.owner.id());
        assertThat(storedFor(owner)).as("the event survives its session").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT session_id IS NULL FROM analytics_events WHERE owner_id = ?",
                Boolean.class, owner.owner.id())).isTrue();
    }

    @Test
    @DisplayName("BA-033-T3 retention deletes by received_at, not by the device clock")
    void retentionUsesTheServerClock() throws Exception {
        var owner = owner();
        send(owner, batch(tripCreated(UUID.randomUUID().toString())))
                .andExpect(status().isAccepted());
        assertThat(storedFor(owner)).isEqualTo(1);

        // A device clock set years in the past would make occurredAt look long expired. The row is
        // not expired: retention is a promise about how long WE keep it, and that clock is ours.
        jdbc.update("UPDATE analytics_events SET occurred_at = received_at - interval '6 days'"
                + " WHERE owner_id = ?", owner.owner.id());
        assertThat(eraser.erase(java.time.Instant.now())).isZero();
        assertThat(storedFor(owner)).isEqualTo(1);

        // Past its own retention, it goes.
        jdbc.update("UPDATE analytics_events SET received_at = received_at - interval '91 days',"
                + " occurred_at = occurred_at - interval '91 days' WHERE owner_id = ?",
                owner.owner.id());
        assertThat(eraser.erase(java.time.Instant.now())).isPositive();
        assertThat(storedFor(owner)).isZero();
    }

    private void awaitCompleted(UUID ownerId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM deletion_requests WHERE owner_id = ?", String.class, ownerId);
            if ("COMPLETED".equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("deletion did not complete in time");
    }
}
