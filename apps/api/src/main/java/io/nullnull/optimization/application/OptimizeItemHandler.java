package io.nullnull.optimization.application;

import io.nullnull.catalog.application.CatalogHoursQuery;
import io.nullnull.catalog.application.CatalogHoursQuery.CatalogOpeningWindow;
import io.nullnull.catalog.application.CatalogPlaceQuery;
import io.nullnull.catalog.application.CatalogPublicationProperties;
import io.nullnull.catalog.application.CatalogVersion;
import io.nullnull.crowd.domain.CrowdMetricLabel;
import io.nullnull.identity.application.OwnerPreferencesService;
import io.nullnull.operations.application.JobContext;
import io.nullnull.operations.application.JobExecutionException;
import io.nullnull.operations.application.JobHandler;
import io.nullnull.optimization.domain.OptimizationFailureCode;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.optimization.domain.OptimizationStatus;
import io.nullnull.recommendation.application.ProposalRevalidator;
import io.nullnull.recommendation.application.RecommendationGateway;
import io.nullnull.recommendation.application.RunFingerprint;
import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.Reason;
import io.nullnull.recommendation.domain.explanation.ExplanationRenderRequest;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.application.TripService;
import io.nullnull.trip.domain.Trip;
import io.nullnull.trip.domain.TripItem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Runs one ITEM optimization end to end: freeze, ask, re-check, store.
 *
 * <p>BA-050 built everything up to the gate and stopped there, because the call this makes had
 * nothing to carry. BA-051 fills that in, and the order below is the whole of it. The run freezes
 * what an answer will be judged against; the gate asks whether the frozen input still describes the
 * trip; the candidates are assembled from stored forecasts; {@code apps/ai} is asked; the answer is
 * re-checked against the request it came from; and only then is a preview stored and published.
 *
 * <p>Every step that can end the run ends it with a reason, and the reasons are not
 * interchangeable. No comparable forecast is {@code DATA_INSUFFICIENT} and never
 * {@code NO_IMPROVEMENT} - nothing was judged and found wanting, there was nothing to judge. An
 * answer the revalidator refuses is {@code DATA_CHANGED} - the service replied, but not about the
 * evidence this run froze.
 *
 * <p>What this handler must NOT do is equally deliberate. It writes no trip row: invariant 3 says a
 * preview changes nothing until a person applies it, and this module has no mutation to call even
 * if it wanted one. The call to {@code apps/ai} happens outside every transaction, asserted at the
 * call site rather than described here ({@link #requireNoTransaction()}).
 */
@Component
public class OptimizeItemHandler implements JobHandler {

    private final OptimizationRunStore runs;
    private final OptimizationProposalStore proposals;
    private final OptimizationEvidence evidence;
    private final TemporalCandidateAssembler candidates;
    private final ItemProposalMapper mapper;
    private final TripService trips;
    private final CatalogHoursQuery hours;
    private final CatalogPlaceQuery places;
    private final CatalogPublicationProperties publication;
    private final CatalogVersion catalogVersion;
    private final OwnerPreferencesService owners;
    private final RecommendationGateway recommendations;
    private final Clock clock;

    public OptimizeItemHandler(OptimizationRunStore runs, OptimizationProposalStore proposals,
            OptimizationEvidence evidence, TemporalCandidateAssembler candidates,
            ItemProposalMapper mapper, TripService trips, CatalogHoursQuery hours,
            CatalogPlaceQuery places, CatalogPublicationProperties publication,
            CatalogVersion catalogVersion, OwnerPreferencesService owners,
            RecommendationGateway recommendations, Clock clock) {
        this.runs = runs;
        this.proposals = proposals;
        this.evidence = evidence;
        this.candidates = candidates;
        this.mapper = mapper;
        this.trips = trips;
        this.hours = hours;
        this.places = places;
        this.publication = publication;
        this.catalogVersion = catalogVersion;
        this.owners = owners;
        this.recommendations = recommendations;
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

        if (!requireInputStillHolds(context, run)) {
            return;
        }

        // Everything the question is built from, read in one unit of work. Assembling the request is
        // not another chance to read: a value fetched afterwards would describe a later moment than
        // the evidence this run froze.
        Prepared prepared = context.transactional(() -> prepare(run));
        if (prepared.candidates().isEmpty()) {
            // Not NO_IMPROVEMENT. Nothing was judged and found wanting - there was nothing to judge,
            // because no forecast covers this trip or none covers the day the item is on. The card
            // forbids hiding a data failure as an improvement failure, and #225 added the code that
            // lets this be said at all.
            failRun(context, run, OptimizationFailureCode.DATA_INSUFFICIENT,
                    "There was not enough forecast evidence to compare any other day.");
            return;
        }

        // OUTSIDE the unit of work, and that is BA-051-T4. An external call inside a transaction
        // holds a database connection for the length of a network round trip, and JobContext refuses
        // to open one inside another anyway. It is the mirror of createWithinCallersGuard's
        // MANDATORY: both are measured with isActualTransactionActive(), one demanding a transaction
        // and this one demanding its absence.
        requireNoTransaction();
        // The policy this run is judged against, read once and used twice: the revalidator compares
        // the answer to it and the fingerprint records it. Two reads could straddle a deployment of
        // apps/ai, and then the run would be checked against one policy and stamped with another.
        //
        // Built here rather than injected because ProposalRevalidator is not a singleton and must not
        // be: its whole contract is "the descriptor the worker cached FOR THIS RUN". A bean made at
        // startup would hold whatever the service published then, which is the drift it exists to
        // catch. Nothing in this repository defines such a bean, and a constructor asking for one
        // would not start.
        PolicyDescriptor policy = recommendations.policy();
        ItemProposeResponse answer = recommendations.proposeItem(prepared.request());

        List<Reason> refusals = new ProposalRevalidator(policy).check(prepared.request(), answer);
        if (!refusals.isEmpty()) {
            // The service answered something we are not entitled to store. DATA_CHANGED rather than
            // a new code: the evidence the run froze no longer supports what came back.
            failRun(context, run, OptimizationFailureCode.DATA_CHANGED,
                    "The answer did not match the evidence this run froze.");
            return;
        }

        switch (answer.outcome()) {
            case PROPOSALS -> publish(context, run, prepared, answer, policy);
            case LOCK_CONFLICT -> failRun(context, run, OptimizationFailureCode.LOCK_CONFLICT,
                    "A lock on this item refuses every day the optimizer could offer.");
            case ROUTE_UNAVAILABLE -> failRun(context, run, OptimizationFailureCode.ROUTE_UNAVAILABLE,
                    "No route evidence was available to show a change is reachable.");
            case DATA_INSUFFICIENT -> failRun(context, run, OptimizationFailureCode.DATA_INSUFFICIENT,
                    "There was not enough evidence to judge any alternative.");
            case NO_IMPROVEMENT -> failRun(context, run, OptimizationFailureCode.NO_IMPROVEMENT,
                    "Nothing offered was better than the day this item already has.");
        }
    }

    /**
     * The gate a preview has to pass, and the only thing in this slice that can end a run.
     *
     * <p>The run froze a trip version. If the trip has since moved or been deleted, everything after
     * this point would describe an itinerary the owner does not have, so the run ends with the reason
     * rather than producing a preview nobody could apply. This runs BEFORE any preview is stored,
     * which is where the card's "READY 저장 전에 재검증" lives.
     */
    private boolean requireInputStillHolds(JobContext context, OptimizationRun run) {
        Instant failedAt = clock.instant();
        // The version read and the failure are one transaction. Split in two, the trip could move
        // between them - so a run could be failed for a version it no longer has, or worse, pass a
        // check that stopped being true before anything acted on it. The gate is only a gate if
        // reading it and acting on it cannot be separated.
        // true from the transaction means "this call failed the run", so the gate answers the
        // opposite: the input still holds when nothing was failed.
        return !context.transactional(() -> {
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

    /**
     * The question, and the facts that will be needed after the answer comes back.
     *
     * <p>All of it read inside one transaction. The place name and the owner's language are here
     * rather than at explanation time for the same reason the candidates are: an explanation written
     * from a name fetched later would describe a catalog that had moved since the evidence froze.
     */
    private Prepared prepare(OptimizationRun run) {
        Trip trip = trips.findForOwner(run.ownerId(), run.tripId())
                .orElseThrow(() -> new IllegalStateException("the run outlived its trip"));
        List<TripItem> items = trips.itemsOf(run.tripId());
        TripItem target = items.stream().filter(item -> item.id().equals(run.targetItemId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the run's target item is no longer in the trip"));
        Instant now = clock.instant();
        TemporalCandidateAssembler.Candidates offered = candidates.candidatesFor(target.placeId(),
                target.date(), trip.range().startDate(), trip.range().endDate(),
                trip.range().timezone(), now);
        if (offered.isEmpty()) {
            return new Prepared(null, offered, null, null, null, trip, target);
        }
        // The gate applies here even though this is a job: the place name travels into a sentence a
        // traveller reads, which is exactly the provider-derived content the publication gate exists
        // to hold back.
        publication.requirePublicProjection();
        String locale = owners.get(run.ownerId()).locale().startsWith("ko") ? "ko" : "en";
        String placeName = places.find(target.placeId(), locale, now)
                .orElseThrow(() -> new IllegalStateException("the target item names a place the catalog has not published"))
                .name();
        ItemProposeRequest request = ItemProposeRequests.of(run, trip, items,
                openingHours(target.placeId(), trip, now), offered.items(), now);
        return new Prepared(request, offered, locale, placeName, catalogVersion.current(), trip, target);
    }

    /**
     * Stores the preview and publishes it, in that order and in one unit of work.
     *
     * <p>The explanations are rendered first, outside any transaction, because each is another call
     * to {@code apps/ai}. They are paired to the proposals by position, which the mapper checks: a
     * sentence explaining a different row than the one it sits on would tell a traveller why to move
     * to a day that row does not move to.
     */
    private void publish(JobContext context, OptimizationRun run, Prepared prepared,
            ItemProposeResponse answer, PolicyDescriptor policy) {
        Map<LocalDate, TemporalCandidateIn> byDate = new LinkedHashMap<>();
        prepared.candidates().items().forEach(candidate -> byDate.put(candidate.date(), candidate));

        requireNoTransaction();
        List<String> summaries = new ArrayList<>(answer.proposals().size());
        answer.proposals().forEach(proposal -> summaries.add(
                recommendations.renderExplanation(explanation(prepared, byDate, proposal)).summary()));

        Instant at = clock.instant();
        List<OptimizationProposal> stored = mapper.toProposals(run, prepared.target(),
                answer.proposals(), byDate, at, summaries);
        String fingerprint = RunFingerprint.of(new RunFingerprint.Inputs(
                Objects.requireNonNull(run.inputRevisionId(), "every trip has a first revision"),
                run.inputTripVersion(), prepared.candidates().snapshotIds(),
                prepared.candidates().sourceRegistryVersions(),
                prepared.candidates().normalizationVersion(), policy.policyVersion(),
                policy.policyHash(), policy.pipelineVersion(), prepared.catalogVersion(),
                at.plus(OptimizationService.PREVIEW_TTL)));

        // One unit of work: a preview that is stored but not published would be offered by nothing,
        // and one published without its proposals would be offered with nothing in it.
        context.transactional(() -> {
            proposals.insertAll(stored);
            return runs.markReady(run.id(), fingerprint, policy.pipelineVersion(), at);
        });
    }

    private ExplanationRenderRequest explanation(Prepared prepared,
            Map<LocalDate, TemporalCandidateIn> byDate,
            io.nullnull.recommendation.domain.item.ItemProposalOut proposal) {
        TemporalCandidateIn candidate = byDate.get(proposal.date());
        return new ExplanationRenderRequest(prepared.locale(), prepared.placeName(),
                prepared.target().date(), prepared.target().startTime(), proposal.date(),
                candidate.effectiveStartTime(prepared.target().startTime()), candidate.beforeValue(),
                candidate.afterValue(),
                CrowdMetricLabel.of(prepared.candidates().metricCode(), prepared.locale()),
                prepared.candidates().attribution(), prepared.candidates().forecastIssueId());
    }

    private Map<LocalDate, OpeningWindowIn> openingHours(java.util.UUID placeId, Trip trip, Instant now) {
        Map<LocalDate, CatalogOpeningWindow> verified = hours.windowsFor(placeId,
                trip.range().startDate(), trip.range().endDate(), now);
        Map<LocalDate, OpeningWindowIn> converted = new LinkedHashMap<>(verified.size());
        verified.forEach((date, window) -> converted.put(date,
                window.state() == CatalogOpeningWindow.State.OPEN
                        ? OpeningWindowIn.open(window.opensAt(), window.closesAt())
                        : OpeningWindowIn.closed()));
        return converted;
    }

    private void failRun(JobContext context, OptimizationRun run, OptimizationFailureCode code,
            String message) {
        Instant at = clock.instant();
        context.transactional(() -> runs.fail(run.id(), OptimizationStatus.RUNNING, code, message, at));
    }

    /**
     * BA-051-T4, asserted where the call is made rather than described in a comment.
     *
     * <p>The mirror of {@code Propagation.MANDATORY}: that refuses to run without a caller's
     * transaction, this refuses to call out of the process inside one. Both read the same flag, and
     * saying so here means the pair can be found from either side.
     */
    private static void requireNoTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "apps/ai is called outside the unit of work; a transaction is open (BA-051-T4)");
        }
    }

    /** What one run needs, read once. */
    private record Prepared(ItemProposeRequest request, TemporalCandidateAssembler.Candidates candidates,
            String locale, String placeName, String catalogVersion, Trip trip, TripItem target) {
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
