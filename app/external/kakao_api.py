"""카카오모빌리티 길찾기 API(무료 쿼터) — 기획서 8-3.

'대안 코스' 이동시간 근거 확보용. 키가 없으면 None → 하버사인 추정(app.geo)으로 폴백.
"""
import httpx

from app.config import get_settings


def directions(origin_lng: float, origin_lat: float,
              dest_lng: float, dest_lat: float) -> tuple[float, int] | None:
    """(거리 km, 소요 분) 또는 None."""
    settings = get_settings()
    if settings.is_demo or not settings.kakao_rest_api_key:
        return None
    try:
        resp = httpx.get(
            "https://apis-navi.kakaomobility.com/v1/directions",
            params={"origin": f"{origin_lng},{origin_lat}",
                    "destination": f"{dest_lng},{dest_lat}"},
            headers={"Authorization": f"KakaoAK {settings.kakao_rest_api_key}"},
            timeout=10,
        )
        resp.raise_for_status()
        summary = resp.json()["routes"][0]["summary"]
        return round(summary["distance"] / 1000, 2), max(summary["duration"] // 60, 1)
    except Exception:
        return None
