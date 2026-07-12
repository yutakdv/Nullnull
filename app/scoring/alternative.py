"""대안지 추천 점수(AlternativeScore) — 기획서 9-2.

AlternativeScore = 0.30×테마유사도 + 0.25×혼잡완화 + 0.15×이동편의
                 + 0.10×숨은명소성 + 0.10×날씨적합 − 0.10×추천부하
- 테마유사도 = 0.6×Jaccard(카테고리 코드 집합) + 0.4×연관 API similarity, 둘 다 없으면 태그 폴백
- 숨은명소성 = 방문자수 하위 분위 × 콘텐츠 풍부도(모두 0~1)
- 추천부하 = 최근 7일 (노출 + 선택×2) 0~1 정규화, 로그 없으면 0 (F8 콜드스타트 안전)
- 날씨 항은 예보 범위 밖이면 제외 후 남은 가중치 재정규화
"""
from collections.abc import Iterable

from app.scoring.weights import renormalize


def jaccard(a: set, b: set) -> float:
    if not a and not b:
        return 0.0
    union = a | b
    return len(a & b) / len(union) if union else 0.0


def theme_similarity(
    cats_a: set[str],
    cats_b: set[str],
    related_sim: float | None = None,
    tags_a: Iterable[str] = (),
    tags_b: Iterable[str] = (),
    combine_weights: dict[str, float] | None = None,
) -> float:
    """0.6×자카드 + 0.4×연관도. 두 소스 모두 없으면 키워드(태그) 매칭 폴백."""
    weights = combine_weights or {"jaccard": 0.6, "related": 0.4}
    j = jaccard(cats_a, cats_b) if (cats_a and cats_b) else None
    parts = {"jaccard": j, "related": related_sim}
    available = {k for k, v in parts.items() if v is not None}
    if not available:
        return jaccard(set(tags_a), set(tags_b))
    normalized = renormalize(weights, available)
    return round(sum(normalized[k] * parts[k] for k in available), 4)


def mobility_score(travel_min: float, max_min: float = 90.0) -> float:
    """이동 편의성: 가까울수록 1.0, max_min 이상이면 0."""
    return round(min(max(1.0 - travel_min / max_min, 0.0), 1.0), 4)


def hidden_gem_score(visitor_low_percentile: float, content_richness: float) -> float:
    """방문자수 하위 분위(0~1, 낮을수록 한적) × 콘텐츠 풍부도(0~1) 교차 — 9-2 프록시."""
    return round(min(max(visitor_low_percentile * content_richness, 0.0), 1.0), 4)


def recommendation_load(
    exposures: int, selections: int, max_raw: float, select_weight: int = 2
) -> float:
    """추천 부하(F8). 로그가 없으면(max_raw=0) 0 → 페널티 없이 자연 동작."""
    if max_raw <= 0:
        return 0.0
    raw = exposures + select_weight * selections
    return round(min(raw / max_raw, 1.0), 4)


def weather_fit(is_indoor: bool, precip_prob: float | None) -> float | None:
    """날씨/운영 적합성(0~1). 예보가 없으면 None → 가중치 재정규화 대상."""
    if precip_prob is None:
        return None
    if is_indoor:
        return round(0.6 + precip_prob / 250, 4)      # 비 올수록 실내 가점
    return round(max(1.0 - precip_prob / 100, 0.0), 4)


COMPANION_KINDS = ("solo", "couple", "family")


def companion_fit(
    companion: str | None,
    *,
    low_percentile: float,
    tags: Iterable[str] = (),
    is_indoor: bool = False,
) -> float | None:
    """동행 유형별 '우선 추천' 소프트 신호(0~1). 지정 안 하면 None → 재정규화 대상.

    하드 필터가 아니라 작은 가중치의 우선순위 nudge다. 사용자가 고른 테마/카테고리는
    그대로 존중하고(예: 혼자여도 '미식' 슬롯이면 맛집이 후보), 그 안에서 동행에 맞는
    곳을 살짝 위로 올릴 뿐이다.
    - 혼자: 한적하고 덜 알려진 곳 선호(방문 규모 하위 분위가 높을수록 가점)
    - 둘이서: 포토스팟·자연·뷰 선호
    - 가족: 실내·편의 선호(짧은 이동은 mobility 항이 이미 반영)
    """
    if companion not in COMPANION_KINDS:
        return None
    tagset = set(tags or ())
    if companion == "solo":
        return round(0.4 + 0.6 * low_percentile, 4)
    if companion == "couple":
        if {"포토스팟", "자연"} & tagset:
            return 1.0
        if "미식" in tagset:
            return 0.7
        return 0.5
    base = 0.5 + 0.5 * (1.0 if is_indoor else 0.0)   # family
    if "자연" in tagset:                              # 공원·산책 등 가족 나들이 적합
        base = min(base + 0.2, 1.0)
    return round(base, 4)


def alternative_score(
    theme: float,
    relief: float,
    mobility: float,
    hidden: float,
    weather: float | None,
    load: float,
    weights: dict[str, float],
    companion: float | None = None,
) -> float:
    """가중 합산. weather/companion=None이면 해당 항 제외 후 남은 항만 재정규화.

    companion(동행 적합도)은 지정 시에만 작은 가중치 항으로 더해지는 소프트 nudge다.
    companion=None이면 항 자체가 빠져 기존 산식과 완전히 동일(회귀 없음)하다.
    """
    positive = {
        "theme": weights["theme"], "relief": weights["relief"],
        "mobility": weights["mobility"], "hidden": weights["hidden"],
        "weather": weights["weather"],
    }
    parts = {"theme": theme, "relief": relief, "mobility": mobility,
             "hidden": hidden, "weather": weather}
    if companion is not None:
        positive["companion"] = weights.get("companion", 0.12)
        parts["companion"] = companion
    available = {k for k, v in parts.items() if v is not None}
    normalized = renormalize(positive, available)
    score = sum(normalized[k] * parts[k] for k in available)
    score -= weights["load_penalty"] * load
    return round(score, 4)
