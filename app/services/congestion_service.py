"""널널도 조회 서비스(F3) — 시간 분산의 근거 데이터를 만든다.

조회 우선순위: spot_score_daily 캐시(배치 산출) → 실시간(당일·서울 키 보유 시)
→ congestion_snapshot(예측/시드) → base_popularity 휴리스틱.
피드백 보정(9-4)은 창(30건) 채운 장소에만 적용한다.
"""
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import models
from app.config import KR_HOLIDAYS, get_settings
from app.external import kma_api, seoul_api
from app.scoring.congestion import (
    calendar_weather_component,
    congestion_risk,
    label_of,
    level_of,
    LEVEL_COLORS,
)
from app.scoring.feedback_adjust import adjusted_risk, ewma_bias
from app.scoring.weights import load_weights

SLOT_LABELS = {"morning": "오전", "afternoon": "오후", "evening": "저녁"}
DAY_LABELS = ["월", "화", "수", "목", "금", "토", "일"]
TIME_SLOTS = ["morning", "afternoon", "evening"]
REALTIME_SLOT_HOUR = {"morning": 10, "afternoon": 14, "evening": 19}
SOURCE_NOTICES = {
    "realtime": "서울 실시간 도시데이터 기반(5분 단위)",
    "prediction": "한국관광공사 집중률 예측 기반(향후 30일)",
    "snapshot": "수집 스냅샷 기반 예측",
    "heuristic": "기준 방문 규모 기반 추정",
    "cache": "일배치 산출 점수(피드백 보정 포함)",
}


def source_notice(source: str) -> str:
    return SOURCE_NOTICES.get(source, "관광 데이터 기반 추정")


def _realtime_slot_score(realtime: dict, time_slot: str) -> float:
    """당일 시간대 분산(9-1): 서울 12시간 예측에서 해당 시간대 값을 집중률 자리에 대입."""
    target = REALTIME_SLOT_HOUR.get(time_slot, 14)
    for fcst in realtime.get("forecast", []):
        try:
            if int(fcst["hour"]) == target:
                return fcst["score"]
        except (TypeError, ValueError):
            continue
    return realtime["score"]


class ForecastWindowError(ValueError):
    """30일 예측 창(8-1) 밖의 날짜 요청."""


def validate_visit_date(d: date) -> None:
    today = date.today()
    window = get_settings().forecast_window_days
    if d < today:
        raise ForecastWindowError("지난 날짜는 조회할 수 없어요. 오늘 이후 날짜를 선택해주세요.")
    if d > today + timedelta(days=window):
        raise ForecastWindowError("예측 데이터는 향후 30일까지 제공됩니다.")


def default_visit_date() -> date:
    """다가오는 주말(토요일, 오늘이 토요일이면 오늘) — 기획서 6장 기본값."""
    today = date.today()
    return today + timedelta(days=(5 - today.weekday()) % 7)


def feedback_bias(db: Session, spot_id: int) -> tuple[float, bool]:
    """(bias, 적용 여부). 창 내 피드백이 min_count 미만이면 미적용(콜드스타트 방지)."""
    fw = load_weights()["feedback"]
    rows = db.scalars(
        select(models.VisitFeedback.perceived)
        .where(models.VisitFeedback.spot_id == spot_id)
        .order_by(models.VisitFeedback.created_at.desc())
        .limit(fw["window"])
    ).all()
    if len(rows) < fw["min_count"]:
        return 0.0, False
    return ewma_bias(list(reversed(rows)), span=fw["ewma_span"]), True


