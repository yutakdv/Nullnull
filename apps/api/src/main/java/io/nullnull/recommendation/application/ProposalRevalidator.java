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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * checks in a fixed order. Verdicts, snapshot ids and metric values are read from the <em>request</em>
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

    /** Any fixed date: a stay is compared inside one calendar day, never across one. */
    private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

    private final PolicyDescriptor cachedPolicy;

    /** @param cachedPolicy the {@code /policy} descriptor the worker cached for this run */
    public ProposalRevalidator(PolicyDescriptor cachedPolicy) {
        this.cachedPolicy = Objects.requireNonNull(cachedPolicy, "cachedPolicy");
    }

    /** Every violation, in a deterministic order. An empty list means the answer may be persisted. */
    public List<Reason> check(ItemProposeRequest request, ItemProposeResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        List<Reason> reasons = new ArrayList<>();
        checkResponse(response, reasons);
        for (int index = 0; index < response.proposals().size(); index++) {
            checkProposal(request, response.proposals().get(index), index, reasons);
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

    private void checkProposal(ItemProposeRequest request, ItemProposalOut proposal, int index, List<Reason> reasons) {
        String where = "preview " + (index + 1) + ": ";
        TargetItemIn target = request.target();
        List<TemporalCandidateIn> matches = request.candidates().stream()
                .filter(candidate -> candidate.placeId().equals(target.placeId())
                        && candidate.date().equals(proposal.date())
                        && Objects.equals(candidate.effectiveStartTime(target.startTime()), proposal.startTime()))
                .toList();
        if (matches.size() != 1) {
            reasons.add(Reason.of(PROPOSAL_NOT_IN_REQUEST, where + "slot was not among the hydrated candidates"));
            return;
        }
        TemporalCandidateIn candidate = matches.get(0);
        // One code per proposal: a missing stay length blocks the opening and the overlap check alike.
        Set<String> codes = new LinkedHashSet<>();
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
        if (proposal.date().equals(target.date()) && Objects.equals(proposal.startTime(), target.startTime())) {
            codes.add(NO_CHANGE);
        }
        if (proposal.date().isBefore(request.tripStart()) || proposal.date().isAfter(request.tripEnd())) {
            codes.add(OUTSIDE_TRIP_RANGE);
        }
        List<ItemLock> locks = new ArrayList<>();
        for (LockIn lock : request.locks()) {
            locks.add(lock.toItemLock());
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
        LocalDateTime end = LocalDateTime.of(EPOCH, start).plusMinutes(durationMinutes);
        boolean wrapped = !end.toLocalDate().equals(EPOCH);
        boolean inside = !wrapped && !start.isBefore(window.opensAt()) && !end.toLocalTime().isAfter(window.closesAt());
        return inside ? null : OUTSIDE_OPENING_HOURS;
    }

    /**
     * Mirrors {@code filters.neighbour_overlap}: both intervals must be fully known, the item is not a
     * neighbour of itself, and a stay running past midnight occupies the rest of its own date.
     */
    private static String neighbourOverlap(List<NeighbourItemIn> neighbours, java.util.UUID targetItemId,
            LocalDate day, LocalTime start, Integer durationMinutes) {
        if (start == null) {
            return null;
        }
        if (durationMinutes == null) {
            return DURATION_UNKNOWN;
        }
        LocalDateTime begins = LocalDateTime.of(EPOCH, start);
        LocalDateTime ends = endOfStay(start, durationMinutes);
        for (NeighbourItemIn neighbour : neighbours) {
            if (neighbour.itemId().equals(targetItemId) || !neighbour.date().equals(day)
                    || neighbour.startTime() == null) {
                continue;
            }
            if (neighbour.durationMinutes() == null) {
                return NEIGHBOUR_DURATION_UNKNOWN;
            }
            LocalDateTime neighbourBegins = LocalDateTime.of(EPOCH, neighbour.startTime());
            LocalDateTime neighbourEnds = endOfStay(neighbour.startTime(), neighbour.durationMinutes());
            if ((begins.isBefore(neighbourEnds) && neighbourBegins.isBefore(ends))
                    || start.equals(neighbour.startTime())) {
                return OVERLAPS_NEIGHBOUR;
            }
        }
        return null;
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

    /** The stay as a half-open interval that never folds back into the morning of its own date. */
    private static LocalDateTime endOfStay(LocalTime start, int durationMinutes) {
        LocalDateTime dayEnd = LocalDateTime.of(EPOCH.plusDays(1), LocalTime.MIDNIGHT);
        LocalDateTime end = LocalDateTime.of(EPOCH, start).plusMinutes(durationMinutes);
        return end.isAfter(dayEnd) ? dayEnd : end;
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
