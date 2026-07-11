"""시간 이동 제안·30일 캘린더·동행 유형(서비스 발전 1~3단계 신규 기능)."""


def test_congestion_includes_time_shift_suggestions(client, gyeongbok_id, visit_date):
    resp = client.get(f"/api/spots/{gyeongbok_id}/congestion",
                      params={"date": visit_date, "time_slot": "afternoon"})
    assert resp.status_code == 200
    body = resp.json()
    assert "time_shift_suggestions" in body
    for s in body["time_shift_suggestions"]:
        assert s["kind"] in ("slot", "date")
        # 제안은 항상 현재 조회보다 널널해야 한다(시간 분산의 존재 이유)
        assert s["risk"] < body["risk"]
        assert s["decrease_pct"] >= 0
        assert s["text"]


def test_spot_calendar_covers_forecast_window(client, gyeongbok_id):
    resp = client.get(f"/api/spots/{gyeongbok_id}/calendar")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body["days"]) == 31            # 오늘~+30일
    assert body["days"][0]["date"] == body["window_from"]
    assert body["days"][-1]["date"] == body["window_to"]
    for d in body["days"]:
        assert 1 <= d["level"] <= 5
        assert d["day"] in list("월화수목금토일")

    assert client.get("/api/spots/99999/calendar").status_code == 404
    assert client.get(
        f"/api/spots/{gyeongbok_id}/calendar", params={"time_slot": "midnight"}
    ).status_code == 422


def test_alternatives_include_coordinates(client, gyeongbok_id, visit_date):
    body = client.get(
        f"/api/spots/{gyeongbok_id}/alternatives",
        params={"date": visit_date, "log_exposure": "false"},
    ).json()
    assert body["origin"]["lat"] and body["origin"]["lng"]
    for alt in body["alternatives"]:
        assert alt["lat"] and alt["lng"]


def test_companion_scales_stay_time_and_survives_reroll(client, gyeongbok_id, visit_date):
    solo = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["역사", "미식"], "companion": "solo",
    }).json()
    family = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["역사", "미식"], "companion": "family",
    }).json()

    assert solo["companion"] == "solo" and family["companion"] == "family"
    assert family["companion_label"] == "가족과"
    assert "가족과 여행 기준" in family["description"]

    def stay_min(item):
        return int(item["meta"].split(" ")[-1].replace("분", ""))

    # 가족 동행은 같은 장소 기준 체류시간이 더 길어야 한다
    assert sum(stay_min(i) for i in family["timeline"]) > \
        sum(stay_min(i) for i in solo["timeline"])

    # reroll·swap을 거쳐도 동행 유형이 유지된다
    rerolled = client.post(f"/api/courses/{family['course_id']}/reroll").json()
    assert rerolled["companion"] == "family"

    unknown = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date, "companion": "pet",
    })
    assert unknown.status_code == 422
