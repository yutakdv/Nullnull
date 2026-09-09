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
            assertThat(rowsBefore).isEqualTo(tablesInUpgradeSchema().size());

            MigrateResult toLatest = upgradeSchemaFlyway(null).migrate();
            assertThat(toLatest.success).isTrue();
            assertThat(toLatest.migrationsExecuted).isEqualTo(versions.size() - untilPrevious.size());
            assertThat(appliedVersionsInUpgradeSchema()).isEqualTo(versions);
            assertThat(jdbc.queryForObject("SELECT bool_and(success) FROM " + UPGRADE_SCHEMA
                    + ".flyway_schema_history WHERE version IS NOT NULL", Boolean.class)).isTrue();

            // Every existing row survived, and every column an older application reads is still there
            // with the same type and nullability: this slice only adds tables.
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
        // Every table the previous schema owns must be covered; a new one has to be added here too.
        assertThat(tablesInUpgradeSchema())
                .containsExactlyInAnyOrder("background_jobs", "owners", "idempotency_records",
                        "demo_sessions", "demo_session_csrf_tokens");
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
