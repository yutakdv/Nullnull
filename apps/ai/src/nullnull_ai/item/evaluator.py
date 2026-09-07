"""§5.4-§5.6, §8 worker step: generate → filter → comparability → score → select → classify.

Pure: the caller supplies every fact. Filters run in a fixed order and stop at the first non-eligible
result; that first reason decides the candidate's rejection class and, when nothing is admitted, the
terminal outcome. The detailed cap is applied on a fixed key so the source's arrival order can never
change which candidates are evaluated.

An item without a start time resolves to local midnight on both sides of the shift (`domain.time`
treats a missing time as the start of the day, D-REC-4). Assigning a time to such an item therefore
costs a move of several hours and saturates `changeCost` at 1, so only a large relief can carry it.
That is the conservative reading - the alternative, charging a date-level cost when the target has no
time, would claim that giving an untimed item a time is a small change, which no product decision
supports yet. Open as **D-REC-16**; `tests/item/test_evaluator.py` pins the current behaviour.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime, time
from enum import Enum

from nullnull_ai.domain import time as trip_time
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Reason, RecommendationContext
from nullnull_ai.item import filters
from nullnull_ai.item.score import (
    COMPARISON_INELIGIBLE,
    METRIC_POLICY_MISSING,
    Admitted,
    ItemScorePolicy,
    Rejected,
    ScoredCandidate,
    TemporalShift,
    proposal_sort_key,
)
from nullnull_ai.item.types import ItemOptimizationInput, LockType, TemporalCandidate, UnknownHours

CANDIDATE_CAP_EXCEEDED = "CANDIDATE_CAP_EXCEEDED"
INVALID_LOCAL_TIME = "INVALID_LOCAL_TIME"
NO_CANDIDATES = "NO_CANDIDATES"

_LOCK = {filters.DATE_LOCKED, filters.TIME_LOCKED, filters.RESERVATION_LOCKED}
_ROUTE = {filters.ROUTE_EVIDENCE_MISSING}
_DATA = {
    filters.OPENING_HOURS_UNKNOWN,
    filters.DURATION_UNKNOWN,
    filters.NEIGHBOUR_DURATION_UNKNOWN,
    COMPARISON_INELIGIBLE,
    METRIC_POLICY_MISSING,
    INVALID_LOCAL_TIME,
}


class Outcome(Enum):
    """Terminal outcome of one ITEM evaluation; each value maps to one async failure plane (§8)."""

    PROPOSALS = "PROPOSALS"
    LOCK_CONFLICT = "LOCK_CONFLICT"
    ROUTE_UNAVAILABLE = "ROUTE_UNAVAILABLE"
    DATA_INSUFFICIENT = "DATA_INSUFFICIENT"
    NO_IMPROVEMENT = "NO_IMPROVEMENT"


@dataclass(frozen=True, slots=True)
class RejectionSummary:
    """Rejection counts per internal reason code - observable without exposing any input."""

    evaluated: int
    rejected_by_reason: Mapping[str, int]


@dataclass(frozen=True, slots=True)
class ItemProposal:
    rank: int
    candidate: TemporalCandidate
    proposed_start_time: time | None
    before_instant: datetime
    after_instant: datetime
    admission: Admitted
    lock_checks: Mapping[LockType, bool]

    def __post_init__(self) -> None:
        if self.rank < 1:
            raise ValueError("rank starts at 1")


@dataclass(frozen=True, slots=True)
class ItemProposalResult:
    outcome: Outcome
    proposals: tuple[ItemProposal, ...]
    reasons: tuple[Reason, ...]
    summary: RejectionSummary

    def __post_init__(self) -> None:
        if (self.outcome is Outcome.PROPOSALS) != bool(self.proposals):
            raise ValueError("PROPOSALS requires at least one proposal and every other outcome requires none")


@dataclass(frozen=True, slots=True)
class _Scored:
    scored: ScoredCandidate
    candidate: TemporalCandidate
    before_instant: datetime
    after_instant: datetime
    lock_checks: Mapping[LockType, bool]


def _classify(code: str) -> str:
    if code in _LOCK:
        return "LOCK"
    if code in _ROUTE:
        return "ROUTE"
    if code in _DATA:
        return "DATA"
    return "NO_IMPROVEMENT"


def _cap_key(candidate: TemporalCandidate) -> tuple[object, int, object, str]:
    """date ASC, time ASC (date-only first), placeId ASC - never the source's arrival order."""
    at = candidate.key.time
    return (candidate.key.date, 0 if at is None else 1, at if at is not None else 0, str(candidate.key.place_id))


