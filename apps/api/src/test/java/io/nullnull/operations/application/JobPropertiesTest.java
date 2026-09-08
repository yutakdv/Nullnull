package io.nullnull.operations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BA-005: the job runtime refuses to start on a value that would silently disable it.
 *
 * <p>Same trap as BA-002's idempotency durations: Spring's simple duration style reads a bare number
 * as milliseconds, so an operator who writes {@code NULLNULL_JOB_LEASE=60} for "a minute" gets a 60
 * millisecond lease. Nothing would fail - every job would simply be re-taken by another worker before
 * any handler finished, forever. The floors turn each of those into a startup failure that names the
 * property and the value it received.
 */
@DisplayName("BA-005 job runtime configuration floors")
class JobPropertiesTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

    @Test
    void aBareNumberLeaseIsRejectedAtStartup() {
        // What "NULLNULL_JOB_LEASE=60" actually binds to.
        assertThatThrownBy(() -> properties(builder -> builder.lease = Duration.ofMillis(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.lease")
                .hasMessageContaining("PT0.06S")
                .hasMessageContaining("PT1S");
    }

    @Test
    void aBareNumberPollIntervalIsRejectedAtStartup() {
        assertThatThrownBy(() -> properties(builder -> builder.pollInterval = Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.poll-interval");
    }

    @Test
    void aSubMillisecondLockTimeoutIsRejectedBecausePostgresReadsZeroAsForever() {
        assertThatThrownBy(() -> properties(builder -> builder.lockTimeout = Duration.ofMillis(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.lock-timeout")
                .hasMessageContaining("PT0.1S");
        assertThatThrownBy(() -> properties(builder -> builder.lockTimeout = Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBackOffCeilingCannotBeBelowTheBackOff() {
        assertThatThrownBy(() -> properties(builder -> {
            builder.retryBackoff = Duration.ofMinutes(2);
            builder.maxRetryBackoff = Duration.ofMinutes(1);
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.max-retry-backoff");
    }

    @Test
    void aMissingEnabledFlagFailsInsteadOfBindingToFalse() {
        // What a primitive boolean would do with a missing NULLNULL_JOBS_ENABLED: bind false without a
        // word, so nothing polls, neither retention sweep runs, and the readiness probe has to be the
        // one to notice. Every other setting here fails loudly when it is absent; so does this one.
        assertThatThrownBy(() -> properties(builder -> builder.enabled = null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullnull.jobs.enabled");
        assertThat(properties(builder -> builder.enabled = false).enabled()).isFalse();
    }

    @Test
    void everyDurationIsRequired() {
        assertThatThrownBy(() -> properties(builder -> builder.deadLetterWindow = null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullnull.jobs.dead-letter-window");
        assertThatThrownBy(() -> properties(builder -> builder.finishedRetention = null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullnull.jobs.finished-retention");
    }

    @Test
    void concurrencyIsBoundedAndKeyedByJobType() {
        assertThatThrownBy(() -> properties(builder -> builder.defaultConcurrency = 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.default-concurrency");
        assertThatThrownBy(() -> properties(builder -> builder.concurrency = Map.of("Deletion", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.concurrency");
        assertThatThrownBy(() -> properties(builder -> builder.concurrency = Map.of("deletion", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.concurrency.deletion");
    }

    @Test
    void maxAttemptsStaysInsideTheDomainCeiling() {
        assertThatThrownBy(() -> properties(builder -> builder.maxAttempts = 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(builder -> builder.maxAttempts = 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.jobs.max-attempts");
    }

    @Test
    void perTypeConcurrencyFallsBackToTheDefault() {
        JobProperties properties = properties(builder -> {
            builder.defaultConcurrency = 2;
            builder.concurrency = Map.of("deletion", 4);
        });
        assertThat(properties.concurrencyFor("deletion")).isEqualTo(4);
        assertThat(properties.concurrencyFor("collector")).isEqualTo(2);
    }

    @Test
    void theHeartbeatBeatsThreeTimesPerLease() {
        assertThat(properties(builder -> builder.lease = Duration.ofSeconds(60)).heartbeatInterval())
                .isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theBackOffDoublesAndThenStopsAtTheCeiling() {
        JobProperties properties = properties(builder -> {
            builder.retryBackoff = Duration.ofSeconds(10);
            builder.maxRetryBackoff = Duration.ofMinutes(5);
        });
        assertThat(properties.nextAttemptAt(NOW, 1)).isEqualTo(NOW.plusSeconds(10));
        assertThat(properties.nextAttemptAt(NOW, 2)).isEqualTo(NOW.plusSeconds(20));
        assertThat(properties.nextAttemptAt(NOW, 3)).isEqualTo(NOW.plusSeconds(40));
        assertThat(properties.nextAttemptAt(NOW, 4)).isEqualTo(NOW.plusSeconds(80));
        assertThat(properties.nextAttemptAt(NOW, 5)).isEqualTo(NOW.plusSeconds(160));
        // Capped, and a very large attempt number must not overflow on its way to the cap.
        assertThat(properties.nextAttemptAt(NOW, 6)).isEqualTo(NOW.plusSeconds(300));
        assertThat(properties.nextAttemptAt(NOW, 1000)).isEqualTo(NOW.plusSeconds(300));
        assertThatThrownBy(() -> properties.nextAttemptAt(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theShippedDefaultsAreValid() {
        assertThatCode(() -> properties(builder -> {
        })).doesNotThrowAnyException();
    }

    /** The values in application.yaml, so a test overrides only the one it is about. */
    private static final class Builder {
        Boolean enabled = true;
        Duration lease = Duration.ofSeconds(60);
        Duration pollInterval = Duration.ofSeconds(1);
        Duration lockTimeout = Duration.ofSeconds(3);
        int maxAttempts = 5;
        Duration retryBackoff = Duration.ofSeconds(10);
        Duration maxRetryBackoff = Duration.ofMinutes(5);
        Duration deadLetterWindow = Duration.ofMinutes(15);
        Duration finishedRetention = Duration.ofDays(7);
        Duration retentionSweepInterval = Duration.ofHours(1);
        int defaultConcurrency = 2;
        Map<String, Integer> concurrency = Map.of();
    }

    private static JobProperties properties(java.util.function.Consumer<Builder> customizer) {
        Builder builder = new Builder();
        customizer.accept(builder);
        return new JobProperties(builder.enabled, builder.lease, builder.pollInterval, builder.lockTimeout,
                builder.maxAttempts, builder.retryBackoff, builder.maxRetryBackoff,
                builder.deadLetterWindow, builder.finishedRetention, builder.retentionSweepInterval,
                builder.defaultConcurrency, builder.concurrency);
    }
}
