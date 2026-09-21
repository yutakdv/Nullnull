package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import io.nullnull.crowd.domain.SeoulCongestionStage;
import io.nullnull.crowd.infrastructure.persistence.ReplayManifestImporter;
import io.nullnull.live.application.LiveAreaQueryService;
import io.nullnull.live.application.LiveAreaStore;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"nullnull.capabilities.live=true", "nullnull.capabilities.replay=true"})
@Import(TestcontainersConfiguration.class)
class ReplayLiveQueryIT {

    @Autowired LiveAreaQueryService query;
    @Autowired LiveAreaStore areas;
    @Autowired SeoulLiveSnapshotStore snapshots;
    @Autowired ReplayManifestImporter replay;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @Test
    @Transactional
    @DisplayName("BA-092-T2 Live 조회는 승인된 replay만 대체하고 LIVE_ONLY는 원 관측을 보존한다")
    void approvedReplayDoesNotMasqueradeAsCurrentLive() {
        Instant now = clock.instant();
        Instant observed = now.minusSeconds(900);
        UUID area = areas.upsertArea("SEOUL_CITYDATA",
                new LiveAreaStore.AreaUpsert("REPLAY-" + UUID.randomUUID(), "replay 검증 구역")).id();
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, records_received, records_accepted,
                     records_rejected, schema_version, started_at, finished_at)
                VALUES (?, 'SEOUL_CITYDATA', 'COMPLETED', 'SCHEDULED', 1, 1, 0,
                        'seoul-citydata-v8.5', ?, ?)
                """, run, Timestamp.from(observed.plusSeconds(5)), Timestamp.from(observed.plusSeconds(5)));
        UUID point = UUID.randomUUID();
        snapshots.save(SeoulLiveSnapshotStore.Reading.of(UUID.randomUUID(), point, run, 2L,
                area, observed, observed.plusSeconds(5), 300L, SeoulCongestionStage.of("보통")));
        replay.importPlan(new ReplayManifestImporter.Plan("query-" + UUID.randomUUID(),
                observed.minusSeconds(1), observed.plusSeconds(1), List.of(point)));

        var automatic = query.query("AUTO", null, null, null, null, null);
        var liveOnly = query.query("LIVE_ONLY", null, null, null, null, null);
        var replayArea = automatic.areas().stream().filter(row -> row.id().equals(area)).findFirst().orElseThrow();
        var staleArea = liveOnly.areas().stream().filter(row -> row.id().equals(area)).findFirst().orElseThrow();

        assertThat(replayArea.crowd().state().name()).isEqualTo("REPLAY");
        assertThat(replayArea.crowd().provenance().sourceState().name()).isEqualTo("REPLAY");
        assertThat(replayArea.crowd().provenance().comparisonEligible()).isFalse();
        assertThat(staleArea.crowd().state().name()).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT source_state FROM crowd_snapshots WHERE id = ?",
                String.class, point)).isEqualTo("LIVE");
    }
}
