package io.nullnull.crowd.infrastructure.persistence;

import io.nullnull.crowd.application.KtoCallInventory;
import io.nullnull.crowd.application.KtoCallInventoryQuery;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The call-audit read behind {@link KtoCallInventory}. Every condition is scoped by release, so the
 * answer never counts another release's rows - including the excluded counts.
 */
@Repository
public class JdbcKtoCallInventoryQuery implements KtoCallInventoryQuery {

    /**
     * A KTO source is one whose registry code starts with {@code KTO_}; the registry names every
     * provider that way ({@code KTO_KOR_SERVICE_2}, {@code KTO_CONCENTRATION_FORECAST},
     * {@code KTO_RELATED_PLACES}). A replay is either a replay run or a source that only replays.
     */
    private static final String SCOPE = """
              FROM api_ingest_logs log
              JOIN collector_runs run ON run.id = log.collector_run_id
              JOIN source_registry source ON source.code = run.source_code
             WHERE log.release_version = ?
               AND left(run.source_code, 4) = 'KTO_'
            """;

    private static final String REPLAY = "(run.trigger_type = 'REPLAY' OR source.source_state = 'REPLAY')";

    private static final String USABLE = "log.outcome = 'OK' AND log.validation_result = 'OK'";

    private final JdbcTemplate jdbc;

    public JdbcKtoCallInventoryQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public KtoCallInventory forRelease(String release) {
        List<KtoCallInventory.Operation> operations = jdbc.query("""
                SELECT run.source_code, log.endpoint_key, count(*) AS calls,
                       min(log.created_at) AS first_at, max(log.created_at) AS last_at
                """ + SCOPE + " AND " + USABLE + " AND NOT " + REPLAY + """

                 GROUP BY run.source_code, log.endpoint_key
                 ORDER BY run.source_code, log.endpoint_key
                """,
                (row, number) -> new KtoCallInventory.Operation(row.getString("source_code"),
                        row.getString("endpoint_key"), row.getInt("calls"),
                        row.getTimestamp("first_at").toInstant(), row.getTimestamp("last_at").toInstant()),
                release);
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE NOT %1$s AND NOT (%2$s)) AS rejected,
                       count(*) FILTER (WHERE %1$s) AS replayed
                """.formatted(REPLAY, USABLE) + SCOPE,
                (row, number) -> new KtoCallInventory(release, operations, row.getInt("rejected"),
                        row.getInt("replayed")),
                release);
    }
}
