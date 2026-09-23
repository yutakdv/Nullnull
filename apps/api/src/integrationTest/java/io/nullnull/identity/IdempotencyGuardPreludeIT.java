package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.IdempotencyGuard.Prelude;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-002 (#340): the guard's other entry point, for a command that has to call out of the process before
 * it runs. Its reservation is committed before that call, so for the length of the call the key is held
 * by a row other transactions can see - a state the single-phase guard never leaves behind.
 *
 * <p>Every case drives the guard directly with a synthetic prelude and command; the APPLY that uses it is
 * measured in {@code OptimizeDecisionIT} and {@code OptimizeGatewayFailureIT}, and the cases that wait out a
 * lease or a lock timeout in {@code IdempotencyGuardLeaseIT}, which shortens both. Timing is never used to
 * order two callers: where a second caller has to meet the key while the first is still in its prelude, a
 * raw connection holds the reservation row until the second caller is seen waiting on it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 idempotency guard: commands with a prelude")
class IdempotencyGuardPreludeIT {

    private static final String ROUTE = "POST /optimizations/{runId}/decisions";

    /** A prelude that answers at once. Its bound only sizes the lease, and these tests keep it small. */
    private static final Duration QUICK = Duration.ofMillis(100);

    record Receipt(String asked, String requestId) {
    }

    @Autowired IdempotencyGuard guard;
    @Autowired OwnerRepository owners;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;
    @Value("${nullnull.idempotency.ttl}") Duration ttl;

    private final List<UUID> ownerIds = new ArrayList<>();

    /** Only this class's rows (AGENTS.md rule 6): records first, since they reference their owner without cascade. */
    @AfterEach
    void removeOnlyOwnFixtures() {
        for (UUID ownerId : ownerIds) {
            jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ?", ownerId);
        }
        for (UUID ownerId : ownerIds) {
            jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
        }
    }

    @Test
    @DisplayName("BA-002-T5 a caller with the same key that arrives while the first is still in its prelude replays the first's response without running either")
    void theKeyIsHeldWhileThePreludeRuns() throws Exception {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"proposalId\":\"p-1\",\"decision\":\"APPLY\"}");
        AtomicInteger preludes = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        CountDownLatch inPrelude = new CountDownLatch(1);
        CountDownLatch mayAnswer = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<GuardedResponse> first = callers.submit(() -> guard.execute(owner.id(), ROUTE, key, hash,
                    new Prelude<>(QUICK, () -> {
                        preludes.incrementAndGet();
                        inPrelude.countDown();
                        await(mayAnswer);
                        return "first";
                    }),
                    asked -> {
                        commands.incrementAndGet();
                        return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                    },
                    Function.identity()));
            assertThat(inPrelude.await(30, TimeUnit.SECONDS)).isTrue();

            // Committed before the prelude: this connection sees the key reserved, with no response yet.
            Map<String, Object> reserved = jdbc.queryForMap("SELECT id, response_status FROM idempotency_records"
                    + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?", owner.id(), ROUTE, key);
            assertThat(reserved.get("response_status")).isNull();

            // Held, so the second caller is seen reading this very row while the first is still asking.
            lockRow(holder, (UUID) reserved.get("id"));
            int holderPid = backendPid(holder);
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
            awaitBlockedBy(holderPid);
            // The second caller holds the owner's row while it reads the reservation, so the first cannot
            // finish before it: what it reads is the reservation with no response.
            holder.rollback();
            mayAnswer.countDown();

            GuardedResponse answered = first.get(30, TimeUnit.SECONDS);
            GuardedResponse replayed = second.get(30, TimeUnit.SECONDS);
            assertThat(preludes).as("the prelude ran for the key, once").hasValue(1);
            assertThat(commands).hasValue(1);
            assertThat(answered.replayed()).isFalse();
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.status()).isEqualTo(200);
            assertThat(json.readTree(replayed.body())).isEqualTo(json.readTree(answered.body()));
            assertThat(replayed.body()).contains("first");
        } finally {
            mayAnswer.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("BA-002-T6 a completed command with a prelude keeps its response for the idempotency retention, not for its lease")
    void aCompletedPreludeCommandIsRetainedLikeAnyOther() {
        Owner owner = owner();
        String key = key();
        Instant before = clock.instant();
        guard.execute(owner.id(), ROUTE, key, hashOf("{\"n\":1}"), new Prelude<>(QUICK, () -> "asked"),
                asked -> new CommandOutcome<>(200, new Receipt(asked, "r-1")), Function.identity());
        Instant after = clock.instant();

        // The lease was the prelude's bound plus a few lock waits - seconds. A record that kept it would stop
        // replaying within seconds, and the next request with this key would run the command again.
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM idempotency_records WHERE owner_id = ?"
                + " AND idempotency_key = ?", Timestamp.class, owner.id(), key).toInstant();
        assertThat(expires).isBetween(before.plus(ttl), after.plus(ttl));
    }

    @Test
    @DisplayName("BA-002-T7 a prelude that fails releases the reservation, so the same key runs again at once")
    void aFailedPreludeFreesTheKey() {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":1}");
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hash,
                new Prelude<>(QUICK, () -> {
                    throw new IllegalStateException("the call out failed");
                }),
                asked -> new CommandOutcome<>(200, new Receipt("never", "r-0")), Function.identity()))
                .hasMessage("the call out failed");
        assertThat(recordCount(owner.id(), key)).as("released, not left to its lease").isZero();

        AtomicInteger commands = new AtomicInteger();
        GuardedResponse retried = guard.execute(owner.id(), ROUTE, key, hash, new Prelude<>(QUICK, () -> "asked"),
                asked -> {
                    commands.incrementAndGet();
                    return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                },
                Function.identity());
        assertThat(retried.replayed()).isFalse();
        assertThat(commands).hasValue(1);
    }

    @Test
    @DisplayName("BA-002-T8 a command that fails after its prelude releases the reservation, so the same key runs again at once")
    void aFailedCommandFreesTheKey() {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":2}");
        // Its transaction rolls back, but the reservation was committed before it by the claim, so the
        // rollback alone would leave the key held until the lease ran out.
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hash, new Prelude<>(QUICK, () -> "asked"),
                asked -> {
                    throw new ApiException(ProblemCode.DATA_CHANGED, "refused");
                }, Function.identity()))
                .isInstanceOf(ApiException.class);
        assertThat(recordCount(owner.id(), key)).as("released, not left to its lease").isZero();

        AtomicInteger commands = new AtomicInteger();
        GuardedResponse retried = guard.execute(owner.id(), ROUTE, key, hash, new Prelude<>(QUICK, () -> "asked"),
                asked -> {
                    commands.incrementAndGet();
                    return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                },
                Function.identity());
        assertThat(retried.replayed()).isFalse();
        assertThat(commands).hasValue(1);
    }

    @Test
    @DisplayName("BA-002-T10 a command without a prelude refuses a key held by a live reservation instead of running")
    void aSinglePhaseCommandDoesNotRunOverAHeldKey() {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":1}");
        UUID held = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("INSERT INTO idempotency_records (id, owner_id, route_key, idempotency_key, request_hash,"
                + " created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", held, owner.id(), ROUTE, key, hash,
                Timestamp.from(now), Timestamp.from(now.plusSeconds(60)));

        AtomicInteger commands = new AtomicInteger();
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hash, () -> {
            commands.incrementAndGet();
            return new CommandOutcome<>(200, new Receipt("single", "r-1"));
        }, Function.identity())).isInstanceOf(IllegalStateException.class);

        assertThat(commands).as("the key's command runs once, and the reservation is running it").hasValue(0);
        Map<String, Object> row = jdbc.queryForMap("SELECT id, response_status FROM idempotency_records"
                + " WHERE owner_id = ? AND idempotency_key = ?", owner.id(), key);
        assertThat(row.get("id")).isEqualTo(held);
        assertThat(row.get("response_status")).isNull();
    }

    @Test
    @DisplayName("BA-002-T11 an owner deleted while its command's prelude runs gets no command")
    void anOwnerDeletedDuringThePreludeRunsNothing() {
        Owner owner = owner();
        String key = key();
        AtomicInteger commands = new AtomicInteger();
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{\"n\":1}"),
                new Prelude<>(QUICK, () -> {
                    // Committed on its own, as the deletion command's soft delete is: the claim has already
                    // committed, so nothing this caller holds stands in its way.
                    jdbc.update("UPDATE owners SET deleted_at = ? WHERE id = ?", Timestamp.from(clock.instant()),
                            owner.id());
                    return "asked";
                }),
                asked -> {
                    commands.incrementAndGet();
                    return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
                },
                Function.identity()))
                .isInstanceOf(ApiException.class);
        assertThat(commands).hasValue(0);
        assertThat(recordCount(owner.id(), key)).isZero();
    }

    @Test
    @DisplayName("BA-002-T12 a caller whose reservation was taken over while its prelude ran does not run the command on the new holder's key, and runs it once the key is free")
    void aTakenOverReservationRunsNothingOnTheNewHolder() {
        Owner owner = owner();
        String key = key();
        String hash = hashOf("{\"n\":1}");
        AtomicInteger preludes = new AtomicInteger();
        AtomicInteger commands = new AtomicInteger();
        UUID newHolder = UUID.randomUUID();

        GuardedResponse answered = guard.execute(owner.id(), ROUTE, key, hash, new Prelude<>(QUICK, () -> {
            if (preludes.incrementAndGet() == 1) {
                // What a caller meets when it outlives its lease: someone else has taken the key over. That
                // holder's reservation lasts one more second and is never completed.
                Instant now = clock.instant();
                jdbc.update("DELETE FROM idempotency_records WHERE owner_id = ? AND idempotency_key = ?",
                        owner.id(), key);
                jdbc.update("INSERT INTO idempotency_records (id, owner_id, route_key, idempotency_key,"
                        + " request_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)", newHolder,
                        owner.id(), ROUTE, key, hash, Timestamp.from(now), Timestamp.from(now.plusSeconds(1)));
            }
            return "asked-" + preludes.get();
        }), asked -> {
            commands.incrementAndGet();
            return new CommandOutcome<>(200, new Receipt(asked, "r-1"));
        }, Function.identity());

        // Its command ran once, for the claim it made AFTER the other holder's lease ran out - not over that
        // holder's reservation, which would have completed a key someone else was running.
        assertThat(commands).hasValue(1);
        assertThat(preludes).as("the key was claimed again, so its prelude ran again").hasValue(2);
        assertThat(answered.body()).contains("asked-2");
        Map<String, Object> row = jdbc.queryForMap("SELECT id, response_status FROM idempotency_records"
                + " WHERE owner_id = ? AND idempotency_key = ?", owner.id(), key);
        assertThat(row.get("id")).isNotEqualTo(newHolder);
        assertThat(row.get("response_status")).isEqualTo(200);
    }

    private Owner owner() {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        ownerIds.add(owner.id());
        return owner;
    }

    private static String key() {
        return "prelude-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return RequestFingerprint.of("decideOptimization", Map.of("runId", "run-1"), body, "1").sha256Hex();
    }

    private int recordCount(UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_id = ?"
                + " AND idempotency_key = ?", Integer.class, ownerId, key);
    }

    private static void lockRow(Connection holder, UUID recordId) throws Exception {
        try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM idempotency_records WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, recordId);
            lock.executeQuery().close();
        }
    }

    private void awaitBlockedBy(int holderPid) {
        org.awaitility.Awaitility.await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(10, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity"
                        + " WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class, holderPid) > 0);
    }

    private static int backendPid(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
            pid.next();
            return pid.getInt(1);
        }
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
