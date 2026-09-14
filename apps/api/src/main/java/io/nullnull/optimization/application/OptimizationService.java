package io.nullnull.optimization.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues preview-only optimization runs and answers polls about them.
 *
 * <p>Nothing here writes a trip, and nothing here computes an itinerary. A run is a record that a
 * question was asked; the worker freezes what the answer must be judged against, and the slice that
 * can produce an answer (BA-051) writes it. That separation is the reason invariant 3 holds by
 * construction rather than by care: this module has no trip mutation to call.
 */
@Service
public class OptimizationService {

    /** Queue type for one ITEM run. Lower case with hyphens, as JobRequest.TYPE requires. */
    public static final String JOB_TYPE = "optimize-item";

    static final String CREATE_ROUTE = "POST /trips/{tripId}/optimizations";

    /**
     * How long a preview stays offerable. Long enough to read a comparison and decide, short enough
     * that the evidence behind it is still the evidence the decision was made on; APPLY revalidates
     * the fingerprint anyway, so this is the window in which that revalidation is likely to pass
     * rather than a promise about the data.
     */
    static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    /** How long a poll is asked to wait. One tick of the worker's poll interval, not a guess at work. */
    public static final int RETRY_AFTER_SECONDS = 2;

    private static final Pattern IF_MATCH = Pattern.compile("\"\\d{1,18}\"");
    private static final int MAX_ATTEMPTS = 3;

