package io.nullnull.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
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

/**
 * {@code searchPlaces} and {@code getPlace} with the catalog publication flag at its DEFAULT, which
 * is closed ({@code nullnull.catalog.public-enabled} defaults to false).
 *
 * <p>BA-022's handoff already states in prose that these two operations are "fail-closed", and
 * {@link io.nullnull.catalog.application.CatalogPlaceProjectionService} does call the gate first in
 * both. Until this class there was no assertion of it at the HTTP surface, which is why the 503 was
 * never declared on either operation in {@code docs/api/openapi.yaml}. FE asked for the producer
 * proof before the declaration (#310 item 3), and this is that half.
 *
 * <p><strong>The acceptance IDs are BA-022's, not BA-080's.</strong> BA-080 is the nearest card by
 * subject ("독립 검색·feed filter와 정렬 확장") but it declares {@code operations: []} and its three
 * clauses are about a future filter/sort extension. BA-022 is the card that owns {@code searchPlaces}
 * and {@code getPlace} and that already makes the fail-closed claim. Registration rule 2 says a test
 * ID names the card of the clause it proves rather than the nearest one, and
 * {@code TripDetailFailsClosedIT} made the same call for the same reason (it carries BA-030-T4, not
 * a BA-040 id).
 *
 * <p>The place row is real and ACTIVE so the refusal cannot be an artefact of an absent row. This
 * class does not measure what an OPEN gate returns for these requests - {@code CatalogPlaceApiIT}
 * owns that path and runs with the flag on.
 *
 * <p>Both requests are otherwise VALID on purpose. {@code PlaceController.search} evaluates
 * {@code CatalogPlaceSearchRequest.of(...)} as an argument, so it runs BEFORE the gate and a
 * malformed body answers 400 while the catalog is shut. A test that sent a sloppy body would assert
 * the validator and report it as the gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-022 the public place projection is fail-closed by default")
class CatalogPlaceFailsClosedIT {

    /**
     * Unique per run. Search reads the WHOLE catalog and the required gate runs every context
     * against ONE database, so a fixture named "장소" competes with every other class's rows
     * (AGENTS.md rule 6).
     */
    private static final String RUN = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    @Autowired
    MockMvc mvc;

    @Autowired
    SessionService sessions;

    @Autowired
    JdbcTemplate jdbc;

    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void capturePlaces() {
        placesBefore = OwnedRows.snapshot(jdbc, "places");
    }

    /** Only the rows that appeared while this class ran. A blanket DELETE takes or trips over others'. */
    @AfterEach
    void removeOnlyOwnFixtures() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
    }

    @Test
    @DisplayName("BA-022-T8 searchPlaces answers 503 SOURCE_UNAVAILABLE while the catalog gate is closed")
    void searchRefusesWhileTheCatalogIsUnpublished() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        activePlace(RUN + " 경복궁");

        mvc.perform(post("/api/v1/places/search")
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content("{\"query\":\"" + RUN + "\",\"locale\":\"ko-KR\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                // Refused AND not served anyway: a page carrying items would be the catalog reaching
                // the client through the operation the flag exists to hold shut.
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    @DisplayName("BA-022-T9 getPlace answers 503 SOURCE_UNAVAILABLE for a place that exists while the gate is closed")
    void detailRefusesWhileTheCatalogIsUnpublished() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        UUID placeId = activePlace(RUN + " 창덕궁");

        mvc.perform(get("/api/v1/places/{placeId}", placeId)
                        .cookie(new Cookie("__Host-nullnull_session", owner.cookie)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                // The id is one the catalog holds, so 503 here is the gate rather than the 404 an
                // unknown id would get. Neither the name nor the id may come back with the refusal.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    private UUID activePlace(String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, region_code, status,"
                + " created_at, updated_at) VALUES (?, ?, 'HS', '11', 'ACTIVE', ?, ?)", id, name, now, now);
        return id;
    }
}
