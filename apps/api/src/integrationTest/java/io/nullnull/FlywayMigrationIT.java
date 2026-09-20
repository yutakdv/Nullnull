package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-002-T1/T2/T4: migrations apply on an empty PostgreSQL and as an upgrade from the previous
 * schema, every check, unique and foreign key rejects a direct SQL violation, and writes shaped for
 * the previous schema are still accepted by the latest one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 Flyway baseline on real PostgreSQL")
class FlywayMigrationIT {

    /** Dropped and recreated by the upgrade test; never the schema the application itself uses. */
    private static final String UPGRADE_SCHEMA = "ba002_upgrade_check";

    /** The same, for the compatibility test. A second name so the two never share a schema. */
    private static final String COMPAT_SCHEMA = "ba002_compat_check";

    // Every table the previous schema owns, which populateEveryTable must cover; a new one has to be
    // added here too. "Previous" is always the migration before the last one, so a table arrives in
    // this list one migration after it is created: optimization_runs arrived when V025 landed,
    // place_hours_* when V026 did, feed_feedback arrived when V027 did, place_relations when V028
    // did, and itinerary_import_drafts arrived when V029 did, and V029's optimization_proposals and
    // optimization_changes arrived when V030 did, and optimization_decisions arrived when V031 did.
    //
    // This list is therefore complete through V036: every table that exists in the previous schema.
    // It is written that way on purpose: an earlier version said "the next migration that does will
    // find this list already complete", which is a claim about the FUTURE and went stale the moment
    // V031, V032 and V033 landed - none of them creates a table, so the sentence stayed true and
    // stopped being useful, and it would need editing again at V034.
    //
    // V037 created notifications, the first new table since V030, and V044 (BA-090) is now the
    // head - so V037 IS the previous schema and notifications belongs here. V037's own author
    // was right to leave it out: on their tree V037 was the head and this list would have been
    // wrong. V044's author was right too: on their tree the previous migration created nothing.
    // Both were correct alone and the merge was red, which is the global-sum shape this file
    // keeps meeting - it is settled by whoever merges last, not by either slice.
    // V045 (BA-082) came next and brought V044's own tables in - live_areas and
    // seoul_live_area_maps. Then V046 (BA-090, the Seoul registry revision) landed on top, which
    // makes V045 the previous schema and adds ITS table, upload_intents.
    //
    // BOTH BRANCHES PREDICTED THIS AND NEITHER COULD SETTLE IT. The V046 side wrote "a sibling
    // branch holds V045; when both land, its table joins this list too and whoever merges last
    // recalculates, because neither branch can see the other." The merge is that moment, and this
    // list plus the count below is the recalculation. V046 creates no table of its own, so
    // nothing new waits behind upload_intents.
    //
    // V047 (BA-086) is the head now, which makes V046 the previous schema - and V046 creates no
    // table, so this list does NOT move. That is the whole edit on this side: the hand-off only
    // has something to hand over when the migration that just became "previous" created a table.
    // V047 creates none either, so the next migration will find this list unchanged again.
    private static final List<String> PREVIOUS_SCHEMA_TABLES = List.of(
            "analytics_events", "background_jobs", "owners", "idempotency_records",
            "demo_sessions", "demo_session_csrf_tokens", "deletion_requests",
            "deletion_tombstones", "source_registry", "source_registry_revisions",
            "source_quality_incidents", "collector_runs", "api_ingest_logs", "kto_place_snapshots",
            "places", "place_localizations", "place_external_refs", "asset_licenses",
            "media_assets", "place_media_assets", "snapshot_sets", "crowd_snapshots",
            "trips", "trip_interests", "trip_revisions", "trip_items", "trip_constraints",
            "posts", "post_places", "saved_posts", "trip_candidates", "candidate_sources",
            "optimization_runs", "optimization_run_snapshot_sets",
            "place_hours_observations", "place_hours_windows", "feed_feedback",
            "place_relations", "itinerary_import_drafts",
            "optimization_proposals", "optimization_changes",
            "optimization_decisions", "notifications",
            "live_areas", "seoul_live_area_maps", "upload_intents");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    @DisplayName("BA-002-T1 every migration on the classpath is applied to an empty database")
    void baselineMigrationIsAppliedOnPostgres17() {
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).startsWith("PostgreSQL 17.");
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version"))
                .containsExactlyElementsOf(classpathVersions());
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("BA-002-T1 the previous schema upgrades to the latest version with data in it")
    void previousSchemaUpgradesToTheLatestVersion() {
        List<String> versions = classpathVersions();
        assertThat(versions).hasSizeGreaterThanOrEqualTo(2);
        String previous = versions.get(versions.size() - 2);
        List<String> untilPrevious = versions.subList(0, versions.size() - 1);
        jdbc.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
        try {
            MigrateResult toPrevious = flywayFor(UPGRADE_SCHEMA, previous).migrate();
            assertThat(toPrevious.success).isTrue();
            assertThat(toPrevious.migrationsExecuted).isEqualTo(untilPrevious.size());
            assertThat(appliedVersionsIn(UPGRADE_SCHEMA)).isEqualTo(untilPrevious);

            // An empty schema upgrades even when a migration cannot: a NOT NULL column without a
            // default, or a unique index over existing duplicates, only fails on populated tables.
            UUID ownerId = UUID.randomUUID();
            String outstandingKey = populateEveryTable(UPGRADE_SCHEMA, ownerId);
            assertThat(tablesInUpgradeSchema()).containsExactlyInAnyOrderElementsOf(PREVIOUS_SCHEMA_TABLES);
            List<String> columnsBefore = columnsInUpgradeSchema();
            long rowsBefore = totalRowsInUpgradeSchema();
            assertThat(tablesInUpgradeSchema()).allSatisfy(table -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + UPGRADE_SCHEMA + "." + table, Long.class)).isPositive());

