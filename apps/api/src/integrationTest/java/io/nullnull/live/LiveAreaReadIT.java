package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import io.nullnull.crowd.domain.SeoulCongestionStage;
import io.nullnull.identity.application.SessionService;
import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the Live page says about readings that are, and are not, current.
 *
 * <p>The flag is ON here, which became possible in this slice: {@code DemoCapabilityQuery} refuses
 * to start with FEATURE_LIVE_DATA on while {@code live} has no source, and it has one now - a
 * promoted registry entry, a collector that stores a reading per area, and this route reading them
 * back.
 */
@SpringBootTest(properties = "nullnull.capabilities.live=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-090 Live page freshness")
class LiveAreaReadIT {

    private static final String SOURCE = "SEOUL_CITYDATA";
    private static final String ORIGIN = "http://localhost:5173";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired LiveAreaStore areas;
    @Autowired SeoulLiveSnapshotStore snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private List<UUID> snapshotsBefore;
    private List<UUID> setsBefore;
    private List<UUID> areasBefore;
    private List<UUID> runsBefore;
    private List<UUID> incidentsBefore;

    @BeforeEach
    void noteRowsAlreadyPresent() {
        snapshotsBefore = OwnedRows.snapshot(jdbc, "crowd_snapshots");
        setsBefore = OwnedRows.snapshot(jdbc, "snapshot_sets");
        areasBefore = OwnedRows.snapshot(jdbc, "live_areas");
        runsBefore = OwnedRows.snapshot(jdbc, "collector_runs");
        incidentsBefore = OwnedRows.snapshot(jdbc, "source_quality_incidents");
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        OwnedRows.remove(jdbc, "crowd_snapshots", OwnedRows.appeared(jdbc, "crowd_snapshots", snapshotsBefore));
        OwnedRows.remove(jdbc, "snapshot_sets", OwnedRows.appeared(jdbc, "snapshot_sets", setsBefore));
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
        OwnedRows.remove(jdbc, "collector_runs", OwnedRows.appeared(jdbc, "collector_runs", runsBefore));
        OwnedRows.remove(jdbc, "source_quality_incidents",
                OwnedRows.appeared(jdbc, "source_quality_incidents", incidentsBefore));
    }

