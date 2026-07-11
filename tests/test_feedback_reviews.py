"""1탭 피드백(F7)·후기 API + 일배치 보정 반영(9-4) 검증."""
from sqlalchemy import func, select

from app import models
from tests.conftest import spot_id_by_name


def test_feedback_saved(client, db, gyeongbok_id):
    resp = client.post("/api/feedback", json={"spot_id": gyeongbok_id, "perceived": 1})
    assert resp.status_code == 201
    assert resp.json()["message"] == "피드백이 반영됐어요"
    saved = db.scalar(
        select(func.count()).select_from(models.VisitFeedback)
        .where(models.VisitFeedback.spot_id == gyeongbok_id,
               models.VisitFeedback.is_seed.is_(False))
    )
    assert saved >= 1


def test_feedback_validation(client, gyeongbok_id):
    assert client.post("/api/feedback",
                       json={"spot_id": gyeongbok_id, "perceived": 5}).status_code == 422
    assert client.post("/api/feedback",
                       json={"spot_id": 99999, "perceived": 0}).status_code == 404


def test_review_create_and_list(client, gyeongbok_id):
    resp = client.post("/api/reviews", json={
        "spot_id": gyeongbok_id, "nickname": "테스터", "rating": 5,
        "tags": ["한산했어요", "동선이 편해요"], "text": "추천 시간대라 여유로웠어요.",
    })
    assert resp.status_code == 201
    assert resp.json()["message"] == "후기가 저장됐어요"

    listing = client.get("/api/reviews", params={"spot_id": gyeongbok_id}).json()
    assert listing["stats"]["count"] >= 1
    assert listing["stats"]["avg_rating"] > 0
    assert listing["recent"][0]["nickname"] == "테스터"
    assert listing["recent"][0]["date_text"]


def test_review_requires_target(client):
    assert client.post("/api/reviews", json={"rating": 4}).status_code == 422


def test_batch_applies_feedback_correction(client, db, visit_date):
    """운현궁에 '한산했다(-1)' 피드백 30건 → 일배치 후 adjusted_risk < congestion_risk."""
    unhyeon_id = spot_id_by_name(client, "운현궁")
    for _ in range(30):
        assert client.post("/api/feedback",
                           json={"spot_id": unhyeon_id, "perceived": -1}).status_code == 201

    from app.batch.daily import recompute_scores
    count = recompute_scores(db)
    assert count > 0

    body = client.get(f"/api/spots/{unhyeon_id}/congestion",
                      params={"date": visit_date}).json()
    assert body["source"] == "cache"          # 배치 산출 캐시 사용(조회 시 추가 연산 없음)
    assert body["adjusted"] is True
    assert body["risk"] < body["raw_risk"]    # 한산 피드백 → 하향 보정

    # 30건 미만 장소는 미적용 — 성균관 명륜당(시드 피드백 없음)
    seonggyun_id = spot_id_by_name(client, "성균관 명륜당")
    body2 = client.get(f"/api/spots/{seonggyun_id}/congestion",
                       params={"date": visit_date}).json()
    assert body2["adjusted"] is False
    assert body2["risk"] == body2["raw_risk"]
