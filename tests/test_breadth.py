"""추천 폭·성능 개편 검증 — 벌크 위험도, 다양성 상한, 코스 지도, 홈 정렬."""
from datetime import date, timedelta

from sqlalchemy import select

from app import models
from app.services.congestion_service import bulk_risks, compute_risk
from app.services.recommend_service import diversify_top


def test_bulk_risks_matches_compute_risk(db):
    """벌크 산출이 기존 단건 산출과 같은 값을 내야 한다(피드백 보정 미대상 스팟)."""
    spots = db.scalars(
        select(models.TouristSpot)
        .where(models.TouristSpot.name.in_(["길상사", "서울숲", "낙산공원"]))
    ).all()
    d = date.today() + timedelta(days=3)
    bulk = bulk_risks(db, spots, d, "afternoon")
    for spot in spots:
        single = compute_risk(db, spot, d, "afternoon", use_realtime=False)["risk"]
        assert bulk[spot.spot_id] == single


def test_diversify_top_caps_category():
    def item(name, cat, score):
        return {"name": name, "cat": cat, "score": score}

    scored = [
        item("궁1", "궁궐", 0.9), item("궁2", "궁궐", 0.8), item("궁3", "궁궐", 0.7),
        item("시장1", "전통시장", 0.6), item("공원1", "공원", 0.5),
    ]
    top = diversify_top(scored, 4, category_of=lambda x: x["cat"])
    assert len(top) == 4
    assert sum(1 for t in top if t["cat"] == "궁궐") == 2   # 상한 2 → 시장·공원이 진입
    assert {"시장1", "공원1"} <= {t["name"] for t in top}


def test_alternatives_category_cap(client, gyeongbok_id, visit_date):
    body = client.get(
        f"/api/spots/{gyeongbok_id}/alternatives",
        params={"date": visit_date, "limit": 5, "log_exposure": "false"},
    ).json()
    categories = {}
    for alt in body["alternatives"]:
        detail = client.get(f"/api/spots/{alt['spot_id']}").json()
        categories[detail["category_name"]] = categories.get(detail["category_name"], 0) + 1
    assert all(count <= 2 for count in categories.values())


def test_course_detail_has_map_points_and_distance(client, gyeongbok_id, visit_date):
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
    }).json()
    points = course["map_points"]
    assert points[0]["order_no"] == 0                 # 출발지(원 관광지)
    assert len(points) == len(course["timeline"]) + 1
    for p in points:
        assert p["lat"] and p["lng"] and p["name"]
    assert course["summary"]["total_distance_km"] >= 0


def test_home_prefers_spots_with_congestion_evidence(client, db, visit_date):
    body = client.get("/api/spots/home",
                      params={"date": visit_date, "limit": 8}).json()
    assert len(body["items"]) == 8
    snap_ids = set(db.scalars(
        select(models.CongestionSnapshot.spot_id).distinct()).all())
    # 실측/스냅샷 근거 보유 스팟이 상위를 차지해야 한다(가나다순 잡동사니 방지)
    top_half = body["items"][:4]
    assert all(item["spot_id"] in snap_ids for item in top_half)
    assert all(item["based_on"] for item in body["items"])
