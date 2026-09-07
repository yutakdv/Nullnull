package io.nullnull.recommendation.application;

import io.nullnull.recommendation.domain.PolicyDescriptor;
import io.nullnull.recommendation.domain.PolicyPins;
import io.nullnull.recommendation.domain.Reason;
import io.nullnull.recommendation.domain.item.ItemProposalOut;
import io.nullnull.recommendation.domain.item.ItemProposeRequest;
import io.nullnull.recommendation.domain.item.ItemProposeResponse;
import io.nullnull.recommendation.domain.item.LockIn;
import io.nullnull.recommendation.domain.item.NeighbourItemIn;
import io.nullnull.recommendation.domain.item.OpeningWindowIn;
import io.nullnull.recommendation.domain.item.TargetItemIn;
import io.nullnull.recommendation.domain.item.TemporalCandidateIn;
import io.nullnull.trip.domain.ItemLock;
import io.nullnull.trip.domain.LockChecks;
import io.nullnull.trip.domain.StayInterval;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * §3.1 final check (invariant 8): re-validates what the recommendation service returned against the
 * facts this API hydrated. It is the safety seam of ADR-0006 — {@code apps/ai} scores, Spring decides
 * whether the answer may touch a trip. The worker (BA-051) runs this right after the gateway returns
 * and, for any reason at all, records the run as {@code FAILED(DATA_CHANGED, retryable=false)},
 * persists nothing and raises an alert (D-REC-13).
 *
 * <p>Every violation is returned, not just the first, so an operator sees the whole disagreement. The
 * order is deterministic: response-level checks first, then each proposal in list order with its
 * checks in a fixed order. The response-level checks are the three policy identities pinned in
 * {@link PolicyPins} — {@code policyVersion}, {@code policyHash} and {@code pipelineVersion}, each
 * failing closed against the pin and not only against the worker's cached descriptor — the agreement
 * between the outcome and the proposal list, the proposal cap, rank contiguity and duplicate slots.
 * Verdicts, snapshot ids and metric values are read from the <em>request</em>
 * candidate; the response's own copies are only compared against it. Score, relief and change cost
 * arithmetic is not recomputed — the service is the scorer and its fixture corpus proves the numbers;
 * this class re-checks the eligibility facts that decide whether a proposal may exist at all.
 *
 * <p>An UNKNOWN fact (unverified hours, unknown stay length, missing route evidence) is a violation
 * here: a proposal the service marked verified must have had those facts (§3.1, UNKNOWN is never
 * promoted). The filters mirror {@code apps/ai/src/nullnull_ai/item/filters.py} one for one.
 *
 * <p>Reason details never carry place names, owner ids or user input.
 */
public final class ProposalRevalidator {

    public static final String POLICY_VERSION_MISMATCH = "POLICY_VERSION_MISMATCH";
    public static final String POLICY_HASH_MISMATCH = "POLICY_HASH_MISMATCH";
    public static final String PIPELINE_VERSION_MISMATCH = "PIPELINE_VERSION_MISMATCH";
    public static final String OUTCOME_MISMATCH = "OUTCOME_MISMATCH";
    public static final String TOO_MANY_PROPOSALS = "TOO_MANY_PROPOSALS";
    public static final String RANK_NOT_CONTIGUOUS = "RANK_NOT_CONTIGUOUS";
    public static final String DUPLICATE_SLOT = "DUPLICATE_SLOT";
    public static final String PROPOSAL_NOT_IN_REQUEST = "PROPOSAL_NOT_IN_REQUEST";
    public static final String VERDICT_INELIGIBLE = "VERDICT_INELIGIBLE";
    public static final String SNAPSHOT_MISMATCH = "SNAPSHOT_MISMATCH";
    public static final String METRIC_UNSUPPORTED = "METRIC_UNSUPPORTED";
    public static final String IMPROVEMENT_MISMATCH = "IMPROVEMENT_MISMATCH";
    public static final String IMPROVEMENT_BELOW_MINIMUM = "IMPROVEMENT_BELOW_MINIMUM";
    public static final String SCORE_NOT_POSITIVE = "SCORE_NOT_POSITIVE";
    public static final String NO_CHANGE = "NO_CHANGE";
    public static final String OUTSIDE_TRIP_RANGE = "OUTSIDE_TRIP_RANGE";
    public static final String OPENING_HOURS_UNKNOWN = "OPENING_HOURS_UNKNOWN";
    public static final String CLOSED = "CLOSED";
    public static final String DURATION_UNKNOWN = "DURATION_UNKNOWN";
    public static final String OUTSIDE_OPENING_HOURS = "OUTSIDE_OPENING_HOURS";
    public static final String NEIGHBOUR_DURATION_UNKNOWN = "NEIGHBOUR_DURATION_UNKNOWN";
    public static final String OVERLAPS_NEIGHBOUR = "OVERLAPS_NEIGHBOUR";
    public static final String ROUTE_EVIDENCE_MISSING = "ROUTE_EVIDENCE_MISSING";

