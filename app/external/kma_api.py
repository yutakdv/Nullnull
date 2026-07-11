"""기상청 단기예보 API(무료) — 기획서 8-3.

방문일이 예보 제공 범위(~3일) 이내일 때만 야외/실내 가중치를 적용하고,
범위 밖이면 해당 항을 제외 후 가중치를 재정규화한다(9-1과 동일 원칙).
키가 없거나 오류면 None을 반환해 산식에서 자연스럽게 빠진다.

발표분(base_time)에 따라 NO_DATA가 나올 수 있어 최신 발표부터 순차 폴백하고,
같은 격자·발표분 응답은 프로세스 내 캐시해 배치에서 반복 호출을 막는다.
"""
import math
from datetime import date, timedelta
from functools import lru_cache

import httpx

from app.config import get_settings

# 시간대별 대표 예보 시각
SLOT_HOURS = {"morning": "1000", "afternoon": "1400", "evening": "1900"}


def latlng_to_grid(lat: float, lng: float) -> tuple[int, int]:
    """기상청 LCC(람베르트 정각원추) 격자 변환 — 공식 배포 산식."""
    re, grid = 6371.00877, 5.0
    slat1, slat2 = math.radians(30.0), math.radians(60.0)
    olon, olat = math.radians(126.0), math.radians(38.0)
    xo, yo = 43, 136

    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(
        math.tan(math.pi / 4 + slat2 / 2) / math.tan(math.pi / 4 + slat1 / 2)
    )
    sf = (math.tan(math.pi / 4 + slat1 / 2) ** sn) * math.cos(slat1) / sn
    ro = re / grid * sf / (math.tan(math.pi / 4 + olat / 2) ** sn)

    ra = re / grid * sf / (math.tan(math.pi / 4 + math.radians(lat) / 2) ** sn)
    theta = math.radians(lng) - olon
    if theta > math.pi:
        theta -= 2 * math.pi
    if theta < -math.pi:
        theta += 2 * math.pi
    theta *= sn
    x = int(ra * math.sin(theta) + xo + 0.5)
    y = int(ro - ra * math.cos(theta) + yo + 0.5)
    return x, y


@lru_cache(maxsize=128)
def _fetch_items(nx: int, ny: int, base_date: str, base_time: str) -> tuple | None:
    """한 발표분의 예보 항목. 실패/NO_DATA → None. lru_cache로 격자당 1회 호출."""
    settings = get_settings()
    try:
        resp = httpx.get(
            "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst",
            params={
                "serviceKey": settings.kma_api_key, "dataType": "JSON",
                "base_date": base_date, "base_time": base_time,
                "nx": nx, "ny": ny, "numOfRows": 1000, "pageNo": 1,
            },
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()["response"]
        if data["header"].get("resultCode") != "00":
            return None
        items = data["body"]["items"]["item"]
    except Exception:
        return None
    return tuple(
        (it.get("category"), it.get("fcstDate"), it.get("fcstTime"), it.get("fcstValue"))
        for it in items
    )


def _forecast_items(nx: int, ny: int) -> tuple | None:
    """최신 발표분부터 폴백: 오늘 0200 → 어제 2300 → 어제 0200."""
    today = date.today()
    yesterday = today - timedelta(days=1)
    candidates = [
        (today.strftime("%Y%m%d"), "0200"),
        (yesterday.strftime("%Y%m%d"), "2300"),
        (yesterday.strftime("%Y%m%d"), "0200"),
    ]
    for base_date, base_time in candidates:
        items = _fetch_items(nx, ny, base_date, base_time)
        if items:
            return items
    return None


def get_precip_prob(lat: float, lng: float, d: date,
                    time_slot: str = "afternoon") -> float | None:
    """방문일·시간대의 강수확률(POP %). 예보 범위 밖/키 없음/오류 → None."""
    settings = get_settings()
    if not settings.kma_api_key:
        return None
    today = date.today()
    if not (today <= d <= today + timedelta(days=settings.weather_forecast_days)):
        return None

    nx, ny = latlng_to_grid(lat, lng)
    items = _forecast_items(nx, ny)
    if not items:
        return None

    target_date = d.strftime("%Y%m%d")
    target_time = SLOT_HOURS.get(time_slot, "1400")
    pops = [
        float(value) for category, fcst_date, fcst_time, value in items
        if category == "POP" and fcst_date == target_date
        and (fcst_time or "") >= target_time
    ]
    return max(pops[:3]) if pops else None
