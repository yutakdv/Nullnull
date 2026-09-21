package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.domain.ApprovalState;
import io.nullnull.crowd.domain.LicenseReviewState;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.crowd.domain.SourceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSourceRegistryStore implements SourceRegistryStore {

    private static final String SELECT = """
            SELECT code, display_name, source_state, approval_state, license_review_state,
                   (quota_policy->>'perDay')::integer AS quota_per_day, stale_after_seconds,
                   enabled, current_revision, provider_schema_version, reviewed_at
              FROM source_registry
            """;

    private final JdbcTemplate jdbc;

    public JdbcSourceRegistryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SourceRegistration> findAll() {
        return jdbc.query(SELECT + " ORDER BY code", JdbcSourceRegistryStore::registration);
    }

    @Override
    public Optional<SourceRegistration> findByCode(String code) {
        return jdbc.query(SELECT + " WHERE code = ?", JdbcSourceRegistryStore::registration, code)
                .stream().findFirst();
    }

    @Override
    public SourceCondition conditionAt(String code, Instant at) {
        boolean incident = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM source_quality_incidents
                     WHERE source_code = ? AND disposition = 'QUARANTINE'
                       AND affected_from <= ? AND (affected_to IS NULL OR ? < affected_to)
                )
                """, Boolean.class, code, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at)));
        // A quarantined latest run shuts the source, and WITHOUT the release clause below that is a
        // deadlock rather than a guard: every gateway asks this before collector.start, so while the
        // latest run is QUARANTINED no newer run can ever be recorded to displace it. The source stays
        // shut forever and nothing in the operator surface opens it (2026-09-21: SEOUL_CITYDATA locked
        // itself this way on its own five-minute schedule, and the staging database is not reachable
        // from outside the VPC).
        //
        // The release is a reviewed incident, not a rewritten run: the refusal happened and its row
        // stays. `RESOLVED` is already in source_incident_disposition_check and `reviewed_at` is NOT
        // NULL, so the human-review record this needs was designed - it simply was not consulted here.
        // `reviewed_at >= started_at` is what makes it a release of THIS quarantine rather than an old
        // review resurrected: a review filed before the refusal says nothing about it.
        boolean quarantined = Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH latest AS (
                    SELECT status, started_at FROM collector_runs
                     WHERE source_code = ? ORDER BY started_at DESC, id DESC LIMIT 1
                )
                SELECT COALESCE((
                    SELECT latest.status = 'QUARANTINED'
                       AND NOT EXISTS (
                           SELECT 1 FROM source_quality_incidents
                            WHERE source_code = ? AND disposition = 'RESOLVED'
                              AND reviewed_at >= latest.started_at
                       )
                      FROM latest
                ), false)
                """, Boolean.class, code, code));
        return new SourceCondition(incident, quarantined);
    }

    private static SourceRegistration registration(ResultSet rs, int row) throws SQLException {
        Number stale = (Number) rs.getObject("stale_after_seconds");
        return new SourceRegistration(
                rs.getString("code"), rs.getString("display_name"),
                SourceState.valueOf(rs.getString("source_state")),
                ApprovalState.valueOf(rs.getString("approval_state")),
                LicenseReviewState.valueOf(rs.getString("license_review_state")),
                rs.getInt("quota_per_day"), stale == null ? null : stale.longValue(),
                rs.getBoolean("enabled"), rs.getLong("current_revision"),
                rs.getString("provider_schema_version"), rs.getTimestamp("reviewed_at").toInstant());
    }

    @Override
    @Transactional
    public Optional<ReleasedRun> releaseLatestQuarantine(String code, String incidentCode, Instant reviewedAt) {
        // FOR UPDATE inside the same transaction as the insert: a collection starting while the
        // operator releases cannot land between the read and the write and leave the release pointing
        // at a run that is no longer the latest.
        List<ReleasedRun> latest = jdbc.query("""
                SELECT id, started_at, status FROM collector_runs
                 WHERE source_code = ? ORDER BY started_at DESC, id DESC LIMIT 1
                 FOR UPDATE
                """, (rs, row) -> "QUARANTINED".equals(rs.getString("status"))
                        ? new ReleasedRun((UUID) rs.getObject("id"), rs.getTimestamp("started_at").toInstant())
                        : null, code);
        if (latest.isEmpty() || latest.get(0) == null) {
            // Nothing to release, and saying so is the point: a command that reported success here
            // would tell the operator a source was reopened when it had never been shut.
            return Optional.empty();
        }
        ReleasedRun run = latest.get(0);
        if (reviewedAt.isBefore(run.startedAt())) {
            // conditionAt compares reviewed_at against the run's started_at, so a release that
            // predates its refusal would be written and then silently ignored.
            throw new IllegalArgumentException("a release cannot predate the run it releases");
        }
        jdbc.update("""
                INSERT INTO source_quality_incidents
                    (id, source_code, incident_code, affected_from, affected_to, scope,
                     official_notice_url, disposition, reviewed_at)
                VALUES (?, ?, ?, ?, ?, 'OPERATOR_REVIEW', NULL, 'RESOLVED', ?)
                """, UUID.randomUUID(), code, incidentCode,
                Timestamp.from(run.startedAt()),
                Timestamp.from(reviewedAt.isAfter(run.startedAt()) ? reviewedAt
                        : run.startedAt().plusMillis(1)),
                Timestamp.from(reviewedAt));
        return Optional.of(run);
    }
}
