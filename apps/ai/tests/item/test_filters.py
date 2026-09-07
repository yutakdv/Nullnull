"""REC-SLOT-02: an unverified fact stays UNKNOWN and is never promoted to ELIGIBLE."""

from __future__ import annotations

from datetime import date, time
from uuid import UUID

from nullnull_ai.domain.types import EligibilityState
from nullnull_ai.item.filters import (
    neighbour_overlap,
    not_unchanged,
    opening_hours,
    route_evidence,
    same_place,
    within_trip_range,
)
from nullnull_ai.item.types import Closed, NeighbourItem, OpenWindow, RouteEvidence, TargetItem, UnknownHours

PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a01")
OTHER_PLACE = UUID("018f3f8e-9b67-7a21-8d31-31d315b93a02")
ITEM = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b01")
NEIGHBOUR = UUID("018f3f8e-9b67-7a21-8d31-31d315b93b02")
D12 = date(2026, 9, 12)
D13 = date(2026, 9, 13)
TARGET = TargetItem(ITEM, PLACE, D12, time(10, 0), 90, 1)
OPEN = OpenWindow(time(9, 0), time(18, 0))


def test_unknown_opening_hours_or_duration_is_unknown_not_eligible() -> None:
    assert opening_hours(UnknownHours(), time(10, 0), 90).state is EligibilityState.UNKNOWN
    assert opening_hours(OPEN, time(17, 59), None).reasons[0].code == "DURATION_UNKNOWN"
    unknown_neighbour = (NeighbourItem(NEIGHBOUR, D13, 1, time(9, 30), None),)
    overlap = neighbour_overlap(unknown_neighbour, ITEM, D13, time(10, 0), 60)
    assert overlap.reasons[0].code == "NEIGHBOUR_DURATION_UNKNOWN"
    assert overlap.state is EligibilityState.UNKNOWN
    assert neighbour_overlap((), ITEM, D13, time(10, 0), None).state is EligibilityState.UNKNOWN


def test_closed_day_and_stay_outside_window_are_ineligible() -> None:
    assert opening_hours(Closed(), None, None).reasons[0].code == "CLOSED"
    assert opening_hours(OPEN, time(17, 0), 90).reasons[0].code == "OUTSIDE_OPENING_HOURS"
    assert opening_hours(OPEN, time(16, 30), 90).is_eligible
    # A date-only move on an open day needs no time check.
    assert opening_hours(OPEN, None, None).is_eligible


def test_neighbour_overlap_uses_known_intervals_only() -> None:
    neighbours = (NeighbourItem(NEIGHBOUR, D13, 1, time(10, 0), 60),)
    assert neighbour_overlap(neighbours, ITEM, D13, time(10, 30), 60).reasons[0].code == "OVERLAPS_NEIGHBOUR"
    assert neighbour_overlap(neighbours, ITEM, D13, time(11, 0), 60).is_eligible
    # No proposed time means no overlap claim.
    assert neighbour_overlap(neighbours, ITEM, D13, None, 60).is_eligible


def test_route_evidence_is_required_when_either_day_has_neighbours() -> None:
    neighbours = (NeighbourItem(NEIGHBOUR, D13, 1, None, None),)
    assert route_evidence(neighbours, ITEM, D12, D13, RouteEvidence.NONE).state is EligibilityState.UNKNOWN
    assert route_evidence(neighbours, ITEM, D12, D13, RouteEvidence.VERIFIED).is_eligible
    # No legs, no route constraint.
    assert route_evidence((), ITEM, D12, D13, RouteEvidence.NONE).is_eligible


def test_same_place_range_and_unchanged_checks() -> None:
    assert same_place(TARGET, OTHER_PLACE).reasons[0].code == "PLACE_MISMATCH"
    assert same_place(TARGET, PLACE).is_eligible
    assert within_trip_range(D12, D13, date(2026, 9, 14)).reasons[0].code == "OUTSIDE_TRIP_RANGE"
    assert within_trip_range(D12, D13, D13).is_eligible
    assert not_unchanged(TARGET, D12, time(10, 0)).reasons[0].code == "NO_CHANGE"
    assert not_unchanged(TARGET, D12, time(11, 0)).is_eligible
