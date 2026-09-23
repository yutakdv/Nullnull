package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nullnull.identity.application.CommandLockTimeoutException;
import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.IdempotencyGuard.Prelude;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The parts of a command with a prelude that are measured in time: a reservation's lease running out, and a
 * claim that cannot reach the owner's row (#340).
 *
 * <p>The lock timeout is shortened so each case takes about a second instead of the production figures'
 * tens of seconds. The lease is derived from it - the prelude's bound plus five lock timeouts
 * ({@code IdempotencyGuard.RESERVATION_MARGIN}) - so with a 0.1s prelude bound a reservation here holds its
 * key for 1.1s.
 */
@SpringBootTest(properties = "nullnull.idempotency.lock-timeout=PT0.2S")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 BA-003 idempotency guard: leases and contention of commands with a prelude")
class IdempotencyGuardLeaseIT {

    private static final String ROUTE = "POST /optimizations/{runId}/decisions";
    private static final Duration QUICK = Duration.ofMillis(100);
    private static final String CONTENTION = "owner command lock contention while claiming";

    record Receipt(String asked, String requestId) {
    }

    @Autowired IdempotencyGuard guard;
    @Autowired OwnerRepository owners;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;

    private final List<UUID> ownerIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> guardLog;

    @BeforeEach
    void captureTheGuardLog() {
        guardLog = new ListAppender<>();
        guardLog.start();
        guardLogger().addAppender(guardLog);
    }

    /** Only this class's rows (AGENTS.md rule 6): records first, since they reference their owner without cascade. */
    @AfterEach
    void removeOnlyOwnFixtures() {
        guardLogger().detachAppender(guardLog);
        guardLog.stop();
        for (UUID ownerId : ownerIds) {
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", ownerId);
        }
        for (UUID ownerId : ownerIds) {
            jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
        }
    }

    @Test
    @DisplayName("BA-002-T9 a reservation whose holder is still in its prelude when its lease runs out is taken over by a caller waiting on it")
    void aLapsedLeaseIsTakenOver() throws Exception {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":1}");
        AtomicInteger preludes = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        CountDownLatch firstAsking = new CountDownLatch(1);
        CountDownLatch firstMayAnswer = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            // The reservation is the guard's own, with the lease the guard gives it. Nothing in this test
            // writes an expiry: a guard that reserved for the full retention would keep the second caller
            // waiting past every bound below.
            Future<GuardedResponse> first = callers.submit(() -> guard.execute(owner.id(), ROUTE, key, hash,
                    new Prelude<>(QUICK, () -> {
                        preludes.incrementAndGet();
                        firstAsking.countDown();
                        await(firstMayAnswer);
                        return "first";
                    }),
                    asked -> {
                        commands.incrementAndGet();
                        return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                    },
                    Function.identity()));
            assertThat(firstAsking.await(30, TimeUnit.SECONDS)).isTrue();

            Future<GuardedResponse> second = callers.submit(() -> guard.execute(owner.id(), ROUTE, key, hash,
                    new Prelude<>(QUICK, () -> {
                        preludes.incrementAndGet();
                        return "second";
                    }),
                    asked -> {
                        commands.incrementAndGet();
                        return new CommandOutcome<>(200, new Receipt(asked, "r-2"));
                    },
                    Function.identity()));
            // The first caller is still in its prelude - its latch is closed - and the second one finishes.
            GuardedResponse tookOver = second.get(30, TimeUnit.SECONDS);
            assertThat(tookOver.replayed()).isFalse();
            assertThat(tookOver.body()).contains("second");