    private final PolicyDescriptor cachedPolicy;

    /** @param cachedPolicy the {@code /policy} descriptor the worker cached for this run */
    public ProposalRevalidator(PolicyDescriptor cachedPolicy) {
        this.cachedPolicy = Objects.requireNonNull(cachedPolicy, "cachedPolicy");
    }

    /**
     * Every violation, in a deterministic order. An empty list means the answer may be persisted.
     *
     * @throws IllegalArgumentException when the hydrated lock set itself is malformed (two locks of one
     *     type, or a lock carrying a field of another type). That is a bug in this API, not an answer
     *     the service got wrong, so it is raised rather than reported; the worker (BA-051) catches it,
     *     records the run as {@code FAILED(DATA_CHANGED, retryable=false)} and alerts, exactly as it
     *     does for a returned reason.
     */
    public List<Reason> check(ItemProposeRequest request, ItemProposeResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        List<Reason> reasons = new ArrayList<>();
        checkResponse(response, reasons);
        // The lock set belongs to the trip, not to a proposal: convert it once, and fail here if it is malformed.
        List<ItemLock> locks = new ArrayList<>();
        for (LockIn lock : request.locks()) {
            locks.add(lock.toItemLock());
        }
        for (int index = 0; index < response.proposals().size(); index++) {
            checkProposal(request, response.proposals().get(index), index, locks, reasons);
        }
        return List.copyOf(reasons);
    }

    private void checkResponse(ItemProposeResponse response, List<Reason> reasons) {
        if (!response.policyVersion().equals(cachedPolicy.policyVersion())
                || !response.policyVersion().equals(PolicyPins.V1.policyVersion())) {
            reasons.add(Reason.of(POLICY_VERSION_MISMATCH, "answer was computed with another policy version"));
        }
        // Fail closed: a cached descriptor that is not the policy this API pins is not evidence either.
        if (!response.policyHash().equals(cachedPolicy.policyHash())
                || !cachedPolicy.policyHash().equals(PolicyPins.V1.policyHash())) {
            reasons.add(Reason.of(POLICY_HASH_MISMATCH, "answer was computed with another policy revision"));
        }
        // Fail closed, like the hash check: a cached descriptor naming a pipeline this API does not pin
        // is not evidence either, so a renamed pipeline cannot be accepted by caching its new name.
        if (!response.pipelineVersion().equals(cachedPolicy.pipelineVersion())
                || !cachedPolicy.pipelineVersion().equals(PolicyPins.V1.pipelineVersion())) {
            reasons.add(Reason.of(PIPELINE_VERSION_MISMATCH, "answer was computed by another pipeline"));
        }
        boolean proposed = !response.proposals().isEmpty();
        if ((response.outcome() == ItemProposeResponse.Outcome.PROPOSALS) != proposed) {
            reasons.add(Reason.of(OUTCOME_MISMATCH, "outcome does not agree with the proposal list"));
        }
        if (response.proposals().size() > PolicyPins.V1.caps().itemProposals()) {
            reasons.add(Reason.of(TOO_MANY_PROPOSALS, "more previews than the policy allows"));
        }
        int expectedRank = 1;
        for (ItemProposalOut proposal : response.proposals()) {
            if (proposal.rank() != expectedRank++) {
                reasons.add(Reason.of(RANK_NOT_CONTIGUOUS, "ranks must run 1..n in list order"));
                break;
            }
        }
        Set<Slot> slots = new HashSet<>();
        for (ItemProposalOut proposal : response.proposals()) {
            if (!slots.add(new Slot(proposal.date(), proposal.startTime()))) {
                reasons.add(Reason.of(DUPLICATE_SLOT, "two previews claim the same slot"));
                break;
            }
        }
    }