def compute_raw_risk(
    db: Session,
    spot: models.TouristSpot,
    d: date,
    time_slot: str = "afternoon",
    use_realtime: bool = True,
    use_weather: bool = True,
) -> tuple[float, str]:
    """보정 전 널널도(0~100)와 데이터 출처 — 배치와 실시간 조회가 공유하는 산출부."""
    weights = load_weights()["congestion_risk"]

    # 집중률 예측값 자리: 실시간(당일·서울) → 스냅샷 → 휴리스틱
    concentration, source = None, "prediction"
    if use_realtime and d == date.today():
        realtime = seoul_api.get_realtime_congestion(spot.name)
        if realtime:
            concentration = _realtime_slot_score(realtime, time_slot)
            source = "realtime"
    if concentration is None:
        snap = db.scalar(
            select(models.CongestionSnapshot).where(
                models.CongestionSnapshot.spot_id == spot.spot_id,
                models.CongestionSnapshot.date == d,
                models.CongestionSnapshot.time_slot == time_slot,
            )
        )
        if snap:
            concentration = snap.congestion_score
            source = "snapshot" if snap.source == "seed" else "prediction"
    if concentration is None:
        concentration, source = spot.base_popularity, "heuristic"

    # 3) 지역 방문자수 상대지수·수요 강도(없으면 재정규화로 흡수)
    region = db.scalar(
        select(models.RegionStatDaily).where(
            models.RegionStatDaily.area_code == spot.area_code,
            models.RegionStatDaily.date == d,
        )
    )

    # 4) 요일/공휴일/날씨 보정 — 날씨는 단기예보 범위 내에서만(조건부 적용)
    precip = kma_api.get_precip_prob(spot.lat, spot.lng, d, time_slot) if use_weather else None
    calendar = calendar_weather_component(d, KR_HOLIDAYS, precip, spot.is_indoor)

    raw = congestion_risk(
        concentration,
        region.visitor_index if region else None,
        region.demand_intensity if region else None,
        calendar,
        weights,
    )
    return raw, source


def compute_risk(
    db: Session,
    spot: models.TouristSpot,
    d: date,
    time_slot: str = "afternoon",
    use_realtime: bool = True,
) -> dict:
    """{'risk','raw_risk','adjusted','source'} — 피드백 보정 반영 최종 널널도."""
    fw = load_weights()["feedback"]

    # 1) 당일 서울 실시간 도시데이터는 캐시보다 우선한다.
    #    (실시간 값은 이미 실제 조건을 반영하므로 날씨 항은 생략 — 요청 경로에서
    #     기상청 HTTP 호출을 없애 응답 지연을 막는다. 날씨는 배치 캐시에 반영됨)
    if use_realtime and d == date.today():
        raw, source = compute_raw_risk(db, spot, d, time_slot,
                                       use_realtime=True, use_weather=False)
        if source == "realtime":
            bias, applied = feedback_bias(db, spot.spot_id)
            final = adjusted_risk(raw, bias, fw["alpha"]) if applied else raw
            return {"risk": final, "raw_risk": raw, "adjusted": applied, "source": source}

    # 2) 배치가 채워 둔 캐시(조회 시 추가 연산 없음, 9-4)
    cached = db.scalar(
        select(models.SpotScoreDaily).where(
            models.SpotScoreDaily.spot_id == spot.spot_id,
            models.SpotScoreDaily.date == d,
            models.SpotScoreDaily.time_slot == time_slot,
        )
    )
    if cached:
        final = cached.adjusted_risk if cached.adjusted_risk is not None else cached.congestion_risk
        return {
            "risk": final, "raw_risk": cached.congestion_risk,
            "adjusted": cached.adjusted_risk is not None, "source": "cache",
        }

    # 3) 캐시 미스 시 즉석 산출 — 날씨 항은 일배치 캐시 전용(요청 경로에서 기상청
    #    호출 금지: 스냅샷 없는 스팟은 어차피 휴리스틱이라 날씨 정밀도가 무의미)
    raw, source = compute_raw_risk(db, spot, d, time_slot, use_realtime,
                                   use_weather=False)
    bias, applied = feedback_bias(db, spot.spot_id)
    final = adjusted_risk(raw, bias, fw["alpha"]) if applied else raw
    return {"risk": final, "raw_risk": raw, "adjusted": applied, "source": source}


def _chunked(items: list, size: int = 900):
    """SQLite IN 절 변수 한도(999) 보호용 청크."""
    for i in range(0, len(items), size):
        yield items[i:i + size]


