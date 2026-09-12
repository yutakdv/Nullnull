package io.nullnull.social;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * BA-032 with the catalog publication flag at its DEFAULT, which is closed.
 *
 * <p>A feed card embeds a canonical place and {@code FeedCard.primaryPlace} is required, so while
 * the catalog is fail-closed the feed has nothing to put there. It must answer 503, not serve the
 * place anyway: the flag exists because the catalog is KTO-derived and BA-021-T3's staging call
 * evidence does not exist yet, and a second operation serving the same rows would make the decision
 * mean nothing.
 *
 * <p>This is the case that would pass silently if the feed read the catalog's query port directly,
 * which is why it is a test and not a comment.
 */
@SpringBootTest(properties = "NULLNULL_CURSOR_SECRET=test-feed-closed-secret-that-is-long-enough")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-032 the feed is fail-closed while the catalog is")
class FeedFailsClosedIT {

    @Autowired SessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("BA-032 listFeed and getPost answer 503 while the catalog projection is closed")
    void theFeedDoesNotRouteAroundTheCatalogGate() throws Exception {
        var reader = sessions.bootstrap(null, null, null);
        Cookie cookie = new Cookie("__Host-nullnull_session", reader.cookie);
        OffsetDateTime now = OffsetDateTime.now();
        UUID placeId = UUID.randomUUID();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, '경복궁', 'HS', '11', 'ACTIVE', ?, ?)",
                placeId, now, now);
        UUID postId = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, status, title, body, cover_url, published_at, created_at,"
                        + " updated_at) VALUES (?, 'PUBLISHED', '글', '본문',"
                        + " 'https://example.test/c.jpg', ?, ?, ?)", postId, now, now, now);
        jdbc.update("INSERT INTO post_places (post_id, place_id, position, mention_type)"
                + " VALUES (?, ?, 0, 'PRIMARY')", postId, placeId);

        mvc.perform(get("/api/v1/feed").cookie(cookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/posts/" + postId).cookie(cookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));

        jdbc.update("DELETE FROM posts WHERE id = ?", postId);
    }
}
