"""홈 방문 기록 API — 실사용(비시드) 피드백·후기 기반 최근 방문 장소."""
from tests.conftest import spot_id_by_name


def test_visited_appears_after_feedback_and_review(client):
    changdeok_id = spot_id_by_name(client, "창덕궁")
    gilsang_id = spot_id_by_name(client, "길상사")

    assert client.post("/api/feedback", json={
        "spot_id": changdeok_id, "perceived": -1,
    }).status_code == 201
    assert client.post("/api/reviews", json={
        "spot_id": gilsang_id, "rating": 4,
        "tags": ["한산했어요"], "text": "조용하게 산책했어요.",
    }).status_code == 201

    body = client.get("/api/spots/visited").json()
    ids = [item["spot_id"] for item in body["items"]]
    assert changdeok_id in ids
    assert gilsang_id in ids
    assert body["total"] >= 2

    for item in body["items"]:
        assert item["visited_text"]
        assert item["visit_count"] >= 1
        assert item["level"] in (1, 2, 3, 4, 5)
        assert item["name"]

    gilsang = next(item for item in body["items"] if item["spot_id"] == gilsang_id)
    assert gilsang["last_rating"] == 4
    changdeok = next(item for item in body["items"] if item["spot_id"] == changdeok_id)
    assert changdeok["last_perceived_label"] == "생각보다 한산했어요"


def test_visited_excludes_seed_records(client, db):
    """시드 후기(is_seed=True)만 있는 장소는 방문 기록에 나오지 않는다."""
    from sqlalchemy import select

    from app import models

    body = client.get("/api/spots/visited", params={"limit": 12}).json()
    listed = {item["spot_id"] for item in body["items"]}

    real_ids = set(db.scalars(
        select(models.VisitFeedback.spot_id)
        .where(models.VisitFeedback.is_seed.is_(False))
    ).all()) | set(db.scalars(
        select(models.VisitReview.spot_id)
        .where(models.VisitReview.is_seed.is_(False),
               models.VisitReview.spot_id.is_not(None))
    ).all())
    assert listed <= real_ids


def test_visited_limit(client):
    body = client.get("/api/spots/visited", params={"limit": 1}).json()
    assert len(body["items"]) <= 1