            // Let the first one go on. The key is no longer its own, so it runs no command and replays what
            // the key did - a caller that outlived its lease is told the truth, not its own guess.
            firstMayAnswer.countDown();
            GuardedResponse outlived = first.get(30, TimeUnit.SECONDS);
            assertThat(outlived.replayed()).isTrue();
            assertThat(outlived.body()).contains("second");
            assertThat(commands).as("the key's command ran once, for the caller that held the key").hasValue(1);
            assertThat(preludes).as("both callers held the key in turn, so both asked").hasValue(2);
        } finally {
            firstMayAnswer.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("BA-003-T13 a command with a prelude whose claim cannot get the owner's row gives up within its bound and reserves nothing")
    void aClaimThatNeverGetsTheOwnerGivesUp() throws Exception {
        Owner owner = owner();
        String key = key();
        AtomicInteger preludes = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        long started = System.nanoTime();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockOwner(holder, owner.id());
            assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{\"n\":1}"),
                    new Prelude<>(QUICK, () -> {
                        preludes.incrementAndGet();
                        return "asked";
                    }),
                    asked -> {
                        commands.incrementAndGet();
                        return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                    },
                    Function.identity()))
                    .isInstanceOf(CommandLockTimeoutException.class);
            holder.rollback();
        }
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(preludes).hasValue(0);
        assertThat(commands).hasValue(0);
        assertThat(recordCount(owner.id(), key)).isZero();
        // Bounded by its own lease (1.1s here) and the claim that was waiting when the lease ran out.
        assertThat(tookMillis).as("gave up rather than waiting on").isLessThan(10_000);
        assertThat(contentionLines()).as("the claims that timed out were read as a key in use").isNotEmpty();
    }

    @Test
    @DisplayName("BA-003-T14 a command with a prelude whose claim waits out a lock timeout on the owner's row runs once the row is free")
    void claimContentionThatClearsIsAbsorbed() throws Exception {
        Owner owner = owner();
        String key = key();
        AtomicInteger commands = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockOwner(holder, owner.id());
            Future<GuardedResponse> answered = caller.submit(() -> guard.execute(owner.id(), ROUTE, key,
                    hashOf("{\"n\":1}"), new Prelude<>(QUICK, () -> "asked"), asked -> {
                        commands.incrementAndGet();
                        return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                    }, Function.identity()));
            // One claim has waited the whole lock timeout and given up: the contention really happened.
            org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(10, TimeUnit.MILLISECONDS)
                    .until(() -> !contentionLines().isEmpty());
            holder.rollback();

            GuardedResponse response = answered.get(30, TimeUnit.SECONDS);
            assertThat(response.replayed()).isFalse();
            assertThat(response.status()).isEqualTo(200);
        } finally {
            caller.shutdownNow();
        }
        assertThat(commands).as("the claims that gave up reserved nothing, so the command ran once").hasValue(1);
    }

    @Test
    @DisplayName("BA-003-T15 a caller waiting on a key does not give up while a live reservation holds it, even after the key changes hands")
    void aWaiterFollowsTheKeyToItsNextHolder() throws Exception {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":1}");
        // A holder that will never finish: its reservation lapses in half a second.
        Instant seeded = clock.instant();
        Instant lapses = seeded.plusMillis(500);
        jdbc.update("INSERT INTO idempotency_records (id, owner_id, route_key, idempotency_key, request_hash,"
                + " created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), owner.id(), ROUTE, key,
                hash, Timestamp.from(seeded), Timestamp.from(lapses));

        AtomicInteger preludes = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        CountDownLatch nextHolderAsking = new CountDownLatch(1);
        CountDownLatch nextHolderMayAnswer = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            List<Future<GuardedResponse>> waiting = new ArrayList<>();
            for (int caller = 0; caller < 2; caller++) {
                waiting.add(callers.submit(() -> guard.execute(owner.id(), ROUTE, key, hash,
                        new Prelude<>(QUICK, () -> {
                            preludes.incrementAndGet();
                            nextHolderAsking.countDown();
                            await(nextHolderMayAnswer);
                            return "next";
                        }),
                        asked -> {
                            commands.incrementAndGet();
                            return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                        },
                        Function.identity())));
            }
            // One of the two has taken the key over and is asking. The other waited on the first holder with a
            // bound of that holder's lease; it is now past that bound, still waiting on the next holder.
            assertThat(nextHolderAsking.await(30, TimeUnit.SECONDS)).isTrue();
            org.awaitility.Awaitility.await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(20, TimeUnit.MILLISECONDS)
                    .until(() -> clock.instant().isAfter(lapses.plusMillis(700)));
            nextHolderMayAnswer.countDown();

            GuardedResponse one = waiting.get(0).get(30, TimeUnit.SECONDS);
            GuardedResponse two = waiting.get(1).get(30, TimeUnit.SECONDS);
            assertThat(List.of(one.replayed(), two.replayed())).containsExactlyInAnyOrder(true, false);
            // Semantically equal: a replayed body is the stored jsonb, normalised (GuardedResponse says so).
            assertThat(json.readTree(one.body())).isEqualTo(json.readTree(two.body()));
            assertThat(commands).hasValue(1);
            assertThat(preludes).as("only the caller that took the key over asked").hasValue(1);
        } finally {
            nextHolderMayAnswer.countDown();
            callers.shutdownNow();
        }
    }

    private Owner owner() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        ownerIds.add(owner.id());
        return owner;
    }

    private static String key() {
        return "lease-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return RequestFingerprint.of("decideOptimization", Map.of("runId", "run-1"), body, "1").sha256Hex();
    }

    private int recordCount(UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_id = ?"
                + " AND idempotency_key = ?", Integer.class, ownerId, key);
    }

    private static void lockOwner(Connection holder, UUID ownerId) throws Exception {
        try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM owners WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, ownerId);
            lock.executeQuery().close();
        }
    }

    private List<String> contentionLines() {
        return guardLog.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(CONTENTION))
                .toList();
    }

    private static ch.qos.logback.classic.Logger guardLogger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(IdempotencyGuard.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released the prelude");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
