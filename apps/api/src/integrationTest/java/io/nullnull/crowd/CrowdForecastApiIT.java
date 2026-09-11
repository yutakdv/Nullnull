package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** C4's local API proof: controlled PostgreSQL fixtures only, never an external KTO call. */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=test-catalog-cursor-secret-that-is-long-enough"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class, CrowdForecastApiIT.Time.class})
@DisplayName("BA-023 crowd forecast HTTP projection")
class CrowdForecastApiIT {

    private static final String FORECAST_SOURCE = "KTO_CONCENTRATION_FORECAST";
    private static final String PLACE_SOURCE = "KTO_KOR_SERVICE_2";

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock crowdClock() {
            return MutableClock.at(Instant.parse("2032-01-01T00:00:00Z"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private final List<UUID> snapshotSets = new ArrayList<>();
    private final List<UUID> collectorRuns = new ArrayList<>();
    private final List<UUID> externalReferences = new ArrayList<>();
    private final List<UUID> localizations = new ArrayList<>();
    private final List<UUID> places = new ArrayList<>();
    private final List<UUID> incidents = new ArrayList<>();

    @AfterEach
    void removeOnlyC4Fixtures() {
        for (UUID snapshotSet : snapshotSets) {
            jdbc.update("DELETE FROM crowd_snapshots WHERE snapshot_set_id = ?", snapshotSet);
        }
        for (UUID snapshotSet : snapshotSets) {
            jdbc.update("DELETE FROM snapshot_sets WHERE id = ?", snapshotSet);
        }
        for (UUID run : collectorRuns) {
            jdbc.update("DELETE FROM collector_runs WHERE id = ?", run);
        }
        for (UUID reference : externalReferences) {
            jdbc.update("DELETE FROM place_external_refs WHERE id = ?", reference);
        }
        for (UUID localization : localizations) {
            jdbc.update("DELETE FROM place_localizations WHERE id = ?", localization);
        }
        for (UUID place : places) {
            jdbc.update("DELETE FROM places WHERE id = ?", place);
        }
        for (UUID incident : incidents) {
            jdbc.update("DELETE FROM source_quality_incidents WHERE id = ?", incident);
        }
    }

    @Test
    @DisplayName("BA-023-T1 fresh forecast preserves null publisher time and full source guidance")
    void freshForecastKeepsTimeAndProvenanceSemantics() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = activePlace("C4 fresh fixture");
        Instant targetOne = clock.instant().plus(Duration.ofDays(1));
        Instant targetTwo = clock.instant().plus(Duration.ofDays(2));
        insertForecastSet(place, "issue-fresh", clock.instant().minus(Duration.ofMinutes(5)),
                clock.instant().plus(Duration.ofHours(23)), List.of(targetOne, targetTwo), BigDecimal.valueOf(42.5));

        MvcResult result = forecast(owner, place, targetOne, targetTwo)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.placeId").value(place.toString()))
                .andExpect(jsonPath("$.state").value("FORECAST"))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].state").value("FORECAST"))
                .andExpect(jsonPath("$.points[0].value").value(42.5))
                .andExpect(jsonPath("$.points[0].provenance.freshness").value("FRESH"))
                .andExpect(jsonPath("$.points[0].provenance.comparisonAxis").value("TEMPORAL"))
                .andExpect(jsonPath("$.points[0].provenance.comparisonEligible").value(true))
                .andExpect(jsonPath("$.points[0].provenance.comparisonReasonCode")
                        .value("SAME_METRIC_AND_ISSUE"))
                .andExpect(jsonPath("$.points[0].provenance.source").value(FORECAST_SOURCE))
                .andExpect(jsonPath("$.points[0].provenance.license").value("이용허락범위 제한 없음"))
                .andExpect(jsonPath("$.points[0].provenance.officialUrl")
                        .value("https://www.data.go.kr/data/15128555/openapi.do"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("\"observedAt\":null", "\"targetAt\":\"" + targetOne + "\"");
    }

    @Test
    @DisplayName("BA-023-T1 stale fallback and invalid windows are explicit rather than invented")
    void staleFallbackAndRangeValidationStayExplicit() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = activePlace("C4 stale fixture");
        Instant target = clock.instant().plus(Duration.ofDays(1));
        insertForecastSet(place, "issue-stale", clock.instant().minus(Duration.ofDays(2)),
                clock.instant().minus(Duration.ofHours(1)), List.of(target), BigDecimal.valueOf(31));

        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.points[0].state").value("STALE"))
                .andExpect(jsonPath("$.points[0].provenance.freshness").value("STALE"))
                .andExpect(jsonPath("$.points[0].provenance.fallbackUsed").value(true))
                .andExpect(jsonPath("$.points[0].provenance.comparisonEligible").value(false))
                .andExpect(jsonPath("$.points[0].provenance.comparisonReasonCode").value("STALE_INPUT"));

        forecast(owner, place, clock.instant(), clock.instant().plus(Duration.ofDays(31)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("BA-023-T3 a newer set changes latest output but cannot rewrite the saved snapshot")
    void newerSetDoesNotMutatePriorSnapshotMeaning() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = activePlace("C4 immutable fixture");
        Instant target = clock.instant().plus(Duration.ofDays(1));
        UUID oldSnapshot = insertForecastSet(place, "issue-before", clock.instant().minus(Duration.ofHours(2)),
                clock.instant().plus(Duration.ofHours(20)), List.of(target), BigDecimal.valueOf(30)).get(0);

        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].value").value(30));

        insertForecastSet(place, "issue-after", clock.instant().minus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofHours(21)), List.of(target), BigDecimal.valueOf(50));
        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].value").value(50));

        assertThat(jdbc.queryForObject("SELECT value FROM crowd_snapshots WHERE id = ?", BigDecimal.class, oldSnapshot))
                .isEqualByComparingTo("30");
        assertThatThrownBy(() -> jdbc.update("UPDATE crowd_snapshots SET value = 99 WHERE id = ?", oldSnapshot))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
    }

    /**
     * The incident is declared after the snapshot was already stored and the row itself is immutable,
     * so eligibility has to be resolved at read time or a quarantined value keeps claiming comparability.
     */
    @Test
    @DisplayName("BA-023-T2 a quarantine incident declared after collection removes comparison eligibility")
    void quarantineIncidentSuppressesComparisonOnAStoredSnapshot() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = activePlace("C4 incident fixture");
        Instant target = clock.instant().plus(Duration.ofDays(1));
        Instant fetchedAt = clock.instant().minus(Duration.ofMinutes(5));
        UUID snapshot = insertForecastSet(place, "issue-incident", fetchedAt,
                clock.instant().plus(Duration.ofHours(23)), List.of(target), BigDecimal.valueOf(42.5)).get(0);

        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].provenance.qualityFlags").isEmpty())
                .andExpect(jsonPath("$.points[0].provenance.comparisonEligible").value(true));

