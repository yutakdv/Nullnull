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
     * Work a command needs done before it runs and outside every transaction: a call out of the process
     * whose answer the command is judged against (#340). See the {@code execute} overload that takes one.
     *
     * @param bound the longest {@code call} can take. It sizes how long the key is held for the caller
     *              running it - a liveness bound, not a promise (see {@link #RESERVATION_MARGIN})
     */
    public record Prelude<P>(Duration bound, Supplier<P> call) {
        public Prelude {
            Objects.requireNonNull(bound, "bound");
            Objects.requireNonNull(call, "call");
            if (bound.isNegative() || bound.isZero()) {
                throw new IllegalArgumentException("a prelude's bound must be positive");
            }
        }
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

    /**
     * How many lock timeouts, beyond its prelude's own bound, a two-phase reservation holds the key for: each
     * command attempt can wait out two locks (the owner's row, then the reservation), there are
     * {@link #LOCK_CONTENTION_ATTEMPTS} attempts, and one more stands for what neither counts - the command
     * itself, DNS resolution, scheduling, clock skew between tasks. It is a liveness bound. A lease that runs
     * out early costs a second prelude call, never a second command: the command transaction re-checks,
     * under the owner's lock, that the reservation is still the caller's (#340).
     */
    private static final int RESERVATION_MARGIN = 2 * LOCK_CONTENTION_ATTEMPTS + 1;

    /**
     * How often a caller waiting on another's reservation looks again: first soon, since the prelude it
     * waits on is usually one quick call, then less often, since a slow one is slow for a while. Engineering
     * values, not contract figures; each look is one unlocked read.
     */
    private static final Duration FIRST_PAUSE = Duration.ofMillis(20);

    private static final Duration LAST_PAUSE = Duration.ofMillis(200);

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

    /**
     * A command with a {@link Prelude}: at most once per key, and its prelude once per key too (#340).
     *
     * <p>{@link #execute(UUID, String, String, String, Supplier, Function)} cannot hold a key across a
     * call out of the process. Its reservation lives in the transaction that runs the command, and that
     * transaction holds the owner's row - so a prelude run inside it would hold every request of that
     * owner for as long as the call took (#271), and a prelude run before it runs once per REQUEST. Two
     * requests carrying one key then each made the call and could each be answered differently: one told
     * the command failed and changed nothing, the other that it succeeded.
     *
     * <p>So the reservation is committed first, and only the caller that made it runs the prelude and the
     * command:
     * <ol>
     * <li><b>Claim</b>, one short transaction: the owner's row, then the reservation. A completed record
     *     replays and another request's hash is {@code IDEMPOTENCY_KEY_REUSED}, exactly as in the other
     *     overload. A free slot - absent, or held by a reservation whose lease has run out - is taken for
     *     {@code prelude.bound} plus {@link #RESERVATION_MARGIN} lock waits.</li>
     * <li><b>Prelude</b>, with no transaction open and no connection held.</li>
     * <li><b>Command</b>, in the documented lock order - owner, reservation, then whatever the command
     *     locks - and only after checking the reservation is still this caller's: a caller that outlived
     *     its lease may find the key taken over, and then it runs nothing and waits like any other.</li>
     * </ol>
     * A prelude or command that fails releases the reservation before the failure propagates, so a failed
     * command still records nothing and the same key can be sent again at once.
     *
     * <p>A caller that finds the key held waits without locking anything: it re-reads the committed record
     * and claims again only once that record is completed, released or out of lease. Its wait is bounded by
     * the lease of the reservation it is waiting on, and starts over when a different reservation holds the
     * key - a holder that took the key over when the first one's lease ran out - so a caller never gives up
     * while a live reservation is still answering for the key. What it does give up on is a claim that
     * cannot even reach the key: the owner's row held past that bound, which ends like all contention in
     * this guard, as {@link CommandLockTimeoutException}.
     */
    public <P, T, S> GuardedResponse execute(UUID ownerId, String routeKey, String idempotencyKey,
            String requestHash, Prelude<P> prelude, Function<P, CommandOutcome<T>> command,
            Function<T, S> responseProjection) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(prelude, "prelude");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(responseProjection, "responseProjection");
        if (!REQUEST_HASH.matcher(Objects.requireNonNull(requestHash, "requestHash")).matches()) {
            throw new IllegalArgumentException("requestHash must be 64 lowercase hex characters");
        }
        requireLength(routeKey, 1, IdempotencyRecord.ROUTE_KEY_MAX_LENGTH,
                "The request route is not valid for a retryable command.");
        requireLength(idempotencyKey, IdempotencyRecord.IDEMPOTENCY_KEY_MIN_LENGTH,
                IdempotencyRecord.IDEMPOTENCY_KEY_MAX_LENGTH,
                "The Idempotency-Key header is missing or malformed.");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The prelude would run inside the caller's transaction - the one thing this overload is for
            // avoiding - and a claim committed by a nested transaction could outlive the caller's rollback.
            throw new IllegalStateException("a command with a prelude is the outermost boundary; its"
                    + " prelude runs outside any transaction");
        }
        Duration lease = prelude.bound().plus(lockTimeout.multipliedBy(RESERVATION_MARGIN));
        long waitUntil = 0;
        boolean waiting = false;
        UUID waitingOn = null;
        while (true) {
            Claim claim = claim(ownerId, routeKey, idempotencyKey, requestHash, lease);
            if (claim.replay() != null) {
                return claim.replay();
            }
            if (claim.reservation() == null) {
                if (claim.heldBy() != null && !claim.heldBy().equals(waitingOn)) {
                    // A reservation this caller has not waited on yet: its own lease bounds the wait.
                    waiting = true;
                    waitingOn = claim.heldBy();
                    waitUntil = System.nanoTime() + untilNanos(claim.heldUntil()) + LAST_PAUSE.toNanos();
                } else if (!waiting) {
                    // The claim could not reach the key at all. Its own lease is the bound it waits.
                    waiting = true;
                    waitUntil = System.nanoTime() + lease.toNanos() + LAST_PAUSE.toNanos();
                } else if (System.nanoTime() - waitUntil >= 0) {
                    // Route template only: never the owner, never the key.
                    throw new CommandLockTimeoutException("A command with this key is still in flight; route "
                            + routeKey, null);
                }
                awaitRelease(ownerId, routeKey, idempotencyKey, waitUntil);
                continue;
            }
            UUID reservation = claim.reservation();
            P prepared;
            try {
                prepared = prelude.call().get();
            } catch (RuntimeException | Error failure) {
                release(reservation, routeKey, failure);
                throw failure;
            }
            Optional<GuardedResponse> done;
            try {
                done = runReserved(ownerId, routeKey, idempotencyKey, reservation, prepared, command,
                        responseProjection);
            } catch (RuntimeException | Error failure) {
                release(reservation, routeKey, failure);
                throw failure;
            }
            if (done.isPresent()) {
                return done.get();
            }
            // Out of lease and taken over by another caller: wait for that one like anyone else would.
        }
    }

    /**
     * What a claim found: a response to replay, a reservation of this caller's, or a key held by another
     * caller's reservation ({@code heldBy}, live until {@code heldUntil}). All three empty: the claim could
     * not reach the key.
     */
    private record Claim(GuardedResponse replay, UUID reservation, UUID heldBy, Instant heldUntil) {
    }

    /**
     * One claim, in its own transaction. A lock wait that expires here is read as "held": the owner's row is
     * taken by the command finishing this key, or by another of the owner's commands, and in both cases
     * nothing was reserved and waiting is the answer.
     */
    private Claim claim(UUID ownerId, String routeKey, String idempotencyKey, String requestHash,
            Duration lease) {
        try {
            return transactions.execute(status -> {
                lockWaitLimit.applyToCurrentTransaction(lockTimeout);
                Owner owner = owners.lockAlive(ownerId).orElseThrow(SessionService::unauthorized);
                Instant now = clock.instant();
                IdempotencyRecord candidate = IdempotencyRecord.reservation(UuidV7.create(clock), owner.id(),
                        routeKey, idempotencyKey, requestHash, now, now.plus(lease));
                IdempotencyRecord reserved = reserve(candidate, now);
                if (!reserved.requestHash().equals(requestHash)) {
                    throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                            "This Idempotency-Key was already used for a different request.");
                }
                if (reserved.completed()) {
                    return new Claim(new GuardedResponse(reserved.responseStatus(), reserved.responseBody(),
                            true), null, null, null);
                }
                if (reserved.id().equals(candidate.id())) {
                    return new Claim(null, candidate.id(), null, null);
                }
                return new Claim(null, null, reserved.id(), reserved.expiresAt());
            });
        } catch (CommandLockTimeoutException contention) {
            log.warn("owner command lock contention while claiming route={}; waiting", routeKey);
            return new Claim(null, null, null, null);
        }
    }

    /**
     * Re-reads the key, unlocked, until the record it finds is completed, released or out of lease, or the
     * wait is over. It only decides when to claim again; the claim decides what the caller gets.
     */
    private void awaitRelease(UUID ownerId, String routeKey, String idempotencyKey, long waitUntil) {
        Duration pause = FIRST_PAUSE;
        while (System.nanoTime() - waitUntil < 0) {
            try {
                Thread.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CommandLockTimeoutException("Interrupted while waiting for a command with this key;"
                        + " route " + routeKey, interrupted);
            }
            pause = pause.multipliedBy(2).compareTo(LAST_PAUSE) > 0 ? LAST_PAUSE : pause.multipliedBy(2);
            Optional<IdempotencyRecord> seen = records.find(ownerId, routeKey, idempotencyKey);
            if (seen.isEmpty() || seen.get().completed() || !seen.get().expiresAt().isAfter(clock.instant())) {
                return;
            }
        }
    }

    /**
     * The command, in its own transaction, if the reservation is still this caller's; empty when it is not.
     *
     * <p>That check is what makes a lease safe to run out. It is made under the owner's lock and the
     * reservation's, in the documented order, so no other caller can take the key over between the check
     * and the write that completes it.
     */
    private <P, T, S> Optional<GuardedResponse> runReserved(UUID ownerId, String routeKey,
            String idempotencyKey, UUID reservation, P prepared, Function<P, CommandOutcome<T>> command,
            Function<T, S> responseProjection) {
        AtomicBoolean commandStarted = new AtomicBoolean();
        for (int attempt = 1; ; attempt++) {
            try {
                return transactions.execute(status -> {
                    lockWaitLimit.applyToCurrentTransaction(lockTimeout);
                    owners.lockAlive(ownerId).orElseThrow(SessionService::unauthorized);
                    Optional<IdempotencyRecord> held = records.lockExisting(ownerId, routeKey, idempotencyKey);
                    if (held.isEmpty() || !held.get().id().equals(reservation) || held.get().completed()) {
                        return Optional.<GuardedResponse>empty();
                    }
                    commandStarted.set(true);
                    CommandOutcome<T> outcome = command.apply(prepared);
                    String response = json.writeValueAsString(outcome.body());
                    String stored = json.writeValueAsString(responseProjection.apply(outcome.body()));
                    // The expiry was the reservation's lease; from here it is the response's retention.
                    records.complete(reservation, outcome.status(), requireStorable(stored),
                            clock.instant().plus(ttl));
                    return Optional.of(new GuardedResponse(outcome.status(), response, false));
                });
            } catch (CommandLockTimeoutException contention) {
                if (attempt >= LOCK_CONTENTION_ATTEMPTS || commandStarted.get()) {
                    throw contention;
                }
                log.warn("owner command lock contention absorbed route={} attempt={} of {}", routeKey,
                        attempt, LOCK_CONTENTION_ATTEMPTS);
            }
        }
    }

    /**
     * Frees a reservation whose prelude or command failed, so the key can be sent again at once. A release
     * that itself fails is attached to the original failure and logged, and the reservation then ends with
     * its lease instead - later, never not at all.
     */
    private void release(UUID reservation, String routeKey, Throwable failure) {
        try {
            transactions.executeWithoutResult(status -> {
                lockWaitLimit.applyToCurrentTransaction(lockTimeout);
                records.release(reservation);
            });
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
            log.warn("idempotency reservation not released route={}; it ends with its lease", routeKey);
        }
    }

    private long untilNanos(Instant heldUntil) {
        Duration left = Duration.between(clock.instant(), heldUntil);
        return left.isNegative() ? 0 : left.toNanos();
    }

    /**
     * Replays an already completed command for a soft-deleted owner. It never reserves a slot and
     * never invokes an effect, so a revoked deletion cookie cannot start a second command.
     *
     * <p>Every refusal here, and the owner check in {@code guarded}, is {@link SessionService#unauthorized()}
     * (#249): a revoked cookie that cannot replay, or an owner deleted after its session resolved, must read
     * like any other dead cookie. A sentence of its own would tell the caller the cookie was once valid.
     */
    public GuardedResponse replayOnly(UUID ownerId, String routeKey, String idempotencyKey,
            String requestHash) {
        Objects.requireNonNull(ownerId, "ownerId");
        int keyLength = idempotencyKey == null ? -1
                : idempotencyKey.codePointCount(0, idempotencyKey.length());
        if (routeKey == null || routeKey.isEmpty()
                || routeKey.codePointCount(0, routeKey.length()) > IdempotencyRecord.ROUTE_KEY_MAX_LENGTH
                || keyLength < IdempotencyRecord.IDEMPOTENCY_KEY_MIN_LENGTH
                || keyLength > IdempotencyRecord.IDEMPOTENCY_KEY_MAX_LENGTH
                || requestHash == null || !REQUEST_HASH.matcher(requestHash).matches()) {
            throw SessionService.unauthorized();
        }
        return transactions.execute(status -> {
            lockWaitLimit.applyToCurrentTransaction(lockTimeout);
            if (owners.lockAny(ownerId).isEmpty()) {
                throw SessionService.unauthorized();
            }
            Instant now = clock.instant();
            IdempotencyRecord record = records.lockExisting(ownerId, routeKey, idempotencyKey)
                    .filter(existing -> existing.expiresAt().isAfter(now))
                    .filter(IdempotencyRecord::completed)
                    .filter(existing -> existing.requestHash().equals(requestHash))
                    .orElseThrow(SessionService::unauthorized);
            return new GuardedResponse(record.responseStatus(), record.responseBody(), true);
        });
    }

    private <T, S> GuardedResponse guarded(UUID ownerId, String routeKey, String idempotencyKey,
            String requestHash, Supplier<CommandOutcome<T>> command, Function<T, S> responseProjection,
            AtomicBoolean commandStarted) {
        // Before the first lock: a stuck command must not hold this connection or this owner forever.
        lockWaitLimit.applyToCurrentTransaction(lockTimeout);

        Owner owner = owners.lockAlive(ownerId).orElseThrow(SessionService::unauthorized);

        Instant now = clock.instant();
        IdempotencyRecord candidate = IdempotencyRecord.reservation(UuidV7.create(clock), owner.id(), routeKey,
                idempotencyKey, requestHash, now, now.plus(ttl));
        IdempotencyRecord reserved = reserve(candidate, now);

        if (!reserved.requestHash().equals(requestHash)) {
            throw new ApiException(ProblemCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        if (reserved.completed()) {
            return new GuardedResponse(reserved.responseStatus(), reserved.responseBody(), true);
        }
        if (!reserved.id().equals(candidate.id())) {
            // A committed reservation with no response, and not this transaction's: only a command with a
            // prelude leaves one (#340), and it is running the key's command right now. Running this one
            // over it would run the key's command twice, so a route must use one overload or the other.
            throw new IllegalStateException("route " + routeKey + " is held by a command with a prelude and"
                    + " cannot also run without one");
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
