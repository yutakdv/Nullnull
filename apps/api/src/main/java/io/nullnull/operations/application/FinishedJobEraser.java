package io.nullnull.operations.application;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention for the queue's own rows: COMPLETED and FAILED jobs are deleted once they are older than
 * {@code nullnull.jobs.finished-retention}.
 *
 * <p>They are kept at all because a finished row is the evidence that a piece of work ran: a dead
 * letter has to survive long enough for an operator to see the alert, find the job and decide what to
 * do. The proposed week is long enough for a weekend incident and short enough that the table stays
 * small. Nothing else depends on the row: deduplication only constrains outstanding work, so deleting
 * a finished job lets the same key be enqueued again, which is the intended behaviour.
 */
@Component
public class FinishedJobEraser implements TtlEraser {

    private final JobQueue queue;
    private final JobProperties properties;

    public FinishedJobEraser(JobQueue queue, JobProperties properties) {
        this.queue = queue;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "background-jobs";
    }

    @Override
    @Transactional
    public int erase(Instant now) {
        return queue.deleteFinishedBefore(now.minus(properties.finishedRetention()));
    }
}
