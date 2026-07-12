"""Task1 — '실시간 시간 기준' 널널도: 현재 시각이 속한 시간대는 실측(live)값을 쓴다."""
from datetime import date, datetime, timedelta

from app.services import congestion_service as cs

KST = cs.KST


def _at(hour: int) -> datetime:
    return datetime(2026, 7, 13, hour, 30, tzinfo=KST)


def test_current_time_slot_boundaries():
    assert cs.current_time_slot(_at(9)) == "morning"
    assert cs.current_time_slot(_at(11)) == "morning"
    assert cs.current_time_slot(_at(12)) == "afternoon"
    assert cs.current_time_slot(_at(16)) == "afternoon"
    assert cs.current_time_slot(_at(17)) == "evening"
    assert cs.current_time_slot(_at(21)) == "evening"
    assert cs.current_time_slot(_at(0)) == "morning"


def test_realtime_current_slot_uses_live_value():
    """현재 시각이 속한 시간대는 예측(대표시각)이 아니라 실측 live score를 쓴다."""
    realtime = {"score": 88.0, "forecast": [
        {"hour": "14", "score": 40.0},   # 오후 대표시각 예측
        {"hour": "19", "score": 55.0},   # 저녁 대표시각 예측
    ]}
    afternoon_now = _at(15)   # 지금이 오후
    assert cs._realtime_slot_score(realtime, "afternoon", now=afternoon_now) == 88.0
    # 지금이 아닌 시간대(저녁)는 그 시간대 대표시각 예측을 쓴다
    assert cs._realtime_slot_score(realtime, "evening", now=afternoon_now) == 55.0


def test_realtime_noncurrent_slot_falls_back_to_live_when_hour_missing():
    realtime = {"score": 70.0, "forecast": [{"hour": "20", "score": 50.0}]}
    now = _at(15)   # 지금 오후
    # 오전 대표시각(10) 예측이 없으면 live로 폴백
    assert cs._realtime_slot_score(realtime, "morning", now=now) == 70.0


def test_resolve_time_slot_today_is_current_slot(monkeypatch):
    fixed_now = _at(19)   # 저녁
    monkeypatch.setattr(cs, "now_kst", lambda: fixed_now)
    today = date.today()
    assert cs.resolve_time_slot(today, None) == "evening"          # 당일 → 현재 시각 기준
    assert cs.resolve_time_slot(today, "morning") == "morning"     # 명시값은 유지
    future = today + timedelta(days=3)
    assert cs.resolve_time_slot(future, None) == "afternoon"       # 그 외 날짜 → 오후
