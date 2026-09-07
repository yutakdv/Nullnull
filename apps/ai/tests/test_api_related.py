"""POST /internal/v1/related/rank contract behaviour (REC-REL-01/02).

Spring hydrates every relation row, so a body that breaks a domain rule is a caller bug and must come
back as 422 VALIDATION_FAILED - never a 500, which would send the caller down the retryable outage
path. `categoryMatch` travels as a string so Spring reads it as an exact BigDecimal, and a missing
category stays null instead of being filled with 0.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

PATH = "/internal/v1/related/rank"
SRC = "018f3f8e-9b67-7a21-8d31-31d315b93a01"
T1 = "018f3f8e-9b67-7a21-8d31-31d315b93a11"
T2 = "018f3f8e-9b67-7a21-8d31-31d315b93a12"
T3 = "018f3f8e-9b67-7a21-8d31-31d315b93a13"
TAXONOMY = "taxonomy-test-1"


def category(place: str, code: str | None, parent: str | None = "HERITAGE", taxonomy: str = TAXONOMY) -> dict[str, Any]:
    return {"placeId": place, "categoryCode": code, "parentCategoryCode": parent, "taxonomyVersion": taxonomy}


def candidate(
    target: str,
    tier: str = "SIMILAR",
    channel: str = "category",
    expires_at: str | None = None,
    mapping: str = "CERTAIN",
    source: str = SRC,
) -> dict[str, Any]:
    return {
        "sourcePlaceId": source,
        "targetPlaceId": target,
        "tier": tier,
        "sourceCode": "KTO_RELATED_PLACES",
        "channel": channel,
        "confidence": "0.7",
        "effectiveAt": "2026-09-05T00:00:00Z",
        "expiresAt": expires_at,
        "mapping": mapping,
    }


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "sourcePlaceId": SRC,
        "sourceCategory": category(SRC, "PALACE"),
        "candidates": [candidate(T1, "EXACT", "kto-direct"), candidate(T1), candidate(T2), candidate(T3)],
        "categories": [category(T1, "PALACE"), category(T3, "RESTAURANT", "FOOD")],
        "lookupOutcome": "COMPLETE",
    }
    payload.update(overrides)
    return payload


def test_merged_items_carry_string_decimals_evidence_counts_and_the_policy_identity(client: TestClient) -> None:
    response = client.post(PATH, json=body(), headers={"X-Request-ID": "req_test-0004"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0004"
    payload = response.json()
    assert payload["policyHash"] == client.get("/internal/v1/policy").json()["policyHash"]
    assert payload["policyVersion"] == "policy-v1" and payload["pipelineVersion"] == "nullnull-ai-pipeline-v1"
    assert payload["state"] == "EXACT" and payload["reasons"] == []
    assert payload["items"] == [
        {
            "placeId": T1,
            "tier": "EXACT",
            "categoryMatch": "1",
            "evidenceCount": 2,
            "channels": ["category", "kto-direct"],
        },
        {"placeId": T3, "tier": "SIMILAR", "categoryMatch": "0", "evidenceCount": 1, "channels": ["category"]},
        {"placeId": T2, "tier": "SIMILAR", "categoryMatch": None, "evidenceCount": 1, "channels": ["category"]},
    ]


def test_a_missing_category_is_null_and_never_a_zero_score(client: TestClient) -> None:
    """T2 has no category row at all and T3 has one that does not match; the two must not read alike."""
    payload = client.post(PATH, json=body()).json()
    by_place = {item["placeId"]: item["categoryMatch"] for item in payload["items"]}
    assert by_place[T2] is None and by_place[T3] == "0"


def test_a_shared_parent_category_scores_half(client: TestClient) -> None:
    payload = client.post(PATH, json=body(candidates=[candidate(T1)], categories=[category(T1, "MUSEUM")])).json()
    assert payload["state"] == "SIMILAR"
    assert payload["items"] == [
        {"placeId": T1, "tier": "SIMILAR", "categoryMatch": "0.5", "evidenceCount": 1, "channels": ["category"]}
    ]


def test_expired_evidence_is_dropped_and_an_uncertain_mapping_answers_unknown(client: TestClient) -> None:
    payload = client.post(
        PATH,
        json=body(
            candidates=[
                candidate(T1, "EXACT", "kto-direct", "2026-09-05T23:59:59Z"),
                candidate(T2, "EXACT", "kto-direct", None, "UNCERTAIN"),
            ]
        ),
    ).json()
    assert payload["items"] == []
    assert payload["state"] == "UNKNOWN"
    assert set(payload["reasons"]) == {"EVIDENCE_EXPIRED", "MAPPING_UNCERTAIN"}


@pytest.mark.parametrize(
    ("outcome", "state"),
    [("COMPLETE", "NONE"), ("SOURCE_FAILED", "UNKNOWN"), ("JOB_RUNNING", "CHECKING")],
)
def test_the_lookup_outcome_decides_the_state_when_nothing_was_found(
    client: TestClient, outcome: str, state: str
) -> None:
    payload = client.post(PATH, json=body(candidates=[], categories=[], lookupOutcome=outcome)).json()
    assert payload["state"] == state and payload["items"] == []


def test_a_running_job_still_reports_what_it_already_has(client: TestClient) -> None:
    payload = client.post(PATH, json=body(lookupOutcome="JOB_RUNNING")).json()
    assert payload["state"] == "CHECKING"
    assert [item["placeId"] for item in payload["items"]] == [T1, T3, T2]


def test_a_relation_to_the_source_place_is_dropped_with_a_reason(client: TestClient) -> None:
    payload = client.post(PATH, json=body(candidates=[candidate(SRC, "EXACT", "kto-direct"), candidate(T1)])).json()
    assert [item["placeId"] for item in payload["items"]] == [T1]
    assert payload["reasons"] == ["SELF_REFERENCE"]


def test_an_unverified_confidence_may_be_absent(client: TestClient) -> None:
    """confidence is required-nullable: the ranker never reads it, and no value is invented for it."""
    row = candidate(T1) | {"confidence": None}
    payload = client.post(PATH, json=body(candidates=[row], categories=[category(T1, "PALACE")])).json()
    assert payload["items"][0]["evidenceCount"] == 1


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"sourceCategory": category(T1, "PALACE")}, "the source category describes another place"),
        ({"candidates": [candidate(T1, source=T2)]}, "a relation that does not start at the source place"),
        ({"categories": [category(T1, "PALACE"), category(T1, "MUSEUM")]}, "two category rows for one place"),
        ({"candidates": [candidate(T1, "NEARBY")]}, "a tier outside the two published values"),
        ({"candidates": [candidate(T1, mapping="PROBABLY")]}, "a mapping certainty outside the two values"),
        ({"lookupOutcome": "PENDING"}, "a lookup outcome outside the three values"),
        ({"candidates": [candidate(T1) | {"channel": ""}]}, "a blank channel"),
        ({"candidates": [candidate(T1) | {"sourceCode": "K" * 65}]}, "a source code past its bound"),
        ({"candidates": [candidate(T1) | {"effectiveAt": "2026-09-05T00:00:00"}]}, "a naive instant"),
        ({"categories": [category(T1, "P" * 65)]}, "a category code past its bound"),
        ({"categories": [category(T1, "PALACE", taxonomy="")]}, "a blank taxonomy version"),
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


@pytest.mark.parametrize("field", ["candidates", "categories"])
def test_bounded_collections_reject_a_body_past_the_cap(client: TestClient, field: str) -> None:
    rows: list[dict[str, Any]] = [
        candidate(f"018f3f8e-9b67-7a21-8d31-{index:012d}")
        if field == "candidates"
        else category(f"018f3f8e-9b67-7a21-8d31-{index:012d}", "PALACE")
        for index in range(2001)
    ]
    rejected = client.post(PATH, json=body(**{field: rows}))
    assert rejected.status_code == 422 and rejected.json()["code"] == "VALIDATION_FAILED"
    accepted = client.post(PATH, json=body(**{field: rows[:2000]}))
    assert accepted.status_code == 200, accepted.text
