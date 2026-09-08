package io.nullnull.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * BA-002 step 3 ("공통 lock 순서") and docs/architecture/SYSTEM_ARCHITECTURE.md §19: an owner deletion
 * must not race a command into resurrecting data.
 *
 * <p>Two real connections, no test transaction. The guard takes the owner-lifecycle lock
 * {@code SELECT ... FOR UPDATE} before it reserves anything, so a soft delete of that owner has to
 * wait for the in-flight command to commit. Without that lock the insert into
 * {@code idempotency_records} would only take the foreign key's {@code FOR KEY SHARE} lock, which does
 * not conflict with the {@code FOR NO KEY UPDATE} of the delete: the delete would slip through while
 * the command is still running.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-002 owner lifecycle lock under real contention")
class OwnerLifecycleLockIT {

    private static final String ROUTE = "POST /trips/{tripId}/candidates";
    private static final int TIMEOUT_SECONDS = 30;

    record Receipt(String requestId) {
    }

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    OwnerRepository owners;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    @Test
    @DisplayName("BA-002-T3 a soft delete waits for the in-flight command and then refuses the next one")
    void aSoftDeleteWaitsForTheGuardedCommandThatHoldsTheOwner() throws Exception {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        CountDownLatch deleteSubmitted = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(2);

        try {
            Future<GuardedResponse> command = threads.submit(() -> guard.execute(owner.id(), ROUTE, key,
                    hashOf("{}"),
                    () -> {
                        runs.incrementAndGet();
                        commandEntered.countDown();
                        await(releaseCommand);
                        return new CommandOutcome<>(201, new Receipt("r-1"));
                    },
                    Function.identity()));
            assertThat(commandEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Integer> softDelete = threads.submit(() -> {
                deleteSubmitted.countDown();
                return jdbc.update("UPDATE owners SET deleted_at = now()"
                        + " WHERE id = ? AND deleted_at IS NULL", owner.id());
            });
            assertThat(deleteSubmitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // The delete is blocked on the owner-lifecycle lock the command holds. Without that lock
            // this update commits in milliseconds and the assertion below fails.
            assertThatThrownBy(() -> softDelete.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(ownerIsAlive(owner.id())).isTrue();

            releaseCommand.countDown();
            assertThat(command.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
            assertThat(softDelete.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isOne();
        } finally {
            releaseCommand.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        // The command that won the race kept its reservation and its effect.
        assertThat(ownerIsAlive(owner.id())).isFalse();
        assertThat(runs).hasValue(1);
        assertThat(recordCount(owner.id(), key)).isOne();

        // A command started after the deletion committed is refused, with nothing reserved.
        String laterKey = idempotencyKey();
        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, laterKey, hashOf("{}"),
                () -> {
                    runs.incrementAndGet();
                    return new CommandOutcome<>(201, new Receipt("r-2"));
                },
                Function.identity()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ProblemCode.UNAUTHORIZED));
        assertThat(runs).hasValue(1);
        assertThat(recordCount(owner.id(), laterKey)).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the command");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the owner lock", interrupted);
        }
    }

    private static String idempotencyKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), body).sha256Hex();
    }

    private boolean ownerIsAlive(UUID ownerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM owners WHERE id = ?", Boolean.class, ownerId));
    }

    private int recordCount(UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records"
                + " WHERE owner_id = ? AND route_key = ? AND idempotency_key = ?",
                Integer.class, ownerId, ROUTE, key);
    }
}
