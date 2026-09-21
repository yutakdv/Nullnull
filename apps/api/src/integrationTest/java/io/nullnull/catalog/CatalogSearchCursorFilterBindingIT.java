package io.nullnull.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.OwnedRows;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * The search cursor is bound to EVERY component of its filter context, not just the search term.
 *
 * <p>{@code CatalogPlaceSearchRequest.cursorContext()} digests {@code query}, {@code locale} and
 * {@code regionCode} together, and {@code BA-022-T2} proves only that changing the QUERY rejects a
 * reused cursor. The other two components were never exercised: no test file in this repository
 * contains both "cursor" and "regionCode". A cursor carries a response-derived sort key (A-035), so
 * a component that silently stopped binding would let a page key from one filter resume a listing
 * under another - the page would be built from rows the new filter never selected.
 *
 * <p>Two IDs, not one: registration rule 3 says an acceptance assertion holds one clause, and "the
 * region and the locale both bind" is two. Each is separately removable from the digest.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-cursor-binding-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-022 the search cursor binds every filter component")
class CatalogSearchCursorFilterBindingIT {

    private static final String SOURCE = "KTO_KOR_SERVICE_2";
    /** Unique per run: search reads the whole catalog and the gate runs every context on ONE database. */
    private static final String RUN = "b" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void capture() {
        placesBefore = OwnedRows.snapshot(jdbc, "places");
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
    }

    @Test
    @DisplayName("BA-022-T10 a cursor issued under one regionCode is rejected under another")
    void theCursorIsBoundToTheRegionFilter() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        seedTwoMatchingPlaces();

        String cursor = cursorFrom(search(owner,
                "{\"query\":\"" + RUN + "\",\"locale\":\"ko-KR\",\"regionCode\":\"1\",\"limit\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn());
        assertThat(cursor).isNotBlank();

        // Same owner, same term, same locale - only the region filter differs.
        search(owner, "{\"query\":\"" + RUN + "\",\"locale\":\"ko-KR\",\"regionCode\":\"2\",\"cursor\":\""
                        + cursor + "\",\"limit\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));

        // Dropping the filter entirely is also a different filter, not "no filter applied".
        search(owner, "{\"query\":\"" + RUN + "\",\"locale\":\"ko-KR\",\"cursor\":\"" + cursor + "\",\"limit\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    }

    @Test
    @DisplayName("BA-022-T11 a cursor issued under one locale is rejected under another")
    void theCursorIsBoundToTheLocale() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        seedTwoMatchingPlaces();

        String cursor = cursorFrom(search(owner,
                "{\"query\":\"" + RUN + "\",\"locale\":\"ko-KR\",\"limit\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.hasMore").value(true))
                .andReturn());
        assertThat(cursor).isNotBlank();

        // Same owner, same term, same (absent) region - only the locale differs.
        search(owner, "{\"query\":\"" + RUN + "\",\"locale\":\"en-US\",\"cursor\":\"" + cursor + "\",\"limit\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    }

    private void seedTwoMatchingPlaces() {
        UUID first = activePlace(RUN + " 가 장소");
        UUID second = activePlace(RUN + " 나 장소");
        localization(first, RUN + " 가 장소");
        localization(second, RUN + " 나 장소");
        reference(first);
        reference(second);
    }

    private ResultActions search(SessionService.Bootstrap owner, String body) throws Exception {
        return mvc.perform(post("/api/v1/places/search")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json").content(body));
    }

    private String cursorFrom(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString())
                .path("page").path("nextCursor").asString();
    }

    private UUID activePlace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, id, name, timestamp(), timestamp());
        return id;
    }

    private void localization(UUID placeId, String name) {
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, updated_at)
                VALUES (?, ?, 'ko-KR', ?, ?)
                """, UUID.randomUUID(), placeId, name, timestamp());
    }

    private void reference(UUID placeId) {
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, 3, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, UUID.randomUUID(), placeId, SOURCE, "fixture-" + placeId, timestamp());
    }

    private static Timestamp timestamp() {
        return Timestamp.from(Instant.now());
    }
}
