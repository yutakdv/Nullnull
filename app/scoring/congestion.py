"""널널도(CongestionRisk) 산식 — 기획서 9-1.

CongestionRisk = 0.55×집중률예측 + 0.20×지역방문자상대지수 + 0.15×수요강도
              + 0.10×요일/공휴일/날씨 보정값
결측 항은 제외 후 가중치 재정규화(합계 1.0 유지). 날씨는 단기예보 범위 내에서만 반영.
"""
from datetime import date

from app.scoring.weights import renormalize

LEVEL_LABELS = ["매우 널널", "널널", "보통", "붐빔", "매우 붐빔"]
LEVEL_COLORS = ["초록", "연두", "노랑", "주황", "빨강"]


def level_of(risk: float) -> int:
    """0~20:1(매우 널널) / 21~40:2 / 41~60:3 / 61~80:4 / 81~100:5(매우 붐빔)"""
    if risk <= 20:
        return 1
    if risk <= 40:
        return 2
    if risk <= 60:
        return 3
    if risk <= 80:
        return 4
    return 5


def label_of(risk: float) -> str:
    return LEVEL_LABELS[level_of(risk) - 1]


def calendar_weather_component(
    d: date,
    holidays: frozenset[date] | set[date] = frozenset(),
    precip_prob: float | None = None,
    is_indoor: bool = False,
) -> float:
    """요일/공휴일/날씨 보정값(0~100). 강수확률은 예보 범위 내일 때만 전달된다."""
    component = 45.0
    weekday = d.weekday()
    if weekday == 5:        # 토
        component += 35.0
    elif weekday == 6:      # 일
        component += 25.0
    elif weekday == 4:      # 금
        component += 12.0
    if d in holidays:
        component += 25.0
    if precip_prob is not None and not is_indoor:
        # 야외 관광지는 비 예보가 높을수록 방문 수요가 줄어든다
        component -= precip_prob * 0.25
    return min(max(component, 0.0), 100.0)


def congestion_risk(
    concentration: float | None,
    region_visitor: float | None,
    demand: float | None,
    calendar_weather: float | None,
    weights: dict[str, float],
) -> float:
    """항목별 0~100 입력(None=결측) → 0~100 위험도. 결측 항은 재정규화로 흡수."""
    components = {
        "concentration": concentration,
        "region_visitor": region_visitor,
        "demand": demand,
        "calendar_weather": calendar_weather,
    }
    available = {k for k, v in components.items() if v is not None}
    if not available:
        raise ValueError("널널도 산출에 필요한 데이터가 없습니다.")
    normalized = renormalize(weights, available)
    risk = sum(normalized[k] * components[k] for k in available)
    return round(min(max(risk, 0.0), 100.0), 1)
