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
 * BA-002-T1/T2: migrations apply on an empty PostgreSQL and as an upgrade from the previous schema,
 * and every check, unique and foreign key rejects a direct SQL violation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 Flyway baseline on real PostgreSQL")
class FlywayMigrationIT {

    /** Dropped and recreated by the upgrade test; never the schema the application itself uses. */
    private static final String UPGRADE_SCHEMA = "ba002_upgrade_check";

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
            MigrateResult toPrevious = upgradeSchemaFlyway(previous).migrate();
            assertThat(toPrevious.success).isTrue();
            assertThat(toPrevious.migrationsExecuted).isEqualTo(untilPrevious.size());
            assertThat(appliedVersionsInUpgradeSchema()).isEqualTo(untilPrevious);

            // An empty schema upgrades even when a migration cannot: a NOT NULL column without a
            // default, or a unique index over existing duplicates, only fails on populated tables.
            UUID ownerId = UUID.randomUUID();
            String outstandingKey = populateEveryTable(ownerId);
            List<String> columnsBefore = columnsInUpgradeSchema();
            long rowsBefore = totalRowsInUpgradeSchema();
            assertThat(tablesInUpgradeSchema()).allSatisfy(table -> assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM " + UPGRADE_SCHEMA + "." + table, Long.class)).isPositive());

            MigrateResult toLatest = upgradeSchemaFlyway(null).migrate();
            assertThat(toLatest.success).isTrue();
            assertThat(toLatest.migrationsExecuted).isEqualTo(versions.size() - untilPrevious.size());
            assertThat(appliedVersionsInUpgradeSchema()).isEqualTo(versions);
            assertThat(jdbc.queryForObject("SELECT bool_and(success) FROM " + UPGRADE_SCHEMA
                    + ".flyway_schema_history WHERE version IS NOT NULL", Boolean.class)).isTrue();

            // Every existing row survived. V013 adds no rows of its own: it is pure DDL - the trips
            // aggregate's tables, plus the owners.active_trip_id foreign key V002 deferred until the
            // trips table existed. This count is deliberately exact rather than "at least", so a
            // migration that quietly seeds data has to say so here.
            assertThat(totalRowsInUpgradeSchema()).isEqualTo(rowsBefore);
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

    private List<String> appliedVersionsInUpgradeSchema() {
        return jdbc.queryForList("SELECT version FROM " + UPGRADE_SCHEMA + ".flyway_schema_history"
                + " WHERE version IS NOT NULL AND success ORDER BY installed_rank", String.class);
    }

    private Flyway upgradeSchemaFlyway(String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(UPGRADE_SCHEMA)
                .defaultSchema(UPGRADE_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    /**
     * One representative row in every table the previous schema has, so the upgrade runs on data.
     *
     * @return the deduplication key of the outstanding job row, which the upgrade must keep exclusive
     */
    private String populateEveryTable(UUID ownerId) {
        String key = "upgrade-" + UUID.randomUUID();
        insertJobInto(UPGRADE_SCHEMA + ".background_jobs", key, "READY");
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".owners"
                        + " (id, kind, locale, timezone, created_at) VALUES (?, 'ANONYMOUS', ?, ?, ?)",
                ownerId, "ko-KR", "Asia/Seoul", OffsetDateTime.now());
        insertRecordInto(UPGRADE_SCHEMA, ownerId);
        UUID sessionId = UUID.randomUUID();
        byte[] hash = new byte[32];
        new java.security.SecureRandom().nextBytes(hash);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".demo_sessions"
                        + " (id, owner_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                sessionId, ownerId, hash, now.plusDays(30), now);
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".demo_session_csrf_tokens"
                        + " (id, demo_session_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, hash, now.plusHours(2), now);
        UUID deletionRequestId = UUID.randomUUID();
        new java.security.SecureRandom().nextBytes(hash);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".deletion_requests"
                        + " (id, owner_id, status_token_hash, status, status_token_expires_at,"
                        + " requested_at, updated_at) VALUES (?, ?, ?, 'ACCEPTED', ?, ?, ?)",
                deletionRequestId, ownerId, hash, now.plusDays(7), now, now);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".deletion_tombstones"
                        + " (id, deletion_request_id, owner_id, delete_before, retain_until, scope_hash, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), deletionRequestId, ownerId, now, now.plusDays(21), "c".repeat(64), now);
        // V007 source registry tables are already seeded with reviewed rows. Add rows to the three
        // operational tables that are otherwise empty, so the C2 migrations are tested against populated C1 data too.
        UUID collectorRunId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".source_quality_incidents"
                        + " (id, source_code, incident_code, affected_from, affected_to, scope, disposition, reviewed_at)"
                        + " VALUES (?, 'KTO_KOR_SERVICE_2', ?, ?, ?, 'PLACE', 'RESOLVED', ?)",
                UUID.randomUUID(), "upgrade-incident-" + UUID.randomUUID(), now, now.plusMinutes(1), now);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".collector_runs"
                        + " (id, source_code, status, trigger_type, records_received, records_accepted,"
                        + " records_rejected, schema_version, started_at, finished_at)"
                        + " VALUES (?, 'KTO_KOR_SERVICE_2', 'COMPLETED', 'MANUAL', 1, 1, 0, 'upgrade-v1', ?, ?)",
                collectorRunId, now.minusSeconds(1), now);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".api_ingest_logs"
                        + " (id, collector_run_id, endpoint_key, outcome, http_status, duration_ms, response_count,"
                        + " release_version, request_id, payload_hash, validation_result, created_at)"
                        + " VALUES (?, ?, 'UPGRADE_TEST', 'OK', 200, 1, 1, 'upgrade-release', ?, ?, 'OK', ?)",
                UUID.randomUUID(), collectorRunId, "upgrade-request-" + UUID.randomUUID(), "d".repeat(64), now);
        jdbc.update("INSERT INTO " + UPGRADE_SCHEMA + ".kto_place_snapshots"
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
                    v_license uuid := gen_random_uuid();
                    v_asset uuid := gen_random_uuid();
                    v_set uuid := gen_random_uuid();
                    v_forecast_run uuid := gen_random_uuid();
                    v_trip uuid := gen_random_uuid();
                    v_item uuid := gen_random_uuid();
                    v_post uuid := gen_random_uuid();
                    v_candidate uuid := gen_random_uuid();
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
                    INSERT INTO posts (id, status, title, body, cover_url, published_at,
                                       created_at, updated_at)
                    VALUES (v_post, 'PUBLISHED', '업그레이드 글', '본문',
                            'https://example.test/cover.jpg', v_at, v_at, v_at);
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
                END
                $upgrade$;
                """.formatted(UPGRADE_SCHEMA));
        // Every table the previous schema owns must be covered; a new one has to be added here too.
        assertThat(tablesInUpgradeSchema())
                .containsExactlyInAnyOrder("background_jobs", "owners", "idempotency_records",
                        "demo_sessions", "demo_session_csrf_tokens", "deletion_requests",
                        "deletion_tombstones", "source_registry", "source_registry_revisions",
                        "source_quality_incidents", "collector_runs", "api_ingest_logs", "kto_place_snapshots",
                        "places", "place_localizations", "place_external_refs", "asset_licenses",
                        "media_assets", "place_media_assets", "snapshot_sets", "crowd_snapshots",
                        "trips", "trip_interests", "trip_revisions", "trip_items", "trip_constraints",
                        "posts", "post_places", "saved_posts", "trip_candidates", "candidate_sources");
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