    private final OptimizationRunStore runs;
    private final OptimizationCapability capability;
    private final TripService trips;
    private final JobQueue jobs;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public OptimizationService(OptimizationRunStore runs, OptimizationCapability capability,
            TripService trips, JobQueue jobs, IdempotencyGuard idempotency, ObjectMapper json,
            Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.trips = Objects.requireNonNull(trips, "trips");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Preflight, then one transaction that creates the run and its job together.
     *
     * <p>The order of the checks is the order of their cost and their secrecy. The capability is
     * first: a server with optimization off answers the same way for every trip, including ones the
     * caller does not own. Ownership is next, so a foreign trip id cannot be probed for shape errors.
     * Only then the request's own shape, the version precondition and the locks.
     */
    public OptimizationRun create(OwnerContext context, UUID tripId, String ifMatch,
            String idempotencyKey, CreateOptimizationCommand command) {
        capability.require(command.scope());
        long expected = parseIfMatch(ifMatch);
        Trip trip = trips.findForOwner(context, tripId).orElseThrow(OptimizationService::notFound);
        if (trip.version() != expected) {
            throw new ApiException(ProblemCode.TRIP_CHANGED, "The trip was modified elsewhere.");
        }
        if (command.inputTripVersion() != expected) {
            // Two statements of the same precondition that disagree. Neither is obviously the one the
            // caller meant, so the request is refused rather than one of them silently preferred.
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "inputTripVersion and If-Match must name the same trip version.");
        }
        TripItem target = requireTarget(tripId, command);
        requireLocksLeaveRoom(target);

        String fingerprint = RequestFingerprint.of("createOptimization",
                        Map.of("tripId", tripId.toString()), canonical(command), Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), CREATE_ROUTE,
                idempotencyKey, fingerprint,
                () -> new IdempotencyGuard.CommandOutcome<>(202,
                        new RunProjection(queue(context, trip, command).id())),
                value -> value);
        RunProjection projection = readProjection(guarded.body());
        return runs.find(projection.runId()).orElseThrow(OptimizationService::notFound);
    }

    /**
     * One transaction: the run row and the job that will pick it up.
     *
     * <p>The transaction is the guard's. {@link IdempotencyGuard#execute} runs the command inside the
     * one it opened, and {@link JobQueue#enqueue} requires a caller's transaction for exactly this
     * reason, so the atomicity is the queue's contract rather than a convention this method keeps: a
     * run with no job would wait forever, and a job with no run would fail on its first read.
     *
     * <p>Deliberately not annotated {@code @Transactional}. This is a call from inside the same bean,
     * which no proxy sees, so the annotation would read as a guarantee while providing none - the
     * transaction it appears to open would be the one that was already there.
     */
    private OptimizationRun queue(OwnerContext context, Trip trip, CreateOptimizationCommand command) {
        Instant now = clock.instant();
        // The revision this run freezes, beside the version it freezes. Both are read from the same
        // trip, so they describe one moment rather than two.
        //
        // Written here and not left null, which is what it was: the column existed from V024 with no
        // producer, so getOptimization answered inputRevisionId null on every run, and RunFingerprint
        // - which requires it - could never be computed. A preview could therefore never reach READY,
        // and the gap was invisible for as long as nothing tried to. The trip's first revision is
        // written with the trip itself, so a trip that exists has one.
        UUID inputRevisionId = trips.revisionAt(context.ownerId(), trip.id(), trip.version())
                .orElseThrow(() -> new IllegalStateException(
                        "trip " + trip.id() + " has no revision at version " + trip.version()));
        OptimizationRun run = new OptimizationRun(UUID.randomUUID(), trip.id(), context.ownerId(),
                command.scope(), command.targetItemId(), command.targetDate(), command.includeCandidates(),
                OptimizationStatus.QUEUED, trip.version(), inputRevisionId, null, null, null, null, now,
                null, null, null);
        runs.insert(run);
        jobs.enqueue(new JobRequest(UUID.randomUUID(), JOB_TYPE, "optimization:" + run.id(),
                JobPayload.of(Map.of("runId", run.id().toString())), MAX_ATTEMPTS, now, now));
        return run;
    }

    /**
     * A run as it reads now, which is not always as it was stored.
     *
     * <p>A run whose preview deadline has passed reads as EXPIRED even before a writer has recorded
     * it, because the deadline is a fact about time rather than about the row. Computing it here
     * rather than storing it is what ERD §2 does for revertAvailability, and it is safe in the same
     * way: every writer checks the same deadline, so a status this read has already reported as
     * EXPIRED can never be answered as something earlier afterwards.
     */
    @Transactional(readOnly = true)
    public OptimizationRun get(OwnerContext context, UUID runId) {
        OptimizationRun stored = runs.findForOwner(context.ownerId(), runId)
                .orElseThrow(OptimizationService::notFound);
        return asReadNow(stored, clock.instant());
    }

    static OptimizationRun asReadNow(OptimizationRun stored, Instant now) {
        if (stored.status().terminal() || !stored.previewExpired(now)) {
            return stored;
        }
        return new OptimizationRun(stored.id(), stored.tripId(), stored.ownerId(), stored.scope(),
                stored.targetItemId(), stored.targetDate(), stored.includeCandidates(),
                OptimizationStatus.EXPIRED, stored.inputTripVersion(), stored.inputRevisionId(),
                stored.dataFingerprint(), stored.algorithmVersion(), stored.failureCode(),
                stored.failureMessage(), stored.queuedAt(), stored.startedAt(),
                stored.completedAt() == null ? stored.expiresAt() : stored.completedAt(),
                stored.expiresAt(), stored.snapshotSetIds());
    }

    private TripItem requireTarget(UUID tripId, CreateOptimizationCommand command) {
        if (command.scope() != OptimizationScope.ITEM) {
            // Unreachable while DAY and TRIP are refused by the capability gate; written as a
            // statement rather than an assumption, so the day the gate opens this does not silently
            // dereference a target that is not an item.
            throw new IllegalStateException("only ITEM runs choose a target item");
        }
        return trips.itemsOf(tripId).stream()
                .filter(item -> item.id().equals(command.targetItemId()))
                .findFirst()
                // Not 404: the trip exists and is the caller's, so this is a body that names
                // something the trip does not contain.
                .orElseThrow(() -> new io.nullnull.trip.domain.TripValidationException("targetItemId",
                        "NotFound", "the trip has no such item"));
    }

    /**
     * Refuses a run whose target cannot move at all.
     *
     * <p>An ITEM run proposes the same place at a different time, so a target whose date and time are
     * both pinned has no candidate the run could generate - and RESERVATION pins both at once.
     * MUST_VISIT alone is not in the way: it pins the place, which this run was never going to change.
     *
     * <p>Refusing here rather than letting the worker discover it is the difference between a caller
     * that is told why and a run that sits QUEUED before failing for a reason the user could have been
     * given immediately.
     */
    private static void requireLocksLeaveRoom(TripItem target) {
        List<LockType> locked = target.constraints().stream()
                .map(constraint -> constraint.lock().type()).toList();
        boolean pinned = locked.contains(LockType.RESERVATION)
                || (locked.contains(LockType.DATE) && locked.contains(LockType.TIME));
        if (pinned) {
            throw new ApiException(ProblemCode.LOCK_CONFLICT,
                    "The target's locks leave no time for this run to propose.");
        }
    }

    private RunProjection readProjection(String body) {
        return json.readValue(body, RunProjection.class);
    }

    private static String canonical(CreateOptimizationCommand command) {
        return command.scope().name() + "|" + command.targetItemId() + "|" + command.targetDate() + "|"
                + command.includeCandidates() + "|" + command.objective();
    }

    private static long parseIfMatch(String ifMatch) {
        if (ifMatch == null || !IF_MATCH.matcher(ifMatch).matches()) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "If-Match must be the quoted trip version.");
        }
        return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
    }

    private static ApiException notFound() {
        return new ApiException(ProblemCode.NOT_FOUND, "The requested optimization is unavailable.");
    }

    /** What the idempotency record replays: the run's id, never the run's contents. */
    public record RunProjection(UUID runId) { }

    /** How long a poll should wait before asking again, or empty when the run is settled. */
    public static Optional<Integer> retryAfter(OptimizationRun run) {
        return run.status().pending() ? Optional.of(RETRY_AFTER_SECONDS) : Optional.empty();
    }
}
