package io.nullnull.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.nullnull.identity.application.IdempotencyGuard.CommandOutcome;
import io.nullnull.identity.application.IdempotencyGuard.GuardedResponse;
import io.nullnull.identity.application.IdempotencyGuard.Prelude;
import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.identity.domain.Owner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * The waiting of a command with a prelude (#340), in an interleaving the database cannot be made to
 * produce on demand: a caller that held the key, lost it to another caller when its lease ran out, and
 * then met a lock timeout on the owner's row while claiming again.
 *
 * <p>The collaborators are scripted rather than real so the order is exact. What is measured is only the
 * guard's own bookkeeping - which deadline a wait is judged against - and that bookkeeping does not
 * depend on how the rows are stored.
 */
@DisplayName("BA-003 idempotency guard: how long a caller with a prelude waits")
class IdempotencyGuardWaitTest {

    private static final String ROUTE = "POST /optimizations/{runId}/decisions";
    private static final String KEY = "wait-" + "0123456789abcdef";
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("BA-003-T15 a caller whose own reservation was taken over keeps waiting for the new holder when its next claim meets a lock timeout")
    void aWaitDoesNotEndOnADeadlineFromBeforeTheCallerHeldTheKey() {
        Clock clock = Clock.systemUTC();
        Owner owner = Owner.anonymous(UUID.randomUUID(), "ko-KR", "Asia/Seoul", clock.instant());
        ScriptedRecords records = new ScriptedRecords();
        ScriptedOwners owners = new ScriptedOwners(owner, records, clock);
        IdempotencyGuard guard = new IdempotencyGuard(owners, records, timeout -> { }, new ObjectMapper(), clock,
                new NoTransactions(), Duration.ofHours(24), Duration.ofMillis(100));

        // Someone else's reservation, about to lapse. The caller's first wait is judged against it.
        Instant now = clock.instant();
        records.row = IdempotencyRecord.reservation(UUID.randomUUID(), owner.id(), ROUTE, KEY, HASH,
                now.minusSeconds(1), now.plusMillis(30));

        AtomicInteger commands = new AtomicInteger();
        GuardedResponse response = guard.execute(owner.id(), ROUTE, KEY, HASH,
                new Prelude<>(Duration.ofMillis(100), () -> {
                    // The caller holds the key now. Its prelude runs past the deadline of the wait it began
                    // with, and meanwhile another caller takes the key over.
                    pause(Duration.ofMillis(400));
                    Instant later = clock.instant();
                    records.row = IdempotencyRecord.reservation(UUID.randomUUID(), owner.id(), ROUTE, KEY, HASH,
                            later, later.plusSeconds(60));
                    owners.nextClaimTimesOut = true;
                    return "asked";
                }),
                asked -> {
                    commands.incrementAndGet();
                    return new CommandOutcome<>(200, "{\"by\":\"this caller\"}");
                },
                Function.identity());

        assertThat(commands).as("the key was no longer this caller's, so it ran no command").hasValue(0);
        assertThat(response.replayed()).isTrue();
        assertThat(response.body()).contains("the new holder");
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /**
     * The owner's row. One claim after the prelude meets a lock timeout, as when another command holds the
     * row; while it does, the new holder of the key finishes and stores its response.
     */
    private static final class ScriptedOwners implements OwnerRepository {

        private final Owner owner;
        private final ScriptedRecords records;
        private final Clock clock;
        volatile boolean nextClaimTimesOut;
        private int locksAfterTheTimeout;

        ScriptedOwners(Owner owner, ScriptedRecords records, Clock clock) {
            this.owner = owner;
            this.records = records;
            this.clock = clock;
        }

        @Override
        public Optional<Owner> lockAlive(UUID id) {
            if (nextClaimTimesOut && locksAfterTheTimeout++ == 1) {
                // The first lock after the prelude is the command transaction, which finds the key taken
                // over. The second is the claim that follows it, and that one waits out the bound.
                IdempotencyRecord taken = records.row;
                records.row = new IdempotencyRecord(taken.id(), taken.ownerId(), taken.routeKey(),
                        taken.idempotencyKey(), taken.requestHash(), 200, "{\"by\":\"the new holder\"}",
                        taken.createdAt(), clock.instant().plus(Duration.ofHours(24)));
                throw new CommandLockTimeoutException("scripted: the owner's row is held", null);
            }
            return Optional.of(owner);
        }

        @Override
        public Optional<Owner> lockAny(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Owner> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Owner create(Owner owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Owner updatePreferences(Owner owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDeleted(UUID id, Instant deletedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void scrubDeleted(UUID id) {
            throw new UnsupportedOperationException();
        }
    }

    /** One key's row, with the store's semantics: a conditional insert, a read, and deletes by id. */
    private static final class ScriptedRecords implements IdempotencyRecordStore {

        volatile IdempotencyRecord row;

        @Override
        public boolean insertIfAbsent(IdempotencyRecord reservation) {
            if (row != null) {
                return false;
            }
            row = reservation;
            return true;
        }

        @Override
        public Optional<IdempotencyRecord> lockExisting(UUID ownerId, String routeKey, String idempotencyKey) {
            return Optional.ofNullable(row);
        }

        @Override
        public Optional<IdempotencyRecord> find(UUID ownerId, String routeKey, String idempotencyKey) {
            return Optional.ofNullable(row);
        }

        @Override
        public void complete(UUID recordId, int responseStatus, String responseBodyJson) {
            throw new UnsupportedOperationException("a command with a prelude stores its retention too");
        }

        @Override
        public void complete(UUID recordId, int responseStatus, String responseBodyJson, Instant expiresAt) {
            IdempotencyRecord held = row;
            row = new IdempotencyRecord(held.id(), held.ownerId(), held.routeKey(), held.idempotencyKey(),
                    held.requestHash(), responseStatus, responseBodyJson, held.createdAt(), expiresAt);
        }

        @Override
        public void release(UUID recordId) {
            if (row != null && row.id().equals(recordId) && !row.completed()) {
                row = null;
            }
        }

        @Override
        public void delete(UUID recordId) {
            if (row != null && row.id().equals(recordId)) {
                row = null;
            }
        }

        @Override
        public int deleteExpired(Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    /** Runs each unit of work without a database: what is measured here happens between them. */
    private static final class NoTransactions implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
