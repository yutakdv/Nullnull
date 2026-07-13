"""서울 실시간 도시데이터 API(무료) — 기획서 8-3, 서울 집중 MVP.

5분 단위 실시간 인구·혼잡도 + 향후 12시간 예측 → F3 당일 시간대 분산에 활용(9-1).
당일 조회 시 집중률 예측값 자리에 실시간/12시간 예측을 대입한다.
"""
import time

import httpx

from app.config import get_settings

CONGEST_LEVEL_SCORE = {"여유": 20.0, "보통": 45.0, "약간 붐빔": 70.0, "붐빔": 90.0}

# 5분 단위 갱신 데이터이므로 60초 캐시로 시간대 비교(3회 조회) 시 중복 호출 방지
_CACHE_TTL_SEC = 60
_cache: dict[str, tuple[float, dict | None]] = {}

# 관광특구·고궁 등 서울시 정의 핫스팟 장소명(citydata 지원 지역)과 시드 스팟 매핑
SPOT_TO_AREA = {
    "경복궁": "경복궁", "창덕궁": "창덕궁·종묘", "덕수궁": "덕수궁길·정동길",
    "북촌한옥마을": "북촌한옥마을", "명동거리": "명동 관광특구",
    "N서울타워": "남산공원", "홍대거리": "홍대 관광특구",
    "익선동 골목": "익선동", "낙산공원": "혜화역", "서울숲": "서울숲공원",
}


def _fcst_hour(raw: str) -> str:
    """FCST_TIME('2026-07-11 12:00' 형태)에서 시(HH) 추출."""
    tail = raw.split(" ")[-1]          # '12:00'
    return tail.split(":")[0].zfill(2) if ":" in tail else ""


def _num(v, cast):
    try:
        return cast(v)
    except (TypeError, ValueError):
        return None


def _parse_ppltn_row(row: dict) -> dict:
    """citydata_ppltn 한 행 → 라벨 score + 실인원·비상주율·메시지·12시간 예측."""
    label = row.get("AREA_CONGEST_LVL")
    forecast = []
    for f in row.get("FCST_PPLTN", []) or []:
        forecast.append({
            "hour": _fcst_hour(str(f.get("FCST_TIME", ""))),
            "level_label": f.get("FCST_CONGEST_LVL"),
            "score": CONGEST_LEVEL_SCORE.get(f.get("FCST_CONGEST_LVL"), 45.0),
            "ppltn_min": _num(f.get("FCST_PPLTN_MIN"), int),
            "ppltn_max": _num(f.get("FCST_PPLTN_MAX"), int),
        })
    return {
        "score": CONGEST_LEVEL_SCORE.get(label, 45.0),
        "level_label": label,
        "ppltn_min": _num(row.get("AREA_PPLTN_MIN"), int),
        "ppltn_max": _num(row.get("AREA_PPLTN_MAX"), int),
        "non_resident_rate": _num(row.get("NON_RESNT_PPLTN_RATE"), float),
        "congest_msg": row.get("AREA_CONGEST_MSG"),
        "ppltn_time": row.get("PPLTN_TIME"),
        "forecast": forecast,
    }


def get_realtime_congestion(spot_name: str) -> dict | None:
    """{'score': 0~100, 'forecast': [{'hour': 'HH', 'score': ...}]} 또는 None."""
    settings = get_settings()
    area = SPOT_TO_AREA.get(spot_name)
    if settings.is_demo or not settings.seoul_api_key or not area:
        return None

    cached = _cache.get(area)
    if cached and time.monotonic() - cached[0] < _CACHE_TTL_SEC:
        return cached[1]
    try:
        resp = httpx.get(
            f"http://openapi.seoul.go.kr:8088/{settings.seoul_api_key}"
            f"/json/citydata_ppltn/1/5/{area}",
            timeout=10,
        )
        resp.raise_for_status()
        rows = resp.json().get("SeoulRtd.citydata_ppltn", [])
        if not rows:
            _cache[area] = (time.monotonic(), None)
            return None
        row = rows[0]
    except Exception:
        return None

    result = _parse_ppltn_row(row)
    _cache[area] = (time.monotonic(), result)
    return result
