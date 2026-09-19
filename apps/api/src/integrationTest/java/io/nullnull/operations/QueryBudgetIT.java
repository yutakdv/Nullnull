package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.nullnull.identity.application.SessionService;
import io.nullnull.testsupport.CountingDataSource;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BA-070-T3: what a request of a known size costs, stated as structure rather than as time.
 *
 * <p>The card's own safety boundary forbids recording a target as a measurement and says to keep a
 * noisy CI runner's numbers away from an SLO. A p95 asserted here would be both of those, so this
 * measures what CI can state honestly: how many statements and pooled connections one request takes,
 * and whether either grows with the amount of data it reads.
 *
 * <p><b>Growth is the assertion, not the number.</b> A fixed budget would be a constant nobody
 * measured - pick 7 and the test passes until someone adds an eighth honest statement, then gets
 * raised to 8 and has asserted nothing. That the cost does not change when the rows triple is a
 * property of the code, and it is the property an N+1 breaks.
 */
@SpringBootTest(properties = {
        "nullnull.catalog.public-enabled=true",
        "NULLNULL_CURSOR_SECRET=budget-cursor-secret-that-is-long-enough-abc"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        QueryBudgetIT.Counting.class})
@DisplayName("BA-070 a request's cost does not grow with the rows it reads")
class QueryBudgetIT {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    @TestConfiguration
    static class Counting {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource source && !(bean instanceof CountingDataSource)
                            ? new CountingDataSource(source)
                            : bean;
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;

    /** Ids this class inserted, so teardown touches nothing else. */
    private final java.util.List<UUID> seeded = new java.util.ArrayList<>();
    private final java.util.List<UUID> deprecated = new java.util.ArrayList<>();
    private final java.util.List<UUID> snapshotSets = new java.util.ArrayList<>();
    private final java.util.List<UUID> collectorRuns = new java.util.ArrayList<>();
    @Autowired DataSource dataSource;
    @Autowired Clock clock;

    @AfterEach
    void removeOnlyOwnFixtures() {
        // The forecast rows first, in foreign-key order, then the merged ids ahead of the places they
        // point at: places does not cascade, and a deprecated row names its canonical one.
        snapshotSets.forEach(set -> jdbc.update("DELETE FROM crowd_snapshots WHERE snapshot_set_id = ?", set));
        snapshotSets.forEach(set -> jdbc.update("DELETE FROM snapshot_sets WHERE id = ?", set));
        collectorRuns.forEach(run -> jdbc.update("DELETE FROM collector_runs WHERE id = ?", run));
        deprecated.forEach(id -> jdbc.update("DELETE FROM places WHERE id = ?", id));
        snapshotSets.clear();
        collectorRuns.clear();
        deprecated.clear();
        // Only the rows this class created. A blanket DELETE FROM places is the wrong shape
        // under the gate, which shares ONE database across every context: places is
        // deliberately not cascaded, so the class that tries to clear the table is the one
        // that dies on somebody else's trip_items - and a blanket delete would take their
        // fixtures with it when it succeeds. Local runs cannot show this, because
        // TestcontainersConfiguration gives each distinct @SpringBootTest its own container.
        seeded.forEach(id -> {
            jdbc.update("DELETE FROM place_localizations WHERE place_id = ?", id);
            jdbc.update("DELETE FROM places WHERE id = ?", id);
        });
        seeded.clear();
    }

    @Test
    @DisplayName("BA-070-T3 nine times the rows does not cost nine times the statements")
    void costIsBoundedAndDoesNotGrowWithTheData() throws Exception {
        CountingDataSource counter = (CountingDataSource) dataSource;
        SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");

        for (int at = 0; at < 5; at++) {
            place("예산 시험 장소 " + at);
        }
        // Reset AFTER seeding. The first version of this test did not, and measured its own fixture:
        // two inserts per place, each taking a connection, so the "cost" tripled with the data while
        // the request under test had not changed at all. A counter that spans the setup is measuring
        // the test.
        counter.reset();
        search(owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5));
        CountingDataSource.Counts small = counter.reset();

        for (int at = 5; at < 45; at++) {
            place("예산 시험 장소 " + at);
        }
        counter.reset();
        search(owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(45));
        CountingDataSource.Counts large = counter.reset();

        // The request actually did something, or a cost of zero would compare equal to a cost of zero.
        assertThat(small.statements()).as("the small request must have run statements").isPositive();
        assertThat(small.connections()).isPositive();

