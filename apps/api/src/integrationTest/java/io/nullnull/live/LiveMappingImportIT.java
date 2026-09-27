package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.live.infrastructure.curation.LiveMappingImporter;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LiveMappingImportIT {

    @Autowired LiveMappingImporter importer;
    @Autowired LiveAreaStore areas;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private UUID placeId;
    private UUID otherPlaceId;
    private UUID areaId;
    private UUID replacementAreaId;

    @AfterEach
    void removeOnlyThisTestsRows() {
        if (placeId != null) {
            jdbc.update("DELETE FROM seoul_live_area_maps WHERE place_id = ?", placeId);
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
        if (otherPlaceId != null) {
            jdbc.update("DELETE FROM seoul_live_area_maps WHERE place_id = ?", otherPlaceId);
            jdbc.update("DELETE FROM places WHERE id = ?", otherPlaceId);
        }
        if (areaId != null) {
            jdbc.update("DELETE FROM live_areas WHERE id = ?", areaId);
        }
        if (replacementAreaId != null) {
            jdbc.update("DELETE FROM live_areas WHERE id = ?", replacementAreaId);
        }
    }

    @Test
    @DisplayName("BA-091-T17 승인된 Live 매핑은 정확한 활성 구역에만 기록된다")
    void approvedMappingIsBoundToTheNamedArea() {
        placeId = place();
        String areaName = "검토 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), areaName)).id();
        var plan = new LiveMappingImporter.Plan(List.of(mapping(areaName)));

        assertThat(importer.importPlan(plan)).containsExactly(placeId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM seoul_live_area_maps WHERE place_id = ?",
                Integer.class, placeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT live_area_id FROM seoul_live_area_maps WHERE place_id = ?",
                UUID.class, placeId)).isEqualTo(areaId);
        assertThat(jdbc.queryForObject("SELECT mapping_type FROM seoul_live_area_maps WHERE place_id = ?",
                String.class, placeId)).isEqualTo("AREA_FALLBACK");
    }

    @Test
    @DisplayName("BA-091-T18 같은 Live 매핑 계획을 다시 적용해도 행은 하나다")
    void repeatedPlanHasOneRow() {
        placeId = place();
        String areaName = "검토 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), areaName)).id();
        var plan = new LiveMappingImporter.Plan(List.of(mapping(areaName)));

        importer.importPlan(plan);
        importer.importPlan(plan);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM seoul_live_area_maps WHERE place_id = ?",
                Integer.class, placeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-091 존재하지 않는 구역의 매핑은 기록하지 않는다")
    void absentAreaLeavesNoMapping() {
        placeId = place();

        assertThatThrownBy(() -> importer.importPlan(new LiveMappingImporter.Plan(List.of(mapping("없는 구역")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seoul_live_area_maps WHERE place_id = ?",
                Integer.class, placeId)).isZero();
    }

    @Test
    @DisplayName("BA-091-T19 오래된 승인 계획은 더 새로운 매핑 검토를 되돌리지 않는다")
    void olderApprovedPlanCannotOverwriteReview() {
        placeId = place();
        String areaName = "검토 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), areaName)).id();
        var newer = mapping(areaName, Instant.parse("2026-09-19T20:00:00Z"));
        var older = mapping(areaName, Instant.parse("2026-09-18T00:00:00Z"));
        importer.importPlan(new LiveMappingImporter.Plan(List.of(newer)));

        assertThatThrownBy(() -> importer.importPlan(new LiveMappingImporter.Plan(List.of(older))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT verified_at FROM seoul_live_area_maps WHERE place_id = ?",
                Timestamp.class, placeId).toInstant()).isEqualTo(newer.verifiedAt());
    }

    @Test
    @DisplayName("BA-091-T25 같은 승인 시각의 다른 매핑 판단은 기존 판단을 덮지 않는다")
    void equalTimeDifferentMetadataCannotOverwriteReview() {
        placeId = place();
        String areaName = "검토 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), areaName)).id();
        var original = mapping(areaName);
        importer.importPlan(new LiveMappingImporter.Plan(List.of(original)));
        var conflicting = new LiveMappingImporter.Mapping(placeId, areaName, "AREA", BigDecimal.ONE,
                false, original.verifiedAt(), original.evidenceUrl());

        assertThatThrownBy(() -> importer.importPlan(new LiveMappingImporter.Plan(List.of(conflicting))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT mapping_type FROM seoul_live_area_maps WHERE place_id = ?",
                String.class, placeId)).isEqualTo("AREA_FALLBACK");
    }

    @Test
    @DisplayName("BA-091-T20 새 승인 계획은 이전 구역 연결을 원자적으로 교체한다")
    void newerReviewCanReplaceArea() {
        placeId = place();
        String oldName = "이전 구역 " + UUID.randomUUID();
        String newName = "새 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), oldName)).id();
        replacementAreaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), newName)).id();
        importer.importPlan(new LiveMappingImporter.Plan(List.of(mapping(oldName,
                Instant.parse("2026-09-18T00:00:00Z")))));

        importer.importPlan(new LiveMappingImporter.Plan(List.of(mapping(newName,
                Instant.parse("2026-09-19T20:00:00Z")))));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM seoul_live_area_maps WHERE place_id = ?",
                Integer.class, placeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT live_area_id FROM seoul_live_area_maps WHERE place_id = ?",
                UUID.class, placeId)).isEqualTo(replacementAreaId);
    }

    /**
     * The half of T20 the end state cannot show. The replacement is a DELETE of the old link and then an INSERT
     * of the new one, and a reader between the two would find the place mapped to nothing. The INSERT's foreign
     * key check takes a key-share lock on the new area's row, so holding that row FOR UPDATE from another
     * connection parks the import after its DELETE, inside its INSERT. While it waits, a third connection must
     * still see exactly the old link: the DELETE becomes visible only with the whole plan.
     */
    @Test
    @DisplayName("BA-091-T20 교체 도중 다른 연결은 이전 구역 연결만 본다")
    void aReaderDuringTheReplacementSeesOnlyTheOldLink() throws Exception {
        placeId = place();
        String oldName = "이전 구역 " + UUID.randomUUID();
        String newName = "새 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), oldName)).id();
        replacementAreaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), newName)).id();
        importer.importPlan(new LiveMappingImporter.Plan(List.of(mapping(oldName,
                Instant.parse("2026-09-18T00:00:00Z")))));

        ExecutorService importing = Executors.newSingleThreadExecutor();
        try {
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT id FROM live_areas WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, replacementAreaId);
                    lock.executeQuery().close();
                }
                Future<List<UUID>> replacing = importing.submit(() -> importer.importPlan(
                        new LiveMappingImporter.Plan(List.of(mapping(newName, Instant.parse("2026-09-19T20:00:00Z"))))));
                try {
                    // Parked on the new link's INSERT, which the plan reaches only after its DELETE ran.
                    String parked = awaitStatementBlockedBy(backendPid(holder));
                    assertThat(parked).as("the statement the import waits in")
                            .contains("INSERT INTO seoul_live_area_maps");
                    assertThat(jdbc.queryForList("SELECT live_area_id FROM seoul_live_area_maps WHERE place_id = ?",
                            UUID.class, placeId)).as("what another connection sees mid-replacement")
                            .containsExactly(areaId);
                } finally {
                    // Released on every path, so the import can finish whether or not the lines above held.
                    holder.rollback();
                }
                assertThat(replacing.get(30, TimeUnit.SECONDS)).containsExactly(placeId);
            }
        } finally {
            // Joined, not only interrupted: a JDBC call ignores the interrupt, so a worker still writing when
            // @AfterEach deletes this test's rows races that cleanup - on a failing path above, and under the
            // gate's one shared database a neighbour's cleanup too. Bounded, so a stuck worker fails loudly.
            importing.shutdown();
            assertThat(importing.awaitTermination(30, TimeUnit.SECONDS))
                    .as("the import finished before this test's rows are removed").isTrue();
        }
        assertThat(jdbc.queryForList("SELECT live_area_id FROM seoul_live_area_maps WHERE place_id = ?",
                UUID.class, placeId)).containsExactly(replacementAreaId);
    }

    @Test
    @DisplayName("BA-091-T21 계획의 두 번째 매핑이 무효이면 첫 번째도 기록되지 않는다")
    void laterInvalidMappingRollsBackWholePlan() {
        placeId = place();
        otherPlaceId = place();
        String areaName = "검토 구역 " + UUID.randomUUID();
        areaId = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("POI-" + UUID.randomUUID(), areaName)).id();
        var plan = new LiveMappingImporter.Plan(List.of(mapping(areaName),
                mappingFor(otherPlaceId, "존재하지 않는 구역", Instant.parse("2026-09-19T20:00:00Z"))));

        assertThatThrownBy(() -> importer.importPlan(plan)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seoul_live_area_maps WHERE place_id IN (?, ?)",
                Integer.class, placeId, otherPlaceId)).isZero();
    }

    private LiveMappingImporter.Mapping mapping(String areaName) {
        return mapping(areaName, Instant.parse("2026-09-20T06:00:00Z"));
    }

    private LiveMappingImporter.Mapping mapping(String areaName, Instant verifiedAt) {
        return mappingFor(placeId, areaName, verifiedAt);
    }

    private LiveMappingImporter.Mapping mappingFor(UUID id, String areaName, Instant verifiedAt) {
        return new LiveMappingImporter.Mapping(id, areaName, "AREA_FALLBACK",
                new BigDecimal("0.7500"), true, verifiedAt,
                "https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do");
    }

    /** Waits until a backend is blocked by {@code holderPid} and returns the statement it is blocked in. */
    private String awaitStatementBlockedBy(int holderPid) {
        return Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(10, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.query("SELECT query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                        (row, ignored) -> row.getString(1), holderPid).stream().findFirst().orElse(null),
                        java.util.Objects::nonNull);
    }

    private static int backendPid(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            return pid.getInt(1);
        }
    }

    private UUID place() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO places (id, canonical_name, category_code, latitude, longitude, region_code,
                                    status, created_at, updated_at)
                VALUES (?, ?, 'A0201', 37.579617, 126.977041, '11', 'ACTIVE', ?, ?)
                """, id, "검토 장소 " + id, now, now);
        return id;
    }
}
