package io.nullnull.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import io.nullnull.crowd.application.CrowdProvenanceProjection;
import io.nullnull.crowd.application.SeoulLiveSnapshotStore;
import io.nullnull.crowd.infrastructure.persistence.JdbcReplayManifestReader;
import io.nullnull.crowd.application.ReplayManifestReader.ReplayBatch;
import io.nullnull.crowd.infrastructure.persistence.ReplayManifestImporter;
import io.nullnull.operations.application.DemoCapabilityQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
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
    @DisplayName("BA-092-T8 최신 승인 manifest가 손상되면 과거 replay로 조용히 후퇴하지 않는다")
    void invalidLatestApprovalDoesNotFallBackToOlderReplay() throws Exception {
        UUID snapshot = snapshot();
        UUID older = manifest(OBSERVED.minusSeconds(60), OBSERVED.plusSeconds(60),
                checksum("0:" + snapshot + "\n"));
        entry(older, snapshot, 0);
        UUID newerInvalid = manifest(OBSERVED.minusSeconds(60), OBSERVED.plusSeconds(120));
        entry(newerInvalid, snapshot, 0);

        assertThat(replayLatest(OBSERVED.plusSeconds(600))).isEmpty();
    }

    @Test
    @DisplayName("BA-092-T9 승인한 정규화 관측 목록만 replay manifest로 캡처한다")
    void capturesOnlyTheExplicitNormalizedSnapshot() throws Exception {
        UUID snapshot = snapshot();
        var plan = new ReplayManifestImporter.Plan("approved-" + UUID.randomUUID(),
                OBSERVED.minusSeconds(60), OBSERVED.plusSeconds(60), List.of(snapshot));
        UUID manifest = new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            ReplayManifestImporter importer = new ReplayManifestImporter(jdbc,
                    Clock.fixed(OBSERVED.plusSeconds(120), ZoneOffset.UTC));
            UUID imported = importer.importPlan(plan);
            assertThat(importer.importPlan(plan)).isEqualTo(imported);
            return imported;
        });

        assertThat(replay(manifest, OBSERVED.plusSeconds(600))).isPresent()
                .get().satisfies(batch -> assertThat(batch.readings()).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".replay_manifest_entries WHERE manifest_id = ?", Long.class, manifest)).isEqualTo(1);
    }

    @Test
    @DisplayName("BA-092-T10 현재 승인이 철회된 출처의 replay는 제공하지 않는다")
    void aRevokedSourceCannotKeepServingAnOldReplay() throws Exception {
        UUID snapshot = snapshot();
        UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                checksum("0:" + snapshot + "\n"));
        entry(manifest, snapshot, 0);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            jdbc.update("UPDATE source_registry SET approval_state = 'DISABLED', enabled = false"
                    + " WHERE code = 'SEOUL_CITYDATA'");

            assertThat(new JdbcReplayManifestReader(jdbc, new CrowdProvenanceProjection())
                    .read(manifest, OBSERVED.plusSeconds(600))).isEmpty();
            assertThatThrownBy(() -> new ReplayManifestImporter(jdbc,
                    Clock.fixed(OBSERVED.plusSeconds(600), ZoneOffset.UTC)).importPlan(
                    new ReplayManifestImporter.Plan("revoked-" + UUID.randomUUID(),
                            OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(1), List.of(snapshot))))
                    .isInstanceOf(IllegalArgumentException.class);
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("BA-092-T12 비활성 구역의 관측은 replay로 캡처하지 않는다")
    void aRetiredAreaCannotBeCaptured() {
        UUID snapshot = snapshot();
        retireArea(snapshot);
        var plan = new ReplayManifestImporter.Plan("retired-" + UUID.randomUUID(),
                OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(1), List.of(snapshot));

        assertThatThrownBy(() -> importInIsolatedSchema(plan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BA-092-T12 승인 후 비활성화된 구역의 replay도 제공하지 않는다")
    void aRetiredAreaCannotBeRead() throws Exception {
        UUID snapshot = snapshot();
        UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                checksum("0:" + snapshot + "\n"));
        entry(manifest, snapshot, 0);
        retireArea(snapshot);

        assertThat(replay(manifest, OBSERVED.plusSeconds(600))).isEmpty();
        var readiness = new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            return new DemoCapabilityQuery(false, true, false,
                    new JdbcReplayManifestReader(jdbc, new CrowdProvenanceProjection()),
                    Clock.fixed(OBSERVED.plusSeconds(600), ZoneOffset.UTC)).readiness();
        });
        assertThat(readiness.capabilities().stream()
                .filter(capability -> capability.name().equals("replay")).findFirst().orElseThrow()
                .status().name()).isEqualTo("UNAVAILABLE");
    }

    @Test
    @DisplayName("BA-092-T13 서울 표준 혼잡도와 다른 metric은 캡처하지 않는다")
    void anotherMetricCannotBeCaptured() {
        UUID snapshot = snapshot("OTHER_METRIC", SeoulLiveSnapshotStore.NORMALIZATION_VERSION);
        var plan = new ReplayManifestImporter.Plan("wrong-metric-" + UUID.randomUUID(),
                OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(1), List.of(snapshot));

        assertThatThrownBy(() -> importInIsolatedSchema(plan)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BA-092-T13 잘못된 metric 또는 normalization manifest는 읽지 않는다")
    void anotherMetricOrNormalizationCannotBeRead() throws Exception {
        for (UUID snapshot : List.of(
                snapshot("OTHER_METRIC", SeoulLiveSnapshotStore.NORMALIZATION_VERSION),
                snapshot(SeoulLiveSnapshotStore.METRIC_CODE, "other-normalization"))) {
            UUID manifest = manifest(OBSERVED.minusSeconds(1), OBSERVED.plusSeconds(60),
                    checksum("0:" + snapshot + "\n"));
            entry(manifest, snapshot, 0);
            assertThat(replay(manifest, OBSERVED.plusSeconds(600))).isEmpty();
        }
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
        // Tests share this schema. Keep each fixture newer than earlier approvals regardless of test order.
        Timestamp latestApproval = jdbc.queryForObject("SELECT max(approved_at) FROM " + SCHEMA
                + ".replay_manifests", Timestamp.class);
        Instant approvedAt = to.plusSeconds(60);
        if (latestApproval != null && !approvedAt.isAfter(latestApproval.toInstant())) {
            approvedAt = latestApproval.toInstant().plusSeconds(1);
        }
        jdbc.update("INSERT INTO " + SCHEMA + ".replay_manifests"
                + " (id, name, schema_version, source_code, source_registry_version, checksum,"
                + " source_license_snapshot, captured_from, captured_to, approved_at, created_at)"
                + " VALUES (?, ?, 'replay-manifest-v1', 'SEOUL_CITYDATA', 2, ?, ?, ?, ?, ?, ?)",
                id, "test-" + id, checksum, license,
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(approvedAt),
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

    private Optional<ReplayBatch> replay(UUID manifest, Instant now) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            return new JdbcReplayManifestReader(jdbc, new CrowdProvenanceProjection())
                    .read(manifest, now);
        });
    }

    private Optional<ReplayBatch> replayLatest(Instant now) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            return new JdbcReplayManifestReader(jdbc, new CrowdProvenanceProjection())
                    .latestFor("SEOUL_CITYDATA", now);
        });
    }

    private UUID importInIsolatedSchema(ReplayManifestImporter.Plan plan) {
        return new TransactionTemplate(transactions).execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + SCHEMA);
            return new ReplayManifestImporter(jdbc, Clock.fixed(OBSERVED.plusSeconds(120), ZoneOffset.UTC))
                    .importPlan(plan);
        });
    }

    private void retireArea(UUID snapshot) {
        jdbc.update("UPDATE " + SCHEMA + ".live_areas SET status = 'RETIRED'"
                + " WHERE id = (SELECT live_area_id FROM " + SCHEMA + ".crowd_snapshots WHERE id = ?)",
                snapshot);
    }

    private static String checksum(String canonical) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private UUID snapshot() {
        return snapshot(SeoulLiveSnapshotStore.METRIC_CODE, SeoulLiveSnapshotStore.NORMALIZATION_VERSION);
    }

    private UUID snapshot(String metricCode, String normalizationVersion) {
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
                    + " VALUES (?, 'SEOUL_CITYDATA', 2, ?, 'LIVE', ?, ?, ?, ?, ?)",
                    set, run, Timestamp.from(OBSERVED), Timestamp.from(fetched), Timestamp.from(stale),
                    normalizationVersion, Timestamp.from(fetched));
            jdbc.update("INSERT INTO crowd_snapshots"
                    + " (id, snapshot_set_id, source_code, source_registry_version, live_area_id, source_state,"
                    + " observed_at, fetched_at, stale_at, metric_code, ordinal_level, normalization_version,"
                    + " scope, scope_label, mapping_type, created_at)"
                    + " VALUES (?, ?, 'SEOUL_CITYDATA', 2, ?, 'LIVE', ?, ?, ?,"
                    + " ?, '2', ?,"
                    + " 'LIVE_AREA', '서울 실시간 도시데이터 주요 장소', 'DIRECT', ?)",
                    point, set, area, Timestamp.from(OBSERVED), Timestamp.from(fetched),
                    Timestamp.from(stale), metricCode, normalizationVersion, Timestamp.from(fetched));
        });
        return point;
    }
}
