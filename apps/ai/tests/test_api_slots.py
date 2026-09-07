"""POST /internal/v1/slots/evaluate contract behaviour (BA-042).

Spring hydrates every fact, so a body that breaks a domain rule is a caller bug and must come back as
422 VALIDATION_FAILED - never a 500, which would send the caller down the retryable outage path. Every
reasonCode that leaves this route has to sit in the published D-REC-2 allowlist.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient

from nullnull_ai.api.slots import _PUBLIC_REASON, SLOT_REASON_CODES

PATH = "/internal/v1/slots/evaluate"
TRIP = "018f3f8e-9b67-7a21-8d31-31d315b93c01"
CANDIDATE = "018f3f8e-9b67-7a21-8d31-31d315b93d01"
PLACE = "018f3f8e-9b67-7a21-8d31-31d315b93a01"
NEIGHBOUR = "018f3f8e-9b67-7a21-8d31-31d315b93b02"
OPEN = {"state": "OPEN", "opensAt": "09:00:00", "closesAt": "18:00:00"}
CLOSED = {"state": "CLOSED"}
UNKNOWN = {"state": "UNKNOWN"}
ALL_OPEN = {"2026-09-12": OPEN, "2026-09-13": OPEN, "2026-09-14": OPEN}


def days(count: int, start: date = date(2026, 9, 12)) -> list[str]:
    return [(start + timedelta(days=offset)).isoformat() for offset in range(count)]


def item(day: str, position: int, item_id: str = NEIGHBOUR) -> dict[str, Any]:
    return {"itemId": item_id, "date": day, "position": position, "startTime": "10:00:00", "durationMinutes": 60}


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "tripId": TRIP,
        "candidateId": CANDIDATE,
        "placeId": PLACE,
        "tripStart": "2026-09-12",
        "tripEnd": "2026-09-14",
        "tripZone": "Asia/Seoul",
        "durationMinutes": 60,
        "items": [],
        "openingHours": ALL_OPEN,
        "datesWithSamePlace": [],
        "routeEvidence": "NONE",
        "maxItemsPerDay": 20,
        "checking": False,
    }
    payload.update(overrides)
    return payload


def test_open_dates_are_exact_and_never_carry_a_suggested_time(client: TestClient) -> None:
    response = client.post(PATH, json=body(), headers={"X-Request-ID": "req_test-0003"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0003"
    payload = response.json()
    assert payload["state"] == "EXACT" and payload["reasons"] == []
    assert payload["policyHash"] == client.get("/internal/v1/policy").json()["policyHash"]
    assert payload["policyVersion"] == "policy-v1" and payload["pipelineVersion"] == "nullnull-ai-pipeline-v1"
    assert payload["slots"] == [
        {"date": "2026-09-12", "suggestedTime": None, "eligible": True, "reasonCode": None},
        {"date": "2026-09-13", "suggestedTime": None, "eligible": True, "reasonCode": None},
        {"date": "2026-09-14", "suggestedTime": None, "eligible": True, "reasonCode": None},
    ]


def test_a_running_verification_job_answers_checking(client: TestClient) -> None:
    payload = client.post(PATH, json=body(checking=True)).json()
    assert payload["state"] == "CHECKING"
    assert [slot["eligible"] for slot in payload["slots"]] == [True, True, True]


@pytest.mark.parametrize(
    ("overrides", "state", "codes"),
    [
        ({"openingHours": {**ALL_OPEN, "2026-09-13": CLOSED}}, "EXACT", [None, "CLOSED", None]),
        (
            {"openingHours": {"2026-09-12": UNKNOWN, "2026-09-13": UNKNOWN, "2026-09-14": CLOSED}},
            "UNKNOWN",
            ["OPENING_HOURS_UNKNOWN", "OPENING_HOURS_UNKNOWN", "CLOSED"],
        ),
        ({"openingHours": {}}, "UNKNOWN", ["OPENING_HOURS_UNKNOWN"] * 3),
        (
            {"openingHours": {"2026-09-12": CLOSED, "2026-09-13": CLOSED, "2026-09-14": CLOSED}},
            "NONE",
            ["CLOSED"] * 3,
        ),
        ({"datesWithSamePlace": ["2026-09-12"]}, "EXACT", ["DUPLICATE_PLACE", None, None]),
        ({"items": [item("2026-09-13", 1)]}, "EXACT", [None, "ROUTE_EVIDENCE_MISSING", None]),
        (
            {"items": [item("2026-09-13", 1)], "routeEvidence": "VERIFIED", "maxItemsPerDay": 1},
            "EXACT",
            [None, "DAY_ITEM_LIMIT", None],
        ),
    ],
)
def test_every_reason_code_is_public_and_names_the_first_failing_check(
    client: TestClient, overrides: dict[str, Any], state: str, codes: list[str | None]
) -> None:
    response = client.post(PATH, json=body(**overrides))
    assert response.status_code == 200, response.text
    payload = response.json()
    assert payload["state"] == state
    assert [slot["reasonCode"] for slot in payload["slots"]] == codes
    for slot in payload["slots"]:
        assert slot["suggestedTime"] is None
        assert slot["eligible"] == (slot["reasonCode"] is None)
        assert slot["reasonCode"] is None or slot["reasonCode"] in SLOT_REASON_CODES


def test_the_projection_keeps_every_internal_code_inside_the_allowlist() -> None:
    assert set(_PUBLIC_REASON.values()) <= SLOT_REASON_CODES
    # Unreachable while a slot carries no start time; the entry keeps a future timed check public.
    assert _PUBLIC_REASON["DURATION_UNKNOWN"] == "OPENING_HOURS_UNKNOWN"


def test_the_published_allowlist_is_the_six_agreed_codes() -> None:
    assert SLOT_REASON_CODES == {
        "OUTSIDE_TRIP_RANGE",
        "CLOSED",
        "OPENING_HOURS_UNKNOWN",
        "DUPLICATE_PLACE",
        "ROUTE_EVIDENCE_MISSING",
        "DAY_ITEM_LIMIT",
    }


def test_a_trip_longer_than_the_cap_is_truncated_and_reported(client: TestClient) -> None:
    """policy-v1 candidateCaps.slotDates = 30: the extra dates are reported, never silently dropped."""
    payload = client.post(PATH, json=body(tripEnd="2027-03-31", openingHours={})).json()
    assert payload["reasons"] == ["DATE_CAP_EXCEEDED"]
    assert len(payload["slots"]) == 30
    assert payload["slots"][0]["date"] == "2026-09-12" and payload["slots"][-1]["date"] == "2026-10-11"


def test_bounded_collections_reject_a_body_larger_than_the_longest_trip(client: TestClient) -> None:
    windows = {day: OPEN for day in days(31, date(2026, 9, 1))}
    rejected = client.post(PATH, json=body(openingHours=windows))
    assert rejected.status_code == 422 and rejected.json()["code"] == "VALIDATION_FAILED"
    accepted = client.post(PATH, json=body(openingHours=dict(list(windows.items())[:30])))
    assert accepted.status_code == 200, accepted.text


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"tripZone": "Mars/Olympus"}, "unknown IANA zone"),
        ({"tripZone": "../etc/passwd"}, "a path is not a zone"),
        ({"tripStart": "2026-09-15"}, "tripEnd before tripStart"),
        ({"maxItemsPerDay": 0}, "a day that can hold nothing"),
        ({"datesWithSamePlace": ["2026-09-11"]}, "a duplicate date outside the trip"),
        ({"datesWithSamePlace": days(31)}, "more duplicates than trip dates"),
        (
            {
                "items": [
                    item("2026-09-13", index, f"018f3f8e-9b67-7a21-8d31-31d315b9{index:04d}") for index in range(101)
                ]
            },
            "more items than a day can hold",
        ),
        ({"durationMinutes": 0}, "a stay of zero minutes"),
        ({"openingHours": {"2026-09-12": {"state": "OPEN"}}}, "an OPEN window needs both times"),
        ({"openingHours": {"2026-09-12": {"state": "CLOSED", "opensAt": "09:00:00"}}}, "CLOSED carries no times"),
        (
            {"openingHours": {"2026-09-12": {"state": "OPEN", "opensAt": "18:00:00", "closesAt": "09:00:00"}}},
            "closesAt must follow opensAt",
        ),
        ({"routeEvidence": "MAYBE"}, "route evidence is NONE or VERIFIED"),
        ({"evaluatedAt": "2026-09-06T00:00:00"}, "a naive instant"),
        ({"ownerId": "someone"}, "owner identifiers never reach this service"),
    ],
)
def test_domain_violations_are_validation_failures(client: TestClient, overrides: dict[str, Any], note: str) -> None:
    response = client.post(PATH, json=body(**overrides))
    assert response.status_code == 422, f"{note}: {response.text}"
    assert response.headers["content-type"].startswith("application/problem+json")
    payload = response.json()
    assert payload["code"] == "VALIDATION_FAILED", note
    assert payload["retryable"] is False and payload["requestId"]
