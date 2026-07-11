"""코스 점수(CourseScore) — 기획서 9-3.

CourseScore = 평균 AlternativeScore − 이동시간 패널티 − 동일 카테고리 반복 패널티 + 지역 분산 보너스
"""


def course_score(
    alternative_scores: list[float],
    total_move_min: float,
    category_repeats: int,
    distinct_zones: int,
    weights: dict[str, float],
) -> float:
    if not alternative_scores:
        return 0.0
    avg = sum(alternative_scores) / len(alternative_scores)
    score = avg
    score -= weights["move_penalty_per_10min"] * (total_move_min / 10.0)
    score -= weights["category_repeat_penalty"] * category_repeats
    score += weights["dispersion_bonus"] * max(distinct_zones - 1, 0)
    return round(score, 4)
