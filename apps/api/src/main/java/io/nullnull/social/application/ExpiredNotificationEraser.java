package io.nullnull.social.application;

import io.nullnull.operations.application.TtlEraser;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Retention for notifications: 90 days, or 30 days after it was read, whichever is earlier
 * (ERD §6).
 *
 * <p>Both halves are already inside {@code expires_at} when this runs, because
 * {@link NotificationService} resolved them to instants when the row was written and again when it
 * was marked read. So the sweep is a single comparison and holds no policy of its own - there is no
 * second place for the rule to drift to.
 *
 * <p>Joining the schedule needs nothing more than being a bean: {@link TtlEraser} is collected by
 * {@code TtlSweep}, which {@code JobWorker} runs on its retention tick. The lease, attempt cap,
 * retry and dead-letter the repository requires of an asynchronous job are that worker's, already.
 */
@Component
public class ExpiredNotificationEraser implements TtlEraser {

    private final NotificationService notifications;

    public ExpiredNotificationEraser(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public String name() {
        return "notifications-retention";
    }

    @Override
    public int erase(Instant now) {
        return notifications.deleteExpired(now);
    }
}
