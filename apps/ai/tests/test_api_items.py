"""POST /internal/v1/items/propose contract behaviour.

Spring hydrates every fact, so a body that breaks a domain rule is a caller bug and must come back as
422 VALIDATION_FAILED - never a 500, which would send the caller down the retryable outage path.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

PATH = "/internal/v1/items/propose"
TRIP = "018f3f8e-9b67-7a21-8d31-31d315b93c01"
PLACE = "018f3f8e-9b67-7a21-8d31-31d315b93a01"
ITEM = "018f3f8e-9b67-7a21-8d31-31d315b93b01"
NEIGHBOUR = "018f3f8e-9b67-7a21-8d31-31d315b93b02"
BEFORE_SNAPSHOT = "018f3f8e-9b67-7a21-8d31-31d315b93d01"
AFTER_SNAPSHOT = "018f3f8e-9b67-7a21-8d31-31d315b93d02"
METRIC = "KTO_RELATIVE_CONCENTRATION_INDEX"
OPEN = {"state": "OPEN", "opensAt": "09:00:00", "closesAt": "18:00:00"}


def candidate(
    day: str,
    at: str | None,
    before: int,
    after: int,
    *,
    resolution: str = "HOUR",
    eligible: bool = True,
    code: str = "SAME_METRIC_AND_ISSUE",
) -> dict[str, Any]:
    return {
        "placeId": PLACE,
        "date": day,
        "time": at,
        "resolution": resolution,
        "beforeValue": before,
        "afterValue": after,
        "metricCode": METRIC,
        "verdictEligible": eligible,
        "verdictReasonCode": code,
        "beforeSnapshotId": BEFORE_SNAPSHOT,
        "afterSnapshotId": AFTER_SNAPSHOT,
    }


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "tripId": TRIP,
        "tripVersion": 7,
        "tripStart": "2026-09-12",
        "tripEnd": "2026-09-14",
        "tripZone": "Asia/Seoul",
        "target": {
            "itemId": ITEM,
            "placeId": PLACE,
            "date": "2026-09-12",
            "startTime": "10:00:00",
            "durationMinutes": 90,
            "position": 1,
        },
        "locks": [],
        "neighbours": [],
        "openingHours": {"2026-09-12": OPEN, "2026-09-13": OPEN, "2026-09-14": OPEN},
        "routeEvidence": "NONE",
        "candidates": [candidate("2026-09-12", "12:00:00", 80, 50)],
    }
    payload.update(overrides)
    return payload


def test_proposals_carry_string_decimals_snapshot_ids_and_the_policy_identity(client: TestClient) -> None:
    response = client.post(PATH, json=body(), headers={"X-Request-ID": "req_test-0002"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0002"
    payload = response.json()
    assert payload["outcome"] == "PROPOSALS"
    assert payload["evaluated"] == 1 and payload["rejectedByReason"] == {} and payload["reasons"] == []
    assert payload["policyHash"] == client.get("/internal/v1/policy").json()["policyHash"]
    assert payload["policyVersion"] == "policy-v1" and payload["pipelineVersion"] == "nullnull-ai-pipeline-v1"
    proposal = payload["proposals"][0]
    assert proposal["rank"] == 1
    assert proposal["date"] == "2026-09-12" and proposal["startTime"] == "12:00:00"
    assert proposal["beforeInstant"] == "2026-09-12T01:00:00Z"
    assert proposal["afterInstant"] == "2026-09-12T03:00:00Z"
    # Decimals travel as strings so Spring reads them as BigDecimal without a float round trip.
    assert proposal["score"] == "0.140000"
    assert proposal["relief"] == "0.300000" and proposal["changeCost"] == "0.500000"
    assert proposal["improvement"] == "30"
    assert proposal["beforeSnapshotId"] == BEFORE_SNAPSHOT and proposal["afterSnapshotId"] == AFTER_SNAPSHOT
    assert proposal["lockChecks"] == {}


def test_day_resolution_keeps_the_current_start_time_and_reports_every_lock(client: TestClient) -> None:
    payload = body(
        locks=[{"type": "DATE", "date": "2026-09-13"}, {"type": "MUST_VISIT"}],
        candidates=[candidate("2026-09-13", None, 80, 40, resolution="DAY")],
    )
    response = client.post(PATH, json=payload)
    assert response.status_code == 200, response.text
    proposal = response.json()["proposals"][0]
    assert proposal["startTime"] == "10:00:00"
    assert proposal["afterInstant"] == "2026-09-13T01:00:00Z"
    assert proposal["score"] == "0.120000"
    assert proposal["lockChecks"] == {"DATE": True, "MUST_VISIT": True}


def test_a_terminal_plane_reports_reasons_without_proposals(client: TestClient) -> None:
    payload = body(
        locks=[{"type": "RESERVATION", "date": "2026-09-12", "startTime": "10:00:00", "endTime": "11:30:00"}],
        candidates=[candidate("2026-09-12", "12:00:00", 80, 50), candidate("2026-09-13", "10:00:00", 80, 40)],
    )
    response = client.post(PATH, json=payload)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["outcome"] == "LOCK_CONFLICT"
    assert result["proposals"] == [] and result["reasons"] == ["RESERVATION_LOCKED"]
    assert result["rejectedByReason"] == {"RESERVATION_LOCKED": 2}


def test_a_route_gap_is_its_own_plane(client: TestClient) -> None:
    payload = body(
        neighbours=[
            {
                "itemId": NEIGHBOUR,
                "date": "2026-09-13",
                "position": 2,
                "startTime": "14:00:00",
                "durationMinutes": 60,
            }
        ],
        candidates=[candidate("2026-09-13", "10:00:00", 80, 40)],
    )
    result = client.post(PATH, json=payload).json()
    assert result["outcome"] == "ROUTE_UNAVAILABLE"
    assert result["rejectedByReason"] == {"ROUTE_EVIDENCE_MISSING": 1}


def test_no_candidates_is_data_insufficient_not_an_error(client: TestClient) -> None:
    result = client.post(PATH, json=body(candidates=[])).json()
    assert result["outcome"] == "DATA_INSUFFICIENT"
    assert result["reasons"] == ["NO_CANDIDATES"] and result["evaluated"] == 0


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"tripZone": "Mars/Olympus"}, "unknown IANA zone"),
        ({"tripZone": "../etc/passwd"}, "a path is not a zone"),
        ({"tripStart": "2026-09-15"}, "tripEnd before tripStart"),
        ({"locks": [{"type": "DATE"}]}, "a DATE lock needs its date"),
        ({"locks": [{"type": "TIME", "startTime": "10:00:00"}]}, "a TIME lock needs its tolerance"),
        ({"locks": [{"type": "DATE", "date": "2026-09-12", "startTime": "10:00:00"}]}, "a DATE lock pins no time"),
        ({"locks": [{"type": "MUST_VISIT", "date": "2026-09-12"}]}, "MUST_VISIT carries no fields"),
        (
            {"locks": [{"type": "RESERVATION", "date": "2026-09-12", "startTime": "11:00:00", "endTime": "10:00:00"}]},
            "reservation ends before it starts",
        ),
        (
            {"locks": [{"type": "DATE", "date": "2026-09-12"}, {"type": "DATE", "date": "2026-09-13"}]},
            "at most one lock per type",
        ),
        ({"openingHours": {"2026-09-12": {"state": "OPEN"}}}, "an OPEN window needs both times"),
        ({"openingHours": {"2026-09-12": {"state": "CLOSED", "opensAt": "09:00:00"}}}, "CLOSED carries no times"),
        (
            {"openingHours": {"2026-09-12": {"state": "OPEN", "opensAt": "18:00:00", "closesAt": "09:00:00"}}},
            "closesAt must follow opensAt",
        ),
        (
            {"candidates": [candidate("2026-09-13", "10:00:00", 80, 40, resolution="DAY")]},
            "a DAY candidate carries no time",
        ),
        (
            {"candidates": [candidate("2026-09-13", "10:00:00", 80, 40, code="STALE_INPUT")]},
            "only SAME_METRIC_AND_ISSUE marks a temporal pair comparable",
        ),
        (
            {"candidates": [candidate("2026-09-13", "10:00:00", 80, 40, code="NOT_A_REASON", eligible=False)]},
            "reason code outside SOURCE_CATALOG.md §9",
        ),
        ({"target": {**body()["target"], "durationMinutes": 0}}, "a duration of zero minutes"),
        ({"evaluatedAt": "2026-09-06T00:00:00"}, "a naive instant"),
        ({"ownerId": "someone"}, "owner identifiers never reach this service"),
        ({"routeEvidence": "MAYBE"}, "route evidence is NONE or VERIFIED"),
    ],
)
def test_domain_violations_are_validation_failures(client: TestClient, overrides: dict[str, Any], note: str) -> None:
    response = client.post(PATH, json=body(**overrides))
    assert response.status_code == 422, f"{note}: {response.text}"
    assert response.headers["content-type"].startswith("application/problem+json")
    payload = response.json()
    assert payload["code"] == "VALIDATION_FAILED", note
    assert payload["retryable"] is False and payload["requestId"]


def test_an_ineligible_verdict_is_a_data_gap_not_a_rejected_request(client: TestClient) -> None:
    """A stale pair is a fact Spring computed, so the service answers with a plane, not a 422."""
    payload = body(candidates=[candidate("2026-09-13", "10:00:00", 80, 40, eligible=False, code="STALE_INPUT")])
    result = client.post(PATH, json=payload).json()
    assert result["outcome"] == "DATA_INSUFFICIENT"
    assert result["rejectedByReason"] == {"COMPARISON_INELIGIBLE": 1}
