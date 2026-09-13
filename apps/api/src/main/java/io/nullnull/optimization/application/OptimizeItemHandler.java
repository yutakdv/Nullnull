package io.nullnull.optimization.application;

import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.trip.application.TripService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs one ITEM optimization as far as this slice can honestly take it.
 *
 * <p>The handler starts the run, freezes what any answer would have to be judged against, and then
 * asks the one question that decides whether a preview may be written at all: does the input the run
 * froze still describe the trip? If it does not, the run fails with the reason. If it does, the run
 * waits - because the slice that can produce a proposal (BA-051) is what writes READY, and this one
 * has nothing to put there.
 *
 * <p>That waiting is not a gap papered over. A run that is never answered reaches its preview
 * deadline and reads as EXPIRED, which is what actually happened to it: a preview was asked for and
 * none arrived. Writing READY with an empty proposal list instead would be the opposite - a success
 * state nothing can make true, which is the shape of defect this codebase keeps finding.
 *
 * <p>What this handler must NOT do is equally deliberate. It calls {@code apps/ai} nowhere: the
 * request's content is a list of temporal candidates, and generating those is BA-051's first step, so
 * a call from here would either duplicate that work or send something meaningless. It writes no trip
 * row, which invariant 3 requires and which this module cannot do anyway - it has no mutation to
 * call.
 */
@Component
public class OptimizeItemHandler implements JobHandler {

    private final OptimizationRunStore runs;
    private final OptimizationEvidence evidence;
    private final TripService trips;
    private final Clock clock;

    public OptimizeItemHandler(OptimizationRunStore runs, OptimizationEvidence evidence,
            TripService trips, Clock clock) {
        this.runs = runs;
        this.evidence = evidence;
        this.trips = trips;
        this.clock = clock;
    }

    @Override
    public String type() {
        return OptimizationService.JOB_TYPE;
    }

    @Override
    public void handle(JobContext context) {
        UUID runId = runId(context);
        java.util.Optional<OptimizationRun> found = runs.find(runId);
        if (found.isEmpty()) {
            // The owner deleted the trip. optimization_runs.trip_id cascades, so the run went with it
            // and there is nothing left to decide - which is why this is a quiet return rather than a
            // failure: a job that dead-lettered here would raise an operator alert every time someone
            // deleted a trip while a preview was being computed.
            return;
        }
        OptimizationRun run = found.get();

        if (run.status().terminal()) {
            // A re-take of a job whose run already ended. At-least-once delivery makes this normal,
            // and finishing quietly is the idempotent answer: there is nothing left to decide.
            return;
        }
        Instant startedAt = clock.instant();
        if (run.status() == OptimizationStatus.QUEUED
                && !context.transactional(() -> runs.transition(runId, OptimizationStatus.QUEUED,
                        OptimizationStatus.RUNNING, startedAt))) {
            // Another worker started it first. Its lease, its attempt; this one stops rather than
            // racing it to the same writes.
            return;
        }

        // Frozen first, because the gate below is a question ABOUT the frozen input: freezing after
        // the check would leave the two describing different moments.
        //
        // Reading and writing in ONE unit of work, not two. Every read here goes through an owning
        // module's @Transactional service, and a handler that called one outside JobContext is
        // refused outright by JobUnitOfWorkGuard - correctly, because such a read would be in a
        // transaction this lease does not control. The same rule is why the evidence the run stores
        // is the evidence this transaction saw.
        Instant frozenAt = clock.instant();
        context.transactional(() -> runs.recordFrozenEvidence(runId,
                frozenAt.plus(OptimizationService.PREVIEW_TTL), evidence.snapshotSetsFor(run)));

        requireInputStillHolds(context, run);
    }

    /**
     * The gate a preview has to pass, and the only thing in this slice that can end a run.
     *
     * <p>The run froze a trip version. If the trip has since moved or been deleted, everything after
     * this point would describe an itinerary the owner does not have, so the run ends with the reason
     * rather than producing a preview nobody could apply. This runs BEFORE any preview is stored,
     * which is where the card's "READY 저장 전에 재검증" lives.
     */
    private void requireInputStillHolds(JobContext context, OptimizationRun run) {
        Instant failedAt = clock.instant();
        // The version read and the failure are one transaction. Split in two, the trip could move
        // between them - so a run could be failed for a version it no longer has, or worse, pass a
        // check that stopped being true before anything acted on it. The gate is only a gate if
        // reading it and acting on it cannot be separated.
        context.transactional(() -> {
            OptionalLong current = trips.versionFor(run.ownerId(), run.tripId());
            if (current.isPresent() && current.getAsLong() == run.inputTripVersion()) {
                return false;
            }
            // Deliberately one message for both shapes of the answer. "The trip is gone" is not a
            // state this can observe - the run's foreign key cascades, so a deleted trip takes the
            // run with it and handle() has already returned - so a second sentence for it would be
            // one no run can ever carry.
            return runs.fail(run.id(), OptimizationStatus.RUNNING,
                    OptimizationFailureCode.TRIP_CHANGED,
                    "The trip changed while this run was in flight.", failedAt);
        });
    }

    private static UUID runId(JobContext context) {
        try {
            return UUID.fromString(context.payload().get("runId"));
        } catch (RuntimeException invalid) {
            throw new JobExecutionException("INVALID_JOB_PAYLOAD",
                    "The optimization job payload is invalid.");
        }
    }
}
