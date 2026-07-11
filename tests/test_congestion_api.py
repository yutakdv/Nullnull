"""널널도 API(F3) — 5단계 뱃지·요일/시간대 비교·30일 창 방어(8-1)."""
from datetime import date, timedelta


def test_congestion_ok(client, gyeongbok_id, visit_date):
    resp = client.get(f"/api/spots/{gyeongbok_id}/congestion",
                      params={"date": visit_date, "time_slot": "afternoon"})
    assert resp.status_code == 200
    body = resp.json()
    assert 0 <= body["risk"] <= 100
    assert body["level"] in (1, 2, 3, 4, 5)
    assert body["label"] in ("매우 널널", "널널", "보통", "붐빔", "매우 붐빔")
    # 경복궁 주말 오후는 붐벼야 데모 시나리오(6장)가 성립한다
    assert body["level"] >= 4
    # 시간 분산 비교 자료
    assert len(body["time_slots"]) == 3
    assert {s["slot"] for s in body["time_slots"]} == {"morning", "afternoon", "evening"}
    assert len(body["weekday_comparison"]) >= 6
    assert any(w["is_selected"] for w in body["weekday_comparison"])
    assert body["tip"]
    # 데이터 정직성: 출처·예측 범위 고지
    assert body["based_on"]
    assert body["window_from"] == date.today().isoformat()


def test_feedback_adjustment_applied_for_hot_spot(client, gyeongbok_id, visit_date):
    """경복궁은 시드 피드백 35건(+1 편향) → 보정 적용, risk > raw_risk."""
    body = client.get(f"/api/spots/{gyeongbok_id}/congestion",
                      params={"date": visit_date}).json()
    assert body["adjusted"] is True
    assert body["risk"] >= body["raw_risk"]


def test_over_30day_window_returns_400(client, gyeongbok_id):
    too_far = (date.today() + timedelta(days=31)).isoformat()
    resp = client.get(f"/api/spots/{gyeongbok_id}/congestion", params={"date": too_far})
    assert resp.status_code == 400
    assert "30일" in resp.json()["detail"]


def test_past_date_returns_400(client, gyeongbok_id):
    yesterday = (date.today() - timedelta(days=1)).isoformat()
    resp = client.get(f"/api/spots/{gyeongbok_id}/congestion", params={"date": yesterday})
    assert resp.status_code == 400


def test_invalid_time_slot_rejected(client, gyeongbok_id, visit_date):
    resp = client.get(f"/api/spots/{gyeongbok_id}/congestion",
                      params={"date": visit_date, "time_slot": "night"})
    assert resp.status_code == 422


def test_unknown_spot_404(client, visit_date):
    resp = client.get("/api/spots/99999/congestion", params={"date": visit_date})
    assert resp.status_code == 404


def test_spot_list_and_detail(client, gyeongbok_id):
    listing = client.get("/api/spots", params={"region": "서울", "size": 50}).json()
    assert listing["total"] >= 18

    detail = client.get(f"/api/spots/{gyeongbok_id}").json()
    assert detail["name"] == "경복궁"
    assert detail["overview"]
    assert detail["hidden_gem"] is False
    assert "proof" in detail and "review_stats" in detail

    by_category = client.get("/api/spots", params={"category": "궁궐"}).json()
    assert by_category["total"] >= 3


def test_home_spots_include_live_summary(client):
    response = client.get("/api/spots/home", params={"region": "서울", "limit": 3})
    assert response.status_code == 200
    body = response.json()
    assert body["total"] >= 18
    assert len(body["items"]) == 3
    for item in body["items"]:
        assert item["region"] == "서울"
        assert 0 <= item["risk"] <= 100
        assert item["level"] in (1, 2, 3, 4, 5)
        assert item["best_time_slot"] in ("morning", "afternoon", "evening")
        assert item["best_time_slot_label"] in ("오전", "오후", "저녁")
        assert item["based_on"]
        assert "2025" not in item["name"]


def test_home_spots_honor_date_and_theme(client, visit_date):
    response = client.get(
        "/api/spots/home",
        params={"region": "서울", "date": visit_date, "themes": "역사", "limit": 3},
    )
    assert response.status_code == 200
    items = response.json()["items"]
    assert items
    assert all("역사" in item["tags"] for item in items)

    food = client.get(
        "/api/spots/home",
        params={"region": "서울", "date": visit_date, "themes": "미식", "limit": 3},
    )
    assert food.status_code == 200
    assert food.json()["items"]
    assert all("미식" in item["tags"] for item in food.json()["items"])
