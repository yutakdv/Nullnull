package io.nullnull.operations.infrastructure;

import io.nullnull.operations.application.IngestAudit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIngestAudit implements IngestAudit {

    private final JdbcTemplate jdbc;

    public JdbcIngestAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID startRun(StartRun command) {
        UUID id = command.runId() == null ? UUID.randomUUID() : command.runId();
        jdbc.update("""
                INSERT INTO collector_runs
                    (id, source_code, status, trigger_type, schema_version, started_at)
                VALUES (?, ?, 'STARTED', ?, ?, ?)
                """, id, command.sourceCode(), command.triggerType().name(), command.schemaVersion(),
                java.sql.Timestamp.from(command.startedAt()));
        return id;
    }

    @Override
    public void record(CallRecord record) {
        int rows = jdbc.update("""
                UPDATE api_ingest_logs
                   SET outcome = ?, http_status = ?, duration_ms = ?, response_count = ?,
                       payload_hash = ?, validation_result = ?
                 WHERE id = ? AND outcome = 'STARTED' AND validation_result = 'PENDING'
                """, record.outcome().name(), record.httpStatus(), record.durationMs(),
                record.responseCount(), record.payloadHash(), record.validationResult().name(),
                record.ingestLogId());
        if (rows != 1) {
            throw new IllegalStateException("INGEST_LOG_NOT_PENDING");
        }
    }

    @Override
    public void finishRun(FinishRun command) {
        int rows = jdbc.update("""
                UPDATE collector_runs
                   SET status = ?, records_received = ?, records_accepted = ?, records_rejected = ?,
                       error_code = ?, finished_at = ?
                 WHERE id = ? AND status = 'STARTED'
                """, command.status().name(), command.received(), command.accepted(), command.rejected(),
                command.errorCode(), java.sql.Timestamp.from(command.finishedAt()), command.runId());
        if (rows != 1) {
            throw new IllegalStateException("COLLECTOR_RUN_NOT_STARTED");
        }
    }
}
