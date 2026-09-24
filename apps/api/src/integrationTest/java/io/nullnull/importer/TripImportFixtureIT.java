package io.nullnull.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.ContractResponse;
import io.nullnull.testsupport.JsonShape;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.PlaceCredits;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * #16: the three import fixtures describe the shape the server really sends.
 *
 * <p>An ImportDraft's keys depend on what the paste held - a line that resolved brings its place, a line
 * that matched more than one brings suggestions - and confirm answers with the trip the draft became. So
 * the fixtures cannot be compared with just any draft. This flow makes the drafts they show, over HTTP: a
 * paste with three places it resolves and one line it cannot choose for, a remap that sets 명동's time
 * and dismisses that question, and the confirm. Each response is compared with its fixture at every
 * level.
 *
 * <p>The paste is synthetic, and none of it is in a fixture: the server stores no line of it and sends
 * none back (the question carries its line number and an empty label), so the fixtures hold only what
 * was sent back.
 *
 * <p>Every place is referenced to the KTO source the way the canonical ingest writes one, so the fixtures
 * carry the provider credit, as the trip fixtures do (TripMutationFixtureIT).
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-import-fixture-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-060 the import fixtures describe the shape the server really sends")
class TripImportFixtureIT {

    private static final String ORIGIN = "http://localhost:5173";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    /**
     * On every seeded name and every line that has to resolve to it: resolution is "exactly one match",
     * and the database the gate shares is full of other classes' 경복궁.
     */
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> seededPlaces = new ArrayList<>();
    private final List<UUID> seededOwners = new ArrayList<>();

    /** Only this class's rows, each named by an id it created (AGENTS.md rule 6). */
    @AfterEach
    void removeOnlyOwnFixtures() {
        // Trips before places: the confirmed trip's items point at the places and do not cascade.
        seededOwners.forEach(owner -> {
            jdbc.update("DELETE FROM itinerary_import_drafts WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM trips WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", owner);
        });
        // With their localizations and external references, which do not cascade.
        OwnedRows.remove(jdbc, "places", seededPlaces);
    }

