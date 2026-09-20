package io.nullnull.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import io.nullnull.crowd.domain.SeoulCongestionStage;
import io.nullnull.crowd.domain.SourceState;
import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.OwnedRows;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One Seoul reading through a real PostgreSQL, where the CHECKs are.
 *
 * <p>The columns this asserts are the ones a unit test cannot: {@code crowd_snapshots} has seven
 * CHECK constraints that bind the subject, the scope, the state and the staleness to each other, and
 * the writer satisfies them or the row does not exist. A fake store would accept any combination.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-090 stored Seoul readings")
class SeoulLiveSnapshotStoreIT {

    private static final String SOURCE = "SEOUL_CITYDATA";
    private static final Instant OBSERVED = Instant.parse("2026-09-20T06:15:00Z");
    private static final Instant FETCHED = Instant.parse("2026-09-20T06:18:00Z");

    @Autowired SeoulLiveSnapshotStore snapshots;
    @Autowired LiveAreaStore areas;
    @Autowired JdbcTemplate jdbc;

    private List<UUID> snapshotsBefore;
    private List<UUID> setsBefore;
    private List<UUID> areasBefore;
    private List<UUID> runsBefore;

    @BeforeEach
    void noteRowsAlreadyPresent() {
        snapshotsBefore = OwnedRows.snapshot(jdbc, "crowd_snapshots");
        setsBefore = OwnedRows.snapshot(jdbc, "snapshot_sets");
        areasBefore = OwnedRows.snapshot(jdbc, "live_areas");
        runsBefore = OwnedRows.snapshot(jdbc, "collector_runs");
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        // Child first: crowd_snapshots references snapshot_sets, which references collector_runs.
        // Named rows only - the gate runs every suite against ONE database.
        OwnedRows.remove(jdbc, "crowd_snapshots", OwnedRows.appeared(jdbc, "crowd_snapshots", snapshotsBefore));
        OwnedRows.remove(jdbc, "snapshot_sets", OwnedRows.appeared(jdbc, "snapshot_sets", setsBefore));
        OwnedRows.remove(jdbc, "live_areas", OwnedRows.appeared(jdbc, "live_areas", areasBefore));
        OwnedRows.remove(jdbc, "collector_runs", OwnedRows.appeared(jdbc, "collector_runs", runsBefore));
    }

    @Test
    @DisplayName("BA-090 저장된 관측은 구역의 것이고 값도 단계도 담지 않는다")
    void anAreaReadingIsStoredWithItsSubjectAndWithoutAFigureNobodyMeasured() {
        UUID areaId = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert("POI009", "광화문·덕수궁")).id();
        UUID runId = insertRun();
        UUID snapshotId = UUID.randomUUID();

        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), snapshotId, runId, 2L,
                areaId, OBSERVED, FETCHED, 300L, SeoulCongestionStage.of("보통")));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM crowd_snapshots WHERE id = ?", snapshotId);
        // The subject is the area and only the area: crowd_snapshots_subject_xor_check and
        // crowd_snapshots_scope_check both had to be satisfied for this row to exist at all.
        assertThat(row.get("live_area_id")).isEqualTo(areaId);
        assertThat(row.get("place_id")).isNull();
        assertThat(row.get("scope")).isEqualTo("LIVE_AREA");
        assertThat(row.get("source_state")).isEqualTo("LIVE");
        assertThat(((Timestamp) row.get("observed_at")).toInstant()).isEqualTo(OBSERVED);
        assertThat(((Timestamp) row.get("stale_at")).toInstant()).isEqualTo(OBSERVED.plusSeconds(300));
        // No figure, but a stage. Seoul publishes a population RANGE, so a midpoint would be a
        // number nobody took - value stays null. The stage is different: the source publishes a step
        // and A-060 placed those steps on our scale, so "보통" is cell 2 and that is reviewed
        // evidence rather than a reading of the value.
        assertThat(row.get("value")).isNull();
        assertThat(row.get("unit")).isNull();
        assertThat(row.get("ordinal_level")).isEqualTo("2");
        // DIRECT, not AREA: this row IS the area's reading. AREA is the other direction - a place
        // carrying an area's number - and that belongs to seoul_live_area_maps.
        assertThat(row.get("mapping_type")).isEqualTo("DIRECT");
        assertThat(row.get("metric_code")).isEqualTo(SeoulLiveSnapshotStore.METRIC_CODE);
        assertThat(row.get("normalization_version")).isEqualTo(SeoulLiveSnapshotStore.NORMALIZATION_VERSION);
    }

    @Test
    @DisplayName("BA-090 창 밖 관측은 만료 시각 없이 STALE 로 들어간다")
    void anExpiredReadingIsStoredWithoutAnExpiry() {
        UUID areaId = areas.upsertArea(SOURCE, new LiveAreaStore.AreaUpsert("POI010", "강남역")).id();
        UUID runId = insertRun();
        UUID snapshotId = UUID.randomUUID();

        // Observed an hour before it was fetched, so observed + 300s is in the past.
        // crowd_snapshots_staleness_check would refuse that value; the state carries the fact instead.
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), snapshotId, runId, 2L,
                areaId, FETCHED.minusSeconds(3600), FETCHED, 300L, SeoulCongestionStage.of("붐빔")));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM crowd_snapshots WHERE id = ?", snapshotId);
        assertThat(row.get("source_state")).isEqualTo("STALE");
        assertThat(row.get("stale_at")).isNull();
    }

    @Test
    @DisplayName("BA-090 만료 시각이 수집 시각보다 뒤가 아니면 그 모양은 만들어지지 않는다")
    void anExpiryThatIsNotAfterTheFetchIsRefusedBeforeItReachesTheDatabase() {
        // The guard in Reading's constructor states the same rule crowd_snapshots_staleness_check
        // does. It is here so that the branch above is the ONLY producer of a null expiry - a caller
        // assembling the record by hand cannot smuggle an expiry the table would reject.
        assertThatThrownBy(() -> new SeoulLiveSnapshotStore.Reading(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2L, UUID.randomUUID(), SourceState.LIVE, OBSERVED, FETCHED, FETCHED, "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("staleAt");
    }

    private UUID insertRun() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, ?, 'COMPLETED', 'MANUAL', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)
                """, runId, SOURCE, Timestamp.from(FETCHED), Timestamp.from(FETCHED));
        return runId;
    }
}
