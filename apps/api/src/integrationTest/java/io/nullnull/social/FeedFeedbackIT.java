package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * BA-033 recordFeedFeedback: what P0 records, what it refuses, and what a repeat does.
 *
 * <p>The interesting assertions here are about the two facts a feed event has that look like one.
 * The device says when it happened and the server says when it heard; a client clock can be wrong in
 * either direction, so the row keeps both and the retention promise is made in ours.
 */
@SpringBootTest(properties = "nullnull.catalog.public-enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-033 feed feedback")
class FeedFeedbackIT {

    private static final String MOMENT = "2026-09-12T04:05:06Z";

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-033-T2 an IMPRESSION repeated inside one minute is one row, and the next minute is another")
    void repeatsConvergeOnTheMinute() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        UUID postId = publishedPost();

        record(reader, postId, "IMPRESSION", "2026-09-12T04:05:06Z").andExpect(status().isNoContent());
        // Same minute, different second, different Idempotency-Key: still the same impression.
        record(reader, postId, "IMPRESSION", "2026-09-12T04:05:59Z").andExpect(status().isNoContent());
        assertThat(rows(postId)).hasSize(1);

        // The next minute is a new fact about the reader, not a retry of the old one.
        record(reader, postId, "IMPRESSION", "2026-09-12T04:06:00Z").andExpect(status().isNoContent());
        assertThat(rows(postId)).hasSize(2);

        // A different action in the same minute is also its own row: the key is per action.
        record(reader, postId, "OPEN", "2026-09-12T04:05:10Z").andExpect(status().isNoContent());
        assertThat(rows(postId)).hasSize(3);
    }

    @Test
    @DisplayName("BA-033-T2 HIDE, LIKE and DISLIKE are refused in this release and write nothing")
    void theP1ActionsAreRefused() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        UUID postId = publishedPost();

        for (String action : List.of("HIDE", "LIKE", "DISLIKE")) {
            record(reader, postId, action, MOMENT)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("action"));
        }
        assertThat(rows(postId))
                .as("a refused action leaves no row that a later release would have to interpret")
                .isEmpty();
    }

    @Test
    @DisplayName("BA-033-T1 the stored row carries the server's clock and the device's, and nothing else")
    void theRowKeepsBothClocksAndNoIdentityFromTheBody() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        UUID postId = publishedPost();
        UUID stranger = UUID.randomUUID();

        // An owner id in the body is an unknown property: the schema is closed, and the owner is
        // bound from the cookie no matter what the body says (invariant 11).
        mvc.perform(post("/api/v1/feed/feedback")
                        .cookie(new Cookie("__Host-nullnull_session", reader.cookie))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", reader.csrf.token)
                        .header("Idempotency-Key", "feedback-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"postId\":\"" + postId + "\",\"action\":\"OPEN\",\"occurredAt\":\""
                                + MOMENT + "\",\"ownerId\":\"" + stranger + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(rows(postId)).isEmpty();

        record(reader, postId, "OPEN", MOMENT).andExpect(status().isNoContent());
        Map<String, Object> row = rows(postId).getFirst();
        assertThat(row.get("owner_id")).isEqualTo(reader.owner.id());
        assertThat(row.get("occurred_at").toString()).contains("04:05:06");
        // received_at is the server's, so it is not the device's value. The two being different is
        // the property: a row where they always matched would be one clock recorded twice.
        assertThat(row.get("received_at")).isNotEqualTo(row.get("occurred_at"));
        // The bucket the unique index uses is the minute occurredAt falls in. The database cannot
        // check this for itself - date_trunc is not IMMUTABLE - so it is checked here.
        assertThat(row.get("occurred_minute"))
                .isEqualTo(java.time.Instant.parse(MOMENT).getEpochSecond() / 60);
    }

    @Test
    @DisplayName("BA-033-T1 feedback about a post the reader could not have seen is refused")
    void feedbackNeedsAPublishedPost() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        UUID draft = draftPost();

        record(reader, draft, "IMPRESSION", MOMENT).andExpect(status().isNotFound());
        record(reader, UUID.randomUUID(), "IMPRESSION", MOMENT).andExpect(status().isNotFound());
        assertThat(rows(draft)).isEmpty();
    }

    @Test
    @DisplayName("BA-033-T3 deleting the owner removes their feedback immediately")
    void ownerErasureTakesTheFeedback() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        UUID postId = publishedPost();
        record(reader, postId, "OPEN", MOMENT).andExpect(status().isNoContent());
        assertThat(rows(postId)).hasSize(1);

        // The module's own eraser, called the way the deletion job calls it. Feedback is the
        // reader's behaviour, so it goes at once rather than waiting for the 90-day sweep.
        erasers.stream().filter(eraser -> eraser.ownerIdTables().contains("feed_feedback"))
                .forEach(eraser -> eraser.erase(reader.owner.id(), java.time.Instant.now()));
        assertThat(rows(postId)).isEmpty();
    }

    @Autowired List<io.nullnull.identity.application.OwnerDataEraser> erasers;

    private ResultActions record(SessionService.Bootstrap owner, UUID postId, String action,
            String occurredAt) throws Exception {
        return mvc.perform(post("/api/v1/feed/feedback")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", "feedback-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"postId\":\"" + postId + "\",\"action\":\"" + action
                        + "\",\"occurredAt\":\"" + occurredAt + "\"}"));
    }

    private List<Map<String, Object>> rows(UUID postId) {
        return jdbc.queryForList("SELECT owner_id, action, occurred_at, occurred_minute, received_at"
                + " FROM feed_feedback WHERE post_id = ? ORDER BY occurred_at", postId);
    }

    private UUID publishedPost() {
        UUID postId = draftPost();
        OffsetDateTime now = OffsetDateTime.now();
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '피드백 test 장소', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", postId, placeId);
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ? WHERE id = ?", now, postId);
        return postId;
    }

    private UUID draftPost() {
        UUID postId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'DRAFT', '피드백 글', '본문', 'https://example.test/cover.jpg', ?, ?, ?)",
                postId, io.nullnull.testsupport.PostCovers.firstPartyAsset(jdbc, java.time.Instant.now()),
                now, now);
        return postId;
    }
}
