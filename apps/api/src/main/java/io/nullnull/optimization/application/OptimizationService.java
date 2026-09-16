package io.nullnull.optimization.application;

import io.nullnull.identity.application.IdempotencyGuard;
import io.nullnull.identity.application.OwnerContext;
import io.nullnull.identity.domain.RequestFingerprint;
import io.nullnull.operations.application.JobQueue;
import io.nullnull.operations.domain.JobPayload;
import io.nullnull.operations.domain.JobRequest;
import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationDecision;
import io.nullnull.optimization.domain.OptimizationDecisionKind;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationScope;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.shared.ids.UuidV7;
import io.nullnull.shared.problem.ApiException;
import io.nullnull.shared.problem.ProblemCode;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripConstraint;
import io.nullnull.trip.domain.TripItem;
import io.nullnull.trip.domain.TripValidationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
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

    static final String DECIDE_ROUTE = "POST /optimizations/{runId}/decisions";

    /**
     * How long a preview stays offerable. Long enough to read a comparison and decide, short enough
     * that the evidence behind it is still the evidence the decision was made on; APPLY revalidates
     * the fingerprint anyway, so this is the window in which that revalidation is likely to pass
     * rather than a promise about the data.
     */
    static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    /**
     * How long an APPLY can still be taken back. The contract fixes the number, not a policy:
     * "revertUntil is exactly 24 hours after decidedAt; the server clock is authoritative."
     */
    static final Duration REVERT_WINDOW = Duration.ofHours(24);

    /** How long a poll is asked to wait. One tick of the worker's poll interval, not a guess at work. */
    public static final int RETRY_AFTER_SECONDS = 2;

    private static final Pattern IF_MATCH = Pattern.compile("\"\\d{1,18}\"");
    private static final int MAX_ATTEMPTS = 3;

    private final OptimizationRunStore runs;
    private final OptimizationProposalStore proposals;
    private final OptimizationDecisionStore decisions;
    /** For BA-052-T7: the policy a decision is judged against is today's, not the run's. */
    private final RecommendationGateway recommendations;
    private final OptimizationCapability capability;
    private final TripService trips;
    private final JobQueue jobs;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public OptimizationService(OptimizationRunStore runs, OptimizationProposalStore proposals,
            OptimizationDecisionStore decisions, RecommendationGateway recommendations,
            OptimizationCapability capability, TripService trips, JobQueue jobs,
            IdempotencyGuard idempotency, ObjectMapper json, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations");
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
     * decideOptimization: the traveller's answer to one run, recorded once.
     *
     * <p>Order is not arrangement. The capability is consulted before the run is read, so a server
     * with optimization off answers 403 without revealing whether the id exists - the same rule
     * {@code OptimizationCapabilityOffIT} fixes for createOptimization. The run is then read
     * owner-scoped, so a foreign id is indistinguishable from one that never existed (invariant 11).
     *
     * <p><strong>Every failure throws.</strong> {@code IdempotencyGuard} reserves the key before the
     * command and completes it only on a normal return, so a thrown failure rolls the reservation
     * back and the same key may be retried - which is exactly what the contract promises: "A failed
     * APPLY records no decision and changes no trip row, so the same idempotency key can replay it."
     * Handing a failure back as a value would complete the record and pin the key to it.
     *
     * <p>BA-052-T7 is the policy check below. V032 froze the three fingerprint inputs that were
     * previously kept nowhere - {@code policyVersion}, {@code policyHash} and {@code catalogVersion}
     * - so a run can now be compared against the policy it was actually judged under rather than
     * against whatever the service reports today.
     *
     * <p>BA-052-T5 is still open, and what is missing is named rather than implied: the digest was
     * computed over the individual snapshot ids, and what the run froze is the SET ids. Reading the
     * snapshots back needs a {@code CrowdForecastQuery} method that fetches a set by its id - the
     * query exists in that module's Jdbc implementation but is not on its port. Calling
     * {@code latestFresh} instead would ask what is fresh NOW, which is the same defect this
     * migration was written to remove.
     */
    public OptimizationDecision decide(OwnerContext context, UUID runId, String ifMatch,
            String idempotencyKey, DecideOptimizationCommand command) {
        if (!capability.enabled()) {
            throw new ApiException(ProblemCode.FORBIDDEN,
                    "Optimization is not enabled on this server.");
        }
        long expected = parseIfMatch(ifMatch);
        OptimizationRun run = runs.findForOwner(context.ownerId(), runId)
                .orElseThrow(OptimizationService::notFound);
        capability.require(run.scope());

        String fingerprint = RequestFingerprint.of("decideOptimization",
                        Map.of("runId", runId.toString()), canonicalDecision(command),
                        Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), DECIDE_ROUTE,
                idempotencyKey, fingerprint,
                () -> new IdempotencyGuard.CommandOutcome<>(200,
                        new DecisionProjection(record(context, run, expected, command).id())),
                value -> value);
        DecisionProjection projection = readDecision(guarded.body());
        return decisions.findByRun(runId).stream()
                .filter(decision -> decision.id().equals(projection.decisionId()))
                .findFirst()
                .orElseThrow(OptimizationService::notFound);
    }

    /**
     * The decision itself, inside the guard's transaction.
     *
     * <p>An expired preview is refused as DATA_CHANGED and not as PREVIEW_EXPIRED, which looks wrong
     * until both canonical documents are read together: {@code docs/api/README.md} lists the codes an
     * APPLY may answer with and PREVIEW_EXPIRED is not among them, while the same file maps
     * PREVIEW_EXPIRED to 410 on the read. The two agree - the preview being gone is something you
     * learn by reading the run, and at the moment of deciding, "the evidence you are acting on is no
     * longer valid" is what DATA_CHANGED says, with the CTA the mapping already gives it.
     */
    private OptimizationDecision record(OwnerContext context, OptimizationRun run, long expected,
            DecideOptimizationCommand command) {
        Instant now = clock.instant();
        if (run.status() != OptimizationStatus.READY) {
            // Includes a run already decided. The partial unique index refuses a second initial
            // decision underneath this, so removing this check loses the message, not the guarantee.
            throw new ApiException(ProblemCode.DATA_CHANGED,
                    "This run is no longer offering a preview to decide on.");
        }
        if (run.previewExpired(now)) {
            throw new ApiException(ProblemCode.DATA_CHANGED,
                    "The preview expired before this decision was made.");
        }
        requirePolicyStillInForce(run);
        OptimizationProposal proposal = proposals.findByRun(run.id()).stream()
                .filter(each -> each.id().equals(command.proposalId()))
                .findFirst()
                // Not 404: the run exists and is the caller's, so this is a body naming something
                // the run does not hold - the reading createOptimization gives an unknown item id.
                .orElseThrow(() -> new TripValidationException("proposalId", "NotFound",
                        "this run has no such proposal"));

        UUID decisionId = UuidV7.create(clock);
        OptimizationDecision decision = command.decision() == OptimizationDecisionKind.APPLY
                ? applied(context, run, proposal, expected, decisionId, now)
                : new OptimizationDecision(decisionId, run.id(), proposal.id(), run.ownerId(),
                        OptimizationDecisionKind.KEEP, expected, null, null, null, null, null, now);
        if (!decisions.insertIfFirst(decision)) {
            // Another caller decided this run first; the database said so, not a read we did.
            throw new ApiException(ProblemCode.DATA_CHANGED,
                    "This run was already decided.");
        }
        OptimizationStatus ended = decision.decision() == OptimizationDecisionKind.APPLY
                ? OptimizationStatus.APPLIED
                : OptimizationStatus.KEPT;
        if (!runs.transition(run.id(), OptimizationStatus.READY, ended, now)) {
            throw new ApiException(ProblemCode.DATA_CHANGED, "This run was already decided.");
        }
        return decision;
    }

    /**
     * BA-052-T7: a preview judged under a policy that has since been withdrawn is not applicable.
     *
     * <p>Compares what the run recorded against what the service reports now. The version is the
     * readable half and the hash is the load-bearing one: a policy can be revised without renaming
     * itself, and then only the digest differs. Both are checked because they fail differently - a
     * changed version is a deliberate release, a changed hash alone is a revision nobody announced.
     *
     * <p>{@code algorithm_version} cannot answer this. It holds the PIPELINE version, and a withdrawn
     * policy is not a changed pipeline - which is why V032 had to add the two columns rather than
     * reuse the one already there.
     *
     * <p>A run that never reached READY has no stored policy, and the status check above has already
     * refused it; this asserts that rather than treating null as agreement.
     */
    private void requirePolicyStillInForce(OptimizationRun run) {
        if (run.policyVersion() == null || run.policyHash() == null) {
            throw new IllegalStateException(
                    "run " + run.id() + " is READY without the policy it was judged under");
        }
        PolicyDescriptor current = recommendations.policy();
        if (!run.policyVersion().equals(current.policyVersion())
                || !run.policyHash().equals(current.policyHash())) {
            throw new ApiException(ProblemCode.DATA_CHANGED,
                    "The policy this preview was computed under is no longer in force.");
        }
    }

    /** APPLY: the trip moves, in the trip module, and this records what that produced. */
    private OptimizationDecision applied(OwnerContext context, OptimizationRun run,
            OptimizationProposal proposal, long expected, UUID decisionId, Instant now) {
        TripService.AppliedMoves moved = trips.applyOptimizationMoves(context.ownerId(), run.tripId(),
                expected, movesOf(proposal));
        return new OptimizationDecision(decisionId, run.id(), proposal.id(), run.ownerId(),
                OptimizationDecisionKind.APPLY, expected, moved.trip().version(),
                moved.beforeRevisionId(), moved.afterRevisionId(), null,
                now.plus(REVERT_WINDOW), now);
    }

    /**
     * The preview's changes as moves the trip module can apply.
     *
     * <p>Only MOVE is translated, and anything else is refused rather than skipped. The only producer
     * today emits one MOVE per proposal; the day another operation is produced, an apply that quietly
     * ignored it would change less than the traveller approved.
     */
    private List<TripService.ItemMove> movesOf(OptimizationProposal proposal) {
        List<TripService.ItemMove> moves = new java.util.ArrayList<>(proposal.changes().size());
        for (OptimizationChange change : proposal.changes()) {
            if (change.operation() != OptimizationChangeOperation.MOVE) {
                throw new ApiException(ProblemCode.APPLY_FAILED, HttpStatus.SERVICE_UNAVAILABLE,
                        "This preview contains a change this release cannot apply.", false, null);
            }
            ItemState after = json.readValue(change.afterValue(), ItemState.class);
            moves.add(new TripService.ItemMove(change.tripItemId(), LocalDate.parse(after.date()),
                    after.position(),
                    after.startTime() == null ? null : LocalTime.parse(after.startTime())));
        }
        return moves;
    }

    /** The shape ItemProposalMapper writes into optimization_changes. Parsed by its own module. */
    private record ItemState(String placeId, String date, Integer position, String startTime) {
    }

    private record DecisionProjection(UUID decisionId) {
    }

    private DecisionProjection readDecision(String body) {
        return json.readValue(body, DecisionProjection.class);
    }

    private static String canonicalDecision(DecideOptimizationCommand command) {
        return "{\"proposalId\":\"" + command.proposalId() + "\",\"decision\":\""
                + command.decision() + "\"}";
    }

    /**
     * A run as it reads now, which is not always as it was stored.
     *
     * <p>A run whose preview deadline has passed reads as EXPIRED even before a writer has recorded
     * it, because the deadline is a fact about time rather than about the row. Computing it here
     * rather than storing it is what ERD §2 does for revertAvailability, and it is safe in the same
     * way: every writer checks the same deadline, so a status this read has already reported as
     * EXPIRED can never be answered as something earlier afterwards.
     *
     * <p>One deadline, two answers. A run that HAD a preview answers 410 rather than reading as
     * EXPIRED - see {@link #requirePreviewStillOffered}. The rewrite below is for the runs that
     * never got one.
     */
    @Transactional(readOnly = true)
    public OptimizationRun get(OwnerContext context, UUID runId) {
        OptimizationRun stored = runs.findForOwner(context.ownerId(), runId)
                .orElseThrow(OptimizationService::notFound);
        Instant now = clock.instant();
        requirePreviewStillOffered(stored, now);
        return asReadNow(stored, now);
    }

    /**
     * BA-051-T5: a preview that existed and has since passed its deadline is gone, and 410 says so.
     *
     * <p>Only a stored READY run gets this, and the two exclusions are the whole of the rule. A run
     * that never reached READY has no preview that could have expired - one was asked for and never
     * arrived - so it keeps reading as EXPIRED with 200, which is what BA-050-T7 fixes. A terminal
     * run is left alone because the contract says expiry "must not turn an already APPLIED or
     * REVERTED run into PREVIEW_EXPIRED": a decision is a fact that happened, and a deadline cannot
     * un-happen it.
     *
     * <p>The difference is the caller's, not ours. 200 with EXPIRED hands back a body holding
     * proposals, and a client that renders them is offering changes nobody may apply; 410 says the
     * thing being asked for is gone and the way forward is a new run - which is exactly the CTA this
     * Problem code is mapped to in {@code docs/api/README.md}.
     */
    private static void requirePreviewStillOffered(OptimizationRun stored, Instant now) {
        if (stored.status() == OptimizationStatus.READY && stored.previewExpired(now)) {
            // The same sentence the published fixture carries
            // (packages/contracts/fixtures/problems/preview-expired.json), so the example a client
            // was built against and the string a client receives cannot drift apart.
            throw new ApiException(ProblemCode.PREVIEW_EXPIRED,
                    "The optimization preview has expired.");
        }
    }

    static OptimizationRun asReadNow(OptimizationRun stored, Instant now) {
        if (stored.status().terminal() || !stored.previewExpired(now)) {
            return stored;
        }
        return new OptimizationRun(stored.id(), stored.tripId(), stored.ownerId(), stored.scope(),
                stored.targetItemId(), stored.targetDate(), stored.includeCandidates(),
                OptimizationStatus.EXPIRED, stored.inputTripVersion(), stored.inputRevisionId(),
                stored.dataFingerprint(), stored.algorithmVersion(), stored.policyVersion(),
                stored.policyHash(), stored.catalogVersion(), stored.failureCode(),
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
