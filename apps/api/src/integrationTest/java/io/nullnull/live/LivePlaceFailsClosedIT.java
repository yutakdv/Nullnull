package io.nullnull.live;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
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
 * The Live tab ON and the catalog's publication gate CLOSED - which is this build's actual default,
 * and a combination no other Live test drives.
 *
 * <p>The gate exists because the canonical catalog is KTO-derived and BA-021-T3's staging call
 * evidence does not exist yet. Both new routes serve catalog text: {@code listLiveAreaPlaces} a
 * summary per mapped place, {@code getLivePlace} a full detail. A Live route that answered while the
 * gate was closed would serve exactly the data the fail-closed decision covers, through an operation
 * it was never asked about - which is the reason {@code embeddedSummaries} exists rather than a join
 * in this module.
 *
 * <p><strong>The area itself is real and reporting.</strong> Without that the refusal would be
 * indistinguishable from "no such area", and this case would pass on a server that simply 404s
 * everything.
 */
@SpringBootTest(properties = {
        "nullnull.capabilities.live=true",
        "nullnull.catalog.public-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-091 catalog 게이트가 닫히면 Live 장소 route 둘은 503 이다")
class LivePlaceFailsClosedIT {

    private static final String SOURCE = "SEOUL_CITYDATA";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired LiveAreaStore areas;
    @Autowired JdbcTemplate jdbc;

    private List<UUID> placesBefore;
    private List<UUID> areasBefore;

    @BeforeEach
    void noteRowsAlreadyPresent() {
        placesBefore = OwnedRows.snapshot(jdbc, "places");
        areasBefore = OwnedRows.snapshot(jdbc, "live_areas");
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        OwnedRows.remove(jdbc, "places", OwnedRows.appeared(jdbc, "places", placesBefore));
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
    }

    @Test
    @DisplayName("BA-091 닫힌 catalog 에서 Live 목록과 상세는 SOURCE_UNAVAILABLE 로 거절한다")
    void bothRoutesRefuseWhileTheCatalogIsUnpublished() throws Exception {
        Cookie cookie = new Cookie("__Host-nullnull_session",
                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie);
        UUID area = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert("POI201", "명동 관광특구")).id();
        UUID place = place();
        map(place, area);

        // The code, not just the status. 503 has two producers on this server - SOURCE_UNAVAILABLE
        // for a data source that is not published and ROUTE_UNAVAILABLE for a route that could not
        // be confirmed - so a case reading only the status cannot say which one answered, and the
        // client's branch depends on exactly that.
        mvc.perform(get("/api/v1/live/areas/{id}/places", area).cookie(cookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        mvc.perform(get("/api/v1/live/places/{id}", place).cookie(cookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));

        // NEITHER OPERATION DECLARES A 503, and that is recorded here rather than left for a reader
        // to discover. ServiceUnavailableContractTest keeps a register of operations that send one,
        // and its assertion runs one way only: a registered operation must declare it. Nothing looks
        // for the reverse, so these two are silent there - together with searchPlaces, getPlace,
        // listRelatedPlaces, getTrip and the trip-import operations, whose own fails-closed tests
        // assert the same code and which are likewise unregistered. The cost is the Frontend's: a
        // generated client cannot type a 503 the contract does not declare, so the screen falls
        // through to the default Problem and has to read the code as a string. Closing it means
        // opening the contract for every one of them, which is not this card's scope.
    }

    private UUID place() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '닫힌 카탈로그의 장소', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', now(), now())
                """, id);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', '닫힌 카탈로그의 장소', '서울시 어딘가', now())
                """, UUID.randomUUID(), id);
        return id;
    }

    private void map(UUID placeId, UUID areaId) {
        jdbc.update("""
                INSERT INTO seoul_live_area_maps
                    (id, place_id, live_area_id, mapping_type, confidence, fallback_used, verified_at)
                VALUES (?, ?, ?, 'AREA', 0.9000, false, now())
                """, UUID.randomUUID(), placeId, areaId);
    }
}
