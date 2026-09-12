package io.nullnull.analytics.application;

import io.nullnull.analytics.application.AnalyticsEventService.StoredEvent;
import java.time.Instant;

/** Persistence for {@code analytics_events}. */
public interface AnalyticsEventStore {

    /**
     * @return true when the row was inserted, false when the eventId was already stored.
     *     The database decides, not a preceding read: two concurrent retries of the same batch
     *     would both pass a read-then-write and store the event twice.
     */
    boolean store(StoredEvent event);

    /** Deletes rows received before {@code before}; returns how many went. */
    int deleteReceivedBefore(Instant before);
}
