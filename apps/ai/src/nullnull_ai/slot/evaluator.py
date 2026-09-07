"""§5.3: per trip date, decide whether a candidate could be scheduled without inventing a time.

Pure: the caller supplies every fact. Each date is judged in a fixed order - duplicate place, day item
limit, opening hours, route evidence - and the first non-eligible verdict names the slot's reason.
Aggregate: a running verification job answers CHECKING; otherwise any eligible date is EXACT, an
unverified fact without any eligible date is UNKNOWN, and a fully known refusal is NONE. P0 never
returns SIMILAR and never fills `suggestedTime`.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum
from uuid import UUID
from zoneinfo import ZoneInfo

from nullnull_ai.domain.policy import RecommendationPolicy
from nullnull_ai.domain.types import Eligibility, EligibilityState, Reason, RecommendationContext
from nullnull_ai.item import filters
from nullnull_ai.item.types import NeighbourItem, OpeningWindow, RouteEvidence, UnknownHours

DUPLICATE_PLACE = "DUPLICATE_PLACE"
DAY_ITEM_LIMIT = "DAY_ITEM_LIMIT"
DATE_CAP_EXCEEDED = "DATE_CAP_EXCEEDED"

_NO_ITEM = UUID(int=0)
"""A candidate is not on the itinerary yet, so no neighbouring item is ever the candidate itself."""


class SlotState(Enum):
    EXACT = "EXACT"
    CHECKING = "CHECKING"
    UNKNOWN = "UNKNOWN"
    NONE = "NONE"


@dataclass(frozen=True, slots=True)
class Slot:
    """One trip date. `suggested_time` stays None: P0 offers a date and lets the user pick the time."""

    date: date
    suggested_time: None
    eligible: bool
    reason_code: str | None

    def __post_init__(self) -> None:
        if self.eligible == (self.reason_code is not None):
            raise ValueError("reasonCode is present exactly when the slot is not eligible")


@dataclass(frozen=True, slots=True)
class CandidateSlotInput:
    """Facts for one ACTIVE candidate.

    `dates_with_same_place` are the trip dates that already schedule this canonical place (duplicate
    policy), `max_items_per_day` is the API's per-day item limit, and `checking` is true only while a
    real verification job runs for this candidate.
    """

    trip_id: UUID
    candidate_id: UUID
    place_id: UUID
    trip_start: date
    trip_end: date
    trip_zone: ZoneInfo
    duration_minutes: int | None
    items: tuple[NeighbourItem, ...]
    opening_hours: Mapping[date, OpeningWindow]
    dates_with_same_place: frozenset[date]
    route_evidence: RouteEvidence
    max_items_per_day: int
    checking: bool

    def __post_init__(self) -> None:
        if self.trip_end < self.trip_start:
            raise ValueError("tripEnd before tripStart")
        if self.max_items_per_day < 1:
            raise ValueError("maxItemsPerDay must be >= 1")
        if self.duration_minutes is not None and self.duration_minutes <= 0:
            raise ValueError("durationMinutes must be positive when present")
        # A duplicate outside the trip can only be a hydration bug: it would silently mask nothing.
        if any(day < self.trip_start or day > self.trip_end for day in self.dates_with_same_place):
            raise ValueError("datesWithSamePlace must lie inside the trip range")


@dataclass(frozen=True, slots=True)
class SlotResult:
    state: SlotState
    slots: tuple[Slot, ...]
    reasons: tuple[Reason, ...]


class SlotEvaluator:
    def __init__(self, policy: RecommendationPolicy) -> None:
        self._policy = policy

    def evaluate(self, context: RecommendationContext, inp: CandidateSlotInput) -> SlotResult:
        """`context` pins the policy identity the caller records with the run; it never steers the result."""
        del context
        cap = self._policy.candidate_caps.slot_dates
        reasons: list[Reason] = []
        total_days = (inp.trip_end - inp.trip_start).days + 1
        if total_days > cap:
            # The first `cap` dates are answered and the truncation is reported; nothing is guessed.
            reasons.append(Reason(DATE_CAP_EXCEEDED, "trip has more dates than the slot cap"))
        slots: list[Slot] = []
        any_unknown = False
        for offset in range(min(total_days, cap)):
            day = inp.trip_start + timedelta(days=offset)
            verdict = self._evaluate_date(inp, day)
            any_unknown = any_unknown or verdict.state is EligibilityState.UNKNOWN
            slots.append(
                Slot(day, None, True, None) if verdict.is_eligible else Slot(day, None, False, verdict.reasons[0].code)
            )
        if inp.checking:
            state = SlotState.CHECKING
        elif any(slot.eligible for slot in slots):
            state = SlotState.EXACT
        elif any_unknown:
            state = SlotState.UNKNOWN
        else:
            state = SlotState.NONE
        return SlotResult(state, tuple(slots), tuple(reasons))

    @staticmethod
    def _evaluate_date(inp: CandidateSlotInput, day: date) -> Eligibility:
        if day in inp.dates_with_same_place:
            return Eligibility.ineligible(Reason(DUPLICATE_PLACE, "place already scheduled on that date"))
        same_day: Sequence[NeighbourItem] = [item for item in inp.items if item.date == day]
        if len(same_day) >= inp.max_items_per_day:
            return Eligibility.ineligible(Reason(DAY_ITEM_LIMIT, "day already holds the maximum number of items"))
        # No start time is proposed, so only the day itself has to be open (§5.4 "체류 전체" needs both).
        hours = filters.opening_hours(inp.opening_hours.get(day, UnknownHours()), None, inp.duration_minutes)
        if not hours.is_eligible:
            return hours
        # An empty day gains no travel leg, so route evidence is not required for it (§5.4).
        evidence = RouteEvidence.VERIFIED if not same_day else inp.route_evidence
        return filters.route_evidence(inp.items, _NO_ITEM, day, day, evidence)
