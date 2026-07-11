"""가중치 로드/재정규화 — 가중치는 코드가 아닌 weights.yaml로 외부화한다(기획서 9-5)."""
from functools import lru_cache

import yaml

from app.config import get_settings


@lru_cache
def load_weights(path: str | None = None) -> dict:
    weights_path = path or get_settings().weights_path
    with open(weights_path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def renormalize(weights: dict[str, float], available: set[str]) -> dict[str, float]:
    """결측 항을 제외하고 남은 가중치를 원래 총합으로 재정규화한다(합계 유지, 9-1).

    예) {a:0.55, b:0.20, c:0.15, d:0.10}에서 d 결측 →
        {a,b,c}를 비율 유지한 채 합이 1.0이 되도록 스케일.
    """
    total = sum(weights.values())
    kept = {k: v for k, v in weights.items() if k in available}
    kept_sum = sum(kept.values())
    if not kept or kept_sum == 0:
        return {}
    scale = total / kept_sum
    return {k: v * scale for k, v in kept.items()}
