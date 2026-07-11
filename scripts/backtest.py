"""가중치 백테스트(기획서 9-5) — `python -m scripts.backtest`

"왜 이 가중치인가"에 대한 근거 산출물. weights.yaml의 현재 가중치로 산출한
CongestionRisk가 상식적 순서를 재현하는지 확인한다.

- 검증 세트: 방문 규모 상위 5곳(대표 명소) + 하위 5곳(소규모 명소) × 오늘~+13일(오후)
- 검증 ①(시간): 스팟별 평균 위험도가 공휴일 > 주말 > 평일 순서인지(창 내 공휴일 없으면 N/A)
- 검증 ②(규모): 스팟 평균 위험도 순위와 방문 규모 순위의 스피어만 순위 상관(1.0에 가까울수록 재현)
- 조정 절차: 순서가 깨지면 weights.yaml을 ±0.05 단위로 수정 → 재실행 → 전/후 비교

결과는 stdout 요약 + `docs/backtest/<날짜>.md` 1페이지로 저장한다
(발표자료 "데이터로 검증한 가중치" 인용용). 시드 데이터로 실행하면 절차 검증이고,
공사 API 실데이터 수집(일배치) 후 실행한 결과가 심사 근거가 된다.
"""
from datetime import date, timedelta
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.config import KR_HOLIDAYS
from app.scoring.weights import load_weights
from app.services.congestion_service import compute_raw_risk

GRID_DAYS = 14
GROUP_SIZE = 5          # 대표/소규모 각 5곳 → 10곳 격자(기획서 9-5)
TIME_SLOT = "afternoon"


def spearman(xs: list[float], ys: list[float]) -> float:
    """스피어만 순위 상관(동순위는 평균 순위) — 외부 의존성 없이 계산."""

    def ranks(vals: list[float]) -> list[float]:
        order = sorted(range(len(vals)), key=lambda i: vals[i])
        result = [0.0] * len(vals)
        i = 0
        while i < len(order):
            j = i
            while j + 1 < len(order) and vals[order[j + 1]] == vals[order[i]]:
                j += 1
            avg_rank = (i + j) / 2 + 1
            for k in range(i, j + 1):
                result[order[k]] = avg_rank
            i = j + 1
        return result

    rx, ry = ranks(xs), ranks(ys)
    n = len(xs)
    mx, my = sum(rx) / n, sum(ry) / n
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    vx = sum((a - mx) ** 2 for a in rx)
    vy = sum((b - my) ** 2 for b in ry)
    return round(cov / (vx * vy) ** 0.5, 4) if vx and vy else 0.0


def day_type(d: date) -> str:
    if d in KR_HOLIDAYS:
        return "holiday"
    return "weekend" if d.weekday() >= 5 else "weekday"


