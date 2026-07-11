"""자유여행(카테고리 시퀀스) 코스 추천 + 코스 대안·교체 API."""
from app.services.recommend_service import slot_theme_fit


def _spot_detail(client, spot_id):
    return client.get(f"/api/spots/{spot_id}").json()


def test_recommend_free_course_with_theme_sequence(client, db, gyeongbok_id, visit_date):
    sequence = ["여행지", "미식", "포토스팟"]
    resp = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": sequence,
    })
    assert resp.status_code == 201
    body = resp.json()

    assert body["mode"] == "free"
    assert body["slot_themes"] == sequence
    assert len(body["timeline"]) == 3
    assert [t["order_no"] for t in body["timeline"]] == [1, 2, 3]
    assert body["timeline"][-1]["move"] == "마무리"

    # 슬롯 순서가 요청 시퀀스 그대로 보존되고, 각 장소가 해당 카테고리에 적합해야 한다
    from app import models
    for slot_theme, item in zip(sequence, body["timeline"]):
        assert item["slot_theme"] == slot_theme
        spot = db.get(models.TouristSpot, item["spot_id"])
        assert slot_theme_fit(spot, slot_theme) > 0

    # 장소 중복 없음 + 원 관광지 제외
    spot_ids = [t["spot_id"] for t in body["timeline"]]
    assert len(set(spot_ids)) == 3
    assert gyeongbok_id not in spot_ids

    assert body["summary"]["theme_keep_pct"] > 0
    assert body["summary"]["total_move_min"] > 0
    assert len(body["evidence"]) == 3


def test_recommend_defaults_to_free_travel_sequence(client, gyeongbok_id, visit_date):
    """theme_sequence 생략 → 기본 자유여행(여행지→미식→포토스팟)."""
    resp = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
    })
    assert resp.status_code == 201
    body = resp.json()
    assert body["mode"] == "free"
    assert body["slot_themes"] == ["여행지", "미식", "포토스팟"]


def test_recommend_single_theme_sequence(client, gyeongbok_id, visit_date):
    """단일 카테고리 반복(미식만 2곳)도 허용 — 모든 조합 지원."""
    resp = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["미식", "미식"],
    })
    assert resp.status_code == 201
    assert len(resp.json()["timeline"]) == 2


def test_recommend_validation(client, gyeongbok_id, visit_date):
    # 알 수 없는 카테고리 → 422
    assert client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["우주여행"],
    }).status_code == 422
    # 5개 초과 슬롯 → 422
    assert client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["미식"] * 5,
    }).status_code == 422
    # 없는 관광지 → 404
    assert client.post("/api/courses/recommend", json={
        "origin_spot_id": 99999, "date": visit_date,
    }).status_code == 404


def test_course_alternatives_per_slot(client, gyeongbok_id, visit_date):
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["역사", "미식", "포토스팟"],
    }).json()

    resp = client.get(f"/api/courses/{course['course_id']}/alternatives")
    assert resp.status_code == 200
    body = resp.json()
    assert body["course_id"] == course["course_id"]
    assert len(body["items"]) == len(course["timeline"])

    course_spot_ids = {t["spot_id"] for t in course["timeline"]}
    for slot in body["items"]:
        assert slot["order_no"] in (1, 2, 3)
        for alt in slot["alternatives"]:
            # 이미 코스에 있는 장소는 대안으로 다시 추천하지 않는다
            assert alt["spot_id"] not in course_spot_ids
            assert alt["spot_id"] != gyeongbok_id
            assert alt["reason"]
            assert alt["level"] in (1, 2, 3, 4, 5)

    assert client.get("/api/courses/99999/alternatives").status_code == 404


def test_swap_creates_new_course_preserving_other_slots(client, gyeongbok_id, visit_date):
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["여행지", "미식", "포토스팟"],
    }).json()
    alts = client.get(f"/api/courses/{course['course_id']}/alternatives").json()

    target = next(slot for slot in alts["items"] if slot["alternatives"])
    new_spot_id = target["alternatives"][0]["spot_id"]

    resp = client.post(f"/api/courses/{course['course_id']}/swap", json={
        "order_no": target["order_no"], "new_spot_id": new_spot_id,
    })
    assert resp.status_code == 201
    swapped = resp.json()

    assert swapped["course_id"] != course["course_id"]
    assert swapped["mode"] == course["mode"]
    assert len(swapped["timeline"]) == len(course["timeline"])
    for before, after in zip(course["timeline"], swapped["timeline"]):
        if before["order_no"] == target["order_no"]:
            assert after["spot_id"] == new_spot_id
        else:
            # 나머지 슬롯은 순서·장소 유지
            assert after["spot_id"] == before["spot_id"]


