package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-032 over HTTP and a real PostgreSQL.
 *
 * <p>The catalog publication flag is enabled here for the same reason CatalogPlaceApiIT enables it:
 * a feed card embeds a canonical place, so with the flag closed the feed correctly answers 503 and
 * there is nothing else to test. That behaviour has its own test below.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-feed-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-032 fixed feed, posts and saved posts")
class FeedIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /**
     * The feed is GLOBAL, unlike a trip listing: every owner sees the same posts in the same order.
     * So a test that asserts on that order needs the table to itself - otherwise another test's
     * posts appear in this one's page and the assertions are about the suite's execution order.
     * saved_posts and post_places follow through their foreign keys.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearTheSharedFeed() {
        jdbc.update("DELETE FROM posts");
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, null, null);
    }

    private Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }

    /** A PUBLISHED post whose primary place is {@code placeId}, published at the given instant. */
    private UUID post(String title, UUID placeId, String publishedAt) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, published_at, created_at,"
                        + " updated_at) VALUES (?, 'PUBLISHED', ?, ?, 'https://example.test/cover.jpg',"
                        + " ?::timestamptz, ?, ?)",
                id, title, "본문 " + title, publishedAt, now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", id, placeId);
        return id;
    }

    @Test
    @DisplayName("BA-032-T1 the feed order is publishedAt DESC then id ASC and pages do not overlap")
    void theFeedOrderIsFixedAndPagesDoNotOverlap() throws Exception {
        var reader = owner();
        UUID place = place("경복궁");
        post("셋째", place, "2026-09-03T00:00:00Z");
        post("첫째", place, "2026-09-01T00:00:00Z");
        post("둘째", place, "2026-09-02T00:00:00Z");

        String first = mvc.perform(get("/api/v1/feed").param("limit", "2").cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].post.title").value("셋째"))
                .andExpect(jsonPath("$.items[1].post.title").value("둘째"))
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = first.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/v1/feed").param("limit", "2").param("cursor", cursor)
                        .cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].post.title").value("첫째"))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andExpect(jsonPath("$.page.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("BA-032-T1 two posts published at the same instant keep a stable order across pages")
    void aTieIsBrokenDeterministically() throws Exception {
        var reader = owner();
        UUID place = place("인사동");
        // Same publishedAt: the id breaks the tie, ASC, which is what FeedOrdering defines. Without
        // a total order the two could swap between pages and one would be served twice.
        UUID a = post("동시 A", place, "2026-09-05T00:00:00Z");
        UUID b = post("동시 B", place, "2026-09-05T00:00:00Z");
        UUID firstExpected = a.toString().compareTo(b.toString()) < 0 ? a : b;

        String page = mvc.perform(get("/api/v1/feed").param("limit", "1").cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].post.id").value(firstExpected.toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor = page.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/v1/feed").param("limit", "1").param("cursor", cursor)
                        .cookie(cookie(reader)))
                .andExpect(jsonPath("$.items[0].post.id")
                        .value(firstExpected.equals(a) ? b.toString() : a.toString()));
    }

    @Test
    @DisplayName("BA-032-T1 DRAFT and HIDDEN posts never reach the feed or a post read")
    void unpublishedPostsAreInvisible() throws Exception {
        var reader = owner();
        UUID place = place("명동");
        UUID hidden = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, created_at, updated_at)"
                + " VALUES (?, 'HIDDEN', ?, ?, 'https://example.test/c.jpg', ?, ?)",
                hidden, "숨긴 글", "본문", now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", hidden, place);

        mvc.perform(get("/api/v1/feed").cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        // 404, not 403: a reader has no claim on an unpublished post and must not learn it exists.
        mvc.perform(get("/api/v1/posts/" + hidden).cookie(cookie(reader)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * PM-010, the half that is a data gap rather than a projection gap. A place image can only reach
     * a response through place_media_assets -> media_assets -> asset_licenses, and the query requires
     * license.redistribution_allowed; V010 deliberately left no URL column on places or
     * place_localizations that could bypass that review. posts.cover_url is a bare NOT NULL text
     * column with no licence at all, so every post is REQUIRED to carry a cover image that has passed
     * no review, and coverAsset is therefore always null.
     *
     * <p>That is not something the server can fix on its own: where P0 cover images come from is an
     * owner decision, and if the answer is KTO imagery those carry per-image terms
     * (docs/data/SOURCE_CATALOG.md). Until it is answered, the emptiness is pinned here rather than
     * left to a fixture to imply - post-detail.json carries coverAsset null, but a fixture agreeing
     * with the server today is not a guard. This turns RED the moment a reviewed cover exists, which
     * is exactly when it must be replaced by a real assertion.
     */
    @Test
    @DisplayName("BA-032-T1 a post cover carries no rights yet, and nothing claims otherwise")
    void aPostCoverHasNoReviewedLicenceYet() throws Exception {
        var reader = owner();
        UUID postId = post("표지 권리", place("경복궁"), "2026-09-09T02:00:00Z");

        mvc.perform(get("/api/v1/posts/" + postId).cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverUrl").value("https://example.test/cover.jpg"))
                .andExpect(jsonPath("$.coverAsset").isEmpty());
        // The feed card does not even have a coverAsset field to be null, so the list projection
        // cannot express the rights of the image it shows. Adding the field before there is a
        // reviewed asset to put in it would only move the silence.
        mvc.perform(get("/api/v1/feed").cookie(cookie(reader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].post.coverUrl").value("https://example.test/cover.jpg"))
                .andExpect(jsonPath("$.items[0].post.coverAsset").doesNotExist());
        // posts has no column that could hold one, which is why this is a data decision.
        Integer licenceColumns = jdbc.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'posts'
                   AND column_name IN ('cover_media_asset_id', 'cover_asset_license_id')
                """, Integer.class);
        assertThat(licenceColumns).isZero();
    }

    @Test
    @DisplayName("BA-032-T2 one owner's saved state never appears in another owner's feed")
    void savedStateIsPerOwner() throws Exception {
        var mine = owner();
        var theirs = owner();
        UUID place = place("북촌");
        UUID postId = post("저장 대상", place, "2026-09-04T00:00:00Z");

        mvc.perform(put("/api/v1/posts/" + postId + "/saved").cookie(cookie(mine))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", mine.csrf.token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mvc.perform(get("/api/v1/feed").cookie(cookie(mine)))
                .andExpect(jsonPath("$.items[0].savedPost").value(true));
        // The other owner sees the same card with savedPost false. Same post, same order, different
        // per-owner state - which is the whole reason the response may not be shared-cached.
        mvc.perform(get("/api/v1/feed").cookie(cookie(theirs)))
                .andExpect(jsonPath("$.items[0].post.id").value(postId.toString()))
                .andExpect(jsonPath("$.items[0].savedPost").value(false));
        mvc.perform(get("/api/v1/posts/" + postId).cookie(cookie(theirs)))
                .andExpect(jsonPath("$.saved").value(false));
    }

    @Test
    @DisplayName("BA-032-T3 saving twice returns the ORIGINAL savedAt and creates one row")
    void savingTwiceDoesNotMoveTheTimestamp() throws Exception {
        var owner = owner();
        UUID place = place("서촌");
        UUID postId = post("중복 저장", place, "2026-09-04T00:00:00Z");

        String first = mvc.perform(put("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(put("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andReturn().getResponse().getContentAsString();

        String savedAt = first.replaceAll(".*\"savedAt\":\"([^\"]+)\".*", "$1");
        // The timestamp must not move: it orders the owner's saved list, and a no-op that reordered
        // it would be a change the user never made.
        assertThat(second).contains("\"savedAt\":\"" + savedAt + "\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saved_posts WHERE owner_id = ?",
                Integer.class, owner.owner.id())).isOne();
    }

    @Test
    @DisplayName("BA-032-T3 save and unsave touch no trip, candidate, item or version")
    void savingDoesNotTouchATrip() throws Exception {
        var owner = owner();
        UUID place = place("삼청동");
        UUID postId = post("여행 무관", place, "2026-09-04T00:00:00Z");
        String trip = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID tripId = UUID.fromString(trip.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1"));

        mvc.perform(put("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token))
                .andExpect(status().isNoContent());

        // Invariant 2: a SavedPost is not a TripCandidate and not a TripItem. Nothing about the trip
        // may have moved, least of all its version - an ETag that changed would make every open tab
        // think someone edited the itinerary.
        assertThat(jdbc.queryForObject("SELECT version FROM trips WHERE id = ?", Long.class, tripId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_items WHERE trip_id = ?",
                Integer.class, tripId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trip_revisions WHERE trip_id = ?",
                Integer.class, tripId)).isOne();
    }

    @Test
    @DisplayName("BA-032-T3 unsaving something that was never saved is success, not an error")
    void unsaveIsIdempotent() throws Exception {
        var owner = owner();
        UUID place = place("연남동");
        UUID postId = post("저장한 적 없음", place, "2026-09-04T00:00:00Z");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete("/api/v1/posts/" + postId + "/saved").cookie(cookie(owner))
                            .header("Origin", "http://localhost:5173")
                            .header("X-CSRF-Token", owner.csrf.token))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    @DisplayName("BA-032 a supplied tripId changes only what a card shows, never the order")
    void tripIdChangesDisplayNotOrder() throws Exception {
        var owner = owner();
        UUID first = place("먼저");
        UUID second = place("나중");
        post("나중 글", second, "2026-09-06T00:00:00Z");
        post("먼저 글", first, "2026-09-05T00:00:00Z");

        mvc.perform(get("/api/v1/feed").cookie(cookie(owner)))
                .andExpect(jsonPath("$.items[0].post.title").value("나중 글"))
                // No trip named: the question has no answer, which is not the same as "no".
                .andExpect(jsonPath("$.items[0].candidateState").value("NO_TRIP_SELECTED"));

        String trip = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trips").cookie(cookie(owner))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[],"
                                + "\"seedItems\":[{\"placeId\":\"" + first + "\",\"date\":\"2026-10-04\","
                                + "\"position\":0}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String tripId = trip.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        mvc.perform(get("/api/v1/feed").param("tripId", tripId).cookie(cookie(owner)))
                // Order unchanged: the scheduled place's card did NOT move to the top.
                .andExpect(jsonPath("$.items[0].post.title").value("나중 글"))
                .andExpect(jsonPath("$.items[0].candidateState").value("NOT_SAVED"))
                .andExpect(jsonPath("$.items[1].post.title").value("먼저 글"))
                .andExpect(jsonPath("$.items[1].candidateState").value("SCHEDULED_IN_SELECTED_TRIP"));
    }

    @Test
    @DisplayName("BA-032 a tripId the caller does not own reveals nothing about it")
    void aForeignTripIdRevealsNothing() throws Exception {
        var mine = owner();
        var theirs = owner();
        UUID placeId = place("남의 여행 장소");
        post("글", placeId, "2026-09-07T00:00:00Z");
        String trip = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/trips").cookie(cookie(theirs))
                        .header("Origin", "http://localhost:5173")
                        .header("X-CSRF-Token", theirs.csrf.token)
                        .header("Idempotency-Key", "trip-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                                + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[],"
                                + "\"seedItems\":[{\"placeId\":\"" + placeId + "\",\"date\":\"2026-10-04\","
                                + "\"position\":0}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String foreignTrip = trip.replaceFirst("(?s)^.*?\"id\":\"([^\"]+)\".*$", "$1");

        // The place IS scheduled - in someone else's trip. Answering SCHEDULED here would confirm
        // both that the trip exists and what is in it.
        mvc.perform(get("/api/v1/feed").param("tripId", foreignTrip).cookie(cookie(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].candidateState").value("NOT_SAVED"));
    }
}
