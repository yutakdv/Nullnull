package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.CommandLockTimeoutException;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobEnqueueException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.application.TtlSweep;
import io.nullnull.operations.domain.AbandonedJob;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.EnqueuedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.operations.domain.JobStatus;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-005 at the port level on a real PostgreSQL: enqueue, claim eligibility, the lease condition every
 * later write carries, and the retention sweep.
 *
 * <p>The clock is a {@link MutableClock} because every one of those rules is a comparison against the
 * injected clock and never against the database's {@code now()}. Moving the clock is therefore the
 * production path to an expired lease or a reached retention cutoff, where a {@code sleep} would be
 * slow and editing rows would test a hand-written state instead of a reachable one.
 *
 * <p>The worker stays off (the suite default) so nothing claims a job behind an assertion.
 */
@SpringBootTest(properties = {
        // Non-default on purpose: the retention sweep below measures how long a contended eraser waits,
        // and at the shipped default that measurement cannot tell the injected property from a
        // hardcoded constant - replacing the eraser's @Value with Duration.ofSeconds(3) kept it green.
        "nullnull.idempotency.lock-timeout=PT1S"})
@Import({TestcontainersConfiguration.class, JobQueueIT.TestJobs.class})
@DisplayName("BA-005 job queue on real PostgreSQL")
class JobQueueIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String TYPE = "queue-test";
    static final String OTHER_TYPE = "queue-test-other";

    /**
     * A copy of the shipped default as it stood when this was written, not a reading of it: the
     * assertion below only proves this context is not running at THAT value, so a change to
     * application.yaml would go unnoticed here. It is a guard against the context silently losing its
     * override, not a drift check on the default itself.
     */
    private static final Duration DEFAULT_IDEMPOTENCY_LOCK_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The two handlers exist so that {@code enqueue} accepts these types: an enqueue for a type
     * nothing handles is refused, which is itself one of the cases below. Neither is ever executed -
     * the worker is off in this context.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        @Bean
        JobHandler queueTestHandler() {
            return namedHandler(TYPE);
        }

        @Bean
        JobHandler queueTestOtherHandler() {
            return namedHandler(OTHER_TYPE);
        }

        private static JobHandler namedHandler(String type) {
            return new JobHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void handle(JobContext context) {
                    throw new UnsupportedOperationException("the worker is disabled in this context");
                }
            };
        }
    }

    @Autowired
    JobQueue queue;

    @Autowired
    JobProperties properties;

    @Autowired
    TtlSweep retention;

    @Autowired
    OwnerRepository owners;

    @Autowired
    JobUnitOfWorkGuard guard;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    @Value("${nullnull.idempotency.lock-timeout}")
    Duration configuredIdempotencyLockTimeout;

    @Test
    void enqueueRequiresTheCallersTransaction() {
        // "run creation and job registration are the same transaction" is enforced, not documented:
        // a job registered outside one could outlive a command that rolled back.
        assertThatThrownBy(() -> queue.enqueue(request(TYPE, key(), 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * The context is shared by every test in this class and the worker is off, so nothing removes the
     * rows a test leaves behind. Claims are "the next eligible job of this type", so a leftover would
     * be claimed by the following test instead of the row it just wrote.
     */
    @BeforeEach
    void clearTheQueue() {
        jdbc.update("DELETE FROM deletion_tombstones");
        jdbc.update("DELETE FROM deletion_requests");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM background_jobs");
        // Shared Compose DB retains earlier identity fixtures; clear children before owners.
        jdbc.update("DELETE FROM demo_sessions");
        jdbc.update("DELETE FROM owners");
    }

    @Test
    void aCollidingDeduplicationKeyReturnsTheExistingJob() {
        String key = key();
        EnqueuedJob first = enqueue(request(TYPE, key, 3));
        EnqueuedJob second = enqueue(request(TYPE, key, 3));

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo(JobStatus.READY);
        assertThat(rowsWithKey(key)).isOne();
    }

    @Test
    void aKeyHeldByAnotherTypeIsRefusedInsteadOfSilentlyDropped() {
        String key = key();
        enqueue(request(TYPE, key, 1));
        assertThatThrownBy(() -> enqueue(request(OTHER_TYPE, key, 1)))
                .isInstanceOf(JobEnqueueException.class)
                .hasMessageContaining(OTHER_TYPE);
    }

    @Test
    void aTypeWithNoHandlerIsRefusedBecauseTheRowWouldNeverBeClaimed() {
        assertThatThrownBy(() -> enqueue(request("no-such-handler", key(), 1)))
                .isInstanceOf(JobEnqueueException.class)
                .hasMessageContaining("no-such-handler");
    }

    @Test
    void aRequestAboveTheConfiguredCeilingIsRefused() {
        int aboveCeiling = properties.maxAttempts() + 1;
        assertThatThrownBy(() -> enqueue(request(TYPE, key(), aboveCeiling)))
                .isInstanceOf(JobEnqueueException.class)
                .hasMessageContaining("nullnull.jobs.max-attempts");
    }

    @Test
    void aJobIsClaimedOnlyByItsOwnTypeAndOnlyAfterItsBackOff() {
        String key = key();
        JobRequest request = new JobRequest(UuidV7.create(clock), TYPE, key, JobPayload.empty(), 3,
                clock.instant().plus(Duration.ofMinutes(10)), clock.instant());
        enqueue(request);

        assertThat(claim(OTHER_TYPE)).isEmpty();
        assertThat(claim(TYPE)).as("the back-off has not passed").isEmpty();

        clock.advance(Duration.ofMinutes(10));
        assertThat(claim(TYPE)).isPresent();
    }

    @Test
    void aCrashedWorkersJobIsRetakenOnlyAfterTheLeaseExpires() {
        String key = key();
        enqueue(request(TYPE, key, 3));
        ClaimedJob first = claim(TYPE).orElseThrow();

        // The worker "crashes": no heartbeat, no completion, nothing to clean up.
        assertThat(claim(TYPE)).as("the lease is still valid").isEmpty();

        clock.advance(properties.lease().plusSeconds(1));
        ClaimedJob second = claim(TYPE).orElseThrow();

        assertThat(second.lease().jobId()).isEqualTo(first.lease().jobId());
        assertThat(second.lease().attempt()).isEqualTo(2);
        assertThat(second.lease().token()).isNotEqualTo(first.lease().token());
        assertThat(second.deduplicationKey()).isEqualTo(key);
    }

    @Test
    void thePayloadSurvivesTheRoundTripAndCarriesIdentifiersOnly() {
        String key = key();
        UUID ownerId = UuidV7.create(clock);
        JobPayload payload = JobPayload.of(Map.of("ownerId", ownerId.toString(), "reason", "OWNER_DELETED"));
        enqueue(new JobRequest(UuidV7.create(clock), TYPE, key, payload, 3, clock.instant(), clock.instant()));

        ClaimedJob claimed = claim(TYPE).orElseThrow();
        assertThat(claimed.payload()).isEqualTo(payload);
        assertThat(claimed.payload().get("ownerId")).isEqualTo(ownerId.toString());
    }

    @Test
    void everyWriteOnAStaleLeaseIsRejectedInsteadOfSilentlySucceeding() {
        enqueue(request(TYPE, key(), 3));
        ClaimedJob claimed = claim(TYPE).orElseThrow();
        JobLease lease = claimed.lease();
        JobLease wrongToken = new JobLease(lease.jobId(), lease.type(), lease.token() + "x", lease.attempt());
        JobLease wrongAttempt = new JobLease(lease.jobId(), lease.type(), lease.token(), lease.attempt() + 1);
        Instant now = clock.instant();

        for (JobLease stale : new JobLease[] {wrongToken, wrongAttempt}) {
            assertThatThrownBy(() -> queue.heartbeat(stale, now, now.plusSeconds(60)))
                    .isInstanceOf(StaleLeaseException.class);
            assertThatThrownBy(() -> queue.complete(stale, now)).isInstanceOf(StaleLeaseException.class);
            assertThatThrownBy(() -> queue.retry(stale, "X", now, now.plusSeconds(60)))
                    .isInstanceOf(StaleLeaseException.class);
            assertThatThrownBy(() -> queue.deadLetter(stale, "X", now))
                    .isInstanceOf(StaleLeaseException.class);
            assertThatThrownBy(() -> transactions.execute(status -> {
                queue.assertLeaseHeld(stale, now);
                return null;
            })).isInstanceOf(StaleLeaseException.class);
        }

        // The real lease still works, so the rejections above are about the lease and not about state.
        assertThatCode(() -> queue.complete(lease, now)).doesNotThrowAnyException();
        assertThat(status(lease.jobId())).isEqualTo("COMPLETED");
    }

    @Test
    void anExpiredLeaseCannotCompleteEvenBeforeAnyoneRetakesTheJob() {
        enqueue(request(TYPE, key(), 3));
        ClaimedJob claimed = claim(TYPE).orElseThrow();
        clock.advance(properties.lease().plusSeconds(1));

        assertThatThrownBy(() -> queue.complete(claimed.lease(), clock.instant()))
                .isInstanceOf(StaleLeaseException.class);
        assertThat(status(claimed.lease().jobId())).isEqualTo("RUNNING");
    }

    @Test
    void aHandlerUnitOfWorkCommitsWithTheJobRowWhileTheLeaseHolds() {
        enqueue(request(TYPE, key(), 3));
        ClaimedJob claimed = claim(TYPE).orElseThrow();
        JobContext context = new JobContext(claimed, queue, transactions, properties.lease(), clock, guard);

        Owner owner = context.transactional(() -> owners.create(OwnerFixtures.anonymous(clock)));
        assertThat(owners.findById(owner.id())).isPresent();

        queue.complete(claimed.lease(), clock.instant());
        assertThat(status(claimed.lease().jobId())).isEqualTo("COMPLETED");
    }

    @Test
    void theRetentionSweepDeletesExactlyWhatIsPastRetention() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        UUID expiredRecord = insertIdempotencyRecord(owner.id(), clock.instant().minusSeconds(1));
        // Past the point the clock reaches below, so the sweep has to leave it alone.
        UUID liveRecord = insertIdempotencyRecord(owner.id(),
                clock.instant().plus(properties.finishedRetention()).plus(Duration.ofDays(1)));

        UUID oldJob = completeNow(key());
        clock.advance(properties.finishedRetention().plusSeconds(1));
        UUID freshJob = completeNow(key());
        UUID readyJob = enqueue(request(TYPE, key(), 3)).id();

        TtlSweep.SweepReport report = retention.sweep();

        assertThat(report.deleted()).containsEntry("idempotency-records", 1).containsEntry("background-jobs", 1);
        assertThat(recordExists(expiredRecord)).isFalse();
        assertThat(recordExists(liveRecord)).isTrue();
        assertThat(jobExists(oldJob)).isFalse();
        assertThat(jobExists(freshJob)).isTrue();
        assertThat(jobExists(readyJob)).as("an unfinished job is never swept").isTrue();
    }

    @Test
    @DisplayName("a unit of work whose lease lapses before it commits rolls back, and the job runs once")
    void aUnitOfWorkThatOutlivesItsLeaseRollsBackInsteadOfRunningTheJobTwice() {
        enqueue(request(TYPE, key(), 3));
        ClaimedJob first = claim(TYPE).orElseThrow();
        Owner attempted = OwnerFixtures.anonymous(clock);

        // Measured before the pre-commit assertion existed: with a PT2S lease and an eight second unit
        // of work, this write committed under a lease that had lapsed six seconds earlier, the job was
        // re-claimed, and the handler wrote a second row.
        assertThatThrownBy(() -> new JobContext(first, queue, transactions, properties.lease(), clock, guard)
                .transactional(() -> {
                    Owner created = owners.create(attempted);
                    clock.advance(properties.lease().plusSeconds(1));
                    return created;
                }))
                .isInstanceOf(StaleLeaseException.class);
        assertThat(owners.findById(attempted.id())).as("the domain write rolled back").isEmpty();

        // The job is still there to be re-taken, and the second attempt is the only one that commits.
        ClaimedJob second = claim(TYPE).orElseThrow();
        assertThat(second.lease().attempt()).isEqualTo(2);
        Owner bySecond = new JobContext(second, queue, transactions, properties.lease(), clock, guard)
                .transactional(() -> owners.create(OwnerFixtures.anonymous(clock)));
        queue.complete(second.lease(), clock.instant());

        assertThat(owners.findById(bySecond.id())).isPresent();
        assertThat(ownerCount()).as("the work committed exactly once").isOne();
    }

    @Test
    @DisplayName("an expired lease is re-taken only while attempts remain, then dead-lettered")
    void anAbandonedJobStopsAtTheCeilingAndBecomesADeadLetter() {
        UUID jobId = enqueue(request(TYPE, key(), 2)).id();
        UUID untouched = enqueue(request(OTHER_TYPE, key(), 2)).id();

        ClaimedJob first = claim(TYPE).orElseThrow();
        assertThat(first.lease().attempt()).isOne();
        clock.advance(properties.lease().plusSeconds(1));
        // A worker that dies leaves exactly this: RUNNING, lease expired, nothing thrown anywhere.
        assertThat(claim(TYPE).orElseThrow().lease().attempt()).isEqualTo(2);
        clock.advance(properties.lease().plusSeconds(1));

        assertThat(claim(TYPE)).as("the ceiling stops the re-take, which used to run for ever").isEmpty();
        assertThat(queue.failAbandoned(OTHER_TYPE, clock.instant()))
                .as("a job nobody ever claimed is not abandoned").isEmpty();

        List<AbandonedJob> ended = queue.failAbandoned(TYPE, clock.instant());

        assertThat(ended).singleElement().satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(jobId);
            assertThat(job.type()).isEqualTo(TYPE);
            assertThat(job.attempts()).isEqualTo(2);
        });
        assertThat(status(jobId)).isEqualTo("FAILED");
        assertThat(errorCode(jobId)).isEqualTo(JobQueue.LEASE_EXPIRED_ERROR_CODE);
        // completed_at is what makes it visible to the readiness probe and to retention.
        assertThat(queue.countDeadLettersSince(clock.instant().minusSeconds(1))).isOne();
        assertThat(queue.failAbandoned(TYPE, clock.instant())).as("nothing is ended twice").isEmpty();
        assertThat(status(untouched)).isEqualTo("READY");
    }

    @Test
    void aJobWhoseLeaseIsStillValidIsNeitherRetakenNorAbandoned() {
        enqueue(request(TYPE, key(), 1));
        ClaimedJob claimed = claim(TYPE).orElseThrow();

        assertThat(claim(TYPE)).isEmpty();
        assertThat(queue.failAbandoned(TYPE, clock.instant()))
                .as("a live lease is not an abandoned job, whatever the attempt count says").isEmpty();
        assertThat(status(claimed.lease().jobId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("the deduplication key holds only while the work is outstanding")
    void aFinishedJobReleasesItsDeduplicationKey() {
        String key = key();
        EnqueuedJob first = enqueue(request(TYPE, key, 3));
        ClaimedJob claimed = claim(TYPE).orElseThrow();

        EnqueuedJob whileRunning = enqueue(request(TYPE, key, 3));
        assertThat(whileRunning.created()).as("outstanding work is not duplicated").isFalse();
        assertThat(whileRunning.id()).isEqualTo(first.id());
        assertThat(whileRunning.status()).isEqualTo(JobStatus.RUNNING);

        queue.complete(claimed.lease(), clock.instant());

        // The failure this replaces: the second enqueue returned the COMPLETED job with created=false,
        // so a collector with a natural key ran once and then silently never again for P7D.
        EnqueuedJob afterFinishing = enqueue(request(TYPE, key, 3));
        assertThat(afterFinishing.created()).isTrue();
        assertThat(afterFinishing.id()).isNotEqualTo(first.id());
        assertThat(rowsWithKey(key)).isEqualTo(2);
        assertThat(claim(TYPE).orElseThrow().lease().jobId())
                .as("and it actually runs").isEqualTo(afterFinishing.id());
    }

    @Test
    @DisplayName("the retention sweep gives up on a locked row within the configured bound")
    void theRetentionSweepFailsFastWhenARowIsHeldElsewhere() throws Exception {
        assertThat(configuredIdempotencyLockTimeout)
                .isEqualTo(Duration.ofSeconds(1)).isNotEqualTo(DEFAULT_IDEMPOTENCY_LOCK_TIMEOUT);
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        UUID expired = insertIdempotencyRecord(owner.id(), clock.instant().minusSeconds(1));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM idempotency_records WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, expired);
                try (ResultSet locked = lock.executeQuery()) {
                    assertThat(locked.next()).isTrue();
                }
            }

            long startedAt = System.nanoTime();
            // Measured while the sweep set no bound: still blocked after twelve seconds, holding a
            // scheduler thread and a pooled connection, with every other module's sweep behind it.
            assertThatThrownBy(() -> retention.sweep())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("idempotency-records")
                    .hasCauseInstanceOf(CommandLockTimeoutException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // Bounded by the injected value, not by a generous ceiling: PostgreSQL waits out the whole
            // lock_timeout before it raises, so the wait is at least the bound, and twice the bound is
            // still well under the shipped default the eraser must not be using.
            assertThat(waited).isBetween(configuredIdempotencyLockTimeout,
                    configuredIdempotencyLockTimeout.multipliedBy(2));
            holder.rollback();
        }

        // Nothing was lost: the row is still expired and the next tick deletes it.
        assertThat(recordExists(expired)).isTrue();
        assertThat(retention.sweep().deleted()).containsEntry("idempotency-records", 1);
        assertThat(recordExists(expired)).isFalse();
    }

    private int ownerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM owners", Integer.class);
    }

    private String errorCode(UUID jobId) {
        return jdbc.queryForObject("SELECT last_error_code FROM background_jobs WHERE id = ?",
                String.class, jobId);
    }

    private UUID completeNow(String key) {
        UUID id = enqueue(request(TYPE, key, 3)).id();
        ClaimedJob claimed = claim(TYPE).orElseThrow();
        queue.complete(claimed.lease(), clock.instant());
        return id;
    }

    private EnqueuedJob enqueue(JobRequest request) {
        return transactions.execute(status -> queue.enqueue(request));
    }

    private Optional<ClaimedJob> claim(String type) {
        Instant now = clock.instant();
        return queue.claim(type, "test:" + UUID.randomUUID(), now, now.plus(properties.lease()));
    }

    private JobRequest request(String type, String key, int maxAttempts) {
        return JobRequest.ready(UuidV7.create(clock), type, key, JobPayload.empty(), maxAttempts,
                clock.instant());
    }

    private static String key() {
        return "queue-test:" + UUID.randomUUID();
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
    }

    private int rowsWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE deduplication_key = ?",
                Integer.class, key);
    }

    private boolean jobExists(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE id = ?", Integer.class, jobId) == 1;
    }

    private boolean recordExists(UUID recordId) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE id = ?",
                Integer.class, recordId) == 1;
    }

    private UUID insertIdempotencyRecord(UUID ownerId, Instant expiresAt) {
        UUID id = UuidV7.create(clock);
        jdbc.update("INSERT INTO idempotency_records"
                        + " (id, owner_id, route_key, idempotency_key, request_hash, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, ownerId, "POST /jobs/{jobId}", "idem-" + UUID.randomUUID(), "0".repeat(64),
                OffsetDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return id;
    }
}
