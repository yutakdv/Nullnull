package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.SourceRegistryStore;
import io.nullnull.crowd.domain.ApprovalState;
import io.nullnull.crowd.domain.LicenseReviewState;
import io.nullnull.crowd.domain.SourceRegistration;
import io.nullnull.crowd.domain.SourceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        boolean quarantined = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT status = 'QUARANTINED' FROM collector_runs
                     WHERE source_code = ? ORDER BY started_at DESC, id DESC LIMIT 1
                ), false)
                """, Boolean.class, code));
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
}
