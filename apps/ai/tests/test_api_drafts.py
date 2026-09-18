"""POST /internal/v1/drafts/compose contract behaviour (REC-CON-04, BA-055).

Spring hydrates the pool, so a body that breaks a domain rule is a caller bug and must come back as
422 VALIDATION_FAILED - never a 500, which would send the caller down the retryable outage path.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

PATH = "/internal/v1/drafts/compose"
P1 = "018f3f8e-9b67-7a21-8d31-31d315b93a01"
P2 = "018f3f8e-9b67-7a21-8d31-31d315b93a02"
P3 = "018f3f8e-9b67-7a21-8d31-31d315b93a03"
OPEN = {"state": "OPEN", "opensAt": "09:00:00", "closesAt": "18:00:00"}
CLOSED = {"state": "CLOSED"}


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "tripStart": "2026-09-12",
        "tripEnd": "2026-09-13",
        "tripZone": "Asia/Seoul",
        "maxStopsPerDay": 3,
        "pool": [
            {"placeId": P2, "openingHours": {"2026-09-12": OPEN}},
            {"placeId": P1, "openingHours": {"2026-09-12": CLOSED}},
        ],
    }
    payload.update(overrides)
    return payload


def test_a_pool_is_placed_by_place_id_with_verified_hours_only(client: TestClient) -> None:
    response = client.post(PATH, json=body(), headers={"X-Request-ID": "req_test-0101"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0101"
    payload = response.json()
    assert payload["policyHash"] == client.get("/internal/v1/policy").json()["policyHash"]
    assert payload["policyVersion"] == "policy-v1" and payload["pipelineVersion"] == "nullnull-ai-pipeline-v1"
    assert payload["state"] == "READY" and payload["reasons"] == []
    # P1 is walked first (lowest id) and is CLOSED on the 12th, so it takes the 13th (no window: UNKNOWN);
    # P2 then finds the 12th empty and goes there with its verified OPEN window. Stops are listed by date.
    assert payload["stops"] == [
        {"placeId": P2, "date": "2026-09-12", "position": 0, "hoursState": "OPEN"},
        {"placeId": P1, "date": "2026-09-13", "position": 0, "hoursState": "UNKNOWN"},
    ]
    assert payload["evaluated"] == 2 and payload["rejectedByReason"] == {}


def test_rec_draft_03_a_returned_stop_carries_no_time_field(client: TestClient) -> None:
    """REC-DRAFT-03: on the wire a stop has exactly placeId, date, position and hoursState."""
    stops = client.post(PATH, json=body()).json()["stops"]
    assert stops and all(set(stop) == {"placeId", "date", "position", "hoursState"} for stop in stops)


def test_rec_draft_04_an_empty_pool_answers_empty_without_padding(client: TestClient) -> None:
    """REC-DRAFT-04: an empty pool is a 200 EMPTY with no stop, not an error and not a padded draft."""
    payload = client.post(PATH, json=body(pool=[])).json()
    assert payload["state"] == "EMPTY" and payload["stops"] == []
    assert payload["reasons"] == ["NO_ELIGIBLE_PLACES"]
    assert payload["evaluated"] == 0 and payload["rejectedByReason"] == {}


def test_leftover_places_are_counted_and_reported(client: TestClient) -> None:
    pool = [{"placeId": pid, "openingHours": {}} for pid in (P1, P2, P3)]
    payload = client.post(PATH, json=body(pool=pool, maxStopsPerDay=1)).json()
    assert [stop["placeId"] for stop in payload["stops"]] == [P1, P2]
    assert payload["reasons"] == ["ALL_DATES_FULL"]
    assert payload["rejectedByReason"] == {"DAY_CAP_FULL": 1}


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"tripEnd": "2026-09-11"}, "tripEnd before tripStart"),
        ({"tripEnd": "2026-10-12"}, "31 dates"),
        ({"tripZone": "Mars/Olympus"}, "not an IANA zone"),
        ({"maxStopsPerDay": 0}, "zero day cap"),
        ({"pool": [{"placeId": P1, "openingHours": {}}, {"placeId": P1, "openingHours": {}}]}, "one place twice"),
        ({"pool": [{"placeId": P1, "openingHours": {"2026-09-14": OPEN}}]}, "window outside the trip"),
        ({"pool": [{"placeId": P1, "openingHours": {"2026-09-12": {"state": "OPEN"}}}]}, "OPEN without times"),
        (
            {"pool": [{"placeId": P1, "openingHours": {"2026-09-12": {**CLOSED, "opensAt": "09:00:00"}}}]},
            "CLOSED with a time",
        ),
        ({"pool": [{"placeId": P1, "openingHours": {}, "mustVisit": True}]}, "an unknown place field"),
        ({"interests": ["HISTORY"]}, "an unknown request field"),
        ({"evaluatedAt": "2026-09-06T00:00:00"}, "a naive evaluatedAt"),
        ({"pool": [{"placeId": f"018f3f8e-9b67-7a21-8d31-{n:012d}", "openingHours": {}} for n in range(101)]}, "101"),
    ],
)
def test_an_inconsistent_body_is_a_validation_failure(client: TestClient, overrides: dict[str, Any], note: str) -> None:
    response = client.post(PATH, json=body(**overrides))
    assert response.status_code == 422, note
    problem = response.json()
    assert problem["code"] == "VALIDATION_FAILED" and problem["retryable"] is False, note
