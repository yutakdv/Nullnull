package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.infrastructure.persistence.JdbcReplayManifestReader;
import io.nullnull.crowd.application.ReplayManifestReader.ReplayBatch;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The replay migration's constraints, exercised on PostgreSQL rather than an in-memory substitute. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BA-092 replay manifest storage")
class ReplayManifestMigrationIT {

    private static final String SCHEMA = "ba092_replay_check";
    private static final Instant OBSERVED = Instant.parse("2026-09-20T05:00:00Z");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeAll
    void migrateIsolatedSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).defaultSchema(SCHEMA)
                .createSchemas(true).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    void removeIsolatedSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("BA-092-T1 승인 시점과 entry checksum 이 맞는 manifest 만 replay 된다")
    void onlyAnApprovedManifestWithItsExactEntryListCanReplay() throws Exception {
        UUID snapshot = snapshot();
        String checksum = checksum("0:" + snapshot + "\n");
        UUID valid = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60), checksum);
        entry(valid, snapshot, 0);
        UUID changed = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60));
        entry(changed, snapshot, 0);
        UUID missing = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60), checksum);
        UUID sparse = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                checksum("1:" + snapshot + "\n"));
        entry(sparse, snapshot, 1);
        UUID wrongLicense = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                checksum, "unreviewed-license");
        entry(wrongLicense, snapshot, 0);

        assertThat(replay(valid, OBSERVED.plusSeconds(600))).isPresent()
                .get().satisfies(batch -> {
                    assertThat(batch.readings()).hasSize(1);
                    assertThat(batch.readings().getFirst().crowd().state().name()).isEqualTo("REPLAY");
                    assertThat(batch.readings().getFirst().crowd().provenance().comparisonReasonCode())
                            .isEqualTo("REPLAY_INPUT");
                });
        assertThat(replay(changed, OBSERVED.plusSeconds(600))).isEmpty();
        assertThat(replay(missing, OBSERVED.plusSeconds(600))).isEmpty();
        assertThat(replay(sparse, OBSERVED.plusSeconds(600))).isEmpty();
        assertThat(replay(wrongLicense, OBSERVED.plusSeconds(600))).isEmpty();
        assertThat(replay(valid, OBSERVED.plusSeconds(61))).isEmpty();
    }

    @Test
    @DisplayName("BA-092-T1 다른 source revision 의 snapshot 은 승인 manifest 에 들어갈 수 없다")
    void anotherSourcesSnapshotCannotEnterTheManifest() throws Exception {
        UUID snapshot = snapshot();
        UUID manifest = UUID.randomUUID();
        String license = jdbc.queryForObject("SELECT (canonical_contract->'license')::text FROM "
                + SCHEMA + ".source_registry_revisions"
                + " WHERE source_code = 'KTO_CONCENTRATION_FORECAST' AND version = 2", String.class);
        jdbc.update("INSERT INTO " + SCHEMA + ".replay_manifests"
                + " (id, name, schema_version, source_code, source_registry_version, checksum,"
                + " source_license_snapshot, captured_from, captured_to, approved_at, created_at)"
                + " VALUES (?, ?, 'replay-manifest-v1', 'KTO_CONCENTRATION_FORECAST', 2, ?, ?, ?, ?, ?, ?)",
                manifest, "cross-source-" + manifest, checksum("0:" + snapshot + "\n"), license,
                Timestamp.from(OBSERVED.minusSeconds(1)), Timestamp.from(OBSERVED.plusSeconds(60)),
                Timestamp.from(OBSERVED.plusSeconds(120)), Timestamp.from(OBSERVED.minusSeconds(1)));
        assertThatThrownBy(() -> entry(manifest, snapshot, 0)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("BA-092-T2 replay 는 원 관측을 현재 live 로 내거나 비교 가능으로 바꾸지 않는다")
    void replayKeepsItsOwnStateAndObservationTime() throws Exception {
        UUID snapshot = snapshot();
        UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                checksum("0:" + snapshot + "\n"));
        entry(manifest, snapshot, 0);

        ReplayBatch batch = replay(manifest, OBSERVED.plusSeconds(600)).orElseThrow();
        var crowd = batch.readings().getFirst().crowd();
        assertThat(batch.replayAt()).isEqualTo(OBSERVED);
        assertThat(crowd.state().name()).isEqualTo("REPLAY");
        assertThat(crowd.provenance().sourceState().name()).isEqualTo("REPLAY");
        assertThat(crowd.provenance().freshness()).isEqualTo("UNKNOWN");
        assertThat(crowd.provenance().observedAt()).isEqualTo(OBSERVED);
        assertThat(crowd.provenance().snapshotSetId()).isNotNull();
        assertThat(crowd.provenance().comparisonEligible()).isFalse();
        assertThat(crowd.provenance().comparisonReasonCode()).isEqualTo("REPLAY_INPUT");
        assertThat(jdbc.queryForObject("SELECT source_state FROM " + SCHEMA
                + ".crowd_snapshots WHERE id = ?", String.class, snapshot)).isEqualTo("LIVE");
    }

    @Test
    @DisplayName("BA-092-T4 capture window 밖의 관측은 manifest entry 가 될 수 없다")
    void entryOutsideCaptureWindowIsRejected() {
        UUID snapshot = snapshot();
        UUID before = manifest(OBSERVED.plusSeconds(1), OBSERVED.plusSeconds(60));
        UUID after = manifest(OBSERVED.minusSeconds(60), OBSERVED.minusSeconds(1));
        assertThatThrownBy(() -> entry(before, snapshot, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> entry(after, snapshot, 0)).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("BA-092-T5 승인 manifest 의 snapshot 은 삭제되지 않는다")
    void referencedSnapshotCannotBeDeleted() {
        UUID snapshot = snapshot();
        UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60));
        entry(manifest, snapshot, 0);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".crowd_snapshots WHERE id = ?", snapshot))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("BA-092-T6 manifest 와 entry 의 승인된 내용은 수정되지 않는다")
    void manifestAndEntriesAreImmutable() {
        UUID snapshot = snapshot();
        UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60));
        entry(manifest, snapshot, 0);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA
                + ".replay_manifests SET captured_from = ? WHERE id = ?",
                Timestamp.from(OBSERVED.minusSeconds(3600)), manifest)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA
                + ".replay_manifest_entries SET sequence = 2 WHERE manifest_id = ?", manifest))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA
                + ".replay_manifest_entries WHERE manifest_id = ?", manifest))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA
                + ".replay_manifests WHERE id = ?", manifest))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("BA-092-T7 미승인 scrub 어휘를 받는 column 은 만들지 않는다")
    void scrubMethodIsNotAnUnreviewedColumn() {
        Long tables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_name = 'replay_manifests'", Long.class, SCHEMA);
        assertThat(tables).isEqualTo(1);
        Long count = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = 'replay_manifests' AND column_name = 'scrub_method'",
                Long.class, SCHEMA);
        assertThat(count).isZero();
    }

    private UUID manifest(Instant from, Instant to) {
        return manifest(from, to, "0".repeat(64));
    }

    private UUID manifest(Instant from, Instant to, String checksum) {
        String license = jdbc.queryForObject("SELECT (canonical_contract->'license')::text"
                + " FROM " + SCHEMA + ".source_registry_revisions"
                + " WHERE source_code = 'SEOUL_CITYDATA' AND version = 2", String.class);
        return manifest(from, to, checksum, license);
    }

    private UUID manifest(Instant from, Instant to, String checksum, String license) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + SCHEMA + ".replay_manifests"
                + " (id, name, schema_version, source_code, source_registry_version, checksum,"
                + " source_license_snapshot, captured_from, captured_to, approved_at, created_at)"
                + " VALUES (?, ?, 'replay-manifest-v1', 'SEOUL_CITYDATA', 2, ?, ?, ?, ?, ?, ?)",
                id, "test-" + id, checksum, license,
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(to.plusSeconds(60)),
                Timestamp.from(from));
        return id;
    }

    private void entry(UUID manifest, UUID snapshot, int sequence) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            jdbc.update("INSERT INTO replay_manifest_entries"
                    + " (manifest_id, crowd_snapshot_id, sequence) VALUES (?, ?, ?)",
                    manifest, snapshot, sequence);
        });
    }

    private Optional<ReplayBatch> replay(UUID manifest, Instant now) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(SCHEMA);
            return new JdbcReplayManifestReader(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)),
                    new CrowdProvenanceProjection()).read(manifest, now);
        }
    }

    private static String checksum(String canonical) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private UUID snapshot() {
        UUID area = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID set = UUID.randomUUID();
        UUID point = UUID.randomUUID();
        Instant fetched = OBSERVED.plusSeconds(5);
        Instant stale = OBSERVED.plusSeconds(300);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            jdbc.update("INSERT INTO live_areas"
                    + " (id, source_code, external_id, name, status, updated_at)"
                    + " VALUES (?, 'SEOUL_CITYDATA', ?, '검증 구역', 'ACTIVE', ?)",
                    area, "test-" + area, Timestamp.from(fetched));
            jdbc.update("INSERT INTO collector_runs"
                    + " (id, source_code, status, trigger_type, records_received, records_accepted,"
                    + " records_rejected, schema_version, started_at, finished_at)"
                    + " VALUES (?, 'SEOUL_CITYDATA', 'COMPLETED', 'SCHEDULED', 1, 1, 0, 'seoul-citydata-v8.5', ?, ?)",
                    run, Timestamp.from(fetched), Timestamp.from(fetched));
            jdbc.update("INSERT INTO snapshot_sets"
                    + " (id, source_code, source_registry_version, collector_run_id, source_state, observed_at,"
                    + " fetched_at, stale_at, normalization_version, created_at)"
                    + " VALUES (?, 'SEOUL_CITYDATA', 2, ?, 'LIVE', ?, ?, ?, 'seoul-live-area-v1', ?)",
                    set, run, Timestamp.from(OBSERVED), Timestamp.from(fetched), Timestamp.from(stale),
                    Timestamp.from(fetched));
            jdbc.update("INSERT INTO crowd_snapshots"
                    + " (id, snapshot_set_id, source_code, source_registry_version, live_area_id, source_state,"
                    + " observed_at, fetched_at, stale_at, metric_code, ordinal_level, normalization_version,"
                    + " scope, scope_label, mapping_type, created_at)"
                    + " VALUES (?, ?, 'SEOUL_CITYDATA', 2, ?, 'LIVE', ?, ?, ?,"
                    + " 'SEOUL_LIVE_AREA_CONGESTION_LEVEL', '2', 'seoul-live-area-v1',"
                    + " 'LIVE_AREA', '서울 실시간 도시데이터 주요 장소', 'DIRECT', ?)",
                    point, set, area, Timestamp.from(OBSERVED), Timestamp.from(fetched),
                    Timestamp.from(stale), Timestamp.from(fetched));
        });
        return point;
    }
}
