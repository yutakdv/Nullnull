package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.nullnull.catalog.application.KtoPlaceDetailGateway;
import io.nullnull.catalog.application.KtoPlaceRequest;
import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.testsupport.TripRows;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * BA-090: an external source going down does not change what the trip and optimizer routes answer.
 *
 * <p><strong>The outage is a real one, not a flag.</strong> A capability flag says "this feature is
 * switched off"; this clause is about the other thing - the feature is on and the provider is not
 * answering. So the outage is seeded the way production records one: a {@code QUARANTINE} row in
 * {@code source_quality_incidents} whose window covers now, which is exactly what
 * {@code KtoPlaceDetailGateway.requireHealthySource} reads
 * ({@code JdbcSourceRegistryStore.conditionAt}). Modelling it with
 * {@code nullnull.catalog.public-enabled=false} would have measured a different sentence.
 *
 * <p><strong>Independence is asserted as "the same answer", not as "a 2xx".</strong> Asserting that
 * a trip read returns 200 during an outage cannot tell a trip route that survives the outage from a
 * trip route that never touched the source - and the second is the boring case that passes by
 * accident. Every step below is run twice, once inside the outage window and once outside it, and
 * the two verdicts must match. That is what the clause says.
 *
 * <p><strong>The control is what stops this being vacuous.</strong> In the same run, the gateway the
 * outage actually guards is called directly and must refuse while it is in force, and must stop
 * refusing when it is lifted. Without that, a server that had quietly stopped reading the incident
 * table at all would pass every assertion here.
 *
 * <p><strong>What this measured, and it is worth stating.</strong> No HTTP route traverses that
 * gateway today: its only callers are operator entry points ({@code KtoSmokeMain},
 * {@code KtoDemoRefresh}, {@code KtoDemoRefreshCommand}). Catalog reads are served from stored
 * snapshots, so a provider outage reaches users as staleness rather than as failure. The
 * independence this pins is therefore real but cheap today; it becomes load-bearing the moment a
 * read path starts calling a provider inline, and this test is what would notice.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "nullnull.capabilities.optimization=true",
        "NULLNULL_CURSOR_SECRET=test-source-outage-independence-secret-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-090 an external source outage leaves trip and optimizer answers unchanged")
class SourceOutageIndependenceIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final Pattern PROBLEM_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired KtoPlaceDetailGateway places;

    private final List<UUID> seededIncidents = new ArrayList<>();
    private final List<UUID> seededTrips = new ArrayList<>();

    @AfterEach
    void removeTheRowsThisClassCreated() {
        // Named one by one. The gate runs every context against one database, so an outage row left
        // behind would quarantine the source for every test that ran after this one (AGENTS rule 6).
        for (UUID id : seededIncidents) {
            jdbc.update("DELETE FROM source_quality_incidents WHERE id = ?", id);
        }
        for (UUID id : seededTrips) {
            jdbc.update("DELETE FROM trips WHERE id = ?", id);
        }
        seededIncidents.clear();
        seededTrips.clear();
    }

    @Test
    @DisplayName("BA-090-T3 trip CRUD answers the same during a source outage as outside one")
    void tripCrudIsUnchangedByASourceOutage() throws Exception {
        Map<String, String> healthy = tripCrudVerdicts();
        assertThat(healthy).as("the walk produced verdicts to compare").isNotEmpty();

        quarantineTheSource();
        assertSourceIsActuallyDown();

        assertThat(tripCrudVerdicts())
                .as("every trip CRUD step answers exactly what it answered with the source healthy")
                .isEqualTo(healthy);
    }

    @Test
    @DisplayName("BA-090-T18 the optimizer answers the same during a source outage as outside one")
    void theOptimizerIsUnchangedByASourceOutage() throws Exception {
        Map<String, String> healthy = optimizerVerdicts();
        assertThat(healthy).as("the walk produced verdicts to compare").isNotEmpty();

        quarantineTheSource();
        assertSourceIsActuallyDown();

        assertThat(optimizerVerdicts())
                .as("the optimizer's answer is its own, not the source's")
                .isEqualTo(healthy);
    }

    // ------------------------------------------------------------------ the control

    /**
     * The outage is in force right now, and lifting it lets the gateway through again.
     *
     * <p>Both directions. Asserting only that it refuses would pass against a gateway that refuses
     * unconditionally, which is a different server from the one this test claims to be measuring.
     */
    private void assertSourceIsActuallyDown() {
        assertThatThrownBy(() -> places.detail(new KtoPlaceRequest("126508", "12")).join())
                .as("the gateway the outage guards refuses while it is in force")
                .hasMessageContaining("SOURCE_QUARANTINED");

        UUID lifted = seededIncidents.get(seededIncidents.size() - 1);
        jdbc.update("UPDATE source_quality_incidents SET disposition = 'RESOLVED' WHERE id = ?", lifted);
        // Not "it succeeds" - this context has no provider credentials, so the call cannot get
        // past configuration. What the lift must change is the REASON, and that is what is asserted:
        // the quarantine is no longer what stops it.
        assertThatThrownBy(() -> places.detail(new KtoPlaceRequest("126508", "12")).join())
                .as("once resolved, the quarantine is no longer the reason it refuses")
                .hasMessageNotContaining("SOURCE_QUARANTINED");
        jdbc.update("UPDATE source_quality_incidents SET disposition = 'QUARANTINE' WHERE id = ?", lifted);
    }

    private void quarantineTheSource() {
        // The source this gateway guards, not whichever one the registry happens to list first.
        // The first version took down an arbitrary source and the control passed straight through
        // requireHealthySource into the network path - a quarantine on the wrong row is not an
        // outage of the thing under test, and the failure it produced (KTO_NOT_CONFIGURED) said so.
        String sourceCode = io.nullnull.catalog.domain.KtoPlaceSnapshot.SOURCE_CODE;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_registry WHERE code = ?",
                Integer.class, sourceCode)).as("%s is registered", sourceCode).isOne();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO source_quality_incidents (id, source_code, incident_code,"
                        + " affected_from, affected_to, scope, disposition, reviewed_at)"
                        + " VALUES (?, ?, ?, ?, NULL, 'ALL', 'QUARANTINE', ?)",
                id, sourceCode, "BA-090-T3-outage-" + id, Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now));
        seededIncidents.add(id);
    }

    // ------------------------------------------------------------------ the walks

    /** Create, read, rename and delete a trip, recording what each step answered. */
    private Map<String, String> tripCrudVerdicts() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        Map<String, String> verdicts = new LinkedHashMap<>();

        MvcResult created = mvc.perform(post("/api/v1/trips").cookie(cookie(owner))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", "outage-trip-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"startDate\":\"2026-10-04\",\"endDate\":\"2026-10-07\","
                        + "\"timezone\":\"Asia/Seoul\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andReturn();
        verdicts.put("createTrip", verdict(created));
        UUID tripId = idOf(created);
        if (tripId != null) {
            seededTrips.add(tripId);
            verdicts.put("getTrip", verdict(mvc.perform(get("/api/v1/trips/{id}", tripId)
                    .cookie(cookie(owner))).andReturn()));
            verdicts.put("updateTrip", verdict(mvc.perform(patch("/api/v1/trips/{id}", tripId)
                    .cookie(cookie(owner)).header("Origin", ORIGIN)
                    .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"1\"")
                    .contentType("application/merge-patch+json").content("{\"title\":\"이름 바꾼 여행\"}"))
                    .andReturn()));
            verdicts.put("listTrips", verdict(mvc.perform(get("/api/v1/trips")
                    .cookie(cookie(owner))).andReturn()));
            verdicts.put("deleteTrip", verdict(mvc.perform(delete("/api/v1/trips/{id}", tripId)
                    .cookie(cookie(owner)).header("Origin", ORIGIN)
                    .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"2\""))
                    .andReturn()));
            seededTrips.remove(tripId);
        }
        return verdicts;
    }

    /** Ask for an optimization on a trip the caller owns, recording what the optimizer answered. */
    private Map<String, String> optimizerVerdicts() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        UUID tripId = TripRows.insert(jdbc, owner.owner.id(), Instant.now());
        seededTrips.add(tripId);
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("createOptimization", verdict(mvc.perform(
                post("/api/v1/trips/{id}/optimizations", tripId).cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "outage-opt-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"scope\":\"ITEM\",\"targetItemId\":\"" + UUID.randomUUID()
                                + "\",\"inputTripVersion\":1,\"includeCandidates\":false}"))
                .andReturn()));
        verdicts.put("listOptimizationHistory", verdict(mvc.perform(get("/api/v1/optimizations")
                .cookie(cookie(owner))).andReturn()));
        return verdicts;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Status and Problem code - the pair {@code OwnerIsolationMatrixIT} compares.
     *
     * <p>Bodies are not compared: ids and timestamps differ between the two walks by construction,
     * and normalising them would be this test deciding which differences are allowed to exist.
     */
    private static String verdict(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        Matcher code = PROBLEM_CODE.matcher(body);
        return result.getResponse().getStatus() + "/" + (code.find() ? code.group(1) : "-");
    }

    private static UUID idOf(MvcResult created) throws Exception {
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"")
                .matcher(created.getResponse().getContentAsString());
        return id.find() ? UUID.fromString(id.group(1)) : null;
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
