"""피드백 기반 예측 보정 — 기획서 9-4.

adjusted_risk = CongestionRisk × (1 + α × feedback_bias),  α=0.2(초기값)
feedback_bias = EWMA(체감 혼잡 −1/0/+1 최근 30건)
30건 미만 장소는 보정 미적용(콜드스타트 왜곡 방지) — 판단은 호출부에서 min_count로 수행.
"""


def ewma_bias(perceived: list[int], span: int = 10) -> float:
    """오래된 것부터 순서대로 EWMA. 반환 범위 대략 -1.0 ~ +1.0."""
    if not perceived:
        return 0.0
    alpha = 2.0 / (span + 1)
    bias = float(perceived[0])
    for value in perceived[1:]:
        bias = alpha * value + (1 - alpha) * bias
    return bias


def adjusted_risk(risk: float, bias: float, alpha: float = 0.2) -> float:
    return round(min(max(risk * (1 + alpha * bias), 0.0), 100.0), 1)
