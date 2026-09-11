package io.nullnull.operations.application;

import io.nullnull.operations.domain.JobRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings of the job worker (docs/operations/ENVIRONMENT.md {@code NULLNULL_JOB_*}).
 *
 * <p>Every duration carries a documented floor for the reason BA-002 already met: Spring's simple
 * duration style reads a bare number as milliseconds, so {@code NULLNULL_JOB_LEASE=60} means 60
 * milliseconds. A lease that short would expire before any handler finished and every job would be
 * re-taken forever, with no error anywhere. A floor turns that into a startup failure that names the
 * property and the value it received.
 *
 * <p>The shipped values are engineering proposals, not figures from the contract documents; the
 * reasoning for each is recorded next to it and in docs/operations/ENVIRONMENT.md.
 *
 * <p>{@code maxAttempts} is a ceiling, not a default: each caller chooses its own {@code max_attempts}
 * - a deletion job and a collector poll do not deserve the same patience - and an enqueue asking for
 * more than this is refused, so one lever caps a retry storm across every type without editing code.
 *
 * <p>{@code enabled} is a {@link Boolean} and not a primitive on purpose: a primitive binds a missing
 * key to {@code false} without a word, which is a job runtime that is off and a retention sweep that
 * never runs. Missing here fails the context start like every other setting in this record, and the
 * documented default lives in application.yaml where an operator can see it.
 */
@ConfigurationProperties(prefix = "nullnull.jobs")
public record JobProperties(Boolean enabled, Duration lease, Duration pollInterval, Duration lockTimeout,
        int maxAttempts, Duration retryBackoff, Duration maxRetryBackoff, Duration deadLetterWindow,
        Duration finishedRetention, Duration retentionSweepInterval, int defaultConcurrency,
        Map<String, Integer> concurrency) {

    /** Below a second no handler could finish inside a lease; this is the bare-number trap. */
    static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);

    /** A poll faster than this is a busy loop against PostgreSQL, whatever the operator meant. */
    static final Duration MINIMUM_POLL_INTERVAL = Duration.ofMillis(10);

    /** PostgreSQL reads {@code lock_timeout = 0} as "wait forever", so sub-millisecond removes the bound. */
    static final Duration MINIMUM_LOCK_TIMEOUT = Duration.ofMillis(100);

    /** Shorter back-off than this retries a transient fault before it can have cleared. */
    static final Duration MINIMUM_RETRY_BACKOFF = Duration.ofSeconds(1);

    /** A window under a minute would let a dead letter disappear from readiness before a scrape. */
    static final Duration MINIMUM_DEAD_LETTER_WINDOW = Duration.ofMinutes(1);

    /** Deleting finished rows sooner than this destroys the evidence an incident review needs. */
    static final Duration MINIMUM_FINISHED_RETENTION = Duration.ofHours(1);

    static final Duration MINIMUM_RETENTION_SWEEP_INTERVAL = Duration.ofMinutes(1);

    /** One worker process; a higher ceiling is a scaling decision, not a configuration typo. */
    static final int MAXIMUM_CONCURRENCY = 64;

    public JobProperties {
        Objects.requireNonNull(enabled, "nullnull.jobs.enabled is required");
        lease = atLeast("nullnull.jobs.lease", lease, MINIMUM_LEASE);
        pollInterval = atLeast("nullnull.jobs.poll-interval", pollInterval, MINIMUM_POLL_INTERVAL);
        lockTimeout = atLeast("nullnull.jobs.lock-timeout", lockTimeout, MINIMUM_LOCK_TIMEOUT);
        retryBackoff = atLeast("nullnull.jobs.retry-backoff", retryBackoff, MINIMUM_RETRY_BACKOFF);
        maxRetryBackoff = atLeast("nullnull.jobs.max-retry-backoff", maxRetryBackoff, retryBackoff);
        deadLetterWindow = atLeast("nullnull.jobs.dead-letter-window", deadLetterWindow,
                MINIMUM_DEAD_LETTER_WINDOW);
        finishedRetention = atLeast("nullnull.jobs.finished-retention", finishedRetention,
                MINIMUM_FINISHED_RETENTION);
        retentionSweepInterval = atLeast("nullnull.jobs.retention-sweep-interval", retentionSweepInterval,
                MINIMUM_RETENTION_SWEEP_INTERVAL);
        maxAttempts = inRange("nullnull.jobs.max-attempts", maxAttempts, 1, JobRequest.MAX_ATTEMPTS_LIMIT);
        defaultConcurrency = inRange("nullnull.jobs.default-concurrency", defaultConcurrency, 1,
                MAXIMUM_CONCURRENCY);
        concurrency = concurrency == null ? Map.of() : Map.copyOf(concurrency);
        for (Map.Entry<String, Integer> entry : concurrency.entrySet()) {
            if (!JobRequest.TYPE.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("nullnull.jobs.concurrency has a key that is not a job "
                        + "type (" + JobRequest.TYPE.pattern() + "): " + entry.getKey());
            }
            inRange("nullnull.jobs.concurrency." + entry.getKey(), entry.getValue(), 1, MAXIMUM_CONCURRENCY);
        }
    }

    /** How many jobs of one type this process runs at once; the isolation budget of §10. */
    public int concurrencyFor(String type) {
        return concurrency.getOrDefault(type, defaultConcurrency);
    }

    /** Beat three times per lease, so one missed or slow beat does not lose the job. */
    public Duration heartbeatInterval() {
        return lease.dividedBy(3);
    }

    /**
     * Exponential back-off from {@code retryBackoff}, doubling per failed attempt and capped at
     * {@code maxRetryBackoff}. Deterministic on purpose: a fixed schedule is reproducible in a test
     * and, with one worker process per type and a claim that already serialises, jitter would buy
     * nothing but flaky assertions.
     *
     * @param attempt the attempt that just failed, 1-based
     */
    public Instant nextAttemptAt(Instant now, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1 but was " + attempt);
        }
        Duration delay = retryBackoff;
        // Doubling in a loop rather than with a shift: the cap is applied every step, so a large
        // attempt number can never overflow the Duration on its way to being capped.
        for (int doubled = 1; doubled < attempt && delay.compareTo(maxRetryBackoff) < 0; doubled++) {
            delay = delay.multipliedBy(2);
        }
        return now.plus(delay.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : delay);
    }

    private static Duration atLeast(String property, Duration value, Duration minimum) {
        Objects.requireNonNull(value, property + " is required");
        if (value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    property + " must be at least " + minimum + " but was " + value);
        }
        return value;
    }

    private static int inRange(String property, Integer value, int minimum, int maximum) {
        Objects.requireNonNull(value, property + " is required");
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and "
                    + maximum + " but was " + value);
        }
        return value;
    }
}
