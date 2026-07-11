"""거리·이동시간 추정 — 카카오모빌리티 길찾기 키가 없을 때의 하버사인 폴백."""
import math

CITY_DRIVE_KMH = 24.0     # 서울 도심 평균 주행속도(신호 포함) 근사
WALK_KMH = 4.0
WALK_THRESHOLD_KM = 1.2   # 이내면 도보 이동으로 안내


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return round(2 * r * math.asin(math.sqrt(a)), 3)


def estimate_move(dist_km: float) -> tuple[int, str]:
    """(이동시간 분, 이동수단) — 직선거리에 도로 우회계수 1.35 반영."""
    road_km = dist_km * 1.35
    if dist_km <= WALK_THRESHOLD_KM:
        return max(int(road_km / WALK_KMH * 60), 3), "도보"
    return max(int(road_km / CITY_DRIVE_KMH * 60) + 4, 8), "차량"
