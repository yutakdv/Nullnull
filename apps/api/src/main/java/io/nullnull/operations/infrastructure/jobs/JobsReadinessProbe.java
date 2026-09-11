package io.nullnull.operations.infrastructure.jobs;

import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.ReadinessProbe;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Optional probe: a job that exhausted its attempts is an operations problem, not a reason to take the
 * API out of the load balancer.
 *
 * <p>docs/architecture/SYSTEM_ARCHITECTURE.md §10 asks for an alert on the dead-letter state. Readiness
 * is where this service already publishes capability health, so a dead letter inside
 * {@code nullnull.jobs.dead-letter-window} degrades this one capability and nothing else: reads and
 * edits keep working, liveness is untouched, and the window means the signal clears on its own once
 * the failure stops recurring rather than needing a manual reset.
 *
 * <p>A runtime that is not running is reported first, because counting dead letters cannot detect it:
 * a queue that consumes nothing produces none, so a worker turned off by
 * {@code nullnull.jobs.enabled=false} - or one that never started - used to publish {@code READY} while
 * nothing polled, no job of any kind ran, and both retention sweeps were stopped, leaving idempotency
 * records past their 24 hour retention (docs/architecture/ERD.md §6) and finished job rows past
 * {@code nullnull.jobs.finished-retention} in the database indefinitely.
 */
@Component
public class JobsReadinessProbe implements ReadinessProbe {

    private static final Logger log = LoggerFactory.getLogger(JobsReadinessProbe.class);

    private final JobQueue queue;
    private final JobProperties properties;
    private final JobWorker worker;

    public JobsReadinessProbe(JobQueue queue, JobProperties properties, JobWorker worker) {
        this.queue = queue;
        this.properties = properties;
        this.worker = worker;
    }

    @Override
    public String name() {
        return "jobs";
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    public ProbeResult probe(Instant checkedAt) {
        if (!worker.isRunning()) {
            // Operator-safe and specific enough to act on: the two reasons need different fixes.
            return new ProbeResult(ProbeStatus.DEGRADED, checkedAt, properties.enabled()
                    ? "the background job runtime is not running; no job is being claimed and retention "
                            + "is not being swept"
                    : "the background job runtime is disabled by nullnull.jobs.enabled; no job is being "
                            + "claimed and retention is not being swept");
        }
        try {
            int deadLetters = queue.countDeadLettersSince(checkedAt.minus(properties.deadLetterWindow()));
            if (deadLetters == 0) {
                return new ProbeResult(ProbeStatus.READY, checkedAt, null);
            }
            // Operator-safe detail: a count and the window, never a job id, key or payload.
            return new ProbeResult(ProbeStatus.DEGRADED, checkedAt,
                    deadLetters + " job(s) reached the dead-letter state within " + properties.deadLetterWindow());
        } catch (RuntimeException failure) {
            log.warn("jobs readiness probe failed", failure);
            return new ProbeResult(ProbeStatus.UNAVAILABLE, checkedAt, "job queue state unavailable");
        }
    }
}
