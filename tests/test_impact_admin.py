"""임팩트 집계(시드 제외)·관리자 API·일배치 수집 로그."""
from sqlalchemy import func, select

from app import models

ADMIN = {"X-Admin-Token": "test-admin"}


def test_impact_summary_excludes_seed(client):
    resp = client.get("/api/impact/summary")
    assert resp.status_code == 200
    body = resp.json()
    # 앞선 테스트에서 실사용 코스가 생성되었으므로 시드 없이 집계돼야 한다
    assert body["includes_seed"] is False
    assert body["courses_created"] >= 1
    assert body["avoid_rate_avg_pct"] > 0
    assert body["hidden_pick_count"] >= 1     # 운현궁 등 숨은 명소 선택 로그


def test_admin_requires_token(client):
    assert client.get("/api/admin/ingest-log").status_code == 401
    assert client.get("/api/admin/ingest-log",
                      headers={"X-Admin-Token": "wrong"}).status_code == 401


def test_admin_seed_injection_marked(client, db):
    before = db.scalar(select(func.count()).select_from(models.RecommendationLog))
    resp = client.post("/api/admin/seed", headers=ADMIN,
                       json={"exposures": 10, "selections": 2, "feedbacks": 4})
    assert resp.status_code == 200
    after = db.scalar(select(func.count()).select_from(models.RecommendationLog))
    assert after == before + 12

    # 주입분은 전부 is_seed=true → 임팩트 집계에 영향 없음(데이터 정직성)
    impact = client.get("/api/impact/summary").json()
    assert impact["includes_seed"] is False


def test_admin_ingest_log_and_load_distribution(client):
    body = client.get("/api/admin/ingest-log", headers=ADMIN).json()
    assert any(log["api_name"] == "seed_bootstrap" for log in body["ingest"])
    assert body["load_distribution"]
    top = body["load_distribution"][0]
    assert 0 < top["load"] <= 1.0
    assert top["exposures"] > 0


def test_daily_batch_without_keys_all_skipped(client, db):
    """API 키가 없어도 배치는 성공하고 5종 전부 skipped로 기록된다(콜드스타트 안전)."""
    from app.batch.daily import ingest_all

    results = ingest_all(db)
    assert len(results) == 6      # 공사 5종 + detailCommon2 상세 보강
    assert all(status == "skipped" for status in results.values())

    skipped = db.scalar(
        select(func.count()).select_from(models.ApiIngestLog)
        .where(models.ApiIngestLog.status == "skipped")
    )
    assert skipped >= 6