    @Test
    @DisplayName("BA-090-T14 stale 한 서울 관측을 live 로 표시하지 않는다")
    void anExpiredReadingIsNotShownAsLive() throws Exception {
        Instant now = clock.instant();
        // One reading taken a minute ago and one taken an hour ago, both stored the same way. The
        // second is past its 300-second window, and nothing about the ROW says "stale" for the first
        // one to be compared against - the difference is decided when the page is built, against now.
        UUID fresh = area("POI009", "광화문·덕수궁", now.minusSeconds(60), now.minusSeconds(55), "보통");
        UUID expired = area("POI010", "강남역", now.minusSeconds(3600), now.minusSeconds(3595), "붐빔");

        JsonNode body = JSON.readTree(mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session",
                                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(stateOf(body, fresh)).as("a reading inside its window is live").isEqualTo("LIVE");
        assertThat(stateOf(body, expired)).as("a reading past its window is not").isEqualTo("STALE");
        // And the page as a whole does not round up: one stale area on a page means the page is
        // stale, because a page labelled LIVE tells the reader every reading on it is current.
        assertThat(body.get("mode").asString()).isEqualTo("STALE");
        // The freshness wording travels with it, so a client reading only the provenance sees it too.
        assertThat(provenanceOf(body, expired).get("freshness").asString()).isEqualTo("STALE");
        assertThat(provenanceOf(body, fresh).get("freshness").asString()).isEqualTo("FRESH");
    }

    @Test
    @DisplayName("BA-090 검토된 매핑이 있으므로 단계가 단계로 나간다")
    void areviewedStageIsServedAsAStage() throws Exception {
        Instant now = clock.instant();
        UUID id = area("POI011", "홍대 관광특구", now.minusSeconds(60), now.minusSeconds(55), "약간 붐빔");

        JsonNode body = JSON.readTree(mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session",
                                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode crowd = crowdOf(body, id);
        // "약간 붐빔" is cell 3 under A-060. Without SEOUL_CITYDATA in CrowdStage's reviewed set the
        // read boundary would blank this and add SCHEMA_DRIFT - so this pair is what separates
        // "registered" from "actually served".
        assertThat(crowd.get("ordinalLevel").asString()).isEqualTo("3");
        assertThat(crowd.get("provenance").get("qualityFlags").toString()).doesNotContain("SCHEMA_DRIFT");
        // Still no number: the stage is published, the value is a range we do not reduce.
        assertThat(crowd.get("value").isNull()).isTrue();
    }

    @Test
    @DisplayName("BA-091-T22 뒤늦게 수신한 과거 관측은 더 새로운 관측을 가리지 않는다")
    void lateOldObservationDoesNotReplaceNewerReading() throws Exception {
        Instant now = clock.instant();
        UUID id = area("POI-ORDER-" + UUID.randomUUID(), "관측 순서 구역 " + UUID.randomUUID(),
                now.minusSeconds(60), now.minusSeconds(50), "여유");
        // The provider can return an older publication on a later poll. Fetched time is then newer,
        // but the reading itself is older and already expired.
        UUID lateRun = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)
                """, lateRun, SOURCE, Timestamp.from(now.minusSeconds(10)),
                Timestamp.from(now.minusSeconds(10)));
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), UUID.randomUUID(), lateRun, 2L,
                id, now.minusSeconds(600), now.minusSeconds(10), 300L, SeoulCongestionStage.of("붐빔")));

        JsonNode body = JSON.readTree(mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session",
                                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie))
                        .header("Origin", ORIGIN).contentType("application/json")
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(stateOf(body, id)).isEqualTo("LIVE");
        assertThat(crowdOf(body, id).get("ordinalLevel").asString()).isEqualTo("1");
    }

    /**
     * The same clause as {@code CrowdForecastApiIT}'s, proven on the second read path.
     *
     * <p>Not a new number: "an observation inside a reviewed incident window is isolated" is one
     * statement, and {@code provenBy} takes a list of names per id. What is new is the route - a
     * second read that reached the same rows through its own query could have missed the incident
     * join entirely and still looked right, because the page renders, the state is LIVE, and the
     * only thing absent is the flag saying an operator has quarantined this source right now.
     */
    @Test
    @DisplayName("BA-090-T17 사건 창 안의 서울 관측은 Live 페이지에서도 격리된다")
    void aReadingInsideAReviewedIncidentWindowIsIsolatedOnThisPageToo() throws Exception {
        Instant now = clock.instant();
        Instant fetched = now.minusSeconds(55);
        UUID id = area("POI012", "이태원 관광특구", now.minusSeconds(60), fetched, "보통");

        UUID incident = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_quality_incidents
                    (id, source_code, incident_code, affected_from, affected_to, scope, disposition, reviewed_at)
                VALUES (?, ?, ?, ?, ?, 'LIVE_AREA', 'QUARANTINE', ?)
                """, incident, SOURCE, "live-window-" + incident,
                Timestamp.from(fetched.minusSeconds(60)), Timestamp.from(fetched.plusSeconds(60)),
                Timestamp.from(now));

        JsonNode body = JSON.readTree(mvc.perform(post("/api/v1/live/areas")
                        .cookie(new Cookie("__Host-nullnull_session",
                                sessions.bootstrap(null, "ko-KR", "Asia/Seoul").cookie))
                        .header("Origin", ORIGIN)
                        .contentType("application/json")
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode provenance = provenanceOf(body, id);
        assertThat(provenance.get("qualityFlags").toString()).contains("PROVIDER_INCIDENT");
        assertThat(provenance.get("comparisonEligible").asBoolean()).isFalse();
    }

    private UUID area(String externalId, String name, Instant observed, Instant fetched, String step) {
        UUID areaId = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert(externalId, name)).id();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)
                """, runId, SOURCE, Timestamp.from(fetched), Timestamp.from(fetched));
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), UUID.randomUUID(), runId, 2L,
                areaId, observed, fetched, 300L, SeoulCongestionStage.of(step)));
        return areaId;
    }

    private static JsonNode crowdOf(JsonNode body, UUID areaId) {
        for (JsonNode area : body.get("areas")) {
            if (area.get("id").asString().equals(areaId.toString())) {
                return area.get("crowd");
            }
        }
        throw new AssertionError("area not on the page: " + areaId);
    }

    private static String stateOf(JsonNode body, UUID areaId) {
        return crowdOf(body, areaId).get("state").asString();
    }

    private static JsonNode provenanceOf(JsonNode body, UUID areaId) {
        return crowdOf(body, areaId).get("provenance");
    }
}
