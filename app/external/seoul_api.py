"""서울 실시간 도시데이터 API(무료) — 기획서 8-3, 서울 집중 MVP.

5분 단위 실시간 인구·혼잡도 + 향후 12시간 예측 → F3 당일 시간대 분산에 활용(9-1).
당일 조회 시 집중률 예측값 자리에 실시간/12시간 예측을 대입한다.
"""
import time

import httpx

from app.config import get_settings
from app.matching import normalize_name

CONGEST_LEVEL_SCORE = {"여유": 20.0, "보통": 45.0, "약간 붐빔": 70.0, "붐빔": 90.0}

# 5분 단위 갱신 데이터이므로 60초 캐시로 시간대 비교(3회 조회) 시 중복 호출 방지
_CACHE_TTL_SEC = 60
_cache: dict[str, tuple[float, dict | None]] = {}

# 관광특구·고궁 등 서울시 정의 핫스팟 장소명(citydata 지원 지역)과 시드 스팟 매핑
# (조회 경로는 A3부터 SpotExternalRef 기반 area_key — 이 맵은 하위호환 래퍼용)
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


# 라벨 밴드 중앙값(기존 CONGEST_LEVEL_SCORE와 동일) ± 반폭 안에서 실인원 위치로 보간
BAND_CENTER = {"여유": 20.0, "보통": 45.0, "약간 붐빔": 70.0, "붐빔": 90.0}
BAND_HALFWIDTH = 12.5


def refined_score(level_label, ppltn_mid, area_lo, area_hi) -> float:
    """라벨 밴드 내 실인원 보간. 인원 결측·범위 0이면 라벨 점수 그대로(회귀 0)."""
    center = BAND_CENTER.get(level_label, 45.0)
    if ppltn_mid is None or area_hi <= area_lo:
        return center
    pos = (ppltn_mid - area_lo) / (area_hi - area_lo)
    return round(min(max(center + (pos - 0.5) * 2 * BAND_HALFWIDTH, 0.0), 100.0), 1)


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
    row_min = _num(row.get("AREA_PPLTN_MIN"), int)
    row_max = _num(row.get("AREA_PPLTN_MAX"), int)
    result = {
        "score": CONGEST_LEVEL_SCORE.get(label, 45.0),
        "level_label": label,
        "ppltn_min": row_min,
        "ppltn_max": row_max,
        "non_resident_rate": _num(row.get("NON_RESNT_PPLTN_RATE"), float),
        "congest_msg": row.get("AREA_CONGEST_MSG"),
        "ppltn_time": row.get("PPLTN_TIME"),
        "forecast": forecast,
    }
    # 당일 관측·예측 인원 범위 안에서 현재/예측 스코어를 밴드 내 보간으로 정밀화
    mids = [(e["ppltn_min"] + e["ppltn_max"]) / 2
            for e in forecast if e["ppltn_min"] and e["ppltn_max"]]
    cur_mid = ((row_min + row_max) / 2) if (row_min and row_max) else None
    all_mids = [m for m in ([cur_mid] + mids) if m is not None]
    lo, hi = (min(all_mids), max(all_mids)) if all_mids else (0, 0)
    result["score"] = refined_score(label, cur_mid, lo, hi)
    for e in forecast:
        e_mid = (e["ppltn_min"] + e["ppltn_max"]) / 2 if (e["ppltn_min"] and e["ppltn_max"]) else None
        e["score"] = refined_score(e["level_label"], e_mid, lo, hi)
    return result


# 정규화 area 키(SpotExternalRef.ext_key) → 서울 API 원문 area명
AREA_KEYS = {normalize_name(a): a for a in SPOT_TO_AREA.values()}


def get_realtime_by_area(area_key: str) -> dict | None:
    """정규화 area 키로 실시간 조회 — {'score': 0~100, 'forecast': [...]} 또는 None."""
    settings = get_settings()
    area = AREA_KEYS.get(area_key)
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


def get_realtime_congestion(spot_name: str) -> dict | None:
    """스팟명 래퍼(하위호환) — SPOT_TO_AREA 매핑 후 area_key 조회로 위임."""
    area = SPOT_TO_AREA.get(spot_name)
    if not area:
        return None
    return get_realtime_by_area(normalize_name(area))
