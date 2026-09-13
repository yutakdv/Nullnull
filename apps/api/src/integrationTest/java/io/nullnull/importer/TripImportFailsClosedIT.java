package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * BA-060 with the catalog publication gate in the state it actually ships in.
 *
 * <p>This is the file that checks the default. {@code TripImportIT} and {@code TripImportParseIT} both
 * turn the flag on, so without this one nothing would exercise the setting the submission profile runs
 * with - the same gap {@code OptimizationCapabilityOffIT} and {@code FeedFailsClosedIT} exist to close.
 */
@SpringBootTest(properties = "NULLNULL_CURSOR_SECRET=test-closed-cursor-secret-long-enough")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-060 import with the catalog gate closed")
class TripImportFailsClosedIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final String ORIGIN = "http://localhost:5173";

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeOnlyOwnFixtures() {
        jdbc.update("DELETE FROM itinerary_import_drafts");
        jdbc.update("DELETE FROM trips");
        jdbc.update("DELETE FROM place_localizations");
        jdbc.update("DELETE FROM places");
    }

    @Test
    @DisplayName("BA-060-T18 all three operations answer 503 and write nothing")
    void everyImportOperationFailsClosedAndWritesNothing() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        UUID ownerId = sessions.resolve(owner.cookie, false).ownerId();
        UUID place = place();
        UUID draft = draft(ownerId, place);
        long draftsBefore = rows("itinerary_import_drafts");

        mvc.perform(post("/api/v1/trip-imports/parse")
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "key-closed-parse-001")
                        .contentType("application/json")
                        .content("{\"rawText\":\"2026-10-05\\n경복궁\\n\",\"locale\":\"ko-KR\","
                                + "\"timezone\":\"Asia/Seoul\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));

        // A second paste, with nothing to look up. It matters because the gate is enforced twice on
        // the parse path and only one of those is parse's own: CatalogPlaceProjectionService.search
        // fails closed too, so a paste containing a place line would answer 503 even if parse never
        // checked. A date-only paste reaches no search at all, so this is the case where parse's own
        // check is the only thing standing between a closed catalog and a written draft. Measured:
        // removing that check turns nothing red without this call, and turns the row count below red
        // with it.
        mvc.perform(post("/api/v1/trip-imports/parse")
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "key-closed-nolookup-1")
                        .contentType("application/json")
                        .content("{\"rawText\":\"2026-10-05\\n\",\"locale\":\"ko-KR\","
                                + "\"timezone\":\"Asia/Seoul\"}"))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(patch("/api/v1/trip-imports/{id}", draft)
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"updates\":[{\"clientKey\":\"i1\",\"placeId\":\"" + place + "\"}]}"))
                .andExpect(status().isServiceUnavailable());

        mvc.perform(post("/api/v1/trip-imports/{id}/confirm", draft)
                        .cookie(cookie(owner)).header("Origin", ORIGIN)
                        .header("X-CSRF-Token", owner.csrf.token).header("If-Match", "\"1\"")
                        .header("Idempotency-Key", "key-closed-confirm-01")
                        .contentType("application/json")
                        .content("{\"title\":\"닫힌 게이트\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isServiceUnavailable());

        // Zero writes, and each of the three could have left a different trace: parse a new draft,
        // remap a raised version, confirm a trip. A gate checked after the write would leave the row
        // and hand back the 503 anyway.
        assertThat(rows("itinerary_import_drafts")).isEqualTo(draftsBefore);
        assertThat(rows("trips")).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM itinerary_import_drafts WHERE id = ?",
                Long.class, draft)).isEqualTo(1L);
        assertThat(rows("idempotency_records")).isZero();
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private UUID place() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, '경복궁', 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    private UUID draft(UUID ownerId, UUID place) {
        UUID id = UUID.randomUUID();
        String content = "{\"title\":null,\"startDate\":\"2026-10-05\",\"endDate\":\"2026-10-05\""
                + ",\"timezone\":\"Asia/Seoul\",\"items\":[{\"clientKey\":\"i1\",\"placeId\":\"" + place
                + "\",\"originalLabel\":null,\"date\":\"2026-10-05\",\"startTime\":null"
                + ",\"position\":0,\"confidence\":1}]}";
        jdbc.update("""
                INSERT INTO itinerary_import_drafts
                    (id, owner_id, status, version, structured_draft, unresolved_tokens,
                     confirmed_trip_id, confirmed_at, expires_at, created_at)
                VALUES (?, ?, 'READY', 1, ?::jsonb, '[]'::jsonb, NULL, NULL, ?, ?)
                """, id, ownerId, content, Timestamp.from(NOW.plusSeconds(86400)), Timestamp.from(NOW));
        return id;
    }
}
