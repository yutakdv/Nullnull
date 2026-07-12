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


def walk_minutes(road_km: float) -> int:
    return max(int(road_km / WALK_KMH * 60), 3)


def drive_minutes(road_km: float) -> int:
    return max(int(road_km / CITY_DRIVE_KMH * 60) + 4, 8)


def estimate_move(dist_km: float, transport: str | None = None) -> tuple[int, str]:
    """(이동시간 분, 이동수단) — 직선거리에 도로 우회계수 1.35 반영.

    transport('walk'|'car')가 주어지면 거리 임계값과 무관하게 그 수단 기준으로
    계산한다(AI 코스의 이동 방식 필터).
    """
    road_km = dist_km * 1.35
    if transport == "walk":
        return walk_minutes(road_km), "도보"
    if transport == "car":
        return drive_minutes(road_km), "차량"
    if dist_km <= WALK_THRESHOLD_KM:
        return walk_minutes(road_km), "도보"
    return drive_minutes(road_km), "차량"