def run(db: Session) -> dict:
    spots = db.scalars(
        select(models.TouristSpot).order_by(models.TouristSpot.base_popularity.desc())
    ).all()
    if len(spots) < GROUP_SIZE * 2:
        raise SystemExit("백테스트에 필요한 스팟이 부족해요(최소 10곳). 시드/수집을 먼저 실행하세요.")
    sample = spots[:GROUP_SIZE] + spots[-GROUP_SIZE:]

    today = date.today()
    days = [today + timedelta(days=offset) for offset in range(GRID_DAYS)]

    per_spot: list[dict] = []
    for spot in sample:
        by_type: dict[str, list[float]] = {"holiday": [], "weekend": [], "weekday": []}
        for d in days:
            risk, _source = compute_raw_risk(db, spot, d, TIME_SLOT,
                                             use_realtime=False, use_weather=False)
            by_type[day_type(d)].append(risk)
        means = {k: (sum(v) / len(v) if v else None) for k, v in by_type.items()}
        all_risks = [r for v in by_type.values() for r in v]
        ordering_ok = (
            (means["holiday"] is None or (
                means["weekend"] is not None and means["holiday"] > means["weekend"]))
            and (means["weekend"] is not None and means["weekday"] is not None
                 and means["weekend"] > means["weekday"])
        )
        per_spot.append({
            "name": spot.name,
            "base_popularity": spot.base_popularity,
            "mean_risk": round(sum(all_risks) / len(all_risks), 1),
            "mean_holiday": means["holiday"] and round(means["holiday"], 1),
            "mean_weekend": means["weekend"] and round(means["weekend"], 1),
            "mean_weekday": means["weekday"] and round(means["weekday"], 1),
            "ordering_ok": ordering_ok,
        })

    day_order_pass = sum(1 for r in per_spot if r["ordering_ok"])
    scale_corr = spearman(
        [r["base_popularity"] for r in per_spot],
        [r["mean_risk"] for r in per_spot],
    )
    has_holiday = any(day_type(d) == "holiday" for d in days)
    return {
        "grid": f"{sample[0].name} 외 {len(sample) - 1}곳 × {GRID_DAYS}일({TIME_SLOT})",
        "window": (today.isoformat(), days[-1].isoformat()),
        "has_holiday_in_window": has_holiday,
        "day_order_pass": day_order_pass,
        "day_order_total": len(per_spot),
        "scale_spearman": scale_corr,
        "per_spot": per_spot,
        "weights": load_weights()["congestion_risk"],
    }


def render_markdown(result: dict) -> str:
    w = result["weights"]
    lines = [
        "# 널널도 가중치 백테스트 결과 (기획서 9-5)",
        "",
        f"- 실행일: {date.today().isoformat()} / 격자: {result['grid']}"
        f" / 기간: {result['window'][0]} ~ {result['window'][1]}",
        f"- 검증 가중치: 집중률 {w['concentration']} · 지역방문자 {w['region_visitor']}"
        f" · 수요강도 {w['demand']} · 요일보정 {w['calendar_weather']} (weights.yaml)",
        "",
        "## 요약",
        "",
        f"| 검증 항목 | 기준 | 결과 |",
        f"|---|---|---|",
        f"| ① 요일 유형 순서(공휴일 > 주말 > 평일) | 전 스팟 재현 "
        f"| **{result['day_order_pass']}/{result['day_order_total']} 스팟 통과**"
        + ("" if result["has_holiday_in_window"] else " (창 내 공휴일 없음 — 주말>평일만 검증)")
        + " |",
        f"| ② 명소 규모 순위 상관(대표 > 소규모) | 스피어만 ≥ 0.8 "
        f"| **ρ = {result['scale_spearman']}** |",
        "",
        "## 스팟별 상세",
        "",
        "| 관광지 | 방문 규모 | 평균 위험도 | 공휴일 | 주말 | 평일 | 순서 재현 |",
        "|---|---:|---:|---:|---:|---:|:-:|",
    ]
    for r in result["per_spot"]:
        lines.append(
            f"| {r['name']} | {r['base_popularity']} | {r['mean_risk']}"
            f" | {r['mean_holiday'] or '—'} | {r['mean_weekend'] or '—'}"
            f" | {r['mean_weekday'] or '—'} | {'✅' if r['ordering_ok'] else '❌'} |"
        )
    lines += [
        "",
        "## 판단·조정 절차",
        "",
        "- 순서가 깨진 항목이 있으면 해당 항 가중치를 ±0.05 단위로 조정 후 재실행해",
        "  전/후 표를 비교한다(기획서 9-5). 최종값은 weights.yaml로 확정.",
        "- 시드 데이터 기준 실행은 절차 검증이며, 공사 OpenAPI 실데이터 수집",
        "  (`python -m app.batch.daily`) 후 재실행한 결과를 심사 자료에 인용한다.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    from app.database import Base, SessionLocal, engine

    Base.metadata.create_all(engine)
    with SessionLocal() as db:
        result = run(db)
    report = render_markdown(result)
    out = Path("docs/backtest") / f"{date.today().isoformat()}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    print(f"→ 저장: {out}")


if __name__ == "__main__":
    main()
