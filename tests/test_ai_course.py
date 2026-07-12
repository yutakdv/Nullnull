"""AI 코스 추천 — 후보 큐레이션·LLM 검증·알고리즘 폴백(Task B2·B3).

테스트 환경은 데모(무키)라 서비스 계층 호출은 폴백 경로를 타고,
LLM 경로는 monkeypatch로 complete_courses를 대체해 검증한다.

이 파일은 실제 코스·추천 로그를 생성하므로, 세션 공유 DB를 오염시켜
F8 부하에 의존하는 다른 테스트(대안지 정렬 등)를 흔들지 않도록
각 테스트가 만든 행을 autouse fixture로 정리한다.
"""
from datetime import date, timedelta

import pytest
from sqlalchemy import delete, func, select

from app import models


@pytest.fixture(autouse=True)
def _isolate(db):
    before_log = db.scalar(select(func.max(models.RecommendationLog.id))) or 0
    before_course = db.scalar(select(func.max(models.Course.course_id))) or 0
    yield
    db.rollback()   # API가 다른 세션에서 커밋한 행까지 보이도록 새 트랜잭션으로
    new_courses = db.scalars(
        select(models.Course.course_id).where(models.Course.course_id > before_course)
    ).all()
    if new_courses:
        db.execute(delete(models.CourseItem)
                   .where(models.CourseItem.course_id.in_(new_courses)))
        db.execute(delete(models.RecommendationEvidence)
                   .where(models.RecommendationEvidence.course_id.in_(new_courses)))
        db.execute(delete(models.Course)
                   .where(models.Course.course_id.in_(new_courses)))
    db.execute(delete(models.RecommendationLog)
               .where(models.RecommendationLog.id > before_log))
    db.commit()


def _saturday():
    today = date.today()
    return today + timedelta(days=(5 - today.weekday()) % 7)


def _item_count(db, course):
    return db.scalar(
        select(func.count()).select_from(models.CourseItem)
        .where(models.CourseItem.course_id == course.course_id)
    )


def test_ai_recommend_falls_back_to_algorithm_in_demo(client, db):
    from app.services import course_service

    courses, source = course_service.ai_recommend_courses(
        db, district="종로구", stops=3, companion=None, visit_date=_saturday(),
        time_slot="afternoon", themes=["역사"], pace="여유",
        indoor_pref="상관없음", count=3)
    assert source == "algorithm"          # 데모=무키
    assert 1 <= len(courses) <= 3
    for course in courses:
        assert _item_count(db, course) >= 2


def test_ai_validates_and_uses_llm_spot_ids(client, db, monkeypatch):
    from app.services import course_service
    from app.external import openai_api

    d = _saturday()
    pool, risks = course_service._curate_candidates(
        db, district="종로구", themes=["역사"], visit_date=d, time_slot="afternoon")
    reference = course_service._crowd_reference(
        db, district="종로구", visit_date=d, time_slot="afternoon")
    ref_id = reference.spot_id if reference else None
    candidate_ids = [s.spot_id for s in pool if s.spot_id != ref_id]
    assert len(candidate_ids) >= 2

    def fake(_payload):
        # 후보 2개 + 존재하지 않는 id(999999)를 섞어 반환 → 검증에서 999999가 걸러져야
        return {"courses": [{
            "title": "종로 널널 산책", "concept": "고궁 사이 여유", "reason": "붐빔 회피",
            "stops": [{"spot_id": candidate_ids[0]}, {"spot_id": 999999},
                      {"spot_id": candidate_ids[1]}],
        }]}

    monkeypatch.setattr(openai_api, "complete_courses", fake)
    courses, source = course_service.ai_recommend_courses(
        db, district="종로구", stops=3, companion=None, visit_date=d,
        time_slot="afternoon", themes=["역사"], pace="여유",
        indoor_pref="상관없음", count=3)

    assert source == "llm"
    assert len(courses) == 1
    item_ids = db.scalars(
        select(models.CourseItem.spot_id)
        .where(models.CourseItem.course_id == courses[0].course_id)
    ).all()
    assert 999999 not in item_ids
    assert all(sid in candidate_ids for sid in item_ids)


def test_ai_recommend_endpoint_returns_multiple_courses(client):
    body = {"district": "종로구", "stops": 3, "date": _saturday().isoformat(),
            "time_slot": "afternoon", "themes": ["역사"], "pace": "여유",
            "indoor_pref": "상관없음"}
    resp = client.post("/api/courses/ai-recommend", json=body)
    assert resp.status_code == 201
    data = resp.json()
    assert data["source"] in ("llm", "algorithm")
    assert 1 <= len(data["courses"]) <= 3
    assert data["courses"][0]["course_id"]
    assert data["courses"][0]["timeline"]


def test_ai_recommend_walk_transport_makes_walkable_courses(client):
    """이동 방식 '도보' — 모든 구간이 도보로 계산되고 도보권 후보로 좁혀진다."""
    body = {"district": "종로구", "stops": 3, "date": _saturday().isoformat(),
            "time_slot": "afternoon", "themes": [], "pace": "여유",
            "indoor_pref": "상관없음", "transport": "walk"}
    resp = client.post("/api/courses/ai-recommend", json=body)
    assert resp.status_code == 201
    for course in resp.json()["courses"]:
        moves = [item["move"] for item in course["timeline"]]
        assert moves[-1] == "마무리"
        assert all(move.startswith("도보") for move in moves[:-1])


def test_ai_recommend_car_transport_uses_drive_moves(client):
    """이동 방식 '차량' — 짧은 구간도 차량 기준으로 계산된다."""
    body = {"district": "종로구", "stops": 2, "date": _saturday().isoformat(),
            "time_slot": "afternoon", "themes": [], "pace": "여유",
            "indoor_pref": "상관없음", "transport": "car"}
    resp = client.post("/api/courses/ai-recommend", json=body)
    assert resp.status_code == 201
    for course in resp.json()["courses"]:
        moves = [item["move"] for item in course["timeline"]]
        assert all(move.startswith("차량") for move in moves[:-1])
