"""POST /internal/v1/items/propose

Hydration is Spring's job, so every inconsistency in the body is a caller bug: it becomes a 422
`VALIDATION_FAILED`, never a 5xx. A 5xx from this route means the service itself failed.
"""

from __future__ import annotations

from datetime import date as date_
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import (
    ItemOutcome,
    ItemProposalOut,
    ItemProposeRequest,
    ItemProposeResponse,
    LockIn,
    OpeningWindowIn,
    TemporalCandidateIn,
)
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import CandidateKey, RecommendationContext
from nullnull_ai.item.evaluator import ItemProposal, ItemProposalEvaluator, Outcome
from nullnull_ai.item.types import (
    Closed,
    ComparisonVerdict,
    DateLock,
    ForecastResolution,
    ItemLock,
    ItemOptimizationInput,
    MustVisitLock,
    NeighbourItem,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TargetItem,
    TemporalCandidate,
    TimeLock,
    UnknownHours,
)

router = APIRouter(tags=["Item"])

# Written out rather than derived from `Outcome.value` so the contract literal and the domain enum
# cannot drift apart unnoticed: a new outcome fails type checking here.
_OUTCOMES: dict[Outcome, ItemOutcome] = {
    Outcome.PROPOSALS: "PROPOSALS",
    Outcome.LOCK_CONFLICT: "LOCK_CONFLICT",
    Outcome.ROUTE_UNAVAILABLE: "ROUTE_UNAVAILABLE",
    Outcome.DATA_INSUFFICIENT: "DATA_INSUFFICIENT",
    Outcome.NO_IMPROVEMENT: "NO_IMPROVEMENT",
}

_LOCK_FIELDS = ("date", "start_time", "end_time", "tolerance_minutes")


def _zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError("tripZone is not an IANA time zone") from error


def _fields(lock: LockIn, required: frozenset[str], optional: frozenset[str]) -> None:
    """A lock carries exactly the fields its type defines; a stray one would silently pin nothing."""
    present = {name for name in _LOCK_FIELDS if getattr(lock, name) is not None}
    if (required - present) or (present - required - optional):
        raise ValueError(f"{lock.type} lock does not carry exactly its own fields")


def _lock(lock: LockIn) -> ItemLock:
    match lock.type:
        case "MUST_VISIT":
            _fields(lock, frozenset(), frozenset())
            return MustVisitLock()
        case "DATE":
            _fields(lock, frozenset({"date"}), frozenset())
            assert lock.date is not None
            return DateLock(lock.date)
        case "TIME":
            _fields(lock, frozenset({"start_time", "tolerance_minutes"}), frozenset())
            assert lock.start_time is not None and lock.tolerance_minutes is not None
            return TimeLock(lock.start_time, lock.tolerance_minutes)
        case "RESERVATION":
            _fields(lock, frozenset({"date", "start_time"}), frozenset({"end_time"}))
            assert lock.date is not None and lock.start_time is not None
            return ReservationLock(lock.date, lock.start_time, lock.end_time)


def _window(window: OpeningWindowIn) -> OpeningWindow:
    match window.state:
        case "OPEN":
            if window.opens_at is None or window.closes_at is None:
                raise ValueError("an OPEN window needs opensAt and closesAt")
            return OpenWindow(window.opens_at, window.closes_at)
        case "CLOSED" | "UNKNOWN":
            if window.opens_at is not None or window.closes_at is not None:
                raise ValueError(f"a {window.state} window carries no opening times")
            return Closed() if window.state == "CLOSED" else UnknownHours()


def _candidate(item: TemporalCandidateIn) -> TemporalCandidate:
    if item.verdict_eligible and item.verdict_reason_code != "SAME_METRIC_AND_ISSUE":
        # §9/§10: only that code marks a temporal before/after pair comparable.
        raise ValueError("an eligible temporal pair must carry SAME_METRIC_AND_ISSUE")
    return TemporalCandidate(
        key=CandidateKey(item.place_id, item.date, item.time),
        resolution=ForecastResolution(item.resolution),
        before_value=item.before_value,
        after_value=item.after_value,
        metric_code=item.metric_code,
        verdict=ComparisonVerdict(item.verdict_eligible, item.verdict_reason_code),
        before_snapshot_id=item.before_snapshot_id,
        after_snapshot_id=item.after_snapshot_id,
    )


def _to_domain(body: ItemProposeRequest) -> ItemOptimizationInput:
    target = body.target
    hours: dict[date_, OpeningWindow] = {day: _window(window) for day, window in body.opening_hours.items()}
    return ItemOptimizationInput(
        trip_id=body.trip_id,
        trip_version=body.trip_version,
        trip_start=body.trip_start,
        trip_end=body.trip_end,
        trip_zone=_zone(body.trip_zone),
        target=TargetItem(
            item_id=target.item_id,
            place_id=target.place_id,
            date=target.date,
            start_time=target.start_time,
            duration_minutes=target.duration_minutes,
            position=target.position,
        ),
        locks=tuple(_lock(lock) for lock in body.locks),
        neighbours=tuple(
            NeighbourItem(
                item_id=neighbour.item_id,
                date=neighbour.date,
                position=neighbour.position,
                start_time=neighbour.start_time,
                duration_minutes=neighbour.duration_minutes,
            )
            for neighbour in body.neighbours
        ),
        opening_hours=hours,
        route_evidence=RouteEvidence(body.route_evidence),
        candidates=tuple(_candidate(candidate) for candidate in body.candidates),
    )


def _proposal_out(proposal: ItemProposal) -> ItemProposalOut:
    day = proposal.candidate.key.date
    assert day is not None  # TemporalCandidate rejects a candidate without a date
    admission = proposal.admission
    return ItemProposalOut(
        rank=proposal.rank,
        date=day,
        start_time=proposal.proposed_start_time,
        before_instant=proposal.before_instant,
        after_instant=proposal.after_instant,
        score=admission.score.score,
        improvement=admission.improvement,
        relief=admission.relief,
        change_cost=admission.change_cost,
        before_snapshot_id=proposal.candidate.before_snapshot_id,
        after_snapshot_id=proposal.candidate.after_snapshot_id,
        lock_checks={lock.value: passed for lock, passed in proposal.lock_checks.items()},
    )


@router.post("/items/propose", response_model=ItemProposeResponse, response_model_by_alias=True)
async def propose_item(request: Request, body: ItemProposeRequest) -> ItemProposeResponse:
    policy: RecommendationPolicy = request.app.state.policy
    try:
        context = RecommendationContext(
            evaluated_at=body.evaluated_at,
            policy_version=policy.version,
            policy_hash=policy.hash,
            catalog_version=request.app.state.settings.effective_catalog_version,
            request_id=getattr(request.state, "request_id", "unassigned"),
        )
        optimization_input = _to_domain(body)
    except ValueError as error:
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    result = ItemProposalEvaluator(policy).evaluate(context, optimization_input)
    return ItemProposeResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        outcome=_OUTCOMES[result.outcome],
        proposals=[_proposal_out(proposal) for proposal in result.proposals],
        reasons=[reason.code for reason in result.reasons],
        evaluated=result.summary.evaluated,
        rejected_by_reason=dict(result.summary.rejected_by_reason),
    )