def bulk_risks(
    db: Session,
    spots: list[models.TouristSpot],
    d: date,
    time_slot: str = "afternoon",
) -> dict[int, float]:
    """후보 풀 전체의 널널도를 벌크 쿼리로 산출 — 요청 경로의 N+1 제거.

    우선순위는 compute_risk와 동일: 배치 캐시(보정 포함) → 스냅샷 → 휴리스틱.
    (캐시 미보유 스팟의 피드백 보정은 생략 — 보정 대상 스팟은 일배치가 캐시를
    채우므로 실서비스 경로에서는 차이가 없다.)
    """
    ids = [s.spot_id for s in spots]
    out: dict[int, float] = {}
    for chunk in _chunked(ids):
        rows = db.execute(
            select(models.SpotScoreDaily.spot_id,
                   models.SpotScoreDaily.congestion_risk,
                   models.SpotScoreDaily.adjusted_risk)
            .where(models.SpotScoreDaily.spot_id.in_(chunk),
                   models.SpotScoreDaily.date == d,
                   models.SpotScoreDaily.time_slot == time_slot)
        ).all()
        for sid, raw, adjusted in rows:
            out[sid] = adjusted if adjusted is not None else raw

    remaining = [s for s in spots if s.spot_id not in out]
    if not remaining:
        return out

    snap_map: dict[int, float] = {}
    for chunk in _chunked([s.spot_id for s in remaining]):
        rows = db.execute(
            select(models.CongestionSnapshot.spot_id,
                   models.CongestionSnapshot.congestion_score)
            .where(models.CongestionSnapshot.spot_id.in_(chunk),
                   models.CongestionSnapshot.date == d,
                   models.CongestionSnapshot.time_slot == time_slot)
        ).all()
        snap_map.update(dict(rows))

    region_map = {
        r.area_code: r
        for r in db.scalars(
            select(models.RegionStatDaily).where(
                models.RegionStatDaily.area_code.in_(
                    {s.area_code for s in remaining}),
                models.RegionStatDaily.date == d,
            )
        )
    }
    weights = load_weights()["congestion_risk"]
    calendar = calendar_weather_component(d, KR_HOLIDAYS)   # 날씨 항은 배치 캐시 전용
    for s in remaining:
        region = region_map.get(s.area_code)
        out[s.spot_id] = congestion_risk(
            snap_map.get(s.spot_id, s.base_popularity),
            region.visitor_index if region else None,
            region.demand_intensity if region else None,
            calendar, weights,
        )
    return out


SLOT_NOTE_BY_RANK = ["가장 널널한 시간대", "무난한 시간대", "가장 붐비는 시간대"]


