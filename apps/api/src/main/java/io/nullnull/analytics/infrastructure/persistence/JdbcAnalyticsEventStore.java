package io.nullnull.analytics.infrastructure.persistence;

import io.nullnull.analytics.application.AnalyticsEventService.StoredEvent;
import io.nullnull.analytics.application.AnalyticsEventStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalyticsEventStore implements AnalyticsEventStore {

    private final JdbcClient jdbc;

    public JdbcAnalyticsEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean store(StoredEvent event) {
        // ON CONFLICT DO NOTHING on the primary key: the duplicate answer comes from the database,
        // so two concurrent retries of one batch converge on one row rather than both inserting.
        int inserted = jdbc.sql("""
                INSERT INTO analytics_events (event_id, owner_id, session_id, name, occurred_at,
                                              received_at, route, locale, timezone, app_version,
                                              properties)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """)
                .params(event.eventId(), event.ownerId(), event.sessionId(), event.name(),
                        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(event.receivedAt(), ZoneOffset.UTC),
                        event.route(), event.locale(), event.timezone(), event.appVersion(),
                        // Cast in SQL rather than through the driver's PGobject: the PostgreSQL
                        // driver is runtimeOnly on purpose, and reaching for its types here would
                        // put a provider class on the compile classpath of a persistence adapter.
                        event.properties())
                .update();
        return inserted == 1;
    }

    @Override
    public int deleteReceivedBefore(Instant before) {
        return jdbc.sql("DELETE FROM analytics_events WHERE received_at < ?")
                .param(OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .update();
    }
}
