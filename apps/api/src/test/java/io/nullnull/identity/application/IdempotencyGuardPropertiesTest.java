package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.identity.domain.Owner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * BA-002: the two duration properties of the guard are floored at startup.
 *
 * <p>Spring's simple duration style reads a bare number as milliseconds, so an operator who writes
 * {@code APP_IDEMPOTENCY_TTL=24} for "24 hours" gets a 24 millisecond replay window and a service
 * that starts happily with idempotency effectively disabled. The floor makes that a startup failure
 * that names the property and the value it received.
 */
@DisplayName("BA-002 idempotency guard configuration floors")
class IdempotencyGuardPropertiesTest {

    private static final Duration VALID_TTL = Duration.ofHours(24);
    private static final Duration VALID_LOCK_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void aBareNumberTtlIsRejectedAtStartup() {
        // What "APP_IDEMPOTENCY_TTL=24" actually binds to.
        assertThatThrownBy(() -> guardWith(Duration.ofMillis(24), VALID_LOCK_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.idempotency.ttl")
                .hasMessageContaining("PT0.024S")
                .hasMessageContaining("PT1M");
    }

    @Test
    void theTtlFloorIsOneMinute() {
        assertThatThrownBy(() -> guardWith(Duration.ofSeconds(59), VALID_LOCK_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guardWith(Duration.ZERO, VALID_LOCK_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guardWith(Duration.ofHours(-1), VALID_LOCK_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> guardWith(Duration.ofMinutes(1), VALID_LOCK_TIMEOUT))
                .doesNotThrowAnyException();
    }

    @Test
    void aBareNumberLockTimeoutIsRejectedAtStartup() {
        // PostgreSQL reads lock_timeout = 0 as "wait forever", so a sub-millisecond value would
        // silently remove the bound: "APP_IDEMPOTENCY_LOCK_TIMEOUT=3" is 3 milliseconds.
        assertThatThrownBy(() -> guardWith(VALID_TTL, Duration.ofMillis(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nullnull.idempotency.lock-timeout")
                .hasMessageContaining("PT0.003S")
                .hasMessageContaining("PT0.1S");
        assertThatThrownBy(() -> guardWith(VALID_TTL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> guardWith(VALID_TTL, Duration.ofMillis(100)))
                .doesNotThrowAnyException();
    }

    @Test
    void bothDurationsAreRequired() {
        assertThatThrownBy(() -> guardWith(null, VALID_LOCK_TIMEOUT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullnull.idempotency.ttl");
        assertThatThrownBy(() -> guardWith(VALID_TTL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nullnull.idempotency.lock-timeout");
    }

    private static IdempotencyGuard guardWith(Duration ttl, Duration lockTimeout) {
        return new IdempotencyGuard(new UnusedOwners(), new UnusedRecords(), new UnusedLockWaitLimit(),
                new ObjectMapper(), Clock.systemUTC(), new UnusedTransactionManager(), ttl, lockTimeout);
    }

    /** The constructor must fail before any collaborator is touched. */
    private static final class UnusedOwners implements OwnerRepository {

        @Override
        public Owner updatePreferences(Owner owner) { throw new UnsupportedOperationException(); }

        @Override
        public Owner create(Owner owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Owner> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Owner> lockAlive(UUID id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedRecords implements IdempotencyRecordStore {

        @Override
        public boolean insertIfAbsent(IdempotencyRecord reservation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IdempotencyRecord> lockExisting(UUID ownerId, String routeKey,
                String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(UUID recordId, int responseStatus, String responseBodyJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(UUID recordId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteExpired(Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedLockWaitLimit implements LockWaitLimit {

        @Override
        public void applyToCurrentTransaction(Duration timeout) {
            throw new UnsupportedOperationException();
        }
    }

    /** The guard owns its transaction boundary so a lock-timeout retry starts a new one (BA-003). */
    private static final class UnusedTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit(TransactionStatus status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rollback(TransactionStatus status) {
            throw new UnsupportedOperationException();
        }
    }
}