    private void checkProposal(ItemProposeRequest request, ItemProposalOut proposal, int index,
            List<ItemLock> locks, List<Reason> reasons) {
        String where = "preview " + (index + 1) + ": ";
        TargetItemIn target = request.target();
        List<TemporalCandidateIn> matches = request.candidates().stream()
                .filter(candidate -> candidate.placeId().equals(target.placeId())
                        && candidate.date().equals(proposal.date())
                        && Objects.equals(candidate.effectiveStartTime(target.startTime()), proposal.startTime()))
                .toList();
        if (matches.isEmpty()) {
            reasons.add(Reason.of(PROPOSAL_NOT_IN_REQUEST, where + "slot was not among the hydrated candidates"));
            return;
        }
        // Several candidates may share one slot - a DAY candidate keeps the item's current time, so it can
        // land on the same (date, start time) as an HOUR candidate, and the service merges them into one
        // preview. The snapshot pair says which one it kept; without a match the answer cites evidence this
        // API never hydrated, so only the slot itself can still be judged.
        TemporalCandidateIn candidate = matches.size() == 1 ? matches.get(0) : matches.stream()
                .filter(match -> match.beforeSnapshotId().equals(proposal.beforeSnapshotId())
                        && match.afterSnapshotId().equals(proposal.afterSnapshotId()))
                .findFirst().orElse(null);
        // One code per proposal: a missing stay length blocks the opening and the overlap check alike.
        Set<String> codes = new LinkedHashSet<>();
        if (candidate == null) {
            codes.add(SNAPSHOT_MISMATCH);
        } else {
            checkCandidateValues(candidate, proposal, codes);
        }
        if (proposal.date().equals(target.date()) && Objects.equals(proposal.startTime(), target.startTime())) {
            codes.add(NO_CHANGE);
        }
        if (proposal.date().isBefore(request.tripStart()) || proposal.date().isAfter(request.tripEnd())) {
            codes.add(OUTSIDE_TRIP_RANGE);
        }
        codes.addAll(LockChecks.evaluate(locks, proposal.date(), proposal.startTime(), target.durationMinutes())
                .reasonCodes());
        addIfPresent(codes, openingHours(request.openingHours().get(proposal.date()), proposal.startTime(),
                target.durationMinutes()));
        addIfPresent(codes, neighbourOverlap(request.neighbours(), target.itemId(), proposal.date(),
                proposal.startTime(), target.durationMinutes()));
        addIfPresent(codes, routeEvidence(request, proposal.date()));
        for (String code : codes) {
            reasons.add(Reason.of(code, where + "re-validation failed"));
        }
    }

    /**
     * The facts that belong to the matched candidate. Verdict, snapshots, metric and the before/after
     * values are read from the request; only {@code score} is the service's own number, and it is only
     * checked for a sign, never recomputed.
     */
    private static void checkCandidateValues(TemporalCandidateIn candidate, ItemProposalOut proposal,
            Set<String> codes) {
        if (!candidate.verdictEligible()) {
            codes.add(VERDICT_INELIGIBLE);
        }
        if (!candidate.beforeSnapshotId().equals(proposal.beforeSnapshotId())
                || !candidate.afterSnapshotId().equals(proposal.afterSnapshotId())) {
            codes.add(SNAPSHOT_MISMATCH);
        }
        PolicyPins.MetricPin metric = PolicyPins.V1.metric(candidate.metricCode());
        if (metric == null) {
            codes.add(METRIC_UNSUPPORTED);
        }
        BigDecimal improvement = candidate.beforeValue().subtract(candidate.afterValue());
        if (improvement.compareTo(proposal.improvement()) != 0) {
            codes.add(IMPROVEMENT_MISMATCH);
        }
        if (metric != null && improvement.compareTo(BigDecimal.valueOf(metric.minimumImprovement())) < 0) {
            codes.add(IMPROVEMENT_BELOW_MINIMUM);
        }
        if (proposal.score().signum() <= 0) {
            codes.add(SCORE_NOT_POSITIVE);
        }
    }

