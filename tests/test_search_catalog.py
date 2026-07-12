"""검색 카탈로그 — /api/spots 구·카테고리 필터와 페이지 혼잡 배지(Task A1).

테스트 DB는 시드(서울 대표 명소 ~19곳, cat1=A01/A02/A04)만 적재된다.
"""


def _total(client, **params):
    return client.get("/api/spots", params={"size": 100, **params}).json()["total"]


def test_district_filter_narrows_to_addr(client):
    resp = client.get("/api/spots", params={"district": "종로구", "size": 100})
    assert resp.status_code == 200
    items = resp.json()["items"]
    assert items and all("종로구" in (s["addr"] or "") for s in items)


def test_category_bolgeori_excludes_shopping(client):
    # 볼거리군(A01/A02/A03)만 — 쇼핑(A04)이 빠져 전체보다 개수가 적다
    all_total = _total(client)
    bolgeori = _total(client, category="볼거리")
    assert 0 < bolgeori < all_total


def test_category_shopping_returns_shopping(client):
    # 시드에 쇼핑거리(A04) 명소가 있다
    assert _total(client, category="쇼핑") > 0


def test_page_level_badge_present_for_snapshot_spots(client):
    resp = client.get("/api/spots", params={"category": "볼거리", "size": 100})
    items = resp.json()["items"]
    assert items and "level" in items[0]
    # 스냅샷 보유 스팟이 페이지에 포함되면 level이 채워진다(없는 곳은 None)
    assert any(s.get("level") is not None for s in items)
