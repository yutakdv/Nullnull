package io.nullnull.operations;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.application.ReadinessProbe.ProbeStatus;
import io.nullnull.operations.application.ReadinessQuery;
import io.nullnull.operations.application.ReadinessQuery.ReadinessReport;
import io.nullnull.operations.application.ReadinessQuery.ReadinessState;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.testsupport.MutableClock;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.ServletPathMockMvcConfiguration;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BA-005-T3 (retry ceiling and dead letter) plus the two properties that make a handler's write safe:
 * the happy path commits through {@link JobContext#transactional}, and a write attempted outside it is
 * refused rather than committed unbound.
 *
 * <p>The worker really runs here - {@code nullnull.jobs.enabled=true} overrides the suite default -
 * and the clock is a {@link MutableClock} so the exponential back-off between attempts is crossed by
 * moving time rather than by waiting for it.
 */
@SpringBootTest(properties = {
        "nullnull.jobs.enabled=true",
        "nullnull.jobs.poll-interval=PT0.05S",
        "nullnull.jobs.default-concurrency=1",
        // Four synthetic types run beside the production deletion type: 5 slots, heartbeats and
        // claims plus the sweep require 16 connections, with two reserved for readiness.
        "spring.datasource.hikari.maximum-pool-size=18",
        // Out of reach on purpose, so the optional recommendation probe cannot add latency or noise.
        "nullnull.ai.base-url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ServletPathMockMvcConfiguration.class,
        JobWorkerIT.TestJobs.class})
@DisplayName("BA-005 job worker")
class JobWorkerIT {

    static final Instant START = Instant.parse("2026-03-04T05:06:07Z");
    static final String OK = "worker-ok";
    static final String POISON = "worker-poison";
    static final String UNBOUND = "worker-unbound";
    static final String NESTED = "worker-nested";
    static final String POISON_CODE = "POISON_PILL";

    /** What the handlers did, read back by the assertions. */
    static final List<UUID> WRITTEN = new CopyOnWriteArrayList<>();
    static final List<UUID> ATTEMPTED_UNBOUND = new CopyOnWriteArrayList<>();
    static final List<UUID> ATTEMPTED_INSIDE_UNIT_OF_WORK = new CopyOnWriteArrayList<>();
    static final List<UUID> ATTEMPTED_REQUIRES_NEW = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJobs {

        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }

        /** The intended shape: every write goes through the lease-checked unit of work. */
        @Bean
        JobHandler okHandler(OwnerRepository owners, MutableClock clock) {
            return handler(OK, context -> WRITTEN.add(
                    context.transactional(() -> owners.create(OwnerFixtures.anonymous(clock))).id()));
        }

        /** Fails the same way every time: the poison job BA-005-T3 is about. */
        @Bean
        JobHandler poisonHandler() {
            return handler(POISON, context -> {
                throw new JobExecutionException(POISON_CODE,
                        "this handler always fails, by design, on every attempt");
            });
        }

        /** Writes outside the helper, which must be refused instead of committing unbound. */
        @Bean
        JobHandler unboundHandler(OwnerRepository owners, MutableClock clock) {
            return handler(UNBOUND, context -> {
                var owner = OwnerFixtures.anonymous(clock);
                ATTEMPTED_UNBOUND.add(owner.id());
                owners.create(owner);
            });
        }

        /**
         * The subtler escape: a REQUIRES_NEW service called from <em>inside</em> the unit of work. Its
         * transaction is a separate one, so a rollback of the unit of work leaves it standing - the
         * measured failure was a dead-lettered job whose owner row was still in the table.
         */
        @Bean
        JobHandler nestedHandler(RequiresNewWriter writer, OwnerRepository owners, MutableClock clock) {
            return handler(NESTED, context -> context.transactional(() -> {
                var inside = OwnerFixtures.anonymous(clock);
                ATTEMPTED_INSIDE_UNIT_OF_WORK.add(inside.id());
                owners.create(inside);
                var escaping = OwnerFixtures.anonymous(clock);
                ATTEMPTED_REQUIRES_NEW.add(escaping.id());
                writer.writeInItsOwnTransaction(escaping);
                return null;
            }));
        }

        @Bean
        RequiresNewWriter requiresNewWriter(OwnerRepository owners) {
            return new RequiresNewWriter(owners);
        }

        /** A perfectly ordinary application service - the kind a handler would call without thinking. */
        public static class RequiresNewWriter {

            private final OwnerRepository owners;

            RequiresNewWriter(OwnerRepository owners) {
                this.owners = owners;
            }

            @Transactional(propagation = Propagation.REQUIRES_NEW)
            public Owner writeInItsOwnTransaction(Owner owner) {
                return owners.create(owner);
            }
        }

        private static JobHandler handler(String type, java.util.function.Consumer<JobContext> body) {
            return new JobHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void handle(JobContext context) {
                    body.accept(context);
                }
            };
        }
    }

    @Autowired
    JobQueue queue;

    @Autowired
    ReadinessQuery readiness;

    @Autowired
    OwnerRepository owners;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private ListAppender<ILoggingEvent> workerLog;

    @BeforeEach
    void startFromAQuietQueue() {
        WRITTEN.clear();
        ATTEMPTED_UNBOUND.clear();
        ATTEMPTED_INSIDE_UNIT_OF_WORK.clear();
        ATTEMPTED_REQUIRES_NEW.clear();
        jdbc.update("DELETE FROM deletion_tombstones");
        jdbc.update("DELETE FROM deletion_requests");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM background_jobs");
        // Shared Compose DB retains earlier identity fixtures; clear children before owners.
        jdbc.update("DELETE FROM demo_sessions");
        jdbc.update("DELETE FROM owners");
        workerLog = new ListAppender<>();
        workerLog.start();
        workerLogger().addAppender(workerLog);
    }

    @AfterEach
    void detachTheLogAppender() {
        workerLogger().detachAppender(workerLog);
        workerLog.stop();
    }

    @Test
    void aHandlerCommitsItsWriteThroughTheLeaseCheckedUnitOfWork() {
        UUID jobId = enqueue(OK, JobPayload.empty(), 3);

        assertThat(awaitTerminal(jobId)).isEqualTo("COMPLETED");
        assertThat(WRITTEN).hasSize(1);
        assertThat(owners.findById(WRITTEN.getFirst())).isPresent();
        assertThat(attempts(jobId)).isOne();
    }

    @Test
    @DisplayName("BA-005-T3 a poison job stops at the retry ceiling with a dead letter and a degraded probe")
    void aPoisonJobStopsAtTheCeilingAndDegradesTheJobsCapability() {
        UUID ownerId = UuidV7.create(clock);
        JobPayload payload = JobPayload.of(Map.of("ownerId", ownerId.toString()));
        String key = "worker-poison:" + UUID.randomUUID();
        UUID jobId = enqueue(POISON, key, payload, 3);

        assertThat(awaitTerminal(jobId)).isEqualTo("FAILED");
        assertThat(attempts(jobId)).as("the ceiling is the number of attempts, not a suggestion").isEqualTo(3);
        assertThat(errorCode(jobId)).isEqualTo(POISON_CODE);

        List<ILoggingEvent> deadLetters = workerLog.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains("job dead-letter"))
                .toList();
        assertThat(deadLetters).as("one alertable line per exhausted job").hasSize(1);
        String line = deadLetters.getFirst().getFormattedMessage();
        assertThat(line).contains(POISON, jobId.toString(), POISON_CODE);
        // The alert line carries identifiers and a code. Never the key, never a payload value.
        assertThat(line).doesNotContain(key).doesNotContain(ownerId.toString());

        ReadinessReport report = readiness.readiness();
        assertThat(report.checks())
                .filteredOn(check -> check.name().equals("jobs"))
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.required()).as("a queue problem must not take the API down").isFalse();
                    assertThat(check.result().status()).isEqualTo(ProbeStatus.DEGRADED);
                    assertThat(check.result().detail()).contains("dead-letter");
                });
        assertThat(report.state()).isEqualTo(ReadinessState.DEGRADED);

        // The API keeps answering: readiness degrades, liveness is untouched.
        MvcTestResult ready = mvc.get().uri("/api/v1/health/ready").exchange();
        assertThat(ready).hasStatus(HttpStatus.OK);
        assertThat(ready).bodyJson().extractingPath("$.status").isEqualTo("DEGRADED");
        assertThat(ready).bodyJson().extractingPath("$.checks[?(@.name=='jobs')].status")
                .asArray().containsExactly("DEGRADED");
        assertThat(mvc.get().uri("/api/v1/health/live").exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void aWriteAttemptedOutsideTheUnitOfWorkIsRefusedInsteadOfCommittedUnbound() {
        UUID jobId = enqueue(UNBOUND, JobPayload.empty(), 1);

        assertThat(awaitTerminal(jobId)).isEqualTo("FAILED");
        assertThat(errorCode(jobId)).isEqualTo(JobExecutionException.DEFAULT_ERROR_CODE);
        assertThat(ATTEMPTED_UNBOUND).hasSize(1);
        assertThat(owners.findById(ATTEMPTED_UNBOUND.getFirst()))
                .as("the transaction never began, so nothing was written")
                .isEmpty();
    }

    @Test
    @DisplayName("a REQUIRES_NEW service called from inside the unit of work is refused, not committed "
            + "separately")
    void aRequiresNewTransactionInsideTheUnitOfWorkIsRefused() {
        UUID jobId = enqueue(NESTED, JobPayload.empty(), 1);

        assertThat(awaitTerminal(jobId)).isEqualTo("FAILED");
        assertThat(ATTEMPTED_INSIDE_UNIT_OF_WORK).hasSize(1);
        assertThat(ATTEMPTED_REQUIRES_NEW).hasSize(1);
        // The guard arms one begin and the unit of work consumed it, so the second begin never happened.
        assertThat(owners.findById(ATTEMPTED_REQUIRES_NEW.getFirst()))
                .as("a transaction that would outlive the unit of work never started")
                .isEmpty();
        // And the work that did join the unit of work rolled back with it.
        assertThat(owners.findById(ATTEMPTED_INSIDE_UNIT_OF_WORK.getFirst()))
                .as("the unit of work rolled back")
                .isEmpty();
    }

    @Test
    void aJobsCapabilityIsReadyWhileNothingHasFailed() {
        assertThat(readiness.readiness().checks())
                .filteredOn(check -> check.name().equals("jobs"))
                .singleElement()
                .satisfies(check -> assertThat(check.result().status()).isEqualTo(ProbeStatus.READY));
    }

    private UUID enqueue(String type, JobPayload payload, int maxAttempts) {
        return enqueue(type, type + ":" + UUID.randomUUID(), payload, maxAttempts);
    }

    private UUID enqueue(String type, String key, JobPayload payload, int maxAttempts) {
        JobRequest request = JobRequest.ready(UuidV7.create(clock), type, key, payload, maxAttempts,
                clock.instant());
        return transactions.execute(status -> queue.enqueue(request)).id();
    }

    /**
     * Waits for a terminal status, moving the clock to each scheduled retry as the worker writes it.
     * The wall-clock deadline is the test's own, not the application's: the worker polls in real time
     * while every lease and back-off comparison uses the injected clock.
     */
    private String awaitTerminal(UUID jobId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM background_jobs WHERE id = ?", String.class, jobId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            if ("RETRY".equals(status)) {
                // Only while the job is not running, so no in-flight lease is expired by the jump.
                Instant next = jdbc.queryForObject(
                        "SELECT next_attempt_at FROM background_jobs WHERE id = ?",
                        OffsetDateTime.class, jobId).toInstant();
                if (next.isAfter(clock.instant())) {
                    clock.set(next);
                }
            }
            sleep();
        }
        throw new AssertionError("job " + jobId + " never reached a terminal status");
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static ch.qos.logback.classic.Logger workerLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger("io.nullnull.operations.infrastructure.jobs.JobWorker");
    }

    private int attempts(UUID jobId) {
        return jdbc.queryForObject("SELECT attempt_count FROM background_jobs WHERE id = ?",
                Integer.class, jobId);
    }

    private String errorCode(UUID jobId) {
        return jdbc.queryForObject("SELECT last_error_code FROM background_jobs WHERE id = ?",
                String.class, jobId);
    }
}
