package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobProperties;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.JobUnitOfWorkGuard;
import io.nullnull.operations.application.StaleLeaseException;
import io.nullnull.operations.domain.ClaimedJob;
import io.nullnull.operations.domain.JobLease;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-005-T1 and BA-005-T2 (= REC-JOB-01) on a real PostgreSQL, with two workers on two connections and
 * no test transaction.
 *
 * <p>These are the two ways one job can end up with two workers. In T1 they arrive together and the
 * claim has to hand the job to exactly one of them. In T2 the first worker stops heartbeating, its
 * lease expires, a second worker re-takes the job, and the first one comes back to life and tries to
 * finish: its domain write and its completion must both be refused, which is what
 * docs/engineering/TEST_STRATEGY.md REC-JOB-01 means by "only the current lease may commit, zero
 * duplicate proposals".
 *
 * <p>The domain write is a real owner insert through the real repository, so what is being proved is
 * that the handler's own write rolls back with the job row - not that some test-only table does.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, JobLeaseIT.TestJobs.class})
@DisplayName("BA-005 two workers, one job")
class JobLeaseIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String TYPE = "lease-test";
    private static final int TIMEOUT_SECONDS = 30;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        /** Only registers the type for enqueue; the worker is off, so the test plays both workers. */
        @Bean
        JobHandler leaseTestHandler() {
            return new JobHandler() {
                @Override
                public String type() {
                    return TYPE;
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
    OwnerRepository owners;

    @Autowired
    JobUnitOfWorkGuard guard;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

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
    @DisplayName("BA-005-T1 two workers race for one job: one claims it, one commits")
    void twoWorkersRacingForOneJobProduceOneClaimAndOneCommit() throws Exception {
        UUID jobId = enqueue();
        CountDownLatch claimTaken = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            // The first worker claims and holds its transaction open, so the row is locked for as long
            // as the second worker is trying. Deterministic, unlike hoping two threads collide.
            Future<ClaimedJob> first = workers.submit(() -> transactions.execute(status -> {
                ClaimedJob claimed = claim("worker-a").orElseThrow();
                claimTaken.countDown();
                await(releaseFirst);
                return claimed;
            }));
            assertThat(claimTaken.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Optional<ClaimedJob>> second = workers.submit(() -> claim("worker-b"));

            // SKIP LOCKED: the second worker steps over the row being taken instead of queueing behind
            // it. Without it this call blocks on the row lock and then takes the same job as well.
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("only one worker may hold one job")
                    .isEmpty();

            releaseFirst.countDown();
            ClaimedJob winner = first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(winner.lease().jobId()).isEqualTo(jobId);

            Owner byWinner = new JobContext(winner, queue, transactions, properties.lease(), clock, guard)
                    .transactional(() -> owners.create(OwnerFixtures.anonymous(clock)));
            queue.complete(winner.lease(), clock.instant());

            // The loser believes it holds the job - a worker that lost a lease looks exactly like this.
            // Its unit of work and its completion are both refused, and its domain write rolls back.
            JobLease forged = new JobLease(jobId, TYPE, "worker-b:" + UUID.randomUUID(), 1);
            ClaimedJob asLoser = new ClaimedJob(forged, winner.deduplicationKey(), JobPayload.empty(), 3);
            Owner byLoser = OwnerFixtures.anonymous(clock);
            assertThatThrownBy(() -> new JobContext(asLoser, queue, transactions, properties.lease(), clock, guard)
                    .transactional(() -> owners.create(byLoser)))
                    .isInstanceOf(StaleLeaseException.class);
            assertThatThrownBy(() -> queue.complete(forged, clock.instant()))
                    .isInstanceOf(StaleLeaseException.class);

            assertThat(owners.findById(byWinner.id())).isPresent();
            assertThat(owners.findById(byLoser.id())).as("the loser's write rolled back").isEmpty();
            assertThat(ownerCount()).as("the work committed exactly once").isOne();
            assertThat(status(jobId)).isEqualTo("COMPLETED");
            assertThat(attempts(jobId)).isOne();
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aJobBeingTakenDoesNotBlockTheNextEligibleJob() throws Exception {
        UUID first = enqueue();
        UUID second = enqueue();
        CountDownLatch claimTaken = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();

        try {
            Future<ClaimedJob> holder = workers.submit(() -> transactions.execute(status -> {
                ClaimedJob claimed = claim("worker-a").orElseThrow();
                claimTaken.countDown();
                await(releaseFirst);
                return claimed;
            }));
            assertThat(claimTaken.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // SKIP LOCKED skips the row being taken and keeps looking, so one slow claim never becomes
            // head-of-line blocking for the whole type.
            ClaimedJob next = claim("worker-b").orElseThrow();
            releaseFirst.countDown();
            ClaimedJob held = holder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(next.lease().jobId()).isNotEqualTo(held.lease().jobId());
            assertThat(List.of(first, second)).contains(next.lease().jobId(), held.lease().jobId());
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the first worker");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    @DisplayName("BA-005-T2 a completion after the lease expired is rejected, with its domain write "
            + "(REC-JOB-01)")
    void anExpiredLeaseCannotCommitAfterAnotherWorkerRetookTheJob() {
        UUID jobId = enqueue();

        ClaimedJob first = claim("worker-a").orElseThrow();
        // The first worker stops heartbeating: a crash, a pause, a lost connection. Time passes.
        clock.advance(properties.lease().plusSeconds(1));

        ClaimedJob second = claim("worker-b").orElseThrow();
        assertThat(second.lease().jobId()).isEqualTo(jobId);
        assertThat(second.lease().attempt()).isEqualTo(2);

        // The first worker comes back and tries to finish its attempt.
        Owner byFirst = OwnerFixtures.anonymous(clock);
        assertThatThrownBy(() -> new JobContext(first, queue, transactions, properties.lease(), clock, guard)
                .transactional(() -> owners.create(byFirst)))
                .isInstanceOf(StaleLeaseException.class);
        assertThatThrownBy(() -> queue.complete(first.lease(), clock.instant()))
                .isInstanceOf(StaleLeaseException.class);
        assertThatThrownBy(() -> queue.heartbeat(first.lease(), clock.instant(),
                clock.instant().plus(properties.lease()))).isInstanceOf(StaleLeaseException.class);
        assertThatThrownBy(() -> queue.retry(first.lease(), "LATE", clock.instant(),
                clock.instant().plusSeconds(60))).isInstanceOf(StaleLeaseException.class);

        // Nothing the stale worker attempted survived, and the current lease still owns the job.
        assertThat(owners.findById(byFirst.id())).isEmpty();
        assertThat(status(jobId)).isEqualTo("RUNNING");

        Owner bySecond = new JobContext(second, queue, transactions, properties.lease(), clock, guard)
                .transactional(() -> owners.create(OwnerFixtures.anonymous(clock)));
        queue.complete(second.lease(), clock.instant());

        assertThat(owners.findById(bySecond.id())).isPresent();
        assertThat(ownerCount()).as("the work committed exactly once").isOne();
        assertThat(status(jobId)).isEqualTo("COMPLETED");
        assertThat(attempts(jobId)).isEqualTo(2);
    }

    private UUID enqueue() {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), TYPE,
                "lease-test:" + UUID.randomUUID(), JobPayload.empty(), 3, clock.instant());
        return transactions.execute(status -> queue.enqueue(request)).id();
    }

    private Optional<ClaimedJob> claim(String worker) {
        Instant now = clock.instant();
        return queue.claim(TYPE, worker + ":" + UUID.randomUUID(), now, now.plus(properties.lease()));
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
    }

    private int attempts(UUID jobId) {
        return jdbc.queryForObject("SELECT attempt_count FROM background_jobs WHERE id = ?",
                Integer.class, jobId);
    }

    private int ownerCount() {
        return jdbc.queryForObject("SELECT count(*) FROM owners", Integer.class);
    }
}