    /**
     * Mirrors {@code filters.opening_hours}: the whole stay must sit inside a verified window (§5.4).
     * A missing entry is an unverified window, never an open one.
     */
    private static String openingHours(OpeningWindowIn window, LocalTime start, Integer durationMinutes) {
        if (window == null || window.state() == OpeningWindowIn.OpeningState.UNKNOWN) {
            return OPENING_HOURS_UNKNOWN;
        }
        if (window.state() == OpeningWindowIn.OpeningState.CLOSED) {
            return CLOSED;
        }
        if (start == null) {
            return null;
        }
        if (durationMinutes == null) {
            return DURATION_UNKNOWN;
        }
        StayInterval.End end = StayInterval.endOf(start, durationMinutes);
        boolean inside = !end.wrappedPastMidnight() && !start.isBefore(window.opensAt())
                && !end.time().isAfter(window.closesAt());
        return inside ? null : OUTSIDE_OPENING_HOURS;
    }

    /**
     * Mirrors {@code filters.neighbour_overlap}: both intervals must be fully known, the item is not a
     * neighbour of itself, and a stay running past midnight occupies the rest of its own date.
     *
     * <p>Every neighbour of that date is scanned before answering and a fact outranks a missing one: a
     * confirmed overlap is reported even when another neighbour has no verified length, and only when
     * nothing overlaps does an unmeasured neighbour make the check UNKNOWN. Returning the first verdict
     * seen would make the answer depend on the order this API hydrated the items in (§6).
     */
    private static String neighbourOverlap(List<NeighbourItemIn> neighbours, java.util.UUID targetItemId,
            LocalDate day, LocalTime start, Integer durationMinutes) {
        if (start == null) {
            return null;
        }
        if (durationMinutes == null) {
            return DURATION_UNKNOWN;
        }
        StayInterval.Stay stay = StayInterval.of(start, durationMinutes);
        boolean unmeasured = false;
        for (NeighbourItemIn neighbour : neighbours) {
            if (neighbour.itemId().equals(targetItemId) || !neighbour.date().equals(day)
                    || neighbour.startTime() == null) {
                continue;
            }
            if (neighbour.durationMinutes() == null) {
                unmeasured = true;
                continue;
            }
            StayInterval.Stay neighbourStay = StayInterval.of(neighbour.startTime(), neighbour.durationMinutes());
            if ((stay.beginsAt().isBefore(neighbourStay.endsAt()) && neighbourStay.beginsAt().isBefore(stay.endsAt()))
                    || start.equals(neighbour.startTime())) {
                return OVERLAPS_NEIGHBOUR;
            }
        }
        return unmeasured ? NEIGHBOUR_DURATION_UNKNOWN : null;
    }

    /** Mirrors {@code filters.route_evidence}: legs change when either day holds another item. */
    private static String routeEvidence(ItemProposeRequest request, LocalDate day) {
        LocalDate from = request.target().date();
        boolean legsAffected = request.neighbours().stream()
                .anyMatch(neighbour -> !neighbour.itemId().equals(request.target().itemId())
                        && (neighbour.date().equals(from) || neighbour.date().equals(day)));
        return legsAffected && request.routeEvidence() != ItemProposeRequest.RouteEvidence.VERIFIED
                ? ROUTE_EVIDENCE_MISSING : null;
    }

    private static void addIfPresent(Set<String> codes, String code) {
        if (code != null) {
            codes.add(code);
        }
    }

    /** One (date, start time) preview slot; an untimed proposal keeps a null time. */
    private record Slot(LocalDate date, LocalTime startTime) {
    }
}
