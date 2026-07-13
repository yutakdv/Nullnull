"""당일이고 area_key가 있으면 top 후보 risk가 실시간으로 교체된다(단위 계약)."""


def test_realtime_slot_score_blend_contract():
    from app.services.congestion_service import _realtime_slot_score, current_time_slot
    from datetime import datetime
    rt = {"score": 88.0, "forecast": [{"hour": "19", "score": 55.0}]}
    now = datetime(2026, 7, 13, 15, 0)  # 오후
    assert _realtime_slot_score(rt, current_time_slot(now), now=now) == 88.0
