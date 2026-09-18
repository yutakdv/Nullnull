package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.domain.LockType;
import io.nullnull.trip.domain.TripItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One answer from {@code apps/ai}, turned into the rows a traveller will be shown.
 *
 * <p>An ITEM run proposes one thing: the target item on a different day. So each proposal becomes
 * exactly one MOVE, and the before and after are the item as it is now and as it would be - the
 * shape the contract calls {@code TripItemState}.
 *
 * <p><strong>The crowd numbers are not copied into the change.</strong> The run already froze the
 * snapshot sets its answer was judged against, and a second copy here would be a second source for
 * one fact - the pair that drifts is the pair nobody compares. What IS stored is the delta on the
 * proposal, because that is the claim the proposal makes, and it is stored only when the pair was
 * eligible: {@code OptimizationProposal} refuses a delta without eligibility, so an ineligible
 * candidate arrives here with its reason and no number.
 */
@Component
public class ItemProposalMapper {

    private final ObjectMapper json;

    public ItemProposalMapper(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * @param summaries one explanation per answer, in the same order. Each proposal's summary is
     *     about that proposal; a single shared supplier would give every row the same sentence.
     * @param candidatesByDate the candidates the request carried, which is where eligibility lives.
     *     A proposal for a date nobody offered is a contradiction rather than a surprise: the
     *     revalidator refuses it as {@code PROPOSAL_NOT_IN_REQUEST} before this runs.
     */
    public List<OptimizationProposal> toProposals(OptimizationRun run, TripItem target,
            List<ItemProposalOut> answers, Map<LocalDate, TemporalCandidateIn> candidatesByDate,
            Instant createdAt, List<String> summaries) {
        // Positional, and checked. A summary explains one proposal, so the two lists are the same
        // answer read twice; if they ever differ in length the explanations have been paired with
        // the wrong proposals, and a traveller would read why to move to a day the row does not
        // move to.
        if (summaries.size() != answers.size()) {
            throw new IllegalArgumentException("every proposal needs its own explanation");
        }
        return java.util.stream.IntStream.range(0, answers.size())
                .mapToObj(index -> toProposal(run, target, answers.get(index),
                        candidatesByDate.get(answers.get(index).date()), createdAt,
                        summaries.get(index)))
                .toList();
    }

    private OptimizationProposal toProposal(OptimizationRun run, TripItem target, ItemProposalOut answer,
            TemporalCandidateIn candidate, Instant createdAt, String summary) {
        if (candidate == null) {
            throw new IllegalStateException("a proposal names a date the request did not offer");
        }
        boolean eligible = candidate.verdictEligible();
        BigDecimal delta = eligible
                ? candidate.afterValue().subtract(candidate.beforeValue())
                : null;
        LocalTime startTime = candidate.effectiveStartTime(target.startTime());
        OptimizationChange move = new OptimizationChange(UUID.randomUUID(), target.id(),
                OptimizationChangeOperation.MOVE,
                state(target.placeId(), target.date(), target.position(), target.startTime()),
                state(target.placeId(), answer.date(), target.position(), startTime), 0);
        return new OptimizationProposal(UUID.randomUUID(), run.id(), answer.rank(), summary, eligible,
                eligible ? null : candidate.verdictReasonCode(), delta,
                // P0 confirms no route provider, so no proposal can claim a travel-time difference.
                // Null is the honest value; zero would be a measurement.
                null, answer.beforeSnapshotId(), answer.afterSnapshotId(),
                json.writeValueAsString(Map.of("checks", answer.lockChecks())), createdAt,
                List.of(move));
    }

    /**
     * A stored {@link #state} read back, for the response that shows it (getOptimization).
     *
     * <p>Kept beside the writer so the stored shape has one owner in both directions. The start time
     * is a {@link LocalTime} again rather than the stored text: {@code LocalTime.toString()} wrote
     * {@code 09:00} on the minute, and the contract's pattern requires the seconds - rendering is the
     * response's job, not the row's.
     */
    public ItemState readState(String stored) {
        return stored == null ? null : json.readValue(stored, ItemState.class);
    }

    /**
     * The stored {@code validation_summary} as the contract's {@code ValidationSummary} (#242).
     *
     * <p>The row keeps what apps/ai asserted - its {@code lockChecks} map, one entry per lock the
     * target item has - and this is the only place that turns it into the contract's list. Storage is
     * left as it is because it is evidence as received; the list is a projection of it.
     *
     * <p>{@code allConstraintsPreserved} is derived: every check passed. An ITEM proposal changes only
     * its target's day, so the target's locks are the constraints it can break, and an item with no
     * lock has none to break - true, not unknown. The checks are ordered by {@link LockType} because a
     * jsonb object does not keep the order its keys were written in.
     *
     * <p>A key that is not a lock type, or a value that is not a boolean, is refused rather than
     * dropped: the response would otherwise say every constraint held while leaving out the one entry
     * nobody could read.
     */
    public Validation readValidation(String stored) {
        StoredValidation read = json.readValue(stored, StoredValidation.class);
        if (read.checks() == null) {
            throw new IllegalStateException("a stored validation summary has no checks");
        }
        List<Check> checks = new java.util.ArrayList<>(read.checks().size());
        read.checks().forEach((key, passed) -> {
            if (passed == null) {
                throw new IllegalStateException("lock check " + key + " carries no verdict");
            }
            checks.add(new Check(lockType(key), passed));
        });
        checks.sort(java.util.Comparator.comparing(Check::constraintType));
        return new Validation(checks.stream().allMatch(Check::passed), List.copyOf(checks));
    }

    private static LockType lockType(String key) {
        try {
            return LockType.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("lock check names no lock type: " + key, unknown);
        }
    }

    record StoredValidation(Map<String, Boolean> checks) {
    }

    /** {@code TripItemState} as stored, minus {@code crowd}, which is never stored here. */
    public record ItemState(UUID placeId, LocalDate date, int position, LocalTime startTime) {
    }

    public record Validation(boolean allConstraintsPreserved, List<Check> checks) {
    }

    public record Check(LockType constraintType, boolean passed) {
    }

    /**
     * The contract's TripItemState, minus {@code crowd}.
     *
     * <p>Position is carried unchanged because a day move keeps the item's place in its new day's
     * order only by coincidence; what the traveller approves is the day, and the position the
     * itinerary settles on is the trip's own rule at APPLY time rather than a promise made here.
     */
    private String state(UUID placeId, LocalDate date, int position, LocalTime startTime) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("placeId", placeId.toString());
        value.put("date", date.toString());
        value.put("position", position);
        value.put("startTime", startTime == null ? null : startTime.toString());
        return json.writeValueAsString(value);
    }
}
