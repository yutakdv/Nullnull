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
import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.testsupport.OwnerFixtures;
import io.nullnull.testsupport.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * BA-003 step 4, the decision BA-002 deferred: an expired owner-lock wait is absorbed by a bounded
 * retry inside the guard instead of becoming a published error code.
 *
 * <p>The lock wait bound is deliberately short here so two full attempts are quick, and the moment the
 * blocking transaction releases is driven by the guard's own log line rather than by a sleep, so
 * neither test depends on timing.
 */
@SpringBootTest(properties = "nullnull.idempotency.lock-timeout=PT0.5S")
@Import(TestcontainersConfiguration.class)
@DisplayName("BA-003 owner command lock contention")
class OwnerCommandContentionIT {

    private static final String ROUTE = "POST /trips/{tripId}/candidates";
    private static final String ABSORBED = "owner command lock contention absorbed";
    private static final int TIMEOUT_SECONDS = 30;

    @Autowired
    IdempotencyGuard guard;

    @Autowired
    OwnerRepository owners;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    private ListAppender<ILoggingEvent> guardLog;

    @BeforeEach
    void captureTheGuardLog() {
        guardLog = new ListAppender<>();
        guardLog.start();
        guardLogger().addAppender(guardLog);
    }

    @AfterEach
    void detachTheGuardLog() {
        guardLogger().detachAppender(guardLog);
        guardLog.stop();
    }

    @Test
    @DisplayName("contention that clears within the budget is absorbed and the command still runs")
    void transientContentionIsAbsorbedAndTheCommandRuns() throws Exception {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        AtomicInteger runs = new AtomicInteger();
        ExecutorService threads = Executors.newSingleThreadExecutor();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockOwner(holder, owner.id());

            Future<GuardedResponse> command = threads.submit(() -> guard.execute(owner.id(), ROUTE, key,
                    hashOf("{}"), () -> {
                        runs.incrementAndGet();
                        return new CommandOutcome<>(201, new Receipt("r-1"));
                    }, Function.identity()));

            // Release only once the first attempt has actually given up, so the retry - and not a lucky
            // first attempt - is what completes the command.
            awaitAbsorbedLine();
            holder.rollback();

            assertThat(command.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            threads.shutdownNow();
            assertThat(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(runs).as("the absorbed attempt reserved nothing, so the command ran exactly once")
                .hasValue(1);
        assertThat(recordCount(owner.id(), key)).isOne();
        assertThat(absorbedLines()).hasSize(1);
    }

    @Test
    @DisplayName("contention that never clears gives up after the bounded attempts, reserving nothing")
    void exhaustedContentionGivesUpWithoutReservingAnything() throws Exception {
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        AtomicInteger runs = new AtomicInteger();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockOwner(holder, owner.id());

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"),
                    () -> {
                        runs.incrementAndGet();
                        return new CommandOutcome<>(201, new Receipt("r-1"));
                    }, Function.identity()))
                    .isInstanceOf(CommandLockTimeoutException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // Two attempts, each bounded by the configured 500ms: one attempt could not take this long,
            // and an unbounded retry loop would never return.
            assertThat(waited).isGreaterThan(Duration.ofMillis(900))
                    .isLessThan(Duration.ofSeconds(15));
            holder.rollback();
        }

        assertThat(runs).as("the command never ran, so nothing has to be undone").hasValue(0);
        assertThat(recordCount(owner.id(), key)).isZero();
        assertThat(absorbedLines()).as("exactly one retry, not an open-ended loop").hasSize(1);
    }

    @Test
    @DisplayName("a lock timeout raised by the command ITSELF is never retried")
    void aTimeoutFromInsideTheCommandIsNotRetried() {
        // BoundedLockWait.on translates SQLSTATE 55P03 for every statement in identity persistence,
        // not only for the two that precede the command. BA-012's session-deletion command
        // soft-deletes the owner row through that same persistence, so its own lock timeout arrives
        // as this exception from INSIDE the command - after the reservation, with the effect already
        // started. Retrying that would re-run a command that began.
        Owner owner = OwnerFixtures.createAnonymous(owners, clock);
        String key = idempotencyKey();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> guard.execute(owner.id(), ROUTE, key, hashOf("{}"), () -> {
            runs.incrementAndGet();
            throw new CommandLockTimeoutException(
                    "Timed out waiting for a row lock while the command was running.", null);
        }, Function.identity())).isInstanceOf(CommandLockTimeoutException.class);

        assertThat(runs).as("a command that already started runs exactly once").hasValue(1);
        assertThat(recordCount(owner.id(), key)).isZero();
        assertThat(absorbedLines()).as("the retry budget does not apply once the command began")
                .isEmpty();
    }

    record Receipt(String requestId) {
    }

    private void awaitAbsorbedLine() throws InterruptedException {
        for (int attempt = 0; attempt < TIMEOUT_SECONDS * 20; attempt++) {
            if (!absorbedLines().isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the guard never logged an absorbed lock contention");
    }

    private List<String> absorbedLines() {
        return guardLog.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(ABSORBED))
                .toList();
    }

    private static void lockOwner(Connection holder, UUID ownerId) throws Exception {
        try (PreparedStatement lock = holder.prepareStatement(
                "SELECT id FROM owners WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, ownerId);
            try (ResultSet locked = lock.executeQuery()) {
                assertThat(locked.next()).isTrue();
            }
        }
    }

    private static String idempotencyKey() {
        return "idem-" + UUID.randomUUID();
    }

    private static String hashOf(String body) {
        return RequestFingerprint.of(ROUTE, Map.of("tripId", "t-1"), body).sha256Hex();
    }

    private int recordCount(UUID ownerId, String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_records"
                + " WHERE owner_id = ? AND idempotency_key = ?", Integer.class, ownerId, key);
    }

    private static ch.qos.logback.classic.Logger guardLogger() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(IdempotencyGuard.class);
    }
}
