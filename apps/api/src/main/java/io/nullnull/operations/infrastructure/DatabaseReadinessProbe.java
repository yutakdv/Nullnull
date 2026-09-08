package io.nullnull.operations.infrastructure;

import io.nullnull.operations.application.ReadinessProbe;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Required probe: PostgreSQL connection validity within a bounded timeout. */
@Component
public class DatabaseReadinessProbe implements ReadinessProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReadinessProbe.class);

    private final DataSource dataSource;
    private final int timeoutSeconds;

    public DatabaseReadinessProbe(DataSource dataSource,
            @Value("${nullnull.readiness.database-timeout-seconds}") int timeoutSeconds) {
        this.dataSource = dataSource;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public ProbeResult probe(Instant checkedAt) {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(timeoutSeconds)) {
                return new ProbeResult(ProbeStatus.READY, checkedAt, null);
            }
            return new ProbeResult(ProbeStatus.UNAVAILABLE, checkedAt, "connection validation failed");
        } catch (SQLException exception) {
            log.warn("database readiness probe failed sqlState={}", exception.getSQLState());
            return new ProbeResult(ProbeStatus.UNAVAILABLE, checkedAt, "connection unavailable");
        }
    }
}
