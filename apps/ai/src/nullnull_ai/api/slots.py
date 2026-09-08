"""POST /internal/v1/slots/evaluate

Hydration is Spring's job, so every inconsistency in the body is a caller bug: it becomes a 422
`VALIDATION_FAILED`, never a 5xx. A 5xx from this route means the service itself failed.

The evaluator works in internal reason codes; a slot carries the public code the FE renders (D-REC-2
allowlist, `SLOT_REASON_CODES`). `_PUBLIC_REASON` is that projection. It is the identity for the five
codes this evaluator can produce and collapses `DURATION_UNKNOWN` onto `OPENING_HOURS_UNKNOWN`: a P0
slot carries no start time, so `filters.opening_hours` never has to judge the length of a stay today,
but a future timed check must surface an unverified stay as an unverified-hours code instead of
leaking a sixth code past the allowlist. Any other code is a bug and fails loudly as a 500.
"""

from __future__ import annotations

from datetime import date as date_
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import (
    OpeningWindowIn,
    SlotEvaluateRequest,
    SlotEvaluateResponse,
    SlotMatchState,
    SlotOut,
)
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.item import filters
from nullnull_ai.item.types import Closed, NeighbourItem, OpeningWindow, OpenWindow, RouteEvidence, UnknownHours
from nullnull_ai.slot.evaluator import DAY_ITEM_LIMIT, DUPLICATE_PLACE, CandidateSlotInput, SlotEvaluator, SlotState

router = APIRouter(tags=["Slot"])

SLOT_REASON_CODES = frozenset(
    {
        "OUTSIDE_TRIP_RANGE",
        "CLOSED",
        "OPENING_HOURS_UNKNOWN",
        "DUPLICATE_PLACE",
        "ROUTE_EVIDENCE_MISSING",
        "DAY_ITEM_LIMIT",
    }
)
"""The public slot reason codes (D-REC-2). OUTSIDE_TRIP_RANGE belongs to the set the FE renders; this
route only walks dates inside the trip, so it is never emitted here."""

_PUBLIC_REASON = {
    DUPLICATE_PLACE: "DUPLICATE_PLACE",
    DAY_ITEM_LIMIT: "DAY_ITEM_LIMIT",
    filters.CLOSED: "CLOSED",
    filters.OPENING_HOURS_UNKNOWN: "OPENING_HOURS_UNKNOWN",
    filters.DURATION_UNKNOWN: "OPENING_HOURS_UNKNOWN",
    filters.ROUTE_EVIDENCE_MISSING: "ROUTE_EVIDENCE_MISSING",
}

# Written out rather than derived from `SlotState.value` so the contract literal and the domain enum
# cannot drift apart unnoticed: a new state fails type checking here.
_STATES: dict[SlotState, SlotMatchState] = {
    SlotState.EXACT: "EXACT",
    SlotState.CHECKING: "CHECKING",
    SlotState.UNKNOWN: "UNKNOWN",
    SlotState.NONE: "NONE",
}


def _zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError("tripZone is not an IANA time zone") from error


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


def _to_domain(body: SlotEvaluateRequest) -> CandidateSlotInput:
    hours: dict[date_, OpeningWindow] = {day: _window(window) for day, window in body.opening_hours.items()}
    return CandidateSlotInput(
        trip_id=body.trip_id,
        candidate_id=body.candidate_id,
        place_id=body.place_id,
        trip_start=body.trip_start,
        trip_end=body.trip_end,
        trip_zone=_zone(body.trip_zone),
        duration_minutes=body.duration_minutes,
        items=tuple(
            NeighbourItem(
                item_id=item.item_id,
                date=item.date,
                position=item.position,
                start_time=item.start_time,
                duration_minutes=item.duration_minutes,
            )
            for item in body.items
        ),
        opening_hours=hours,
        dates_with_same_place=frozenset(body.dates_with_same_place),
        route_evidence=RouteEvidence(body.route_evidence),
        max_items_per_day=body.max_items_per_day,
        checking=body.checking,
    )


@router.post("/slots/evaluate", response_model=SlotEvaluateResponse, response_model_by_alias=True)
async def evaluate_slots(request: Request, body: SlotEvaluateRequest) -> SlotEvaluateResponse:
    policy: RecommendationPolicy = request.app.state.policy
    try:
        context = RecommendationContext(
            evaluated_at=body.evaluated_at,
            policy_version=policy.version,
            policy_hash=policy.hash,
            catalog_version=request.app.state.settings.effective_catalog_version,
            request_id=getattr(request.state, "request_id", "unassigned"),
        )
        slot_input = _to_domain(body)
    except ValueError as error:
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    result = SlotEvaluator(policy).evaluate(context, slot_input)
    return SlotEvaluateResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        state=_STATES[result.state],
        slots=[
            SlotOut(
                date=slot.date,
                suggested_time=None,
                eligible=slot.eligible,
                reason_code=None if slot.reason_code is None else _PUBLIC_REASON[slot.reason_code],
            )
            for slot in result.slots
        ],
        reasons=[reason.code for reason in result.reasons],
    )
