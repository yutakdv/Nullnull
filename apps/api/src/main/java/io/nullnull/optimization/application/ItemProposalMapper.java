package io.nullnull.optimization.application;

import io.nullnull.optimization.domain.OptimizationChange;
import io.nullnull.optimization.domain.OptimizationChangeOperation;
import io.nullnull.optimization.domain.OptimizationProposal;
import io.nullnull.optimization.domain.OptimizationRun;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
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
                null, json.writeValueAsString(Map.of("checks", answer.lockChecks())), createdAt,
                List.of(move));
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
