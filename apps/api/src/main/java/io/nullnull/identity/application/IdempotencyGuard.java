package io.nullnull.identity.application;

import io.nullnull.identity.domain.IdempotencyRecord;
import io.nullnull.identity.domain.Owner;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a retryable command at most once per {@code (owner, route template, Idempotency-Key)}
 * (docs/api/README.md §5, docs/architecture/SYSTEM_ARCHITECTURE.md §19.1).
 *
 * <p>Everything happens in one transaction, in the documented lock order: the owner-lifecycle lock
 * first, then the idempotency reservation, then whatever the command locks. Every lock wait in that
 * transaction is bounded by {@code nullnull.idempotency.lock-timeout}, and an expired bound is
 * absorbed by a bounded retry of the whole transaction (see {@link #LOCK_CONTENTION_ATTEMPTS}) rather
 * than published as its own error code. The reservation is a
 * conditional insert followed by a locking read, so two concurrent callers with the same key never
 * both run the command:
 * <ul>
 * <li>The caller that wins the insert holds the new row and runs the command.</li>
 * <li>The caller that loses blocks on the locking read until the winner commits or rolls back. On
 *     commit it finds the stored response and replays it; on rollback it finds an unreserved slot on
 *     its next attempt and runs the command itself.</li>
 * </ul>
 *
 * <p>A stored row with a different request hash is {@code IDEMPOTENCY_KEY_REUSED}; a stored row with
 * the same hash and a response replays that response and never runs the command again, with the
 * normalisation described on {@link GuardedResponse}. A command that
 * fails rolls back its own effect together with the reservation, so the retry is a fresh attempt.
 *
 * <p>Retention is enforced here and not only by a sweep job: a row whose {@code expires_at} has
 * passed is treated as an absent slot, deleted under the lock this transaction already holds and
 * replaced by a fresh reservation. An expired key therefore neither replays a response the contract
 * no longer promises to keep nor blocks a genuinely new request, whether or not a sweeper is running.
 */
@Service
public class IdempotencyGuard {

    /** What a command produced: the HTTP status and the body the caller returns. */
    public record CommandOutcome<T>(int status, T body) {
        public CommandOutcome {
            Objects.requireNonNull(body, "body");
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("status out of range: " + status);
            }
        }
    }

    /**
     * The response to write out, as JSON text.
     *
     * <p>A replayed body is the stored projection as PostgreSQL {@code jsonb} returns it: insignificant
     * whitespace is dropped, duplicate object keys are removed and key order is normalised. It is
     * therefore semantically identical to the first response and byte-stable across replays, but not
     * byte-identical to the very first response. The ERD fixes {@code jsonb} for this column, and a
     * normalised replay is the honest consequence.
     */
    public record GuardedResponse(int status, String body, boolean replayed) {
    }

    /**
     * Shortest replay window this service will run with. Spring's simple duration style reads a bare
     * number as milliseconds, so {@code APP_IDEMPOTENCY_TTL=24} means 24 milliseconds and would
     * disable replay entirely; the floor turns that into a startup failure instead.
     */
    static final Duration MINIMUM_TTL = Duration.ofMinutes(1);

    /**
     * Shortest lock wait this service will run with. Same bare-number trap as the TTL, plus a
     * PostgreSQL rule: {@code lock_timeout = 0} means "wait forever", so a sub-millisecond duration
     * would silently remove the bound it is meant to set.
     */
    static final Duration MINIMUM_LOCK_TIMEOUT = Duration.ofMillis(100);

    private static final Pattern REQUEST_HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * The six characters Jackson writes for U+0000. Split so that the Java source is not itself a
     * unicode escape. PostgreSQL {@code jsonb} rejects U+0000 in any string, and the cast happens
     * after the command has already run.
     */
    private static final String NUL_ESCAPE = "\\" + "u0000";

    /**
     * Reserve attempts: one to replace a row whose retention has expired, one to reserve the fresh
     * slot, and one spare for the case where the retention sweep deletes the row between the insert
     * and the locking read. A fourth would mean something is wrong, not racy.
     */
    private static final int RESERVE_ATTEMPTS = 3;

    /**
     * How many times the whole guarded transaction is attempted when a lock wait expires (BA-003, the
     * public contract BA-002 deferred). Two, so one transient blip is absorbed and the caller's worst
     * case stays two lock waits rather than an unbounded queue.
     *
     * <p>Retrying is safe because a retried attempt is always one where the command had not started.
     * That is ENFORCED here, not assumed: {@code commandStarted} is set immediately before
     * {@code command.get()} and a {@link CommandLockTimeoutException} that arrives with the flag set
     * is rethrown instead of retried. Today every reachable {@code BoundedLockWait.on} site - the
     * owner-lifecycle lock and the idempotency reservation - precedes the command, so the flag is
     * always false when the timeout arrives; the flag is what keeps that true when a command itself
     * reaches identity persistence (BA-012's session deletion soft-deletes the owner row through it,
     * and its own lock timeout would translate to this same exception).
     *
     * <p>There is no sleep between attempts: the failed attempt already waited the full
     * {@code nullnull.idempotency.lock-timeout}, which is longer than any command is allowed to be.
     */
    private static final int LOCK_CONTENTION_ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final OwnerRepository owners;
    private final IdempotencyRecordStore records;
    private final LockWaitLimit lockWaitLimit;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Duration ttl;
    private final Duration lockTimeout;

    public IdempotencyGuard(OwnerRepository owners, IdempotencyRecordStore records,
            LockWaitLimit lockWaitLimit, ObjectMapper json, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${nullnull.idempotency.ttl}") Duration ttl,
            @Value("${nullnull.idempotency.lock-timeout}") Duration lockTimeout) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.records = Objects.requireNonNull(records, "records");
        this.lockWaitLimit = Objects.requireNonNull(lockWaitLimit, "lockWaitLimit");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        // An explicit template rather than @Transactional on execute: the retry has to start a NEW
        // transaction, and a self-invoked @Transactional method would run in the one that just failed.
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.ttl = requireAtLeast("nullnull.idempotency.ttl", ttl, MINIMUM_TTL);
        this.lockTimeout = requireAtLeast("nullnull.idempotency.lock-timeout", lockTimeout,
                MINIMUM_LOCK_TIMEOUT);
    }

    /**
     * @param ownerId          derived from the authenticated session, never from the request body
     * @param routeKey         the route template, for example {@code POST /trips/{tripId}/candidates}
     * @param idempotencyKey   the {@code Idempotency-Key} header value
     * @param requestHash      {@code RequestFingerprint.sha256Hex()} of this exact request
     * @param command          the effect; it runs inside this transaction and only on a fresh slot
     * @param responseProjection what is stored for replay, derived from what is returned. It exists so
     *                         a command can return more than it persists: a deletion receipt can store
     *                         a projection without its status token. The guard performs no
     *                         rehydration: a replay returns the stored projection as it is, so a
     *                         caller that projects MUST itself restore the omitted fields before
     *                         returning the replayed body, or the response will be missing fields its
     *                         schema requires. Pass {@code Function.identity()} to store the whole
     *                         response.
     */
    public <T, S> GuardedResponse execute(UUID ownerId, String routeKey, String idempotencyKey,
            String requestHash, Supplier<CommandOutcome<T>> command, Function<T, S> responseProjection) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(responseProjection, "responseProjection");
        if (!REQUEST_HASH.matcher(Objects.requireNonNull(requestHash, "requestHash")).matches()) {
            throw new IllegalArgumentException("requestHash must be 64 lowercase hex characters");
        }
        // The persisted bounds are reached through client input, so a malformed value is a rejected
        // request and not a failed insert. Neither message repeats the value it rejects.
        requireLength(routeKey, 1, IdempotencyRecord.ROUTE_KEY_MAX_LENGTH,
                "The request route is not valid for a retryable command.");
        requireLength(idempotencyKey, IdempotencyRecord.IDEMPOTENCY_KEY_MIN_LENGTH,
                IdempotencyRecord.IDEMPOTENCY_KEY_MAX_LENGTH,
                "The Idempotency-Key header is missing or malformed.");

        // A retry needs a transaction of its own. Joining a caller's transaction means a rollback here
        // has already poisoned theirs, so there is nothing left to retry into and one attempt is all
        // this can honestly offer; the guard is meant to be the outermost boundary of a command.
        int attempts = TransactionSynchronizationManager.isActualTransactionActive()
                ? 1
                : LOCK_CONTENTION_ATTEMPTS;
        // Set before the command runs and never cleared: a timeout raised from inside the command is
        // not a "nothing happened yet" timeout, and re-running it would repeat an effect that started.
        AtomicBoolean commandStarted = new AtomicBoolean();
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> guarded(ownerId, routeKey, idempotencyKey,
                        requestHash, command, responseProjection, commandStarted));
            } catch (CommandLockTimeoutException contention) {
                if (attempt >= attempts || commandStarted.get()) {
                    throw contention;
                }
                // Route template only: never the owner, never the key.
                log.warn("owner command lock contention absorbed route={} attempt={} of {}", routeKey,
                        attempt, attempts);
            }
        }
    }

    private <T, S> GuardedResponse guarded(UUID ownerId, String routeKey, String idempotencyKey,
            String requestHash, Supplier<CommandOutcome<T>> command, Function<T, S> responseProjection,
            AtomicBoolean commandStarted) {
        // Before the first lock: a stuck command must not hold this connection or this owner forever.
        lockWaitLimit.applyToCurrentTransaction(lockTimeout);

        Owner owner = owners.lockAlive(ownerId).orElseThrow(() -> new ApiException(
                ProblemCode.UNAUTHORIZED, "The session owner is no longer active."));

        Instant now = clock.instant();
        IdempotencyRecord reserved = reserve(IdempotencyRecord.reservation(UuidV7.create(clock),
                owner.id(), routeKey, idempotencyKey, requestHash, now, now.plus(ttl)), now);

        if (!reserved.requestHash().equals(requestHash)) {
            throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        if (reserved.completed()) {
            return new GuardedResponse(reserved.responseStatus(), reserved.responseBody(), true);
        }

        commandStarted.set(true);
        CommandOutcome<T> outcome = command.get();
        String response = json.writeValueAsString(outcome.body());
        String stored = json.writeValueAsString(responseProjection.apply(outcome.body()));
        records.complete(reserved.id(), outcome.status(), requireStorable(stored));
        return new GuardedResponse(outcome.status(), response, false);
    }

    private IdempotencyRecord reserve(IdempotencyRecord candidate, Instant now) {
        for (int attempt = 1; attempt <= RESERVE_ATTEMPTS; attempt++) {
            // Whether this caller or an earlier one created the row, the locking read below is what
            // decides: it returns the committed state and holds it for the rest of this transaction.
            records.insertIfAbsent(candidate);
            Optional<IdempotencyRecord> locked = records.lockExisting(candidate.ownerId(),
                    candidate.routeKey(), candidate.idempotencyKey());
            if (locked.isEmpty()) {
                continue;
            }
            IdempotencyRecord existing = locked.get();
            if (existing.expiresAt().isAfter(now)) {
                return existing;
            }
            // Past its retention: the contract no longer promises this response, so the slot counts as
            // absent. Deleting it under the lock already held and re-reserving in the same transaction
            // keeps that decision atomic and independent of any sweep job.
            records.delete(existing.id());
        }
        // Route template only: the key and the owner never reach a log or an exception message.
        throw new IllegalStateException("idempotency reservation could not be taken; the row kept"
                + " vanishing or expiring between insert and lock for " + candidate.routeKey());
    }

    /**
     * The stored projection has to survive the {@code jsonb} cast and the column bound. Both failures
     * would otherwise surface as a driver error after the command has already run, and every retry
     * would fail the same way.
     *
     * <p>The size bound here is the application-facing one, measured on this compact text, and it is
     * the only figure a caller is promised. The column bound measures the re-serialised jsonb, which
     * PostgreSQL can widen past any fixed multiple when the projection carries numbers, so the store
     * translates a violation of that constraint into this same named error rather than letting a
     * driver failure escape (V003__idempotency_records.sql states the derivation and its limit).
     */
    private static String requireStorable(String projection) {
        // The raw check is for a serialiser that writes U+0000 through unescaped; Jackson does not.
        if (projection.indexOf('\0') >= 0 || containsRealNul(projection)) {
            throw new ApiException(ProblemCode.INTERNAL_ERROR,
                    "The command response cannot be stored for replay.");
        }
        if (projection.getBytes(StandardCharsets.UTF_8).length
                > IdempotencyRecord.RESPONSE_BODY_MAX_BYTES) {
            throw new ApiException(ProblemCode.INTERNAL_ERROR,
                    "The command response is too large to store for replay.");
        }
        return projection;
    }

    /**
     * True when {@code json}, a Jackson-serialised document, carries a real U+0000 code point. Only
     * that is unstorable: a string whose VALUE is the six characters {@code \}{@code u0000} is
     * ordinary text, and refusing it would reject a response PostgreSQL stores without complaint.
     *
     * <p>Jackson has no short escape for U+0000, so it always writes those six characters, and it
     * writes a literal backslash as two. The two cases are told apart by what precedes the escape:
     * the backslash that opens a real escape is preceded by an EVEN number of backslashes, because
     * every earlier one is paired off into a literal backslash, while the six characters appearing
     * as text are always preceded by an ODD-numbered run.
     *
     * <p>Pure and package-private on purpose: it is unit-tested in both directions.
     */
    static boolean containsRealNul(String json) {
        for (int at = json.indexOf(NUL_ESCAPE); at >= 0; at = json.indexOf(NUL_ESCAPE, at + 1)) {
            int backslashes = 0;
            for (int before = at - 1; before >= 0 && json.charAt(before) == '\\'; before--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                return true;
            }
        }
        return false;
    }

    private static void requireLength(String value, int min, int max, String detail) {
        // char_length() in PostgreSQL counts code points, so the Java check must too.
        int length = value == null ? -1 : value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw new ApiException(ProblemCode.INVALID_REQUEST, detail);
        }
    }

    private static Duration requireAtLeast(String property, Duration value, Duration minimum) {
        Objects.requireNonNull(value, property + " is required");
        if (value.compareTo(minimum) < 0) {
            throw new IllegalArgumentException(
                    property + " must be at least " + minimum + " but was " + value);
        }
        return value;
    }
}