            MigrateResult toLatest = flywayFor(UPGRADE_SCHEMA, null).migrate();
            assertThat(toLatest.success).isTrue();
            assertThat(toLatest.migrationsExecuted).isEqualTo(versions.size() - untilPrevious.size());
            assertThat(appliedVersionsIn(UPGRADE_SCHEMA)).isEqualTo(versions);
            assertThat(jdbc.queryForObject("SELECT bool_and(success) FROM " + UPGRADE_SCHEMA
                    + ".flyway_schema_history WHERE version IS NOT NULL", Boolean.class)).isTrue();

            // Every existing row survived, and every row a migration added is declared. This count is
            // deliberately exact rather than "at least", so a migration that quietly seeds data has
            // to say so here - which is how V021 came to be named below. V013 adds none of its own:
            // it is pure DDL, the trips aggregate's tables plus the owners.active_trip_id foreign key
            // V002 deferred until the trips table existed.
            //
            // The number is rows seeded by the migrations THIS upgrade applies - the last one alone,
            // since everything up to the previous version is already inside rowsBefore. So it moves
            // as the last migration moves. V021 seeded three (A-024's source, its first registry
            // revision and the 1st-party asset licence) and they are long inside rowsBefore now.
            // V046 is the last one today ON THIS BRANCH and seeds one row, which is why the number below is 1
            // rather than 0. V044, now the previous schema, seeds nothing: it creates live_areas and
            // seoul_live_area_maps (BA-090) and adds the foreign key crowd_snapshots.live_area_id
            // had been waiting for, and writes no row. V037 seeds nothing either: it creates the
            // notifications table (BA-085) and writes no row into it, because nothing produces a
            // notification yet - and with V044 ahead of it, V037 is now the previous schema, so
            // PREVIOUS_SCHEMA_TABLES above DOES carry notifications. V036
            // seeded nothing either: it swaps three foreign keys on
            // optimization_decisions and optimization_runs, which creates no row
            // and no table and holds
            // for the decision rows populateEveryTable wrote under V035. V035 seeded nothing either: it
            // replaced the run failure_code CHECK with a wider one, which every run row already
            // satisfies. V034 seeded nothing either - it added two nullable columns
            // and a CHECK to optimization_proposals, which the proposal rows satisfy with both columns
            // null - and neither did V033, V032, V031, V030, V029,
            // V028, V027, V026. V025's two rows - the NULLNULL_CURATED_HOURS
            // source and its first registry revision - are long inside rowsBefore now. Hence this
            // line changing again the next time a migration seeds anything, which is the point of
            // the count being exact.
            //
            // V045 (BA-082) seeded three - the USER_UPLOAD source, its first registry revision and
            // the one asset licence that source's assets point at, the same three shapes V021
            // seeded for A-024. Those three are NOT counted here any more: V046 is the last
            // migration now, so V045 runs in the first migrate step and its rows are inside
            // rowsBefore. The count is what the LAST migration alone seeds.
            //
            // V046 seeds ONE row: the source_registry_revisions entry (version 2) that the Seoul
            // promotion writes beside its UPDATE. The UPDATE itself adds nothing. This number
            // moving is how this assertion works - it is what notices a migration that quietly
            // plants data - so it is edited with a reason, never deleted. It moved 0 -> 3 -> 1 in
            // one day because two branches each had a different last migration.
            //
            // V047 (BA-086) is the last migration now, so V046 runs in the first migrate step and
            // that Seoul revision row is inside rowsBefore. V047 seeds NOTHING: it adds four
            // nullable provenance columns to place_localizations and deliberately backfills none
            // of them - deriving a text's source from the place's external reference is the thing
            // that migration exists to refuse - so the count is 0. MEASURED, not predicted: with
            // 1 still here the assertion read "expected: 72L but was: 71L".
            long seededAfterPreviousSchema = 0;
            assertThat(totalRowsInUpgradeSchema()).isEqualTo(rowsBefore + seededAfterPreviousSchema);
            assertThat(columnsInUpgradeSchema()).containsAll(columnsBefore);
            // A row that references the owner created before the upgrade is still accepted.
            assertThatCode(() -> insertRecordInto(UPGRADE_SCHEMA, ownerId))
                    .doesNotThrowAnyException();
            // V004 narrowed the deduplication key from "one row ever" to "one outstanding job". The
            // READY row written before the upgrade still holds its key...
            assertThatThrownBy(() -> insertJobInto(UPGRADE_SCHEMA + ".background_jobs", outstandingKey, "READY"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // ...and a finished job of the same key, which the old constraint refused, is now allowed.
            assertThatCode(() -> insertJobInto(UPGRADE_SCHEMA + ".background_jobs", outstandingKey, "COMPLETED"))
                    .doesNotThrowAnyException();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + UPGRADE_SCHEMA + " CASCADE");
        }
    }

    @Test
    @DisplayName("BA-002-T4 a write shaped for the previous schema is still accepted by the latest one")
    void previousSchemaWritesAreAcceptedByTheLatestSchema() {
        // The upgrade test above covers the reading half of compatibility: data written before the
        // last migration survives it, and no column an older application reads was dropped or
        // retyped. This is the writing half. During a rolling deploy an instance built against the
        // previous schema keeps serving after the migration has run, and every INSERT it issues
        // names the previous schema's columns and nothing else - which is exactly what
        // populateEveryTable is, so it is pointed at a schema taken straight to the latest version.
        //
        // The two halves fail apart. A column that gains NOT NULL with a default and then drops the
        // default passes the upgrade test - the existing rows are backfilled and no column
        // disappears - and fails only here, on the next INSERT that does not name it.
        //
        // Not covered, here or anywhere yet: running an actual previous-release binary against this
        // schema. That needs the deploy pipeline of BA-004; see the card.
        jdbc.execute("DROP SCHEMA IF EXISTS " + COMPAT_SCHEMA + " CASCADE");
        try {
            MigrateResult toLatest = flywayFor(COMPAT_SCHEMA, null).migrate();
            assertThat(toLatest.success).isTrue();
            // Without this the test would still pass against a half-migrated schema, and would then
            // be asserting compatibility with a version nothing deploys.
            assertThat(appliedVersionsIn(COMPAT_SCHEMA)).isEqualTo(classpathVersions());

            assertThatCode(() -> populateEveryTable(COMPAT_SCHEMA, UUID.randomUUID()))
                    .doesNotThrowAnyException();

            // And the write set has to have reached every table it claims to cover, or a
            // populateEveryTable that quietly stopped writing would pass this test by writing
            // nothing. (source_registry and its revisions are seeded by the migrations that own
            // them rather than written here; the rest of the list is this method's own writes.)
            assertThat(PREVIOUS_SCHEMA_TABLES).allSatisfy(table -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + COMPAT_SCHEMA + "." + table, Long.class)).isPositive());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + COMPAT_SCHEMA + " CASCADE");
        }
    }

    @Test
    @DisplayName("BA-002-T2 background_jobs rejects a duplicate deduplication key")
    void duplicateDeduplicationKeyIsRejected() {
        String key = "it-dedup-" + UUID.randomUUID();
        insertJob(key, "READY");
        assertThatThrownBy(() -> insertJob(key, "READY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BA-005 a finished job does not hold its deduplication key")
    void aFinishedJobDoesNotHoldItsDeduplicationKey() {
        String key = "it-dedup-finished-" + UUID.randomUUID();
        insertJob(key, "COMPLETED");

        // The measured product failure: a collector with a natural key ran once and then silently
        // never again, because its second enqueue collided with the COMPLETED row.
        assertThatCode(() -> insertJob(key, "READY")).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertJob(key, "RUNNING"))
                .as("only one job may be outstanding for a key")
                .isInstanceOf(DataIntegrityViolationException.class);
        // History does not collide with history either: several finished runs share the same key.
        assertThatCode(() -> insertJob(key, "FAILED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-002-T2 background_jobs rejects an unknown status and a half lease")
    void unknownStatusAndHalfLeaseAreRejected() {
        assertThatThrownBy(() -> insertJob("it-status-" + UUID.randomUUID(), "SOMETHING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO background_jobs (id, type, deduplication_key, status, max_attempts, next_attempt_at, locked_by, created_at)"
                        + " VALUES (?, 'OPTIMIZATION', ?, 'RUNNING', 3, ?, 'worker-a', ?)",
                UUID.randomUUID(), "it-lease-" + UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BA-002-T2 owners rejects an unknown kind, out of range locale or timezone and an anonymous account")
    void ownerChecksRejectInvalidRows() {
        rejects(() -> insertOwner(UUID.randomUUID(), "GUEST", null, "ko-KR", "Asia/Seoul"));
        rejects(() -> insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "k", "Asia/Seoul"));
        rejects(() -> insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "k".repeat(36), "Asia/Seoul"));
        rejects(() -> insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "ko-KR", ""));
        rejects(() -> insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "ko-KR", "z".repeat(101)));
        rejects(() -> insertOwner(UUID.randomUUID(), "ANONYMOUS", UUID.randomUUID(), "ko-KR", "Asia/Seoul"));
    }

    @Test
    @DisplayName("BA-002-T2 owners.account_id is unique only when it is not null")
    void accountIdIsUniqueOnlyWhenPresent() {
        UUID accountId = UUID.randomUUID();
        insertOwner(UUID.randomUUID(), "ACCOUNT", accountId, "ko-KR", "Asia/Seoul");
        rejects(() -> insertOwner(UUID.randomUUID(), "ACCOUNT", accountId, "ko-KR", "Asia/Seoul"));
        assertThatCode(() -> {
            insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "ko-KR", "Asia/Seoul");
            insertOwner(UUID.randomUUID(), "ANONYMOUS", null, "ko-KR", "Asia/Seoul");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BA-002-T2 idempotency_records enforces its owner key, scope key and column checks")
    void idempotencyRecordConstraintsRejectInvalidRows() {
        UUID ownerId = UUID.randomUUID();
        insertOwner(ownerId, "ANONYMOUS", null, "ko-KR", "Asia/Seoul");
        String route = "POST /trips/{tripId}/candidates";
        String key = "it-key-" + UUID.randomUUID();
        String hash = "a".repeat(64);
        insertRecord(ownerId, route, key, hash, null, null, 24);

        // foreign key: the owner must exist
        rejects(() -> insertRecord(UUID.randomUUID(), route, "it-key-" + UUID.randomUUID(), hash, null, null, 24));
        // unique (owner_id, route_key, idempotency_key)
        rejects(() -> insertRecord(ownerId, route, key, hash, null, null, 24));
        // request_hash must be 64 lowercase hex characters
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), "A".repeat(64), null, null, 24));
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), "a".repeat(63), null, null, 24));
        // idempotency_key length matches the header schema
        rejects(() -> insertRecord(ownerId, route, "short-key-12345", hash, null, null, 24));
        // response status and body are stored together, in a valid status range
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, 201, null, 24));
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, null, "{}", 24));
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, 99, "{}", 24));
        // expires_at must be after created_at
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, null, null, 0));
        // The stored projection is bounded like every other variable-length column here. The bound is
        // the column one, deliberately looser than the application bound so that an oversized
        // projection meets the guard's named error and never this constraint (V003 derives the gap).
        rejects(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, 201,
                jsonOfSize(IdempotencyRecord.RESPONSE_BODY_COLUMN_MAX_BYTES + 1), 24));
        assertThatCode(() -> insertRecord(ownerId, route, "it-key-" + UUID.randomUUID(), hash, 201,
                jsonOfSize(IdempotencyRecord.RESPONSE_BODY_COLUMN_MAX_BYTES), 24))
                .doesNotThrowAnyException();
        // the same key on another route is a different reservation
        assertThatCode(() -> insertRecord(ownerId, "DELETE /session", key, hash, null, null, 24))
                .doesNotThrowAnyException();
    }

    /** A JSON object whose jsonb text is exactly {@code bytes} bytes long. */
    private static String jsonOfSize(int bytes) {
        int padding = bytes - "{\"a\": \"\"}".length();
        return "{\"a\": \"" + "x".repeat(padding) + "\"}";
    }

    private static void rejects(ThrowingCallable insert) {
        assertThatThrownBy(insert).isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> classpathVersions() {
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(MigrationVersion::toString)
                .toList();
    }

    private List<String> appliedVersionsIn(String schema) {
        return jdbc.queryForList("SELECT version FROM " + schema + ".flyway_schema_history"
                + " WHERE version IS NOT NULL AND success ORDER BY installed_rank", String.class);
    }

    private Flyway flywayFor(String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    /**
     * One representative row in every table the previous schema has, written the way an application
     * built against that schema writes it: no column the previous schema does not have is named.
     *
     * @return the deduplication key of the outstanding job row, which the upgrade must keep exclusive
     */
    private String populateEveryTable(String schema, UUID ownerId) {
        String key = "upgrade-" + UUID.randomUUID();
        insertJobInto(schema + ".background_jobs", key, "READY");
        jdbc.update("INSERT INTO " + schema + ".owners"
                        + " (id, kind, locale, timezone, created_at) VALUES (?, 'ANONYMOUS', ?, ?, ?)",
                ownerId, "ko-KR", "Asia/Seoul", OffsetDateTime.now());
        insertRecordInto(schema, ownerId);
        UUID sessionId = UUID.randomUUID();
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO " + schema + ".demo_sessions"
                        + " (id, owner_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                sessionId, ownerId, hash, now.plusDays(30), now);
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO " + schema + ".demo_session_csrf_tokens"
                        + " (id, demo_session_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, hash, now.plusHours(2), now);
        UUID deletionRequestId = UUID.randomUUID();
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO " + schema + ".deletion_requests"
                        + " (id, owner_id, status_token_hash, status, status_token_expires_at,"
                        + " requested_at, updated_at) VALUES (?, ?, ?, 'ACCEPTED', ?, ?, ?)",
                deletionRequestId, ownerId, hash, now.plusDays(7), now, now);
        jdbc.update("INSERT INTO " + schema + ".deletion_tombstones"
                        + " (id, deletion_request_id, owner_id, delete_before, retain_until, scope_hash, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), deletionRequestId, ownerId, now, now.plusDays(21), "c".repeat(64), now);
        // V007 source registry tables are already seeded with reviewed rows. Add rows to the three
        // operational tables that are otherwise empty, so the C2 migrations are tested against populated C1 data too.
        UUID collectorRunId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".source_quality_incidents"
                        + " (id, source_code, incident_code, affected_from, affected_to, scope, disposition, reviewed_at)"
                        + " VALUES (?, 'KTO_KOR_SERVICE_2', ?, ?, ?, 'PLACE', 'RESOLVED', ?)",
                UUID.randomUUID(), "upgrade-incident-" + UUID.randomUUID(), now, now.plusMinutes(1), now);
        jdbc.update("INSERT INTO " + schema + ".collector_runs"
                        + " (id, source_code, status, trigger_type, records_received, records_accepted,"
                        + " records_rejected, schema_version, started_at, finished_at)"
                        + " VALUES (?, 'KTO_KOR_SERVICE_2', 'COMPLETED', 'MANUAL', 1, 1, 0, 'upgrade-v1', ?, ?)",
                collectorRunId, now.minusSeconds(1), now);
        jdbc.update("INSERT INTO " + schema + ".api_ingest_logs"
                        + " (id, collector_run_id, endpoint_key, outcome, http_status, duration_ms, response_count,"
                        + " release_version, request_id, payload_hash, validation_result, created_at)"
                        + " VALUES (?, ?, 'UPGRADE_TEST', 'OK', 200, 1, 1, 'upgrade-release', ?, ?, 'OK', ?)",
                UUID.randomUUID(), collectorRunId, "upgrade-request-" + UUID.randomUUID(), "d".repeat(64), now);
        jdbc.update("INSERT INTO " + schema + ".kto_place_snapshots"
                        + " (id, source_code, source_registry_version, collector_run_id, content_id, content_type_id,"
                        + " title, payload_hash, fetched_at, stale_at, created_at)"
                        + " VALUES (?, 'KTO_KOR_SERVICE_2', 2, ?, '126508', '12', 'upgrade place', ?, ?, ?, ?)",
                UUID.randomUUID(), collectorRunId, "e".repeat(64), now, now.plusDays(7), now);
        // V010's canonical catalog belongs to the previous schema from V011 on, so the C4 crowd
        // migration has to run against populated catalog rows too. V010's triggers look their
        // parent rows up through search_path, so these inserts only see the upgrade schema when it
        // is on the path; SET LOCAL confines that to this statement's own implicit transaction and
        // therefore never leaks onto the pooled connection.
        jdbc.execute("""
                DO $upgrade$
                DECLARE
                    v_place uuid := gen_random_uuid();
                    v_related_place uuid := gen_random_uuid();
                    v_proposal uuid := gen_random_uuid();
                    v_license uuid := gen_random_uuid();
                    v_asset uuid := gen_random_uuid();
                    v_set uuid := gen_random_uuid();
                    v_forecast_run uuid := gen_random_uuid();
                    v_trip uuid := gen_random_uuid();
                    v_item uuid := gen_random_uuid();
                    v_post uuid := gen_random_uuid();
                    v_candidate uuid := gen_random_uuid();
                    v_run uuid := gen_random_uuid();
                    v_observation uuid := gen_random_uuid();
                    v_at timestamptz := now();
                BEGIN
                    SET LOCAL search_path TO %s;
                    INSERT INTO collector_runs
                        (id, source_code, status, trigger_type, records_received, records_accepted,
                         records_rejected, schema_version, started_at, finished_at)
                    VALUES (v_forecast_run, 'KTO_CONCENTRATION_FORECAST', 'COMPLETED', 'MANUAL', 1, 1, 0,
                            'upgrade-forecast-v1', v_at, v_at);
                    INSERT INTO places (id, canonical_name, category_code, latitude, longitude,
                                        region_code, status, created_at, updated_at)
                    VALUES (v_place, 'upgrade place', 'A01', 37.579617, 126.977041, 'KR-11',
                            'ACTIVE', v_at, v_at);
                    INSERT INTO place_localizations (id, place_id, locale, name, address, updated_at)
                    VALUES (gen_random_uuid(), v_place, 'ko-KR', 'upgrade place', 'upgrade address', v_at);
                    INSERT INTO place_external_refs (id, place_id, source_code, source_registry_version,
                                                     external_id, external_type, verified_at)
                    VALUES (gen_random_uuid(), v_place, 'KTO_KOR_SERVICE_2', 2,
                            'upgrade-' || gen_random_uuid()::text, 'CONTENT_ID', v_at);
                    INSERT INTO asset_licenses (id, source_code, source_registry_version,
                                                external_license_code, license_name, license_url,
                                                attribution_template, redistribution_allowed,
                                                derivative_allowed, reviewed_at)
                    VALUES (v_license, 'KTO_KOR_SERVICE_2', 2, 'upgrade-' || gen_random_uuid()::text,
                            'upgrade license', 'https://example.test/license', 'upgrade attribution',
                            true, false, v_at);
                    INSERT INTO media_assets (id, asset_license_id, source_external_id, origin_url,
                                              served_url, checksum, media_type, alt_text, license_checked_at)
                    VALUES (v_asset, v_license, 'upgrade-' || gen_random_uuid()::text,
                            'https://example.test/origin.jpg', 'https://example.test/served.jpg',
                            repeat('f', 64), 'IMAGE', 'upgrade alt text', v_at);
                    INSERT INTO place_media_assets (place_id, media_asset_id, position)
                    VALUES (v_place, v_asset, 0);
                    -- V011's immutable crowd tables are part of the previous schema from V012 on. Their
                    -- insert trigger also resolves the parent set through search_path, so they belong in
                    -- this same block.
                    INSERT INTO snapshot_sets
                        (id, source_code, source_registry_version, collector_run_id, source_state,
                         forecast_issue_id, comparison_group_id, observed_at, fetched_at, stale_at,
                         normalization_version, created_at)
                    VALUES (v_set, 'KTO_CONCENTRATION_FORECAST', 2, v_forecast_run, 'FORECAST',
                            'upgrade-issue', 'upgrade-issue', NULL, v_at, v_at + interval '1 day',
                            'upgrade-norm-v1', v_at);
                    INSERT INTO crowd_snapshots
                        (id, snapshot_set_id, source_code, source_registry_version, place_id, source_state,
                         observed_at, target_at, fetched_at, stale_at, metric_code, value, unit, ordinal_level,
                         confidence, quality_flags, forecast_issue_id, comparison_group_id,
                         normalization_version, observed_at_skew_seconds, scope, scope_label, mapping_type,
                         fallback_used, created_at)
                    VALUES (gen_random_uuid(), v_set, 'KTO_CONCENTRATION_FORECAST', 2, v_place, 'FORECAST',
                            NULL, v_at + interval '1 day', v_at, v_at + interval '1 day',
                            'KTO_RELATIVE_CONCENTRATION_INDEX', 42.5, 'relative-index', NULL, NULL,
                            '[]'::jsonb, 'upgrade-issue', 'upgrade-issue', 'upgrade-norm-v1', NULL,
                            'PLACE', 'upgrade place', 'DIRECT', false, v_at);
                    -- V013's trip aggregate. The owner is created outside this block, so it is
                    -- looked up rather than generated: trips.owner_id has a foreign key and the
                    -- same-owner trigger on owners.active_trip_id reads it back.
                    INSERT INTO trips (id, owner_id, title, start_date, end_date, timezone,
                                       planning_level, status, version, created_at, updated_at)
                    VALUES (v_trip, (SELECT id FROM owners LIMIT 1), '업그레이드 여행',
                            v_at::date, (v_at + interval '3 days')::date, 'Asia/Seoul',
                            'NOTHING', 'DRAFT', 1, v_at, v_at);
                    INSERT INTO trip_interests (trip_id, interest_code, weight, created_at)
                    VALUES (v_trip, 'upgrade-interest', 3, v_at);
                    INSERT INTO trip_revisions (id, trip_id, version, snapshot_schema_version,
                                                snapshot_hash, aggregate_snapshot, created_at)
                    VALUES (gen_random_uuid(), v_trip, 1, 'trip-aggregate-v1', repeat('f', 64),
                            '{}'::jsonb, v_at);
                    -- V014's scheduled half. The date must lie inside the trip range above, which
                    -- a trigger enforces, so it reuses v_at rather than a fixed day.
                    INSERT INTO trip_items (id, trip_id, place_id, trip_date, position, start_time,
                                            created_at, updated_at)
                    VALUES (v_item, v_trip, v_place, v_at::date, 0, '09:30:00', v_at, v_at);
                    INSERT INTO trip_constraints (id, trip_id, trip_item_id, type, source,
                                                  created_at, updated_at)
                    VALUES (gen_random_uuid(), v_trip, v_item, 'MUST_VISIT', 'USER', v_at, v_at);
                    -- V015's curated feed.
                    INSERT INTO posts (id, status, title, body, cover_url, cover_asset_id,
                                       published_at, created_at, updated_at)
                    VALUES (v_post, 'PUBLISHED', '업그레이드 글', '본문',
                            'https://example.test/cover.jpg', v_asset, v_at, v_at, v_at);
                    INSERT INTO post_places (post_id, place_id, position, mention_type)
                    VALUES (v_post, v_place, 0, 'PRIMARY');
                    INSERT INTO saved_posts (owner_id, post_id, created_at)
                    VALUES ((SELECT id FROM owners LIMIT 1), v_post, v_at);
                    -- V016's candidates. ACTIVE rather than SCHEDULED: the shape CHECK requires a
                    -- SCHEDULED row to point at an item, and this is about populating the table,
                    -- not about the transition.
                    INSERT INTO trip_candidates (id, trip_id, place_id, status, created_at, updated_at)
                    VALUES (v_candidate, v_trip, v_place, 'ACTIVE', v_at, v_at);
                    INSERT INTO candidate_sources (id, candidate_id, source_type, post_id, created_at)
                    VALUES (gen_random_uuid(), v_candidate, 'POST', v_post, v_at);
                    -- V017's analytics. Nothing joins to it, but the upgrade still has to run with a
                    -- row present: V018 alters a CHECK, and a constraint change is exactly the kind
                    -- that only fails on populated tables.
                    INSERT INTO analytics_events (event_id, owner_id, session_id, name, occurred_at,
                                                  received_at, route, locale, timezone, app_version,
                                                  properties)
                    -- A real event name and a real route template from
                    -- docs/contracts/events.schema.json, so this row does not quietly record a
                    -- vocabulary that does not exist. properties stays empty: the canonical schema is
                    -- enforced by the ingest service, which a migration test deliberately bypasses.
                    VALUES (gen_random_uuid(), (SELECT id FROM owners LIMIT 1), NULL, 'trip_created',
                            v_at, v_at, '/trip/:tripId', 'ko-KR', 'Asia/Seoul', '0.0.0-test',
                            '{}'::jsonb);
                    -- V024's optimization run, which V025 turns into part of the previous schema.
                    -- QUEUED because that is the state a row sits in before a worker touches it, and
                    -- so the one a later schema change is most likely to meet. The scope/status CHECK
                    -- pair fixes the rest of the row: ITEM requires a target item and no target date,
                    -- QUEUED requires started_at and completed_at to stay null.
                    INSERT INTO optimization_runs (id, trip_id, requested_by_owner_id, scope,
                                                   target_item_id, include_candidates, status,
                                                   input_trip_version, queued_at)
                    VALUES (v_run, v_trip, (SELECT id FROM owners LIMIT 1), 'ITEM', v_item, false,
                            'QUEUED', 1, v_at);
                    INSERT INTO optimization_run_snapshot_sets (run_id, snapshot_set_id, purpose,
                                                                sequence)
                    VALUES (v_run, v_set, 'BEFORE', 0);
                    -- V025's curated opening hours, seeded now that V026 has made V025 the previous
                    -- schema. OBSERVED with a window under it, because that is the pair the trigger
                    -- V025 adds exists to police: a window with no observed evidence is refused, so
                    -- an upgrade meeting only the unconstrained shape would not meet the rule.
                    INSERT INTO place_hours_observations (id, place_id, source_code,
                                                          source_registry_version, outcome,
                                                          observed_at, evidence_url, stale_at,
                                                          created_at)
                    VALUES (v_observation, v_place, 'KTO_KOR_SERVICE_2', 2, 'OBSERVED', v_at,
                            'https://example.test/hours', v_at + interval '30 days', v_at);
                    INSERT INTO place_hours_windows (id, observation_id, effective_on, state,
                                                     opens_at, closes_at)
                    VALUES (gen_random_uuid(), v_observation, v_at::date, 'OPEN', '09:00', '18:00');
                    -- V026's feed interaction, which V027 turns into part of the previous schema.
                    -- occurred_minute is written rather than derived because date_trunc on a
                    -- timestamptz is STABLE, not IMMUTABLE; whole minutes since the epoch is the
                    -- same bucket the application computes.
                    INSERT INTO feed_feedback (id, owner_id, post_id, action, occurred_at,
                                               received_at, occurred_minute)
                    VALUES (gen_random_uuid(), (SELECT id FROM owners LIMIT 1), v_post, 'IMPRESSION',
                            v_at, v_at, floor(extract(epoch FROM v_at) / 60)::bigint);
                    -- V027's relation, which V028 turns into part of the previous schema. It needs a
                    -- SECOND active place: place_relations_self_check refuses a loop and
                    -- place_relations_active_places_guard refuses an end that is not an active
                    -- canonical row. The values are the only combination a rule-derived relation can
                    -- take - derivation INTERNAL_RULE and source NULLNULL_CATALOG_RULE imply each
                    -- other, and EXACT would demand PROVIDER_DIRECT, which no approved provider can
                    -- produce today. A representative row that could not exist in production would
                    -- teach the next reader that it could.
                    INSERT INTO places (id, canonical_name, category_code, latitude, longitude,
                                        region_code, status, created_at, updated_at)
                    VALUES (v_related_place, 'upgrade related place', 'A01', 37.579617, 126.977041,
                            'KR-11', 'ACTIVE', v_at, v_at);
                    INSERT INTO place_relations (id, source_place_id, target_place_id, relation_type,
                                                 derivation, mapping_certainty, relation_reason,
                                                 source_code, source_registry_version, effective_at,
                                                 expires_at, created_at)
                    VALUES (gen_random_uuid(), v_place, v_related_place, 'SIMILAR', 'INTERNAL_RULE',
                            'UNCERTAIN', 'upgrade rule reason', 'NULLNULL_CATALOG_RULE', 1,
                            v_at, NULL, v_at);
                    -- V028's draft, which V029 turns into part of the previous schema. NEEDS_REVIEW
                    -- with no trip is the state the confirmed_check demands of anything that is not
                    -- CONFIRMED, and it is also the state a draft spends its whole life in unless
                    -- somebody confirms it.
                    INSERT INTO itinerary_import_drafts (id, owner_id, status, version,
                                                         structured_draft, unresolved_tokens,
                                                         confirmed_trip_id, confirmed_at,
                                                         expires_at, created_at)
                    VALUES (gen_random_uuid(), (SELECT id FROM owners LIMIT 1), 'NEEDS_REVIEW', 1,
                            '{"items":[]}'::jsonb, '[]'::jsonb, NULL, NULL,
                            v_at + interval '24 hours', v_at);
                    -- V029's proposal and its change, which V030 turns into part of the previous
                    -- schema. Eligible with no reason code is the only shape that may carry a delta;
                    -- a MOVE carries both halves of the diff because the CHECKs refuse half of one.
                    INSERT INTO optimization_proposals (id, run_id, rank, summary, comparison_eligible,
                                                        comparison_reason_code, crowd_delta,
                                                        travel_minutes_delta, validation_summary,
                                                        created_at)
                    VALUES (v_proposal, v_run, 1, 'upgrade proposal', true, NULL, -12.5000, NULL,
                            '{}'::jsonb, v_at);
                    INSERT INTO optimization_changes (id, proposal_id, trip_item_id, operation,
                                                      before_value, after_value, sequence)
                    VALUES (gen_random_uuid(), v_proposal, v_item, 'MOVE',
                            '{"position":0}'::jsonb, '{"position":1}'::jsonb, 0);
                    -- V030's decision, which V031 turns into part of the previous schema. KEEP is the
                    -- only one of the three shapes that needs neither a revision pair nor a revert
                    -- window, so it is the representative row that drags nothing else in with it.
                    INSERT INTO optimization_decisions (id, run_id, proposal_id, owner_id, decision,
                                                        expected_trip_version, resulting_trip_version,
                                                        before_revision_id, after_revision_id,
                                                        reverted_decision_id, revert_until, decided_at)
                    VALUES (gen_random_uuid(), v_run, v_proposal, (SELECT id FROM owners LIMIT 1),
                            'KEEP', 1, NULL, NULL, NULL, NULL, NULL, v_at);
                END
                $upgrade$;
                """.formatted(schema));
        // V037's notifications, which V044 turns into part of the previous schema. Nothing produces
        // a notification yet, so this is the table's only writer in the sweep. The row satisfies
        // every constraint the migration declares: a type from the CHECK, an absolute internal path
        // whose second character is not a slash (V037's open-redirect guard refuses "//host/x"),
        // read_at null, and an expiry after creation.
        jdbc.update("INSERT INTO " + schema + ".notifications"
                        + " (id, owner_id, type, title, body, deep_link, created_at, read_at, expires_at)"
                        + " VALUES (?, ?, 'OPTIMIZATION_READY', ?, ?, '/notifications', ?, NULL, ?)",
                UUID.randomUUID(), ownerId, "upgrade", "upgrade", now, now.plusDays(90));
        // V044's live_areas and seoul_live_area_maps, which V045 turns into part of the previous
        // schema. Nothing produces either row yet, so this is their only writer in the sweep.
        // boundary_geojson stays null on purpose: the provider publishes area names without
        // polygons and V044 says a made-up boundary would be a fabricated observation.
        UUID liveAreaId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + schema + ".live_areas"
                        + " (id, source_code, external_id, name, boundary_geojson, status, updated_at)"
                        + " VALUES (?, 'SEOUL_CITYDATA', ?, ?, NULL, 'ACTIVE', ?)",
                liveAreaId, "upgrade-" + liveAreaId, "upgrade", now);
        // AREA pairs with fallback_used false: V044's CHECK makes the two columns dependent, and
        // confidence must sit inside [0, 1].
        jdbc.update("INSERT INTO " + schema + ".seoul_live_area_maps"
                        + " (id, place_id, live_area_id, mapping_type, confidence, fallback_used,"
                        + " verified_at)"
                        + " VALUES (?, (SELECT id FROM " + schema + ".places LIMIT 1), ?,"
                        + " 'AREA', 0.5000, false, ?)",
                UUID.randomUUID(), liveAreaId, now);
        // V045's upload_intents, which V046 turns into part of the previous schema - the same
        // hand-off V045's author performed for V044's two tables just above. Nothing writes an
        // intent in this sweep otherwise. PENDING with consumed_at null is the one pairing
        // upload_intents_consumed_shape_check accepts, the checksum is 64 lowercase hex because
        // the CHECK is a regex, and expires_at sits after created_at.
        jdbc.update("INSERT INTO " + schema + ".upload_intents"
                        + " (id, owner_id, status, content_type, content_length, checksum_sha256,"
                        + " quarantine_key, created_at, expires_at, consumed_at)"
                        + " VALUES (?, ?, 'PENDING', 'image/jpeg', 1024, ?, ?, ?, ?, NULL)",
                UUID.randomUUID(), ownerId, "c".repeat(64), "upgrade-" + UUID.randomUUID(),
                now, now.plusHours(1));
        return key;
    }

    private void insertRecordInto(String schema, UUID ownerId) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        jdbc.update("INSERT INTO " + schema + ".idempotency_records (id, owner_id, route_key,"
                        + " idempotency_key, request_hash, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), ownerId, "POST /trips/{tripId}/candidates",
                "upgrade-key-" + UUID.randomUUID(), "b".repeat(64), createdAt,
                createdAt.plusHours(24));
    }

    /** Application tables only: flyway_schema_history is Flyway's bookkeeping, not app data. */
    private List<String> tablesInUpgradeSchema() {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class, UPGRADE_SCHEMA);
    }

    private List<String> columnsInUpgradeSchema() {
        return jdbc.queryForList("SELECT table_name || '.' || column_name || ':' || data_type"
                + " || ':' || is_nullable FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name <> 'flyway_schema_history'"
                + " ORDER BY table_name, column_name", String.class, UPGRADE_SCHEMA);
    }

    private long totalRowsInUpgradeSchema() {
        return tablesInUpgradeSchema().stream()
                .mapToLong(table -> jdbc.queryForObject(
                        "SELECT count(*) FROM " + UPGRADE_SCHEMA + "." + table, Long.class))
                .sum();
    }

    private void insertJob(String key, String status) {
        insertJobInto("background_jobs", key, status);
    }

    private void insertJobInto(String table, String key, String status) {
        jdbc.update("INSERT INTO " + table + " (id, type, deduplication_key, status,"
                        + " max_attempts, next_attempt_at, created_at)"
                        + " VALUES (?, 'OPTIMIZATION', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), key, status, 3, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private void insertOwner(UUID id, String kind, UUID accountId, String locale, String timezone) {
        jdbc.update("INSERT INTO owners (id, kind, account_id, locale, timezone, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", id, kind, accountId, locale, timezone, OffsetDateTime.now());
    }

    private void insertRecord(UUID ownerId, String routeKey, String idempotencyKey, String requestHash,
            Integer responseStatus, String responseBody, int ttlHours) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        jdbc.update("INSERT INTO idempotency_records (id, owner_id, route_key, idempotency_key, request_hash,"
                        + " response_status, response_body, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                UUID.randomUUID(), ownerId, routeKey, idempotencyKey, requestHash, responseStatus,
                responseBody, createdAt, createdAt.plusHours(ttlHours));
    }
}
