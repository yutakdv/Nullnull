package io.nullnull.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order the PRODUCTION feed query returns, against the order fixture apps/ai owns.
 *
 * <p>{@code FeedFallbackTest} already reads {@code feed-order-cases.json} - but it runs it through
 * {@link io.nullnull.recommendation.application.FeedFallback}, whose comparator has no production
 * caller ({@code REC-ARCH-01} lists it as awaiting its slice). So the fixture was being compared
 * against code the feed does not use, while the order a reader actually gets comes from SQL
 * ({@code JdbcFeedStore.publishedPage}: {@code ORDER BY published_at DESC, id ASC}).
 *
 * <p><strong>Measured, and narrower than it first looked.</strong> Reversing that ORDER BY's
 * tie-break to {@code id DESC} leaves {@code FeedFallbackTest} (2 tests) and
 * {@code FeedOrderingTest} (4 tests) green - every check that reads the fixture is blind to the
 * production query. It does NOT go unnoticed altogether: {@code FeedIT} BA-032-T1 catches it,
 * because it asserts the tie-break itself. So the gap is not "production order is unguarded" but
 * "nothing ties production to the artifact apps/ai reads" - and that is what drifts silently when
 * one language changes its notion of order.
 *
 * <p>This closes that: same fixture, real PostgreSQL, through {@code listFeed}.
 *
 * <p><strong>Scope, and what is deliberately not asserted.</strong> Only the ORDER is compared, over
 * the candidates the fixture itself expects. The two sides' ELIGIBILITY rules genuinely differ and
 * normalising them here would smuggle one side's belief into a parity test:
 * <ul>
 *   <li>The fixture drops a candidate published after {@code evaluatedAt}. The production query has
 *       no time predicate at all - {@code FeedIT} BA-032-T1 relies on that, seeding 2099 dates so
 *       its posts sort above other classes' rows.</li>
 *   <li>The fixture drops a candidate with no {@code primaryPlaceId}. Production does not drop it;
 *       {@code FeedService} treats it as a curator data defect and fails loudly, deliberately
 *       ({@code "Dropping it silently would make the page size lie"}).</li>
 *   <li>The fixture uses {@code WITHDRAWN} and {@code DELETED}, which {@code posts_status_check}
 *       cannot store, and has no {@code HIDDEN}, which it can.</li>
 * </ul>
 * Those three are reported as findings rather than encoded here.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-feed-order-parity-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-032 feed order parity with the apps/ai fixture")
class FeedOrderFixtureParityIT {

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> createdPosts = new ArrayList<>();
    private final List<UUID> createdPlaces = new ArrayList<>();

    @AfterEach
    void removeTheRowsThisClassCreated() {
        // By id. Every context shares one database in the gate, so a statement that does not name
        // its own rows is a statement about every test that ran before it (AGENTS rule 6).
        for (UUID id : createdPosts) {
            jdbc.update("DELETE FROM posts WHERE id = ?", id);
        }
        for (UUID id : createdPlaces) {
            jdbc.update("DELETE FROM places WHERE id = ?", id);
        }
        createdPosts.clear();
        createdPlaces.clear();
    }

