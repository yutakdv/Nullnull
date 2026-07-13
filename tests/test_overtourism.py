"""오버투어리즘(관광객 쏠림) 지수 — 실시간 비상주율 기반, 비실시간이면 전부 None."""
from app.services import congestion_service as cs


def test_overtourism_fields_from_realtime():
    rt = {"non_resident_rate": 63.5, "ppltn_min": 42000, "ppltn_max": 44000,
          "congest_msg": "붐빕니다"}
    out = cs.overtourism_fields(rt)
    assert out["tourist_share_pct"] == 64
    assert out["tourist_pressure"] == "관광객 쏠림"
    assert out["live_ppltn_min"] == 42000 and out["live_ppltn_max"] == 44000


def test_overtourism_fields_none_when_not_realtime():
    out = cs.overtourism_fields(None)
    assert out["tourist_share_pct"] is None and out["tourist_pressure"] is None