        // The bound comes from what an N+1 IS, not from a number somebody picked: hydrating each row
        // with its own query costs at least one extra statement per extra row. So forty more rows
        // must cost fewer than forty more statements. Measured today it costs one - the two sizes are
        // 9 and 10 - and a fixed budget of 9 would be a constant nobody measured, raised to 10 the
        // first time an honest statement is added and asserting nothing thereafter.
        // The bound comes from what an N+1 IS. Hydrating each row with its own query costs one extra
        // statement per row, so the larger call pays 45 and the smaller 5 and the DIFFERENCE is the
        // forty rows between them - exactly the row count. Asserting "fewer than forty" therefore put
        // the defect precisely on the threshold, where rounding decides the verdict; measured, the
        // N+1 landed one statement inside and the test stayed green. Half a statement per added row
        // is the same derivation with the boundary moved off the defect: no per-row query can grow
        // that slowly, and today's honest growth is 1.
        int addedRows = 45 - 5;
        assertThat(large.statements() - small.statements())
                .as("an N+1 grows one statement per row; %s rows were added", addedRows)
                .isLessThan(addedRows / 2);
        assertThat(large.connections() - small.connections())
                .as("a request is one unit of work however many rows it reads")
                .isLessThan(addedRows / 2);
    }

    /**
     * The batch forecast read (#105) under the same rule: five ids and forty-five cost the same number
     * of statements and connections.
     *
     * <p>Measured once per branch rather than once overall, because the bound is half a statement per
     * added id: a query that runs only for SOME ids - a stale lookup for the places without a fresh
     * set, a second look at the ids that did not resolve - would hide under it in a mixed request.
     * Each branch asserts what its items say, so a branch that silently stopped being reached cannot
     * pass as cheap.
     */
    @Test
    @DisplayName("BA-023-T14 the batch forecast's statements and connections do not grow with its placeIds")
    void batchForecastCostDoesNotGrowWithThePlaces() throws Exception {
        CountingDataSource counter = (CountingDataSource) dataSource;
        // Soft, so a defect reports every branch it reaches rather than the first one the loop meets:
        // "which branches go red" is itself the evidence that a per-id query is where it is.
        org.assertj.core.api.SoftAssertions softly = new org.assertj.core.api.SoftAssertions();
        for (Branch branch : Branch.values()) {
            SessionService.Bootstrap owner = sessions.bootstrap(null, "ko-KR", "Asia/Seoul");
            Instant target = clock.instant().plus(Duration.ofDays(1));
            java.util.List<UUID> ids = new java.util.ArrayList<>();
            for (int at = 0; at < 5; at++) {
                ids.add(forecastPlace(branch, at, target));
            }
            // A new session's first request also stamps it as seen - one UPDATE the small request
            // would pay and the large one not. Paid here, outside both measurements.
            forecasts(owner, ids, target).andExpect(status().isOk());
            counter.reset();
            expectBranch(forecasts(owner, ids, target), branch, 5);
            CountingDataSource.Counts small = counter.reset();

            for (int at = 5; at < 45; at++) {
                ids.add(forecastPlace(branch, at, target));
            }
            counter.reset();
            expectBranch(forecasts(owner, ids, target), branch, 45);
            CountingDataSource.Counts large = counter.reset();

            softly.assertThat(small.statements()).as("%s: the small request must have run statements", branch)
                    .isPositive();
            softly.assertThat(small.connections()).as("%s", branch).isPositive();
            // BA-070-T3's derivation: a per-place query costs one statement per added place, so forty
            // more places must cost fewer than twenty more.
            int addedPlaces = 45 - 5;
            softly.assertThat(large.statements() - small.statements())
                    .as("%s: a per-place query grows one statement per place; %s were added", branch, addedPlaces)
                    .isLessThan(addedPlaces / 2);
            softly.assertThat(large.connections() - small.connections())
                    .as("%s: a request is one unit of work however many places it names", branch)
                    .isLessThan(addedPlaces / 2);
        }
        softly.assertAll();
    }

    /** What every requested id is in one measurement, and what its item must then say. */
    private enum Branch {
        FRESH("FORECAST", null),
        STALE("STALE", null),
        NO_COVERAGE("UNAVAILABLE", "NO_COVERAGE"),
        MERGED("FORECAST", null),
        UNRESOLVED("UNAVAILABLE", "PLACE_UNAVAILABLE");

        final String state;
        final String reason;

        Branch(String state, String reason) {
            this.state = state;
            this.reason = reason;
        }
    }

