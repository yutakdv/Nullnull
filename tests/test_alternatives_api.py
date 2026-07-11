"""대안지 추천 API(F4·F6·F8) — 테마 유지·혼잡 완화·노출 로그·추천 부하."""
from sqlalchemy import func, select

from app import models


def test_alternatives_for_gyeongbokgung(client, db, gyeongbok_id, visit_date):
    before = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.is_seed.is_(False))
    )
    resp = client.get(f"/api/spots/{gyeongbok_id}/alternatives",
                      params={"date": visit_date, "limit": 3})
    assert resp.status_code == 200
    body = resp.json()

    # 원 관광지 정보
    assert body["origin"]["name"] == "경복궁"
    assert body["origin"]["level"] >= 4

    # 대안 카드 3개 — FE 계약 필드
    alts = body["alternatives"]
    assert len(alts) == 3
    for alt in alts:
        assert alt["decrease_pct"] >= 0
        assert alt["similarity_pct"] > 0
        assert alt["travel_time_min"] > 0
        assert alt["reason"]
        assert alt["level"] <= body["origin"]["level"]  # 원보다 널널해야 대안
        assert "breakdown" in alt

    # 점수 내림차순 정렬
    scores = [a["score"] for a in alts]
    assert scores == sorted(scores, reverse=True)

    # 경로 요약
    assert body["route_summary"]["total_distance_km"] > 0

    # 노출 로그 기록(F8) — 실로그(is_seed=False) 3건 증가
    after = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.is_seed.is_(False))
    )
    assert after == before + 3


def test_theme_filter(client, gyeongbok_id, visit_date):
    resp = client.get(f"/api/spots/{gyeongbok_id}/alternatives",
                      params={"date": visit_date, "themes": "역사", "limit": 3})
    assert resp.status_code == 200
    # 역사 테마 필터 시 상위 대안은 역사 태그 스팟이어야 한다
    for alt in resp.json()["alternatives"]:
        detail = client.get(f"/api/spots/{alt['spot_id']}").json()
        assert "역사" in detail["tags"]


def test_recommendation_load_penalty_active(client, db, gyeongbok_id, visit_date):
    """시드 노출 로그가 있으므로 최다 노출 대안에는 부하 페널티가 걸려 있어야 한다(F8)."""
    body = client.get(f"/api/spots/{gyeongbok_id}/alternatives",
                      params={"date": visit_date, "limit": 3}).json()
    penalties = [a["breakdown"]["load_penalty"] for a in body["alternatives"]]
    assert any(p > 0 for p in penalties)


def test_window_guard_on_alternatives(client, gyeongbok_id):
    from datetime import date, timedelta
    too_far = (date.today() + timedelta(days=40)).isoformat()
    resp = client.get(f"/api/spots/{gyeongbok_id}/alternatives", params={"date": too_far})
    assert resp.status_code == 400


def test_spot_without_congestion_snapshot_joins_dynamic_candidate_pool(client, db, gyeongbok_id):
    """TourAPI 수집 직후에도 지역 수요·기본 방문 규모 추정으로 후보군에 포함된다."""
    ghost = models.TouristSpot(
        content_id="tour-999999", name="스냅샷없는궁", region="서울", area_code=1,
        cat1="A02", cat2="A0201", cat3="A02010100", category_name="궁궐",
        tags=["역사"], lat=37.57, lng=126.98, base_popularity=10.0,
    )
    db.add(ghost)
    db.commit()
    try:
        from app.services.recommend_service import candidate_map

        origin = db.get(models.TouristSpot, gyeongbok_id)
        assert ghost.spot_id in candidate_map(db, origin)
    finally:
        db.delete(ghost)
        db.commit()