        quarantine(FORECAST_SOURCE, fetchedAt.minus(Duration.ofMinutes(1)));

        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].value").value(42.5))
                .andExpect(jsonPath("$.points[0].provenance.qualityFlags[0]").value("PROVIDER_INCIDENT"))
                .andExpect(jsonPath("$.points[0].provenance.comparisonEligible").value(false))
                .andExpect(jsonPath("$.points[0].provenance.comparisonReasonCode").value("PROVIDER_INCIDENT"));
        assertThat(jdbc.queryForObject("SELECT quality_flags::text FROM crowd_snapshots WHERE id = ?", String.class,
                snapshot)).isEqualTo("[]");
    }

    @Test
    @DisplayName("no local coverage remains an explicit unavailable series")
    void noCoverageDoesNotInventAForecast() throws Exception {
        SessionService.Bootstrap owner = owner();
        UUID place = activePlace("C4 no coverage fixture");
        Instant target = clock.instant().plus(Duration.ofDays(1));

        forecast(owner, place, target, target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.points").isEmpty())
                .andExpect(jsonPath("$.unavailableReason").value("NO_COVERAGE"));
    }

    private org.springframework.test.web.servlet.ResultActions forecast(SessionService.Bootstrap owner, UUID place,
            Instant from, Instant to) throws Exception {
        return mvc.perform(get("/api/v1/places/{placeId}/crowd-forecast", place).cookie(cookie(owner))
                .param("from", from.toString()).param("to", to.toString()));
    }

    private void quarantine(String sourceCode, Instant affectedFrom) {
        UUID incident = UUID.randomUUID();
        incidents.add(incident);
        jdbc.update("""
                INSERT INTO source_quality_incidents
                    (id, source_code, incident_code, affected_from, affected_to, scope, disposition, reviewed_at)
                VALUES (?, ?, ?, ?, NULL, 'PLACE', 'QUARANTINE', ?)
                """, incident, sourceCode, "c4-incident-" + incident, Timestamp.from(affectedFrom), timestamp());
    }

    private SessionService.Bootstrap owner() {
        return sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
    }

    private UUID activePlace(String name) {
        UUID place = UUID.randomUUID();
        places.add(place);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status, created_at, updated_at)
                VALUES (?, ?, 'A0101', 37.566535, 126.978001, '1', 'ACTIVE', ?, ?)
                """, place, name, timestamp(), timestamp());
        UUID localization = UUID.randomUUID();
        localizations.add(localization);
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 종로구', ?)
                """, localization, place, name, timestamp());
        UUID reference = UUID.randomUUID();
        externalReferences.add(reference);
        jdbc.update("""
                INSERT INTO place_external_refs
                    (id, place_id, source_code, source_registry_version, external_id, external_type, verified_at)
                VALUES (?, ?, ?, ?, ?, 'KTO_CONTENT_TYPE:12', ?)
                """, reference, place, PLACE_SOURCE, sourceVersion(PLACE_SOURCE), "fixture-" + place, timestamp());
        return place;
    }

    private List<UUID> insertForecastSet(UUID place, String issue, Instant fetchedAt, Instant staleAt,
            List<Instant> targets, BigDecimal value) {
        long sourceVersion = sourceVersion(FORECAST_SOURCE);
        UUID run = UUID.randomUUID();
        collectorRuns.add(run);
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted, records_rejected,
                     schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'READ_THROUGH', ?, ?, 0, 'kto-tats-cnctr-rate-v4.1', ?, ?)
                """, run, FORECAST_SOURCE, targets.size(), targets.size(), Timestamp.from(fetchedAt),
                Timestamp.from(fetchedAt));
        UUID set = UUID.randomUUID();
        snapshotSets.add(set);
        jdbc.update("""
                INSERT INTO snapshot_sets
                    (id, source_code, source_registry_version, collector_run_id, source_state, forecast_issue_id,
                     comparison_group_id, observed_at, fetched_at, stale_at, normalization_version, created_at)
                VALUES (?, ?, ?, ?, 'FORECAST', ?, ?, NULL, ?, ?, 'kto-tats-cnctr-rate-v4.1', ?)
                """, set, FORECAST_SOURCE, sourceVersion, run, issue, issue, Timestamp.from(fetchedAt),
                Timestamp.from(staleAt), Timestamp.from(fetchedAt));
        List<UUID> snapshots = new ArrayList<>();
        for (Instant target : targets) {
            UUID snapshot = UUID.randomUUID();
            snapshots.add(snapshot);
            jdbc.update("""
                    INSERT INTO crowd_snapshots
                        (id, snapshot_set_id, source_code, source_registry_version, place_id, source_state,
                         observed_at, target_at, fetched_at, stale_at, metric_code, value, unit, ordinal_level,
                         confidence, quality_flags, forecast_issue_id, comparison_group_id, normalization_version,
                         observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used, created_at)
                    VALUES (?, ?, ?, ?, ?, 'FORECAST', NULL, ?, ?, ?, 'KTO_RELATIVE_CONCENTRATION_INDEX', ?,
                            'relative-index', NULL, NULL, '[]'::jsonb, ?, ?, 'kto-tats-cnctr-rate-v4.1', NULL,
                            'PLACE', 'C4 fixture place', 'DIRECT', false, ?)
                    """, snapshot, set, FORECAST_SOURCE, sourceVersion, place, Timestamp.from(target),
                    Timestamp.from(fetchedAt), Timestamp.from(staleAt), value, issue, issue, Timestamp.from(fetchedAt));
        }
        return snapshots;
    }

    private long sourceVersion(String sourceCode) {
        return jdbc.queryForObject("SELECT current_revision FROM source_registry WHERE code = ?", Long.class,
                sourceCode);
    }

    private Timestamp timestamp() {
        return Timestamp.from(clock.instant());
    }

    private static Cookie cookie(SessionService.Bootstrap owner) {
        return new Cookie("__Host-nullnull_session", owner.cookie);
    }
}