    @Test
    @DisplayName("BA-032-T4 the production query returns the order the shared fixture declares")
    void theProductionQueryMatchesTheSharedOrderFixture() throws Exception {
        JsonNode cases = fixtureCases();

        int comparedCases = 0;
        int comparedTies = 0;
        Map<String, List<UUID>> mismatches = new LinkedHashMap<>();

        for (JsonNode fixtureCase : cases) {
            List<String> expected = new ArrayList<>();
            fixtureCase.get("expectedOrder").forEach(node -> expected.add(node.asText()));
            if (expected.size() < 2) {
                // A single row is in order however the query sorts. Counted, not compared.
                continue;
            }
            comparedCases++;

            SessionService.Bootstrap reader = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
            UUID place = place();
            Map<String, UUID> seeded = new LinkedHashMap<>();
            Map<String, String> publishedAt = new LinkedHashMap<>();
            for (JsonNode candidate : fixtureCase.get("candidates")) {
                String fixtureId = candidate.get("postId").asText();
                if (!expected.contains(fixtureId)) {
                    continue;
                }
                String at = candidate.get("publishedAt").asText();
                seeded.put(fixtureId, publishedPost(UUID.fromString(fixtureId), place, at));
                publishedAt.put(fixtureId, at);
            }
            assertThat(seeded.keySet())
                    .as("%s: every expected candidate was found and seeded", fixtureCase.get("id").asText())
                    .containsExactlyInAnyOrderElementsOf(expected);
            comparedTies += publishedAt.values().size() - Set.copyOf(publishedAt.values()).size();

            // Only this case's rows: the shared database holds other classes' posts, and FeedIT
            // deliberately seeds year-2099 dates that sort above everything here.
            List<UUID> mine = new ArrayList<>(seeded.values());
            List<UUID> actual = feedOrderOf(reader, mine);
            List<UUID> want = expected.stream().map(seeded::get).toList();
            if (!actual.equals(want)) {
                mismatches.put(fixtureCase.get("id").asText(), actual);
            }
            removeTheRowsThisClassCreated();
        }

        // Non-vacuity, twice. An empty fixture, or one with no multi-row case, satisfies an order
        // assertion trivially - and a fixture with no equal timestamps would never exercise the
        // tie-break, which is the half of this order that a plain ORDER BY does not give you.
        assertThat(comparedCases).as("fixture cases with an order to compare").isGreaterThan(0);
        assertThat(comparedTies).as("candidates sharing a publishedAt, so the id tie-break is exercised")
                .isGreaterThan(0);
        assertThat(mismatches).as("cases where the production query disagreed with the fixture").isEmpty();
    }

    private List<UUID> feedOrderOf(SessionService.Bootstrap reader, List<UUID> mine) throws Exception {
        String body = mvc.perform(get("/api/v1/feed").param("limit", "50")
                        .cookie(new Cookie("__Host-nullnull_session", reader.cookie)))
                .andReturn().getResponse().getContentAsString();
        List<UUID> order = new ArrayList<>();
        JsonMapper.builder().build().readTree(body).get("items").forEach(item -> {
            UUID id = UUID.fromString(item.get("post").get("id").asText());
            if (mine.contains(id)) {
                order.add(id);
            }
        });
        return order;
    }

    private static JsonNode fixtureCases() throws Exception {
        String directory = System.getProperty("nullnull.ai.fixtures.path");
        assertThat(directory).as("system property nullnull.ai.fixtures.path").isNotBlank();
        return JsonMapper.builder().build()
                .readTree(Files.readString(Path.of(directory, "feed-order-cases.json")))
                .get("cases");
    }

    private UUID place() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '경복궁', 'HS', '11', 'ACTIVE', ?, ?)", id, now, now);
        createdPlaces.add(id);
        return id;
    }

    /**
     * A post the curator has published, built in the order V022's trigger requires.
     *
     * <p>The id is the FIXTURE's postId, not a fresh one. The order under test breaks ties on the
     * post id as a string, so seeding a random id would compare a tie-break the fixture never
     * described - and the first version of this test did exactly that: FEED-ORDER-02 disagreed and
     * FEED-ORDER-01 passed only because the random ids happened to fall the same way.
     */
    private UUID publishedPost(UUID id, UUID placeId, String publishedAt) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'DRAFT', ?, ?, 'https://example.test/cover.jpg', ?, ?, ?)",
                id, "순서 " + id, "본문", 
                io.nullnull.testsupport.PostCovers.firstPartyAsset(jdbc, java.time.Instant.now()),
                now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", id, placeId);
        jdbc.update("UPDATE posts SET status = 'PUBLISHED', published_at = ?::timestamptz WHERE id = ?",
                publishedAt, id);
        createdPosts.add(id);
        return id;
    }
}