def test_reroll_creates_new_course_from_same_conditions(client, gyeongbok_id, visit_date):
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
        "theme_sequence": ["여행지", "미식", "포토스팟"],
    }).json()

    response = client.post(f"/api/courses/{course['course_id']}/reroll")
    assert response.status_code == 201
    rerolled = response.json()
    assert rerolled["course_id"] != course["course_id"]
    assert rerolled["date"] == course["date"]
    assert len(rerolled["timeline"]) == len(course["timeline"])
    assert rerolled["mode"] == "free"

    # '다른 코스 추천'은 이름 그대로 다른 조합이어야 한다 — 직전 구성 장소 전부 제외
    before_ids = {t["spot_id"] for t in course["timeline"]}
    after_ids = {t["spot_id"] for t in rerolled["timeline"]}
    assert before_ids.isdisjoint(after_ids)

    # 한 번 더 눌러도 또 달라진다(변주 시드 = 직전 코스 ID)
    again = client.post(f"/api/courses/{rerolled['course_id']}/reroll").json()
    assert {t["spot_id"] for t in again["timeline"]} != after_ids


def test_reroll_theme_course_keeps_theme_mode(client, gyeongbok_id, visit_date):
    """테마 유지형 코스의 reroll은 자유여행으로 바뀌지 않아야 한다(회귀)."""
    alts = client.get(
        f"/api/spots/{gyeongbok_id}/alternatives", params={"date": visit_date}
    ).json()
    spot_ids = [a["spot_id"] for a in alts["alternatives"]][:3]
    course = client.post("/api/courses", json={
        "origin_spot_id": gyeongbok_id, "spot_ids": spot_ids, "date": visit_date,
    }).json()
    assert course["mode"] == "theme"

    resp = client.post(f"/api/courses/{course['course_id']}/reroll")
    assert resp.status_code == 201
    rerolled = resp.json()
    assert rerolled["mode"] == "theme"
    assert rerolled["slot_themes"] is None
    assert len(rerolled["timeline"]) == len(course["timeline"])
    assert rerolled["title"] == course["title"]   # 제목 유지('다른 조합' 접미사 누적 방지)


def _selection_counts(client) -> dict[int, int]:
    body = client.get(
        "/api/admin/ingest-log", headers={"X-Admin-Token": "test-admin"}
    ).json()
    return {row["spot_id"]: row["selections"] for row in body["load_distribution"]}


def test_swap_logs_only_swapped_spot_selected(client, gyeongbok_id, visit_date):
    """swap은 교체된 장소 1곳만 선택 로그를 남긴다 — 전 슬롯 중복 기록 방지(회귀, F8)."""
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
    }).json()
    alts = client.get(f"/api/courses/{course['course_id']}/alternatives").json()
    target = next(slot for slot in alts["items"] if slot["alternatives"])
    new_spot_id = target["alternatives"][0]["spot_id"]
    kept_spot_ids = [
        t["spot_id"] for t in course["timeline"] if t["order_no"] != target["order_no"]
    ]

    before = _selection_counts(client)
    assert client.post(f"/api/courses/{course['course_id']}/swap", json={
        "order_no": target["order_no"], "new_spot_id": new_spot_id,
    }).status_code == 201
    after = _selection_counts(client)

    assert after.get(new_spot_id, 0) == before.get(new_spot_id, 0) + 1
    for spot_id in kept_spot_ids:
        assert after.get(spot_id, 0) == before.get(spot_id, 0)


def test_swap_validation(client, gyeongbok_id, visit_date):
    course = client.post("/api/courses/recommend", json={
        "origin_spot_id": gyeongbok_id, "date": visit_date,
    }).json()
    existing_spot = course["timeline"][0]["spot_id"]

    # 없는 코스
    assert client.post("/api/courses/99999/swap", json={
        "order_no": 1, "new_spot_id": existing_spot,
    }).status_code == 404
    # 범위 밖 순서
    assert client.post(f"/api/courses/{course['course_id']}/swap", json={
        "order_no": 9, "new_spot_id": existing_spot,
    }).status_code == 404
    # 없는 장소
    assert client.post(f"/api/courses/{course['course_id']}/swap", json={
        "order_no": 1, "new_spot_id": 99999,
    }).status_code == 404
    # 이미 코스에 포함된 장소로 교체 → 409
    other_spot = course["timeline"][1]["spot_id"]
    assert client.post(f"/api/courses/{course['course_id']}/swap", json={
        "order_no": 1, "new_spot_id": other_spot,
    }).status_code == 409
