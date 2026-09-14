package io.nullnull.importer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;

/** BA-060's parseTripImport, over HTTP and a real PostgreSQL, with the catalog gate open. */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-parse-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class})
@DisplayName("BA-060 parse a pasted itinerary")
class TripImportParseIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final String ORIGIN = "http://localhost:5173";

    /**
     * A suffix unique to this run, on every seeded name and on every line that has to resolve to it.
     * Resolution is "exactly one match", so a place another class left behind under the same name
     * turns a resolved item into an unresolved token - and the shared database the gate runs against
     * is full of other classes' 경복궁.
     */
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    /** Ids this class created, so teardown touches nothing another class is still using. */
    private final java.util.List<UUID> seededPlaces = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededOwners = new java.util.ArrayList<>();

    @AfterEach
    void removeOnlyOwnFixtures() {
        // Only what this class created. A blanket delete is the wrong shape under the gate,
        // which shares ONE database across every context while TestcontainersConfiguration gives
        // each distinct @SpringBootTest its own container locally - so the failure exists only
        // where running the classes in order cannot show it. places is deliberately not
        // cascaded and ten tables reference it, so the class that tries to clear the table is
        // the one that dies on somebody else's rows, and when it succeeds it takes their
        // fixtures with it. Owner-scoped first, because trip_items cascade from trips.
        seededOwners.forEach(owner -> {
            jdbc.update("DELETE FROM itinerary_import_drafts WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM trips WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", owner);
        });
        seededPlaces.forEach(place -> {
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", place);
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        });
        seededOwners.clear();
        seededPlaces.clear();
    }

    @Test
    @DisplayName("BA-060-T15 a Korean and an English name for one place both resolve to it")
    void koreanAndEnglishPlaceTokensResolveToTheSameCanonicalPlace() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID palace = place("경복궁" + tag, "Gyeongbokgung Palace " + tag);

        // Two pastes, same place, different language. The catalog's search matches canonical_name or
        // ANY localization with no locale filter, which is the mechanism this rests on - a request in
        // ko-KR still matches the en-US row.
        parse(owner, "key-parse-korean-0001", "2026-10-05\n경복궁" + tag + "\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].place.id").value(palace.toString()))
                .andExpect(jsonPath("$.items[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$.unresolved.length()").value(0));

        parse(owner, "key-parse-english-001", "2026-10-05\nGyeongbokgung Palace " + tag + "\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].place.id").value(palace.toString()))
                .andExpect(jsonPath("$.unresolved.length()").value(0));
    }

    @Test
    @DisplayName("BA-060-T16 the paste and the suggestion list are both refused at their boundary")
    void boundsAreRefusedAtTheBoundaryAndNotOneCharacterLater() throws Exception {
        SessionService.Bootstrap owner = owner();

        // Exactly at the bound is accepted and one past it is not. Asserting only the rejection would
        // pass for a server that refuses everything.
        parse(owner, "key-parse-at-bound-01", "가".repeat(20000)).andExpect(status().isOk());
        parse(owner, "key-parse-over-bound-1", "가".repeat(20001))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // The other bound: eleven places answer one line, and the token carries ten.
        for (int index = 1; index <= 11; index++) {
            place("테스트공원" + tag + " " + index, null);
        }
        parse(owner, "key-parse-suggestions-1", "테스트공원" + tag)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.unresolved.length()").value(1))
                .andExpect(jsonPath("$.unresolved[0].kind").value("PLACE"))
                .andExpect(jsonPath("$.unresolved[0].suggestions.length()").value(10));
    }

    @Test
    @DisplayName("BA-060-T19 parse sends the no-store the contract declares, not the one its siblings send")
    void parseSendsTheCacheControlItDeclares() throws Exception {
        // The contract pins parse's 200 with `const: no-store`, while every other owner-scoped route
        // sends `private, no-store`. SessionContractTest only checks that a route DECLARES a
        // Cache-Control header, never that it sends the value it declared, so the declaration stays an
        // assumption until a real response is read - which is this line.
        parse(owner(), "key-parse-cache-ctl-1", "2026-10-05\n어딘가\n")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("a line nobody could place becomes a token that carries its line and no words")
    void anUnplaceableLineIsLocatedByItsNumberAndNotQuoted() throws Exception {
        SessionService.Bootstrap owner = owner();

        parse(owner, "key-parse-free-memo-1", "2026-10-05\n엄마한테 전화\n")
                .andExpect(status().isOk())
                // #223's answer: the line number locates it, the label stays empty, and the memo is
                // nowhere in the response. A year-less date is the other kind - it matched a strict
                // pattern, so echoing the fragment cannot echo a memo.
                .andExpect(jsonPath("$.unresolved[0].line").value(2))
                .andExpect(jsonPath("$.unresolved[0].label").value(""))
                .andExpect(jsonPath("$.unresolved[0].suggestions.length()").value(0))
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));

        parse(owner, "key-parse-yearless-01", "3/15\n어딘가\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unresolved[?(@.kind == 'DATE')].label")
                        .value(org.hamcrest.Matchers.contains("3/15")))
                .andExpect(jsonPath("$.dates.startDate").doesNotExist());
    }

    @Test
    @DisplayName("BA-060-T14 a year-less date and a bare hour are handed back as questions, not answers")
    void whatThePasteDidNotSayIsNotDecided() throws Exception {
        SessionService.Bootstrap owner = owner();
        place("경복궁" + tag, null);

        parse(owner, "key-parse-ambiguous-1", "3/15\n3시 경복궁" + tag + "\n")
                .andExpect(status().isOk())
                // Neither is completed. "3/15" is not given this year, and "3시" names two moments of
                // the day, so the parser picks neither - each comes back as its own kind of question.
                .andExpect(jsonPath("$.unresolved[?(@.kind == 'DATE')].label")
                        .value(org.hamcrest.Matchers.contains("3/15")))
                .andExpect(jsonPath("$.unresolved[?(@.kind == 'TIME')].label")
                        .value(org.hamcrest.Matchers.contains("3시")))
                .andExpect(jsonPath("$.dates.startDate").doesNotExist());

        // And the case that must NOT become a question, beside it: an hour that names one moment is
        // read. Without this the clause is satisfied by a parser that answers nothing at all.
        parse(owner, "key-parse-unambiguous1", "2026-10-05\n오후 3시 경복궁" + tag + "\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unresolved.length()").value(0))
                .andExpect(jsonPath("$.items[0].startTime").value("15:00:00"));
    }

    private ResultActions parse(SessionService.Bootstrap owner, String key, String rawText)
            throws Exception {
        return mvc.perform(post("/api/v1/trip-imports/parse")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", ORIGIN).header("X-CSRF-Token", owner.csrf.token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(tools.jackson.databind.json.JsonMapper.builder().build()
                        .writeValueAsString(java.util.Map.of("rawText", rawText, "locale", "ko-KR",
                                "timezone", "Asia/Seoul"))));
    }

    private SessionService.Bootstrap owner() {
        return bootstrapped(null, "ko-KR", "Asia/Seoul");
    }

    private UUID place(String koreanName, String englishName) {
        UUID id = UUID.randomUUID();
        seededPlaces.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, koreanName, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, koreanName, Timestamp.from(NOW));
        if (englishName != null) {
            jdbc.update("""
                    INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                    VALUES (?, ?, 'en-US', ?, 'Somewhere in Seoul', ?)
                    """, UUID.randomUUID(), id, englishName, Timestamp.from(NOW));
        }
        return id;
    }

    /** Bootstraps a session and remembers whose rows this class is about to create. */
    private SessionService.Bootstrap bootstrapped(String cookie, String locale, String zone) {
        SessionService.Bootstrap owner = sessions.bootstrap(cookie, locale, zone);
        seededOwners.add(sessions.resolve(owner.cookie, false).ownerId());
        return owner;
    }
}