class ItemProposalEvaluator:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy
        self._score = ItemScorePolicy(policy)

    def evaluate(self, context: RecommendationContext, inp: ItemOptimizationInput) -> ItemProposalResult:
        """`context` pins the policy identity the caller records with the run; it never steers the result."""
        del context
        rejected: Counter[str] = Counter()
        if not inp.candidates:
            return ItemProposalResult(
                Outcome.DATA_INSUFFICIENT,
                (),
                (Reason(NO_CANDIDATES, "no temporal candidates supplied"),),
                RejectionSummary(0, {}),
            )
        cap = self._policy.candidate_caps.item_detailed
        ordered = sorted(inp.candidates, key=_cap_key)  # fixed key, never arrival order (§4.1, §6)
        considered = ordered[:cap]
        if len(ordered) > cap:
            rejected[CANDIDATE_CAP_EXCEEDED] = len(ordered) - cap
        # An untimed target resolves to local midnight, so any timed proposal pays a full move (D-REC-16).
        before = trip_time.resolve(inp.target.date, inp.target.start_time, inp.trip_zone)
        if not isinstance(before, trip_time.Exact):
            return ItemProposalResult(
                Outcome.DATA_INSUFFICIENT,
                (),
                (Reason(INVALID_LOCAL_TIME, "current slot is not a valid local time"),),
                RejectionSummary(0, dict(sorted(rejected.items()))),
            )
        admitted: list[_Scored] = []
        classes: set[str] = set()
        for candidate in considered:
            scored, reason = self._evaluate_one(inp, before.instant, candidate)
            if scored is not None:
                admitted.append(scored)
            else:
                assert reason is not None
                rejected[reason.code] += 1
                classes.add(_classify(reason.code))
        summary = RejectionSummary(len(considered), dict(sorted(rejected.items())))
        if admitted:
            return ItemProposalResult(Outcome.PROPOSALS, self._select(admitted), (), summary)
        reasons = tuple(Reason(code, "all candidates rejected") for code in sorted(rejected))
        if classes and classes <= {"LOCK"}:
            return ItemProposalResult(Outcome.LOCK_CONFLICT, (), reasons, summary)
        if "ROUTE" in classes:
            return ItemProposalResult(Outcome.ROUTE_UNAVAILABLE, (), reasons, summary)
        if "DATA" in classes:
            return ItemProposalResult(Outcome.DATA_INSUFFICIENT, (), reasons, summary)
        return ItemProposalResult(Outcome.NO_IMPROVEMENT, (), reasons, summary)

    def _evaluate_one(
        self, inp: ItemOptimizationInput, before_instant: datetime, candidate: TemporalCandidate
    ) -> tuple[_Scored | None, Reason | None]:
        target = inp.target
        day = candidate.key.date
        assert day is not None  # TemporalCandidate rejects a candidate without a date
        # A DAY-resolution candidate carries no time, so the item keeps the start time it already has.
        at = candidate.key.time if candidate.key.time is not None else target.start_time
        first = (
            filters.same_place(target, candidate.key.place_id)
            .and_(filters.not_unchanged(target, day, at))
            .and_(filters.within_trip_range(inp.trip_start, inp.trip_end, day))
        )
        if not first.is_eligible:
            return None, first.reasons[0]
        locks = filters.lock_checks(inp.locks, day, at, target.duration_minutes)
        if not locks.eligibility.is_eligible:
            return None, locks.eligibility.reasons[0]
        for verdict in (
            filters.opening_hours(inp.opening_hours.get(day, UnknownHours()), at, target.duration_minutes),
            filters.neighbour_overlap(inp.neighbours, target.item_id, day, at, target.duration_minutes),
            filters.route_evidence(inp.neighbours, target.item_id, target.date, day, inp.route_evidence),
        ):
            if not verdict.is_eligible:
                return None, verdict.reasons[0]
        after = trip_time.resolve(day, at, inp.trip_zone)
        if not isinstance(after, trip_time.Exact):
            return None, Reason(INVALID_LOCAL_TIME, "proposed slot is not a valid local time")
        shift = TemporalShift(candidate.before_value, candidate.after_value, before_instant, after.instant)
        admission = self._score.evaluate(candidate.metric_code, candidate.verdict, shift)
        if isinstance(admission, Rejected):
            return None, admission.reason
        scored = ScoredCandidate(candidate.key, at, admission)
        return _Scored(scored, candidate, before_instant, after.instant, locks.passed), None

    def _select(self, admitted: list[_Scored]) -> tuple[ItemProposal, ...]:
        """One proposal per (date, start time): the best-scoring candidate wins the slot (§5.6)."""
        ordered = sorted(admitted, key=lambda item: proposal_sort_key(item.scored))
        unique: dict[str, _Scored] = {}
        for item in ordered:
            slot = f"{item.candidate.key.date}T{item.scored.proposed_start_time}"
            unique.setdefault(slot, item)
        proposals = [
            ItemProposal(
                rank,
                item.candidate,
                item.scored.proposed_start_time,
                item.before_instant,
                item.after_instant,
                item.scored.admission,
                item.lock_checks,
            )
            for rank, item in enumerate(list(unique.values())[: self._policy.candidate_caps.item_proposals], start=1)
        ]
        return tuple(proposals)