    private void expectBranch(org.springframework.test.web.servlet.ResultActions response, Branch branch, int size)
            throws Exception {
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(size))
                .andExpect(jsonPath("$.items[*].state").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo(branch.state))))
                .andExpect(jsonPath("$.items[*].unavailableReason").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.equalTo(branch.reason))));
    }

    /** One requested id of the given branch: the id the request sends, with whatever it needs behind it. */
    private UUID forecastPlace(Branch branch, int at, Instant target) {
        String name = "예산 시험 예보 " + branch + " " + at;
        return switch (branch) {
            case FRESH -> withSet(place(name, true), target, false);
            case STALE -> withSet(place(name, true), target, true);
            case NO_COVERAGE -> place(name, true);
            case MERGED -> merged(withSet(place(name, true), target, false));
            // Half ids no place has, half places without coordinates: the two ways an id is unreadable.
            case UNRESOLVED -> at % 2 == 0 ? UUID.randomUUID() : place(name, false);
        };
    }

    private org.springframework.test.web.servlet.ResultActions forecasts(SessionService.Bootstrap owner,
            java.util.List<UUID> placeIds, Instant target) throws Exception {
        String ids = placeIds.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(post("/api/v1/places/crowd-forecasts/query")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"placeIds\":[" + ids + "],\"from\":\"" + target + "\",\"to\":\"" + target + "\"}"));
    }

    private UUID place(String name, boolean withCoordinates) {
        UUID id = UUID.randomUUID();
        seeded.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', ?, ?, '11', 'ACTIVE', ?, ?)
                """, id, name, withCoordinates ? new java.math.BigDecimal("37.579617") : null,
                withCoordinates ? new java.math.BigDecimal("126.977041") : null, Timestamp.from(NOW),
                Timestamp.from(NOW));
        return id;
    }

    private UUID merged(UUID canonical) {
        UUID id = UUID.randomUUID();
        deprecated.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_place_id, canonical_name, category_code, latitude, longitude, region_code,
                     status, created_at, updated_at)
                VALUES (?, ?, '예산 시험 병합', 'A0201', NULL, NULL, '11', 'DEPRECATED', ?, ?)
                """, id, canonical, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    /** One set per place, as a KTO refresh stores one per mapping; fresh or already past staleAt. */
    private UUID withSet(UUID place, Instant target, boolean stale) {
        Instant now = clock.instant();
        Instant fetchedAt = stale ? now.minus(Duration.ofDays(2)) : now.minus(Duration.ofMinutes(5));
        Instant staleAt = stale ? now.minus(Duration.ofHours(1)) : now.plus(Duration.ofHours(23));
        long version = jdbc.queryForObject(
                "SELECT current_revision FROM source_registry WHERE code = 'KTO_CONCENTRATION_FORECAST'", Long.class);
        UUID run = UUID.randomUUID();
        collectorRuns.add(run);
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted, records_rejected,
                     schema_version, started_at, finished_at)
                VALUES (?, 'KTO_CONCENTRATION_FORECAST', 'COMPLETED', 'READ_THROUGH', 1, 1, 0,
                        'kto-tats-cnctr-rate-v4.1', ?, ?)
                """, run, Timestamp.from(fetchedAt), Timestamp.from(fetchedAt));
        UUID set = UUID.randomUUID();
        snapshotSets.add(set);
        String issue = "budget-" + set;
        jdbc.update("""
                INSERT INTO snapshot_sets
                    (id, source_code, source_registry_version, collector_run_id, source_state, forecast_issue_id,
                     comparison_group_id, observed_at, fetched_at, stale_at, normalization_version, created_at)
                VALUES (?, 'KTO_CONCENTRATION_FORECAST', ?, ?, 'FORECAST', ?, ?, NULL, ?, ?,
                        'kto-tats-cnctr-rate-v4.1', ?)
                """, set, version, run, issue, issue, Timestamp.from(fetchedAt), Timestamp.from(staleAt),
                Timestamp.from(fetchedAt));
        jdbc.update("""
                INSERT INTO crowd_snapshots
                    (id, snapshot_set_id, source_code, source_registry_version, place_id, source_state,
                     observed_at, target_at, fetched_at, stale_at, metric_code, value, unit, ordinal_level,
                     confidence, quality_flags, forecast_issue_id, comparison_group_id, normalization_version,
                     observed_at_skew_seconds, scope, scope_label, mapping_type, fallback_used, created_at)
                VALUES (?, ?, 'KTO_CONCENTRATION_FORECAST', ?, ?, 'FORECAST', NULL, ?, ?, ?,
                        'KTO_RELATIVE_CONCENTRATION_INDEX', 40, 'relative-index', NULL, NULL, '[]'::jsonb, ?, ?,
                        'kto-tats-cnctr-rate-v4.1', NULL, 'PLACE', 'budget fixture place', 'DIRECT', false, ?)
                """, UUID.randomUUID(), set, version, place, Timestamp.from(target), Timestamp.from(fetchedAt),
                Timestamp.from(staleAt), issue, issue, Timestamp.from(fetchedAt));
        return place;
    }

    private org.springframework.test.web.servlet.ResultActions search(SessionService.Bootstrap owner)
            throws Exception {
        return mvc.perform(post("/api/v1/places/search")
                .cookie(new Cookie("__Host-nullnull_session", owner.cookie))
                .header("Origin", "http://localhost:5173")
                .contentType("application/json")
                .content("{\"query\":\"예산 시험 장소\",\"locale\":\"ko-KR\",\"limit\":50}"));
    }

    private void place(String name) {
        UUID id = UUID.randomUUID();
        seeded.add(id);
        jdbc.update("""
                INSERT INTO places
                    (id, canonical_name, category_code, latitude, longitude, region_code, status,
                     created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, name, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                VALUES (?, ?, 'ko-KR', ?, '서울시 어딘가', ?)
                """, UUID.randomUUID(), id, name, Timestamp.from(NOW));
    }
}