def get_congestion_view(
    db: Session, spot: models.TouristSpot, d: date, time_slot: str
) -> dict:
    """F3 응답 전체 — 현재 널널도 + ±5일 요일 비교 + 당일 시간대 비교."""
    settings = get_settings()
    today = date.today()
    window_to = today + timedelta(days=settings.forecast_window_days)

    main = compute_risk(db, spot, d, time_slot)

    weekday_comparison = []
    for offset in range(-5, 6):
        dt = d + timedelta(days=offset)
        if not (today <= dt <= window_to):
            continue
        r = main if offset == 0 else compute_risk(db, spot, dt, time_slot, use_realtime=False)
        weekday_comparison.append({
            "date": dt, "day": DAY_LABELS[dt.weekday()],
            "risk": r["risk"], "level": level_of(r["risk"]),
            "label": label_of(r["risk"]), "is_selected": offset == 0,
        })

    # 당일이면 시간대 비교에도 실시간(12시간 예측)을 사용 — F3 당일 시간대 분산
    slot_risks = {
        slot: main["risk"] if slot == time_slot else compute_risk(
            db, spot, d, slot, use_realtime=(d == today)
        )["risk"]
        for slot in TIME_SLOTS
    }
    rank = {s: i for i, s in enumerate(sorted(TIME_SLOTS, key=lambda s: slot_risks[s]))}
    time_slots = [
        {
            "slot": slot, "slot_label": SLOT_LABELS[slot],
            "risk": slot_risks[slot], "level": level_of(slot_risks[slot]),
            "label": label_of(slot_risks[slot]),
            "note": SLOT_NOTE_BY_RANK[rank[slot]],
        }
        for slot in TIME_SLOTS
    ]

    # 시간 분산 팁: 가장 널널한 시간대 + 가장 널널한 요일(가장 저항이 적은 분산부터 제안)
    best_slot = min(TIME_SLOTS, key=lambda s: slot_risks[s])
    tip = f"{SLOT_LABELS[best_slot]} 방문 시 체류 밀도가 가장 낮아요."
    others = [w for w in weekday_comparison if not w["is_selected"]]
    best_day = min(others, key=lambda w: w["risk"]) if others else None
    if best_day and best_day["risk"] < main["risk"] - 5:
        tip += (f" {best_day['date'].month}월 {best_day['date'].day}일"
                f"({best_day['day']})엔 '{best_day['label']}' 수준이에요.")

    # 행동형 시간 이동 제안(기획서 UX 원칙: 대안 제시 전에 시간 분산부터) —
    # FE가 칩으로 렌더링하고, 탭하면 해당 날짜·시간대로 전환 재조회한다.
    def _suggestion(kind: str, dt: date, slot: str, risk: float) -> dict:
        decrease = round((main["risk"] - risk) / main["risk"] * 100) if main["risk"] else 0
        when = (f"같은 날 {SLOT_LABELS[slot]}" if kind == "slot"
                else f"{dt.month}월 {dt.day}일({DAY_LABELS[dt.weekday()]}) {SLOT_LABELS[slot]}")
        return {
            "kind": kind, "date": dt, "time_slot": slot,
            "slot_label": SLOT_LABELS[slot],
            "risk": risk, "level": level_of(risk), "label": label_of(risk),
            "decrease_pct": max(decrease, 0),
            "text": f"{when}엔 '{label_of(risk)}'",
        }

    suggestions = []
    if best_slot != time_slot and slot_risks[best_slot] < main["risk"] - 5:
        suggestions.append(_suggestion("slot", d, best_slot, slot_risks[best_slot]))
    if best_day and best_day["risk"] < main["risk"] - 5:
        suggestions.append(
            _suggestion("date", best_day["date"], time_slot, best_day["risk"]))

    return {
        "spot_id": spot.spot_id, "name": spot.name, "date": d, "time_slot": time_slot,
        "risk": main["risk"], "raw_risk": main["raw_risk"], "adjusted": main["adjusted"],
        "level": level_of(main["risk"]), "label": label_of(main["risk"]),
        "color": LEVEL_COLORS[level_of(main["risk"]) - 1],
        "source": main["source"], "based_on": source_notice(main["source"]),
        "window_from": today, "window_to": window_to,
        "tip": tip, "weekday_comparison": weekday_comparison, "time_slots": time_slots,
        "time_shift_suggestions": suggestions,
    }


def get_calendar_view(db: Session, spot: models.TouristSpot,
                      time_slot: str = "afternoon") -> dict:
    """30일 널널 캘린더(시간 분산 히트맵) — 예측 창 전체의 일별 널널도.

    spot_score_daily 배치 캐시를 읽으므로 31회 조회여도 저비용이다.
    """
    settings = get_settings()
    today = date.today()
    days = []
    for offset in range(settings.forecast_window_days + 1):
        dt = today + timedelta(days=offset)
        risk = compute_risk(db, spot, dt, time_slot, use_realtime=False)["risk"]
        days.append({
            "date": dt, "day": DAY_LABELS[dt.weekday()],
            "risk": risk, "level": level_of(risk), "label": label_of(risk),
            "is_holiday": dt in KR_HOLIDAYS,
        })
    return {
        "spot_id": spot.spot_id, "name": spot.name, "time_slot": time_slot,
        "window_from": today,
        "window_to": today + timedelta(days=settings.forecast_window_days),
        "days": days,
    }
