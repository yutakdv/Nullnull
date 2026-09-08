package io.nullnull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** BA-002-T1/T2 baseline: migrations apply on an empty PostgreSQL and constraints reject bad rows. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 Flyway baseline on real PostgreSQL")
class FlywayMigrationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baselineMigrationIsAppliedOnPostgres17() {
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).startsWith("PostgreSQL 17.");
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version")).contains("001");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void duplicateDeduplicationKeyIsRejected() {
        String key = "it-dedup-" + UUID.randomUUID();
        insertJob(key, "READY");
        assertThatThrownBy(() -> insertJob(key, "READY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusAndHalfLeaseAreRejected() {
        assertThatThrownBy(() -> insertJob("it-status-" + UUID.randomUUID(), "SOMETHING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO background_jobs (id, type, deduplication_key, status, max_attempts, next_attempt_at, locked_by, created_at)"
                        + " VALUES (?, 'OPTIMIZATION', ?, 'RUNNING', 3, ?, 'worker-a', ?)",
                UUID.randomUUID(), "it-lease-" + UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertJob(String key, String status) {
        jdbc.update("INSERT INTO background_jobs (id, type, deduplication_key, status, max_attempts, next_attempt_at, created_at)"
                        + " VALUES (?, 'OPTIMIZATION', ?, ?, 3, ?, ?)",
                UUID.randomUUID(), key, status, OffsetDateTime.now(), OffsetDateTime.now());
    }
}
