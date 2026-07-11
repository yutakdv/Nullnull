"""코스 API(F5·F6) — 생성·타임라인·근거·임팩트 카드·인기 캐러셀."""
from datetime import date, timedelta

from sqlalchemy import func, select

from app import models
from tests.conftest import spot_id_by_name


def test_create_and_get_course(client, db, gyeongbok_id, visit_date):
    alt_ids = [spot_id_by_name(client, n)
               for n in ("운현궁", "백인제가옥", "서울한양도성 낙산구간")]
    before_selected = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.selected.is_(True),
               models.RecommendationLog.is_seed.is_(False))
    )

    resp = client.post("/api/courses", json={
        "origin_spot_id": gyeongbok_id, "spot_ids": alt_ids, "date": visit_date,
    })
    assert resp.status_code == 201
    body = resp.json()

    # 타임라인 — 동선 순서·마무리 처리(FE 코스 상세 화면 계약)
    assert len(body["timeline"]) == 3
    assert [t["order_no"] for t in body["timeline"]] == [1, 2, 3]
    assert body["timeline"][-1]["move"] == "마무리"
    for item in body["timeline"]:
        assert item["place"] and item["meta"]

    # 요약 지표 + 임팩트 카드
    assert body["summary"]["relief_pct"] > 0
    assert 0 < body["summary"]["theme_keep_pct"] <= 100
    assert body["summary"]["total_move_min"] > 0
    assert "회피했어요" in body["impact_text"]

    # 추천 근거(F6)
    assert len(body["evidence"]) == 3
    for ev in body["evidence"]:
        assert ev["theme_sim"] > 0
        assert ev["travel_time"] > 0

    # 선택 로그(F8) 기록
    after_selected = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.selected.is_(True),
               models.RecommendationLog.is_seed.is_(False))
    )
    assert after_selected == before_selected + 3

    # 조회 API 동일 계약
    got = client.get(f"/api/courses/{body['course_id']}")
    assert got.status_code == 200
    assert got.json()["title"] == body["title"]


def test_course_date_window_guard(client, gyeongbok_id):
    too_far = (date.today() + timedelta(days=45)).isoformat()
    resp = client.post("/api/courses", json={
        "origin_spot_id": gyeongbok_id, "spot_ids": [gyeongbok_id], "date": too_far,
    })
    assert resp.status_code == 400


def test_course_unknown_spot_404(client, gyeongbok_id, visit_date):
    resp = client.post("/api/courses", json={
        "origin_spot_id": gyeongbok_id, "spot_ids": [99999], "date": visit_date,
    })
    assert resp.status_code == 404


def test_popular_courses_for_home_carousel(client):
    resp = client.get("/api/courses/popular", params={"limit": 3})
    assert resp.status_code == 200
    courses = resp.json()
    assert len(courses) == 3
    for course in courses:
        assert course["title"] and course["location"]
        assert course["rate_pct"] >= 0
        assert course["duration_text"]
        assert course["tag"]
        assert course["level"] in (1, 2, 3, 4, 5)


def test_unknown_course_404(client):
    assert client.get("/api/courses/99999").status_code == 404
