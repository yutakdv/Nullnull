package io.nullnull.analytics.application;

import io.nullnull.operations.application.TtlEraser;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 90-day retention for analytics events (ERD §6).
 *
 * <p>The sweep deletes by {@code received_at}, not {@code occurred_at}. {@code occurredAt} comes
 * from a device clock: one set far in the past would make a row arrive already expired, and one set
 * in the future would hold a row past its retention. What the retention promise is actually about
 * is how long WE keep it, and that clock is ours.
 */
@Component
public class ExpiredAnalyticsEventEraser implements TtlEraser {

    private final AnalyticsEventStore events;
    private final Duration retention;

    public ExpiredAnalyticsEventEraser(AnalyticsEventStore events,
            @Value("${nullnull.analytics.retention}") Duration retention) {
        if (retention.isZero() || retention.isNegative()) {
            // A zero retention would delete every row on the next tick, which reads like "keep
            // nothing" but is really "the property was unset and something defaulted".
            throw new IllegalArgumentException(
                    "nullnull.analytics.retention must be positive, was " + retention);
        }
        this.events = events;
        this.retention = retention;
    }

    @Override
    public String name() {
        return "analytics-events-retention";
    }

    @Override
    @Transactional
    public int erase(Instant now) {
        return events.deleteReceivedBefore(now.minus(retention));
    }
}
