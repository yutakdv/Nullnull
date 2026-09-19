package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-012-T2: data an owner owns TRANSITIVELY is actually erased, and at which stage.
 *
 * <p>{@code DeletionIT}'s coverage check sweeps for tables that reference {@code owners} and asks
 * that each be claimed by an eraser or retained for a reason. Most of an owner's data is not in
 * such a table: a trip's items, constraints, interests, revisions and candidates carry no owner
 * identifier at all - they belong to a trip, which belongs to an owner. The sweep is silent about
 * them by design, so nothing proved they are erased. This does.
 *
 * <p>It matters which stage, because the guarantee has a different owner in each:
 *
 * <ul>
 *   <li><b>soft delete</b> - {@code markDeleted} only stamps {@code deletedAt}; the owner row
 *       stays, so the {@code owners -> trips} cascade does NOT fire. At this stage the ONLY thing
 *       removing the trip is {@code TripOwnerDataEraser}, and everything under the trip follows by
 *       cascade from there. A mutation test below proves that single dependency.</li>
 *   <li><b>scrub</b> - the owner row is removed and the cascade would take the trips anyway.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-erasure-cursor-secret-that-is-long-enough",
        // The deletion job runs asynchronously and is off by default; this test is about what it
        // leaves behind, so it has to actually run.
        "nullnull.jobs.enabled=true", "nullnull.jobs.poll-interval=PT0.02S"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-012-T2 transitively owned data is erased")
class OwnerDataErasureIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * Every table that holds an owner's data through a trip rather than an owner_id of its own. The two
     * optimization tables are here because an applied run is the case this erasure never covered: the
     * same trip delete answered 500 through deleteTrip until V036 (BA-053-T12), and the erasure escaped
     * it only because DeleteOwnerDataHandler sorts erasers by name and "optimization-runs" happens to
     * sort before "trip-owned-aggregates". Measured: with V036 removed, renaming that eraser so it runs
     * last makes this test fail; with V036 in place the rename changes nothing. These rows are what
     * keeps the erasure's own coverage of them honest.
     */
    private static final String[] TRANSITIVE_TABLES = {
        "trips", "trip_interests", "trip_revisions", "trip_items", "trip_constraints",
        "trip_candidates", "candidate_sources", "optimization_runs", "optimization_decisions"};

    @Test
    @DisplayName("BA-012-T2 soft delete already removes the whole trip aggregate, not just the owner row")
    void softDeleteRemovesTheWholeAggregate() throws Exception {
        var owner = sessions.bootstrap(null, null, null);
        Map<String, Integer> before = buildEverything(owner);
        // Every table really has a row: an empty "before" would make the "after" assertions pass
        // without erasing anything.
        assertThat(before).allSatisfy((table, count) -> assertThat(count).as(table).isPositive());

        mvc.perform(delete("/api/v1/session").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "erase-" + UUID.randomUUID()))
                .andExpect(status().isAccepted());
        awaitCompleted(owner.owner.id());

        // The owner row is still there - this is a SOFT delete - and yet none of their trip data is.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class,
                owner.owner.id())).as("the owner row survives a soft delete").isOne();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM owners WHERE id = ?",
                Boolean.class, owner.owner.id())).isTrue();
        assertThat(counts(owner)).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_posts WHERE owner_id = ?",
                Integer.class, owner.owner.id())).isZero();
    }

    /** Creates a trip with an item, a constraint, an interest, a candidate and a saved post. */
    private Map<String, Integer> buildEverything(SessionService.Bootstrap owner) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '경복궁', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        UUID postId = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id,"
                        + " created_at, updated_at) VALUES (?, 'DRAFT', '글', '본문',"
                        + " 'https://example.test/c.jpg', ?, ?, ?)", postId,
                io.nullnull.testsupport.PostCovers.firstPartyAsset(jdbc, java.time.Instant.now()),
                now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", postId, placeId);
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ? WHERE id = ?", now, postId);

        String created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\","
                                + "\"interests\":[{\"code\":\"FOOD\",\"weight\":3}],\"seedItems\":["
                                + "{\"placeId\":\"" + placeId + "\",\"date\":\"2026-10-04\",\"position\":0,"
                                + "\"constraints\":[{\"type\":\"MUST_VISIT\",\"locked\":true,"
                                + "\"source\":\"USER\"}]}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String tripId = created.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        UUID otherPlace = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '인사동', 'HS', '11', 'ACTIVE', ?, ?)",
                otherPlace, now, now);
        mvc.perform(post("/api/v1/trips/" + tripId + "/candidates").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "cand-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"placeId\":\"" + otherPlace + "\",\"source\":{\"type\":\"POST\","
                                + "\"postId\":\"" + postId + "\"}}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isCreated());
        applyAnOptimization(owner, UUID.fromString(tripId), now);
        return counts(owner);
    }

    /**
     * An applied run, written the way the service writes one: the decision names the revisions the
     * apply moved the trip between. Seeded rather than driven through the pipeline because what this
     * test needs is the row shape, not another proof that the pipeline produces it (OptimizeRevertIT).
     */
    private void applyAnOptimization(SessionService.Bootstrap owner, UUID tripId, OffsetDateTime now) {
        UUID revisionId = jdbc.queryForObject(
                "SELECT id FROM trip_revisions WHERE trip_id = ? ORDER BY version LIMIT 1", UUID.class, tripId);
        UUID itemId = jdbc.queryForObject(
                "SELECT id FROM trip_items WHERE trip_id = ? LIMIT 1", UUID.class, tripId);
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        jdbc.update("INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,"
                        + " target_item_id, include_candidates, status, input_trip_version, input_revision_id,"
                        + " queued_at, started_at, completed_at) VALUES (?, ?, ?, 'ITEM', ?, false, 'APPLIED',"
                        + " 1, ?, ?, ?, ?)",
                runId, tripId, owner.owner.id(), itemId, revisionId, now, now, now);
        jdbc.update("INSERT INTO optimization_proposals (id, run_id, rank, summary, comparison_eligible,"
                        + " validation_summary, created_at) VALUES (?, ?, 1, '테스트 제안', true, '{}'::jsonb, ?)",
                proposalId, runId, now);
        jdbc.update("INSERT INTO optimization_decisions (id, run_id, proposal_id, owner_id, decision,"
                        + " expected_trip_version, resulting_trip_version, before_revision_id,"
                        + " after_revision_id, revert_until, decided_at) VALUES (?, ?, ?, ?, 'APPLY', 1, 2,"
                        + " ?, ?, ?, ?)",
                UUID.randomUUID(), runId, proposalId, owner.owner.id(), revisionId, revisionId, now, now);
    }

    private Map<String, Integer> counts(SessionService.Bootstrap owner) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("trips", jdbc.queryForObject(
                "SELECT count(*) FROM trips WHERE owner_id = ?", Integer.class, owner.owner.id()));
        for (String table : TRANSITIVE_TABLES) {
            if ("trips".equals(table)) {
                continue;
            }
            String sql = switch (table) {
                case "candidate_sources" -> "SELECT count(*) FROM candidate_sources source"
                        + " JOIN trip_candidates candidate ON candidate.id = source.candidate_id"
                        + " JOIN trips trip ON trip.id = candidate.trip_id WHERE trip.owner_id = ?";
                // Its owner reaches it through the run, which is the only row that names the trip.
                case "optimization_decisions" -> "SELECT count(*) FROM optimization_decisions decision"
                        + " JOIN optimization_runs run ON run.id = decision.run_id"
                        + " JOIN trips trip ON trip.id = run.trip_id WHERE trip.owner_id = ?";
                default -> "SELECT count(*) FROM " + table + " child JOIN trips trip"
                        + " ON trip.id = child.trip_id WHERE trip.owner_id = ?";
            };
            counts.put(table, jdbc.queryForObject(sql, Integer.class, owner.owner.id()));
        }
        return counts;
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private void awaitCompleted(UUID ownerId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
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
