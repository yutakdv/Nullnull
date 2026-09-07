"""POST /internal/v1/explanations/render contract behaviour (REC-LLM-01).

The route renders the KO/EN sentence from facts Spring already verified and reports which source the
text came from. With AI_PROVIDER=NONE that is always TEMPLATE. Every domain rule this route adds -
a locale P0 does not ship, an empty place name, a change that is not an improvement, a sentence that
cannot keep its attribution - is a caller bug and comes back as 422 VALIDATION_FAILED, never a 5xx.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

PATH = "/internal/v1/explanations/render"


def body(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "locale": "ko",
        "placeName": "경복궁",
        "beforeDate": "2026-09-12",
        "beforeTime": "10:00:00",
        "afterDate": "2026-09-12",
        "afterTime": "12:00:00",
        "beforeValue": "80",
        "afterValue": "60",
        "metricLabel": "상대 집중률",
        "attribution": "출처: ⓒ한국관광공사",
        "forecastIssueId": "issue-1",
    }
    payload.update(overrides)
    return payload


def test_the_korean_sentence_carries_the_policy_identity_and_its_source(client: TestClient) -> None:
    response = client.post(PATH, json=body(), headers={"X-Request-ID": "req_test-0008"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0008"
    payload = response.json()
    assert payload == {
        "policyVersion": "policy-v1",
        "policyHash": client.get("/internal/v1/policy").json()["policyHash"],
        "pipelineVersion": "nullnull-ai-pipeline-v1",
        "summary": (
            "경복궁 방문을 9월 12일 10:00에서 9월 12일 12:00로 옮기면 "
            "상대 집중률가 80에서 60로 20포인트 낮아져요. 출처: ⓒ한국관광공사"
        ),
        "source": "TEMPLATE",
    }


def test_the_english_sentence_and_a_date_only_slot(client: TestClient) -> None:
    payload = client.post(
        PATH,
        json=body(
            locale="en",
            placeName="Gyeongbokgung",
            metricLabel="relative concentration index",
            attribution="Source: ⓒ Korea Tourism Organization",
            beforeTime=None,
            afterDate="2026-09-13",
            afterTime=None,
        ),
    ).json()
    assert payload["summary"] == (
        "Moving Gyeongbokgung from Sep 12 to Sep 13 lowers relative concentration index "
        "from 80 to 60 (20 points). Source: ⓒ Korea Tourism Organization"
    )
    assert payload["source"] == "TEMPLATE"


def test_the_provider_is_off_so_no_answer_is_ever_attributed_to_a_model(client: TestClient) -> None:
    """AI_PROVIDER=NONE is the P0 default: the deterministic sentence is the only answer."""
    for locale in ("ko", "en"):
        assert client.post(PATH, json=body(locale=locale)).json()["source"] == "TEMPLATE"


@pytest.mark.parametrize(
    ("overrides", "note"),
    [
        ({"afterValue": "80"}, "a change that lowers nothing is never explained"),
        ({"afterValue": "90"}, "a change that raises the metric is never explained"),
        ({"locale": "ja"}, "a locale P0 does not ship"),
        ({"placeName": ""}, "a blank place name"),
        ({"placeName": "장" * 201}, "a place name past its bound"),
        ({"metricLabel": "m" * 65}, "a metric label past its bound"),
        ({"attribution": ""}, "a source line the sentence could not carry"),
        ({"attribution": "출" * 201}, "a source line past its bound"),
        ({"forecastIssueId": "i" * 65}, "a forecast issue id past its bound"),
        ({"beforeValue": "1" + "0" * 300}, "a sentence that cannot keep its attribution"),
        ({"beforeDate": "2026-09-32"}, "a date that does not exist"),
        ({"beforeValue": "eighty"}, "a value that is not a number"),
        ({"ownerId": "someone"}, "owner identifiers never reach this service"),
        ({"tripNote": "저녁은 광장시장"}, "raw itinerary text never reaches this service"),
    ],
)
def test_domain_violations_are_validation_failures(client: TestClient, overrides: dict[str, Any], note: str) -> None:
    response = client.post(PATH, json=body(**overrides))
    assert response.status_code == 422, f"{note}: {response.text}"
    assert response.headers["content-type"].startswith("application/problem+json")
    payload = response.json()
    assert payload["code"] == "VALIDATION_FAILED", note
    assert payload["retryable"] is False and payload["requestId"]


@pytest.mark.parametrize("field", ["beforeTime", "afterTime", "forecastIssueId"])
def test_nullable_fields_are_required_keys(client: TestClient, field: str) -> None:
    """Required-nullable: an omitted key is a hydration bug, an explicit null is a known absence."""
    missing = body()
    del missing[field]
    assert client.post(PATH, json=missing).status_code == 422
    assert client.post(PATH, json=body(**{field: None})).status_code == 200


def test_a_long_place_name_is_shortened_and_the_attribution_survives(client: TestClient) -> None:
    """At the contract's bounds the sentence still fits, and the source line is what survives whole."""
    attribution = "출" * 200
    payload = client.post(PATH, json=body(placeName="장" * 200, metricLabel="지" * 64, attribution=attribution)).json()
    assert len(payload["summary"]) == 500
    assert payload["summary"].endswith(attribution) and "…" in payload["summary"]
