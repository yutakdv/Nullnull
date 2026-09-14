package io.nullnull.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import io.nullnull.testsupport.OwnedRows;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;

/**
 * BA-024's HTTP shape. The publication flag is on inside this isolated PostgreSQL context only - the
 * route ships closed, which is a deployment condition rather than a reason to leave it unbuilt
 * (HANDOFF §9 requires the controller and its contract test for the unapproved slices).
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-catalog-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        CatalogRelatedPlacesApiIT.Time.class})
@DisplayName("BA-024 related places HTTP projection")
class CatalogRelatedPlacesApiIT {

    private static final Instant NOW = Instant.parse("2032-01-01T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2031-01-01T00:00:00Z");
    private static final String RULE = "NULLNULL_CATALOG_RULE";
    private static final String OFFICIAL = "KTO_RELATED_PLACES";

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock relatedClock() {
            return MutableClock.at(NOW);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    SessionService sessions;

    @Autowired
    JdbcTemplate jdbc;


    /**
     * The places that were already there when this test started. Everything that appears after it
     * is this test's, and only that is removed - a blanket DELETE takes other classes' rows or, more
     * often, fails on one of the foreign keys that deliberately do not cascade (AGENTS.md rule 6).
     */
    private List<UUID> placesBefore = List.of();

    @BeforeEach
    void notePlacesAlreadyPresent() {
        placesBefore = jdbc.queryForList("SELECT id FROM places", UUID.class);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        List<UUID> mine = OwnedRows.appeared(jdbc, "places", placesBefore);
        OwnedRows.remove(jdbc, "places", mine);
    }

    @Test
    @DisplayName("BA-024-T6 a place with no eligible candidate answers UNKNOWN with an empty list")
    void noEligibleCandidateAnswersUnknownAndNeverFillsTheList() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID withCandidate = place("관련 있는 장소");
        UUID target = place("비슷한 장소");
        UUID withoutCandidate = place("관련 없는 장소");
        similar(withCandidate, target);

        // Both directions in one case. Asserting only the empty answer would pass for a service that
        // always returns UNKNOWN with no items - a constant, not a decision - so the case that must
        // come back populated is here beside it.
        related(owner, withoutCandidate)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePlaceId").value(withoutCandidate.toString()))
                .andExpect(jsonPath("$.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.reason").value("SOURCE_DISABLED"))
                .andExpect(jsonPath("$.items.length()").value(0));

        related(owner, withCandidate)
                .andExpect(status().isOk())
                // The contract declares this header for the operation, and SessionContractTest only
                // checks that direction - it asks whether a session-scoped route declares one, never
                // whether the route sends it. So the declaration is an assumption until something
                // reads a real response, which is this line.
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.state").value("SIMILAR"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].place.id").value(target.toString()))
                .andExpect(jsonPath("$.items[0].relation").value("SIMILAR"))
                .andExpect(jsonPath("$.items[0].relationReason").value("같은 분류·지역"));
    }

    @Test
    @DisplayName("a related place carries provenance that claims only what the evidence says")
    void provenanceClaimsOnlyWhatIsStored() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID source = place("출처 있는 장소");
        similar(source, place("대상 장소"));

        related(owner, source)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].provenance.source").value(RULE))
                .andExpect(jsonPath("$.items[0].provenance.sourceDisplayName").value("널널 카탈로그 규칙"))
                .andExpect(jsonPath("$.items[0].provenance.sourceState").value("QUALITATIVE"))
                // A rule-derived relation was never observed, and an unknown observedAt stays null
                // rather than borrowing fetchedAt.
                .andExpect(jsonPath("$.items[0].provenance.observedAt").doesNotExist())
                // mapping_certainty is CONFIRMED or UNCERTAIN, not a number between 0 and 1.
                .andExpect(jsonPath("$.items[0].provenance.confidence").doesNotExist())
                // Invariant 8: a relation says two places are related, never that one is quieter.
                .andExpect(jsonPath("$.items[0].provenance.comparisonEligible").value(false))
                .andExpect(jsonPath("$.items[0].provenance.comparisonReasonCode").value("QUALITATIVE_ONLY"))
                .andExpect(jsonPath("$.items[0].provenance.qualityFlags[0]").value("MAPPING_UNCERTAIN"))
                // No collector run stands behind a rule, and a crowd metric is not synthesised.
                .andExpect(jsonPath("$.items[0].provenance.collectorRunId").doesNotExist())
                .andExpect(jsonPath("$.items[0].crowd").doesNotExist());
    }

    @Test
    @DisplayName("a retired alias answers for the canonical place it resolves to")
    void aDeprecatedIdResolvesBeforeRelationsAreRead() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID canonical = place("살아 있는 장소");
        UUID target = place("대상 장소");
        similar(canonical, target);
        // A merge moves the duplicate's content to the canonical row before retiring it, and V010
        // refuses to deprecate a place that still holds any - so the fixture has to do the same.
        // The first version of this test did not, and the guard said so.
        UUID retired = place("폐기된 장소");
        jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", retired);
        jdbc.update("UPDATE places SET status = 'DEPRECATED', canonical_place_id = ? WHERE id = ?",
                canonical, retired);

        related(owner, retired)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePlaceId").value(canonical.toString()))
                .andExpect(jsonPath("$.state").value("SIMILAR"))
                .andExpect(jsonPath("$.items[0].place.id").value(target.toString()));
    }

    @Test
    @DisplayName("a filter with no reading code is refused rather than silently ignored")
    void unsupportedFiltersAreRefused() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = place("장소");

        mvc.perform(get("/api/v1/places/{id}/related", place).param("source", RULE).cookie(cookie(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/places/{id}/related", place).param("at", "2032-01-01T00:00:00Z")
                        .cookie(cookie(owner)))
                .andExpect(status().isBadRequest());
        related(owner, place).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BA-024-T3 no branch of this route answers CHECKING, because nothing is being checked")
    void checkingIsNeverAnsweredWhileNoVerificationRuns() throws Exception {
        SessionService.Bootstrap owner = owner();
        // Both branches that can actually answer: nothing stored, and something stored. CHECKING
        // would mean a verification is in flight for this place, and P0 registers no such job - so
        // the honest answer on either is a settled one.
        UUID empty = place("후보 없는 장소");
        UUID populated = place("후보 있는 장소");
        similar(populated, place("대상 장소"));

        for (UUID place : List.of(empty, populated)) {
            related(owner, place)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value(org.hamcrest.Matchers.not("CHECKING")));
        }

        // Two things this does not prove, both worth naming. It cannot show that a future branch
        // could not emit CHECKING - it drives the branches that exist, and BA-024-T7 is where the
        // value is registered as having no producer. And there is a third branch in the service, the
        // one that drops a candidate whose target does not project, which no fixture here can reach:
        // V027 refuses a relation to a place that is not active and refuses to retire one that has a
        // relation, and summaries() filters on nothing but ACTIVE. The first version of this test
        // tried to reach it by removing the target's coordinates and got SIMILAR back, because that
        // projection has no coordinate filter.
    }

    @Test
    @DisplayName("BA-024-T1 two sources naming one place answer one related place, not two")
    void twoSourcesForOneCanonicalPairConvergeOnOneItem() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID source = place("출처 장소");
        UUID shared = place("두 출처가 함께 지목한 장소");
        UUID once = place("한 출처만 지목한 장소");

        // place_relations is unique per (source, target, source_code), so one pair holds one row per
        // source and nothing below this layer collapses them. PlaceRelationFoundationIT's BA-024-T1
        // covers the other way a duplicate arises - the same source writing the pair twice - which the
        // table itself refuses. This is the half the table cannot refuse.
        similar(source, shared);
        official(source, shared);
        similar(source, once);

        related(owner, source)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.place.id == '%s')]".formatted(shared))
                        .value(org.hamcrest.Matchers.hasSize(1)))
                // Which of the two survives is settled by the registry code, and that is a tie-break
                // rather than a preference: no reviewed ranking of relation sources exists, so the
                // projection takes the one piece of content guaranteed to differ. What this pins is
                // that it is the same row on every request, not that an official source outranks ours.
                .andExpect(jsonPath("$.items[?(@.place.id == '%s')].provenance.source".formatted(shared))
                        .value(org.hamcrest.Matchers.contains(OFFICIAL)));
    }

    @Test
    @DisplayName("a place that projects nothing is a 404, never an empty relation list")
    void anUnknownPlaceIsNotFound() throws Exception {
        related(owner(), UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private ResultActions related(SessionService.Bootstrap owner, UUID placeId) throws Exception {
        return mvc.perform(get("/api/v1/places/{id}/related", placeId).cookie(cookie(owner)));
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }

    private UUID place(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, timestamp(NOW), timestamp(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, name, timestamp(NOW));
        return id;
    }

    private void similar(UUID source, UUID target) {
        relation(source, target, "INTERNAL_RULE", RULE, "같은 분류·지역");
    }

    /**
     * The same pair from the official relation source. V027 keeps the two halves apart - only
     * NULLNULL_CATALOG_RULE may be filed as our own rule - so a second source is necessarily a
     * provider-direct row. It stays SIMILAR because EXACT would also need a confirmed mapping.
     */
    private void official(UUID source, UUID target) {
        relation(source, target, "PROVIDER_DIRECT", OFFICIAL, "공식 연계 장소");
    }

    private void relation(UUID source, UUID target, String derivation, String sourceCode, String reason) {
        jdbc.update("""
                INSERT INTO place_relations
                    (id, source_place_id, target_place_id, relation_type, derivation, mapping_certainty,
                     relation_reason, source_code, source_registry_version, effective_at, expires_at, created_at)
                VALUES (?, ?, ?, 'SIMILAR', ?, 'UNCERTAIN', ?, ?, 1, ?, NULL, ?)
                """, UUID.randomUUID(), source, target, derivation, reason, sourceCode,
                timestamp(BEFORE), timestamp(BEFORE));
    }

    private static Timestamp timestamp(Instant at) {
        return Timestamp.from(at);
    }
}
