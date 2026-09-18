"""POST /internal/v1/drafts/compose

Hydration is Spring's job, so every inconsistency in the body - dates out of order, a trip longer
than 30 dates, one place twice in the pool, a window outside the trip, an OPEN window without times -
is a caller bug: it becomes a 422 `VALIDATION_FAILED`, never a 5xx. A 5xx from this route means the
service itself failed.
"""

from __future__ import annotations

from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Request

from nullnull_ai.api.problems import ApiProblemError
from nullnull_ai.api.schemas import (
    DraftComposeRequest,
    DraftComposeResponse,
    DraftHoursState,
    DraftReasonCode,
    DraftRejectionCode,
    DraftStateName,
    DraftStopOut,
    OpeningWindowIn,
)
from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import RecommendationContext
from nullnull_ai.draft.composer import (
    ALL_DATES_FULL,
    DAY_CAP_FULL,
    HOURS_CLOSED_ALL_DATES,
    NO_ELIGIBLE_PLACES,
    DraftInput,
    DraftPlace,
    DraftState,
    HoursState,
    compose,
)
from nullnull_ai.item.types import Closed, OpeningWindow, OpenWindow, UnknownHours

router = APIRouter(tags=["Draft"])

# Written out rather than derived from the domain values so the contract literals and the composer
# cannot drift apart unnoticed: a new domain value fails with a KeyError (500) instead of leaking.
_STATES: dict[DraftState, DraftStateName] = {DraftState.READY: "READY", DraftState.EMPTY: "EMPTY"}
_HOURS: dict[HoursState, DraftHoursState] = {HoursState.OPEN: "OPEN", HoursState.UNKNOWN: "UNKNOWN"}
_REASONS: dict[str, DraftReasonCode] = {NO_ELIGIBLE_PLACES: "NO_ELIGIBLE_PLACES", ALL_DATES_FULL: "ALL_DATES_FULL"}
_REJECTIONS: dict[str, DraftRejectionCode] = {
    HOURS_CLOSED_ALL_DATES: "HOURS_CLOSED_ALL_DATES",
    DAY_CAP_FULL: "DAY_CAP_FULL",
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


def _to_domain(body: DraftComposeRequest) -> DraftInput:
    pool = tuple(
        DraftPlace(
            place_id=place.place_id,
            opening_hours={day: _window(window) for day, window in place.opening_hours.items()},
        )
        for place in body.pool
    )
    return DraftInput(
        trip_start=body.trip_start,
        trip_end=body.trip_end,
        trip_zone=_zone(body.trip_zone),
        max_stops_per_day=body.max_stops_per_day,
        pool=pool,
    )


@router.post("/drafts/compose", response_model=DraftComposeResponse, response_model_by_alias=True)
async def compose_draft(request: Request, body: DraftComposeRequest) -> DraftComposeResponse:
    policy: RecommendationPolicy = request.app.state.policy
    try:
        RecommendationContext(
            evaluated_at=body.evaluated_at,
            policy_version=policy.version,
            policy_hash=policy.hash,
            catalog_version=request.app.state.settings.effective_catalog_version,
            request_id=getattr(request.state, "request_id", "unassigned"),
        )
        draft_input = _to_domain(body)
    except ValueError as error:
        raise ApiProblemError("VALIDATION_FAILED", 422, "The request violates a domain rule.") from error
    result = compose(draft_input)
    return DraftComposeResponse(
        policy_version=policy.version,
        policy_hash=policy.hash,
        pipeline_version=policy.pipeline_version,
        state=_STATES[result.state],
        stops=[
            DraftStopOut(
                place_id=stop.place_id, date=stop.date, position=stop.position, hours_state=_HOURS[stop.hours_state]
            )
            for stop in result.stops
        ],
        reasons=[_REASONS[reason] for reason in result.reasons],
        evaluated=result.evaluated,
        rejected_by_reason={_REJECTIONS[code]: count for code, count in result.rejected_by_reason.items()},
    )
