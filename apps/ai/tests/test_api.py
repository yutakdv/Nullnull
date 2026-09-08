from __future__ import annotations

from uuid import UUID

from fastapi.testclient import TestClient

PLACE = "018f3f8e-9b67-7a21-8d31-31d315b93a01"


def test_liveness_readiness_and_policy(client: TestClient) -> None:
    live = client.get("/internal/v1/health/live")
    assert live.status_code == 200 and live.json()["status"] == "UP"
    assert live.headers["X-Request-ID"]
    ready = client.get("/internal/v1/health/ready")
    assert ready.json()["status"] == "READY" and ready.json()["checks"][0]["name"] == "policy"
    policy = client.get("/internal/v1/policy").json()
    assert policy["policyVersion"] == "policy-v1" and len(policy["policyHash"]) == 64
    assert policy["pipelineVersion"] == "nullnull-ai-pipeline-v1"


def test_feed_rank_returns_fixed_order_with_policy_identity(client: TestClient) -> None:
    body = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "locale": "ko",
        "sortVersion": 1,
        "candidates": [
            {
                "postId": str(UUID(int=2)),
                "publishedAt": "2026-09-05T00:00:00Z",
                "status": "PUBLISHED",
                "primaryPlaceId": PLACE,
            },
            {
                "postId": str(UUID(int=1)),
                "publishedAt": "2026-09-05T00:00:00Z",
                "status": "PUBLISHED",
                "primaryPlaceId": PLACE,
            },
            {"postId": str(UUID(int=3)), "publishedAt": None, "status": "DRAFT", "primaryPlaceId": PLACE},
        ],
    }
    response = client.post("/internal/v1/feed/rank", json=body, headers={"X-Request-ID": "req_test-0001"})
    assert response.status_code == 200, response.text
    assert response.headers["X-Request-ID"] == "req_test-0001"
    payload = response.json()
    assert payload["orderedPostIds"] == [str(UUID(int=1)), str(UUID(int=2))]
    assert payload["rejectedByReason"] == {"NOT_PUBLISHED": 1}
    assert payload["evaluated"] == 3 and payload["sortVersion"] == 1
    assert payload["policyHash"] == client.get("/internal/v1/policy").json()["policyHash"]


def test_unknown_fields_and_unsupported_sort_version_are_problems(client: TestClient) -> None:
    body = {"evaluatedAt": "2026-09-06T00:00:00Z", "locale": "ko", "sortVersion": 1, "candidates": [], "ownerId": "x"}
    response = client.post("/internal/v1/feed/rank", json=body)
    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "VALIDATION_FAILED" and response.json()["requestId"]
    body.pop("ownerId")
    body["sortVersion"] = 2
    response = client.post("/internal/v1/feed/rank", json=body)
    assert response.status_code == 422 and response.json()["code"] == "SORT_VERSION_UNSUPPORTED"
    missing = client.get("/internal/v1/nothing")
    assert missing.status_code == 404 and missing.json()["code"] == "NOT_FOUND"


def test_naive_datetimes_are_rejected_as_validation_failures(client: TestClient) -> None:
    body = {
        "evaluatedAt": "2026-09-06T00:00:00",
        "locale": "ko",
        "sortVersion": 1,
        "candidates": [
            {
                "postId": str(UUID(int=1)),
                "publishedAt": "2026-09-05T00:00:00Z",
                "status": "PUBLISHED",
                "primaryPlaceId": PLACE,
            }
        ],
    }
    response = client.post("/internal/v1/feed/rank", json=body)
    assert response.status_code == 422 and response.json()["code"] == "VALIDATION_FAILED"


def test_sub_second_publish_instants_keep_publish_order(client: TestClient) -> None:
    body = {
        "evaluatedAt": "2026-09-06T00:00:00Z",
        "locale": "ko",
        "sortVersion": 1,
        "candidates": [
            {
                "postId": str(UUID(int=1)),
                "publishedAt": "2026-09-05T00:00:00.100000Z",
                "status": "PUBLISHED",
                "primaryPlaceId": PLACE,
            },
            {
                "postId": str(UUID(int=2)),
                "publishedAt": "2026-09-05T00:00:00.500000Z",
                "status": "PUBLISHED",
                "primaryPlaceId": PLACE,
            },
        ],
    }
    response = client.post("/internal/v1/feed/rank", json=body)
    assert response.json()["orderedPostIds"] == [str(UUID(int=2)), str(UUID(int=1))]


def test_unsafe_request_id_is_replaced(client: TestClient) -> None:
    response = client.get("/internal/v1/health/live", headers={"X-Request-ID": "<script>"})
    assert response.headers["X-Request-ID"] != "<script>"
