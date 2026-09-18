package io.nullnull.optimization.application;

import io.nullnull.crowd.application.CrowdForecastQuery;
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
import io.nullnull.shared.cursor.CursorClaims;
import io.nullnull.shared.cursor.CursorException;
import io.nullnull.shared.cursor.CursorSortKey;
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
import java.time.ZoneId;
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

    /** The route template the idempotency slot is keyed by, distinct from the decide route. */
    static final String REVERT_ROUTE = "POST /optimization-decisions/{decisionId}/revert";

    /** Contract: listOptimizationHistory limit, default 20, maximum 50. */
    private static final int DEFAULT_LIMIT = 20;

    private static final int MAX_LIMIT = 50;

    /** How long a poll is asked to wait. One tick of the worker's poll interval, not a guess at work. */
    public static final int RETRY_AFTER_SECONDS = 2;

    private static final Pattern IF_MATCH = Pattern.compile("\"\\d{1,18}\"");
    private static final int MAX_ATTEMPTS = 3;

    private final OptimizationRunStore runs;
    private final OptimizationProposalStore proposals;
    private final OptimizationDecisionStore decisions;
    /** For BA-052-T7: the policy a decision is judged against is today's, not the run's. */
    private final RecommendationGateway recommendations;
    /** For BA-052-T5: the set this run froze, re-read by id rather than looked up again. */
    private final CrowdForecastQuery forecasts;
    private final OptimizationHistoryQuery history;
    private final OptimizationCursorProperties historyCursors;
    private final OptimizationCapability capability;
    private final TripService trips;
    private final JobQueue jobs;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public OptimizationService(OptimizationRunStore runs, OptimizationProposalStore proposals,
            OptimizationDecisionStore decisions, OptimizationHistoryQuery history,
            OptimizationCursorProperties historyCursors, RecommendationGateway recommendations,
            CrowdForecastQuery forecasts, OptimizationCapability capability, TripService trips,
            JobQueue jobs, IdempotencyGuard idempotency, ObjectMapper json, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.history = Objects.requireNonNull(history, "history");
        this.historyCursors = Objects.requireNonNull(historyCursors, "historyCursors");
        this.recommendations = Objects.requireNonNull(recommendations, "recommendations");
        this.forecasts = Objects.requireNonNull(forecasts, "forecasts");
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
     * <p>BA-052-T5 is {@link #requireFrozenEvidenceStillStored}. It asks whether the set this run
     * froze is still stored, by id - not whether a set is fresh now, which is a different question
     * about a different moment. What it deliberately does NOT do is recompute the run's fingerprint
     * and compare: the digest covers candidate verdicts that {@code CrowdProvenanceProjection}
     * derives from the clock, so a set that was fresh when frozen produces a different digest later
     * for no reason but elapsed time, and the instant the handler used is not stored to reproduce.
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
        requireFrozenEvidenceStillStored(run);
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

    /**
     * BA-052-T5: a preview whose frozen evidence is no longer stored cannot be applied.
     *
     * <p>Only one cause is reachable, and saying which is the point of this comment. Crowd snapshots
     * are immutable - V011's {@code crowd_prevent_snapshot_mutation} refuses every UPDATE, and its
     * header says why: "individual rows are never updated in place, so a saved preview can keep its
     * original provenance". So a set that is still there still holds exactly what this run hashed.
     * The only way the evidence can stop supporting the preview is for it to be gone.
     *
     * <p>Nothing in production removes it today - no delete of {@code crowd_snapshots} or
     * {@code snapshot_sets} outside test fixtures, and the ERD gives those tables no retention
     * policy. The guard is written anyway because a retention sweep is a normal thing to add, and on
     * the day it arrives this is already standing. An integration test creates the state by deleting
     * the frozen rows, which is how the assertion fires without a producer.
     *
     * <p>Deliberately NOT a fingerprint recomputation. The digest was taken over candidates whose
     * eligibility {@code CrowdProvenanceProjection.compare} decides from {@code now} - a set that
     * was fresh when frozen is stale later, the verdicts flip, and the recomputed digest differs
     * because time passed rather than because evidence changed. The instant the handler used is not
     * stored, so it cannot even be reproduced. Comparing what cannot be reproduced would refuse
     * valid applies for a reason that is not the one the code claims.
     */
    private void requireFrozenEvidenceStillStored(OptimizationRun run) {
        if (run.snapshotSetIds().isEmpty()) {
            // A READY run froze at least one set; recordFrozenEvidence writes them before markReady.
            throw new IllegalStateException("run " + run.id() + " is READY with no frozen evidence");
        }
        Trip trip = trips.findForOwner(run.ownerId(), run.tripId())
                .orElseThrow(OptimizationService::notFound);
        TripItem target = trips.itemsOf(run.tripId()).stream()
                .filter(item -> item.id().equals(run.targetItemId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ProblemCode.DATA_CHANGED,
                        "The item this preview is about is no longer in the trip."));
        ZoneId zone = trip.range().timezone();
        Instant from = trip.range().startDate().atStartOfDay(zone).toInstant();
        Instant to = trip.range().endDate().plusDays(1).atStartOfDay(zone).toInstant();
        for (UUID setId : run.snapshotSetIds()) {
            if (forecasts.frozenSet(setId, target.placeId(), from, to).isEmpty()) {
                throw new ApiException(ProblemCode.DATA_CHANGED,
                        "The forecast evidence this preview was judged against is no longer stored.");
            }
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
     * BA-053 revertOptimizationDecision: take an APPLY back, once, inside its window.
     *
     * <p>Shaped like {@link #decide}, and for the same reason: the guard is the outermost boundary, so
     * everything that can refuse without writing is read before it, and everything that writes is
     * inside {@link #reverted}. The failures this can answer with are the three the contract lists
     * plus the two generic ones - and which of them applies is decided in there, not here.
     */
    public OptimizationDecision revert(OwnerContext context, UUID decisionId, String ifMatch,
            String idempotencyKey) {
        if (!capability.enabled()) {
            throw new ApiException(ProblemCode.FORBIDDEN,
                    "Optimization is not enabled on this server.");
        }
        long expected = parseIfMatch(ifMatch);
        OptimizationDecision applied = decisions.findForOwner(context.ownerId(), decisionId)
                .orElseThrow(OptimizationService::notFound);
        OptimizationRun run = runs.findForOwner(context.ownerId(), applied.runId())
                .orElseThrow(OptimizationService::notFound);
        capability.require(run.scope());

        String fingerprint = RequestFingerprint.of("revertOptimizationDecision",
                        Map.of("decisionId", decisionId.toString()), "", Long.toString(expected))
                .sha256Hex();
        IdempotencyGuard.GuardedResponse guarded = idempotency.execute(context.ownerId(), REVERT_ROUTE,
                idempotencyKey, fingerprint,
                () -> new IdempotencyGuard.CommandOutcome<>(200,
                        new DecisionProjection(reverted(context, run, applied, expected).id())),
                value -> value);
        DecisionProjection projection = readDecision(guarded.body());
        return decisions.findByRun(run.id()).stream()
                .filter(decision -> decision.id().equals(projection.decisionId()))
                .findFirst()
                .orElseThrow(OptimizationService::notFound);
    }

    /**
     * REVERT: the trip moves back, in the trip module, and this records what that produced.
     *
     * <p>Three refusals come before the write, in the order of what they protect.
     *
     * <p>One, the target must be an APPLY. V030's own comment assigns this check here, because a CHECK
     * constraint sees only its own row and {@code reverted_decision_id} points at another one. It
     * answers 404 rather than a conflict: the contract says a KEEP and a REVERT are not revertable by
     * omitting {@code revertUntil} from their variants, so "no revert exists at this id" is the same
     * sentence in a different grammar. Inventing a code for it would be a contract change, and the
     * canonical list for REVERT (docs/api/README.md section 9) has none that fits.
     *
     * <p>Two, the window. {@code revertUntil} is stored on the APPLY rather than recomputed, so a
     * server whose REVERT_WINDOW changes later cannot retroactively reopen a closed window - the row
     * says when this particular decision stopped being reversible.
     *
     * <p>Three, the trip must still be where the APPLY left it. This is the check that makes T2 real
     * and it is NOT the same as the If-Match precondition: a caller who reads the trip, sees version
     * 9 and sends "9" satisfies If-Match perfectly while the APPLY produced version 8. Comparing the
     * caller's version against what the APPLY produced is what notices the edits in between - the
     * recorded before-values describe a trip that no longer exists, and writing them back would undo
     * the traveller's own later work along with the optimizer's.
     */
    private OptimizationDecision reverted(OwnerContext context, OptimizationRun run,
            OptimizationDecision applied, long expected) {
        Instant now = clock.instant();
        if (applied.decision() != OptimizationDecisionKind.APPLY) {
            throw notFound();
        }
        if (applied.revertUntil() == null || !now.isBefore(applied.revertUntil())) {
            // The same sentence the published fixture carries
            // (packages/contracts/fixtures/problems/revert-window-expired.json).
            throw new ApiException(ProblemCode.REVERT_WINDOW_EXPIRED,
                    "The window to undo this optimization has closed.");
        }
        if (applied.resultingTripVersion() == null || applied.resultingTripVersion() != expected) {
            throw new ApiException(ProblemCode.TRIP_CHANGED,
                    "This trip changed after the optimization was applied, so it cannot be undone.");
        }
        OptimizationProposal proposal = proposals.findByRun(run.id()).stream()
                .filter(each -> each.id().equals(applied.proposalId()))
                .findFirst()
                // Proposals are immutable (V029) and cascade with their run, so a missing one means
                // the evidence of what to undo is gone rather than that the caller named it wrongly.
                .orElseThrow(() -> new ApiException(ProblemCode.APPLY_FAILED,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The record of what this decision changed is no longer stored.", false, null));

        UUID decisionId = UuidV7.create(clock);
        TripService.AppliedMoves moved = trips.applyOptimizationMoves(context.ownerId(), run.tripId(),
                expected, reverseMovesOf(proposal));
        OptimizationDecision decision = new OptimizationDecision(decisionId, run.id(), proposal.id(),
                run.ownerId(), OptimizationDecisionKind.REVERT, expected, moved.trip().version(),
                moved.beforeRevisionId(), moved.afterRevisionId(), applied.id(), null, now);
        if (!decisions.insertIfFirst(decision)) {
            // V033's unique index refused a second revert of this APPLY. The database said so, not a
            // read we did - two callers racing both see an unreverted decision.
            throw new ApiException(ProblemCode.DATA_CHANGED,
                    "This optimization was already undone.");
        }
        if (!runs.transition(run.id(), OptimizationStatus.APPLIED, OptimizationStatus.REVERTED, now)) {
            throw new ApiException(ProblemCode.DATA_CHANGED, "This optimization was already undone.");
        }
        return decision;
    }

    /**
     * The preview's changes as the moves that put the trip back where it was.
     *
     * <p>The mirror of {@link #movesOf}: the same translation reading {@code beforeValue} instead of
     * {@code afterValue}. Reversing the recorded changes is deliberately NOT the same as restoring
     * {@code trip_revisions.aggregate_snapshot}, which ERD section 9 and the operation's own
     * description both now say - the snapshot omits durationMinutes and note, so restoring from it
     * would clear fields the apply never touched. A before-value names only columns the apply wrote.
     */
    private List<TripService.ItemMove> reverseMovesOf(OptimizationProposal proposal) {
        List<TripService.ItemMove> moves = new java.util.ArrayList<>(proposal.changes().size());
        for (OptimizationChange change : proposal.changes()) {
            if (change.operation() != OptimizationChangeOperation.MOVE) {
                throw new ApiException(ProblemCode.APPLY_FAILED, HttpStatus.SERVICE_UNAVAILABLE,
                        "This decision contains a change this release cannot undo.", false, null);
            }
            ItemState before = json.readValue(change.beforeValue(), ItemState.class);
            moves.add(new TripService.ItemMove(change.tripItemId(), LocalDate.parse(before.date()),
                    before.position(),
                    before.startTime() == null ? null : LocalTime.parse(before.startTime())));
        }
        return moves;
    }

    /**
     * BA-053 listOptimizationHistory: the owner's runs, newest first, state and time only.
     *
     * <p>Keyset paging over {@code queued_at DESC, id DESC}, the same way listTrips pages: a run
     * queued later inserts at the head, so an offset would re-serve the row the reader just saw.
     */
    @Transactional(readOnly = true)
    public OptimizationHistoryPageView history(OwnerContext context, UUID tripId, String cursor,
            Integer limit) {
        int size = pageSize(limit);
        String binding = historyCursors.ownerBinding(context.ownerId());
        OptimizationHistoryQuery.PageKey after = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorClaims claims = historyCursors.cursorCodec().decode(cursor, clock.instant(), binding,
                    OptimizationCursorProperties.CONTEXT);
            if (claims.sortVersion() != OptimizationCursorProperties.SORT_VERSION) {
                // A key minted under another order names a row this order would resume elsewhere.
                throw new CursorException(ProblemCode.CURSOR_INVALID);
            }
            CursorSortKey key = CursorSortKey.decode(claims.sortKey());
            after = new OptimizationHistoryQuery.PageKey(key.instantValue(), key.id());
        }
        // One extra row: a page that is exactly full is otherwise indistinguishable from the last
        // page, and a cursor handed out for an empty next page is a wasted round trip.
        Instant now = clock.instant();
        List<OptimizationHistoryQuery.HistoryRow> found =
                history.page(context.ownerId(), tripId, after, size + 1);
        boolean hasMore = found.size() > size;
        List<OptimizationHistoryQuery.HistoryRow> page = hasMore ? found.subList(0, size) : found;
        List<OptimizationHistoryQuery.HistoryRow> views = new java.util.ArrayList<>(page.size());
        for (OptimizationHistoryQuery.HistoryRow row : page) {
            views.add(asReadNow(row, now));
        }
        String next = hasMore ? nextHistoryCursor(page.get(page.size() - 1), binding) : null;
        return new OptimizationHistoryPageView(views, next, hasMore);
    }

    /**
     * The status a reader sees, computed the way {@link #asReadNow(OptimizationRun, Instant)} computes
     * it for the detail.
     *
     * <p>Without this the same run would read READY in the list and EXPIRED on its own screen, and the
     * list is where a traveller decides whether to open it - so the list would be inviting them into
     * a preview that is no longer there. Expiry is a property of the clock, not of the row, which is
     * why neither place stores it.
     */
    private static OptimizationHistoryQuery.HistoryRow asReadNow(
            OptimizationHistoryQuery.HistoryRow row, Instant now) {
        boolean expired = row.expiresAt() != null && !now.isBefore(row.expiresAt());
        if (row.status().terminal() || !expired) {
            return row;
        }
        return new OptimizationHistoryQuery.HistoryRow(row.runId(), row.tripId(), row.tripTitle(),
                row.scope(), OptimizationStatus.EXPIRED, row.queuedAt(), row.expiresAt(),
                row.decision(), row.decidedAt());
    }

    private String nextHistoryCursor(OptimizationHistoryQuery.HistoryRow last, String binding) {
        return historyCursors.cursorCodec().encode(new CursorClaims(OptimizationCursorProperties.CONTEXT,
                CursorSortKey.of(last.queuedAt(), last.runId()).encode(), binding,
                OptimizationCursorProperties.CONTEXT, OptimizationCursorProperties.SORT_VERSION,
                clock.instant().plus(historyCursors.cursorTtl()), historyCursors.keyId()));
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ProblemCode.INVALID_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
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
