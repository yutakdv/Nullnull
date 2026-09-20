package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.live.infrastructure.curation.LiveMappingImporter;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    private UUID placeId;
    private UUID areaId;

    @AfterEach
    void removeOnlyThisTestsRows() {
        if (placeId != null) {
            jdbc.update("DELETE FROM seoul_live_area_maps WHERE place_id = ?", placeId);
            jdbc.update("DELETE FROM places WHERE id = ?", placeId);
        }
        if (areaId != null) {
            jdbc.update("DELETE FROM live_areas WHERE id = ?", areaId);
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

    private LiveMappingImporter.Mapping mapping(String areaName) {
        return new LiveMappingImporter.Mapping(placeId, areaName, "AREA_FALLBACK",
                new BigDecimal("0.7500"), true, Instant.parse("2026-09-20T06:00:00Z"),
                "https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do");
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