    @Test
    @DisplayName("BA-060 parse, remap and confirm answer the shapes their fixtures show")
    void parseRemapAndConfirm() throws Exception {
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
        seededOwners.add(sessions.resolve(owner.cookie, false).ownerId());
        UUID gyeongbokgung = place("경복궁" + tag);
        UUID insadong = place("인사동" + tag);
        UUID myeongdong = place("명동" + tag);
        place("서울숲" + tag);
        place("서울숲" + tag + " 공원");

        String paste = "2026-10-04\n경복궁" + tag + "\n오후 1시 인사동" + tag + "\n2026-10-05\n명동" + tag
                + "\n서울숲" + tag + "\n";
        JsonNode parsed = body(mvc.perform(post("/api/v1/trip-imports/parse")
                        .cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("Idempotency-Key", "parse-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(JSON.writeValueAsString(Map.of("rawText", paste, "locale", "ko-KR",
                                "timezone", "Asia/Seoul"))))
                .andExpect(status().isOk()));
        ContractResponse.assertValid("parseTripImport", 200, parsed);
        // Three lines resolved to one place each; the fourth matched two and is a question.
        assertThat(placesOf(parsed)).containsExactly(gyeongbokgung.toString(), insadong.toString(),
                myeongdong.toString());
        assertThat(parsed.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(parsed.get("unresolved")).hasSize(1);
        assertThat(parsed.get("unresolved").get(0).get("suggestions")).hasSize(2);
        // The parser fills no originalLabel, and the schema types it as a string, so it is absent rather
        // than a null the contract refuses (TripImportController.ImportDraftItemResponse).
        parsed.get("items").forEach(item -> assertThat(item.has("originalLabel")).isFalse());
        assertShape(parsed, "imports/draft-needs-review.json");

        String draftId = parsed.get("id").asString();
        String myeongdongKey = parsed.get("items").get(2).get("clientKey").asString();
        String questionKey = parsed.get("unresolved").get(0).get("clientKey").asString();
        JsonNode remapped = body(mvc.perform(patch("/api/v1/trip-imports/{id}", draftId)
                        .cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"updates\":[{\"clientKey\":\"" + myeongdongKey + "\",\"startTime\":\"14:00:00\"},"
                                + "{\"clientKey\":\"" + questionKey + "\",\"dismissed\":true}]}"))
                .andExpect(status().isOk()));
        ContractResponse.assertValid("remapTripImport", 200, remapped);
        assertThat(remapped.get("status").asString()).isEqualTo("READY");
        assertThat(remapped.get("unresolved")).isEmpty();
        assertShape(remapped, "imports/draft-ready.json");

        JsonNode confirmed = body(mvc.perform(post("/api/v1/trip-imports/{id}/confirm", draftId)
                        .cookie(cookie(owner))
                        .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "confirm-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"title\":\"붙여넣은 서울 일정\",\"planningLevel\":\"NOTHING\",\"interests\":[]}"))
                .andExpect(status().isCreated()));
        ContractResponse.assertValid("confirmTripImport", 201, confirmed);
        assertShape(confirmed, "imports/confirmed-trip.json");
    }

    private static List<String> placesOf(JsonNode draft) {
        List<String> ids = new ArrayList<>();
        draft.get("items").forEach(item -> ids.add(item.get("place").get("id").asString()));
        return ids;
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        seededPlaces.add(id);
        Timestamp now = Timestamp.from(Instant.now());
        // Coordinates because search only offers a place it could put on a map (JdbcCatalogPlaceQuery);
        // PlaceSummary carries none, so they reach no fixture.
        jdbc.update("INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code,"
                + " status, created_at, updated_at) VALUES (?, ?, 'HS', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)",
                id, name, now, now);
        // With the KorService2 revision it was collected under, as JdbcCanonicalCatalogStore writes a KTO
        // place's Korean text (V047): that is what the fixtures' textProvenance credits.
        jdbc.update("INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at,"
                + " source_code, source_registry_version, source_locale, observed_at)"
                + " VALUES (?, ?, 'ko-KR', ?, NULL, ?, 'KTO_KOR_SERVICE_2', 4, 'ko-KR', ?)",
                UUID.randomUUID(), id, name, now, now);
        jdbc.update("INSERT INTO place_external_refs (id, place_id, source_code, source_registry_version,"
                + " external_id, external_type, verified_at) VALUES (?, ?, 'KTO_KOR_SERVICE_2', 4, ?,"
                + " 'KTO_CONTENT_TYPE:12', ?)", UUID.randomUUID(), id, "fixture-" + id, now);
        return id;
    }

    private static JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void assertShape(JsonNode body, String fixture) {
        // The fixture Frontend mocks this step against has the keys the server sends, everywhere,
        // textProvenance (BA-086) included (#60).
        assertThat(JsonShape.of(body)).as(fixture)
                .isEqualTo(JsonShape.of(JsonShape.fixture(fixture)));
        PlaceCredits.assertSameAs(body, JsonShape.fixture(fixture), fixture);
        assertEveryPlaceCredited(body);
    }

    /** Every seeded place is referenced, and JsonShape merges array elements, so a lost credit is checked here. */
    private static void assertEveryPlaceCredited(JsonNode node) {
        if (node.isObject()) {
            if (node.has("id") && node.has("name") && node.has("sourceAttribution")) {
                assertThat(node.get("sourceAttribution").isObject())
                        .as("the credit of %s", node.get("name")).isTrue();
            }
            node.properties().forEach(field -> assertEveryPlaceCredited(field.getValue()));
        } else if (node.isArray()) {
            node.forEach(TripImportFixtureIT::assertEveryPlaceCredited);
        }
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
