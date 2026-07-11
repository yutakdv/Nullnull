"""대안지 추천 서비스(F4·F6·F8) — 공간 분산 + 추천 분산.

후보군 = 연관 관광지(티맵 기반 API 캐시) ∪ 동일 중분류 ∪ 태그 교집합 스팟.
AlternativeScore(9-2)로 정렬하고, 노출된 대안은 recommendation_log에 기록해
다음 추천의 부하 페널티(F8 로테이션)에 반영한다.
"""
from datetime import date, datetime, timedelta

from sqlalchemy import String, case, cast, func, or_, select
from sqlalchemy.orm import Session

from app import models
from app.external import kakao_api, kma_api
from app.geo import estimate_move, haversine_km
from app.scoring.alternative import (
    alternative_score,
    hidden_gem_score,
    mobility_score,
    recommendation_load,
    theme_similarity,
    weather_fit,
)
from app.scoring.congestion import label_of, level_of
from app.scoring.weights import load_weights
from app.services.congestion_service import compute_risk

HIDDEN_GEM_THRESHOLD = 0.35
DYNAMIC_CANDIDATE_RADIUS_KM = 20.0

# 홈 테마 필터·자유여행 슬롯이 함께 쓰는 TourAPI cat1 매핑
THEME_CAT1_CODES = {
    "자연": ("A01", "A03"),
    "역사": ("A02",),
    "미식": ("A04", "A05"),
    "포토스팟": ("A01", "A02", "A03", "A04", "A05"),
}
# '여행지' 슬롯: 일반 관광 명소(자연·역사 명소 계열) — 미식 상권과 구분
ATTRACTION_CAT1 = ("A01", "A02", "A03")
ATTRACTION_TAGS = {"자연", "역사"}
FREE_TRAVEL_DEFAULT_SEQUENCE = ["여행지", "미식", "포토스팟"]


def spot_theme_tags(spot: models.TouristSpot) -> list[str]:
    """시드 태그와 TourAPI cat1을 함께 사용해 홈의 테마 필터를 일관되게 만든다."""
    tags = list(spot.tags or [])
    for theme, cat1_codes in THEME_CAT1_CODES.items():
        if spot.cat1 in cat1_codes and theme not in tags:
            tags.append(theme)
    return tags


def theme_filter(themes: list[str]):
    """TourAPI 코드와 기존 큐레이션 태그를 모두 고려한 SQL 테마 조건."""
    filters = []
    for theme in themes:
        cat1_codes = THEME_CAT1_CODES.get(theme, ())
        if cat1_codes:
            filters.append(models.TouristSpot.cat1.in_(cat1_codes))
        filters.append(cast(models.TouristSpot.tags, String).contains(f'"{theme}"'))
    return or_(*filters) if filters else None


def slot_theme_fit(spot: models.TouristSpot, theme: str) -> float:
    """자유여행 슬롯 카테고리 적합도 — 태그 일치 1.0, cat1 유래 0.75, 불일치 0."""
    tags = set(spot_theme_tags(spot))
    if theme == "여행지":
        if tags & ATTRACTION_TAGS:
            return 1.0
        return 0.75 if spot.cat1 in ATTRACTION_CAT1 else 0.0
    if theme in tags:
        return 1.0
    return 0.75 if spot.cat1 in THEME_CAT1_CODES.get(theme, ()) else 0.0


def spot_categories(spot: models.TouristSpot) -> set[str]:
    return {c for c in (spot.cat1, spot.cat2, spot.cat3) if c}


def spots_with_congestion_data(db: Session) -> set[int]:
    """TourAPI 전체 후보의 혼잡 근거 범위.

    예측 스냅샷과 일배치 점수 캐시는 우선 사용한다. 아직 캐시가 없는 TourAPI 장소도
    좌표와 기본 방문 규모가 있으면 지역 수요·달력 기반 추정 점수를 계산할 수 있으므로
    후보군에서 제외하지 않는다.
    """
    return set(db.scalars(
        select(models.TouristSpot.spot_id).where(
            models.TouristSpot.lat.is_not(None),
            models.TouristSpot.lng.is_not(None),
        )
    ).all())


def candidate_map(db: Session, origin: models.TouristSpot) -> dict[int, float | None]:
    """{spot_id: 연관도(없으면 None)} — TourAPI 전체에서 동적으로 만든 대안 후보군."""
    candidates: dict[int, float | None] = {}
    related = db.execute(
        select(models.RelatedSpot.related_spot_id, models.RelatedSpot.similarity_score)
        .where(models.RelatedSpot.spot_id == origin.spot_id)
    ).all()
    for spot_id, sim in related:
        candidates[spot_id] = sim

    others = db.scalars(
        select(models.TouristSpot).where(
            models.TouristSpot.spot_id != origin.spot_id,
            models.TouristSpot.region == origin.region,
        )
    ).all()
    origin_tags = set(spot_theme_tags(origin))
    for spot in others:
        if spot.spot_id in candidates:
            continue
        same_cat2 = origin.cat2 and spot.cat2 == origin.cat2
        same_cat1 = origin.cat1 and spot.cat1 == origin.cat1
        shares_tag = bool(origin_tags & set(spot_theme_tags(spot)))
        nearby = haversine_km(origin.lat, origin.lng, spot.lat, spot.lng) <= DYNAMIC_CANDIDATE_RADIUS_KM
        if same_cat2 or same_cat1 or shares_tag or nearby:
            candidates[spot.spot_id] = None

    eligible = spots_with_congestion_data(db)
    return {sid: sim for sid, sim in candidates.items() if sid in eligible}


def content_richness(db: Session) -> dict[int, float]:
    """콘텐츠 풍부도(0~1): 이미지 수 + overview 길이 + 연관 목록 등장 횟수의 정규화 합(9-2)."""
    spots = db.scalars(select(models.TouristSpot)).all()
    appearances = dict(
        db.execute(
            select(models.RelatedSpot.related_spot_id, func.count())
            .group_by(models.RelatedSpot.related_spot_id)
        ).all()
    )
    raw = {
        s.spot_id: s.image_count + s.overview_len / 100 + appearances.get(s.spot_id, 0)
        for s in spots
    }
    max_raw = max(raw.values()) if raw else 1.0
    return {k: round(v / max_raw, 4) if max_raw else 0.0 for k, v in raw.items()}


def visitor_low_percentile(db: Session) -> dict[int, float]:
    """방문자수 하위 분위(0~1) — 방문 규모가 작을수록 1에 가깝다."""
    rows = db.execute(
        select(models.TouristSpot.spot_id, models.TouristSpot.base_popularity)
    ).all()
    ordered = sorted(rows, key=lambda r: r[1])   # 방문 규모 오름차순
    n = len(ordered)
    if n <= 1:
        return {r[0]: 1.0 for r in rows}
    return {spot_id: round(1 - i / (n - 1), 4) for i, (spot_id, _) in enumerate(ordered)}


def load_map(db: Session, candidate_ids: list[int]) -> dict[int, float]:
    """추천 부하(F8): 최근 7일 노출+선택×2 → 후보군 내 최대값으로 0~1 정규화."""
    lw = load_weights()["recommendation_load"]
    since = datetime.now() - timedelta(days=lw["window_days"])
    rows = db.execute(
        select(
            models.RecommendationLog.spot_id,
            func.count(),
            func.sum(case((models.RecommendationLog.selected.is_(True), 1), else_=0)),
        )
        .where(
            models.RecommendationLog.spot_id.in_(candidate_ids),
            models.RecommendationLog.exposed_at >= since,
        )
        .group_by(models.RecommendationLog.spot_id)
    ).all()
    counts = {sid: (int(total), int(sel or 0)) for sid, total, sel in rows}
    raws = {
        sid: exposures + lw["select_weight"] * selections
        for sid, (exposures, selections) in counts.items()
    }
    max_raw = max(raws.values()) if raws else 0
    return {
        sid: recommendation_load(*counts.get(sid, (0, 0)), max_raw=max_raw,
                                 select_weight=lw["select_weight"])
        for sid in candidate_ids
    }


def build_reason(origin: models.TouristSpot, spot: models.TouristSpot,
                 decrease_pct: int, move_min: int, similarity_pct: int) -> str:
    origin_tags = spot_theme_tags(origin)
    spot_tags = spot_theme_tags(spot)
    shared = set(origin_tags) & set(spot_tags)
    theme = next(iter(shared), (spot_tags or ["여유"])[0])
    parts = []
    if spot.highlight:
        parts.append(spot.highlight)
    parts.append(
        f"{origin.name} 대비 예상 혼잡 {max(decrease_pct, 0)}% 감소, "
        f"이동 {move_min}분, {theme} 테마 유지(유사도 {similarity_pct}%)예요."
    )
    return " ".join(parts)


def get_alternatives(
    db: Session,
    origin: models.TouristSpot,
    d: date,
    time_slot: str = "afternoon",
    themes: list[str] | None = None,
    limit: int = 3,
    log_exposure: bool = True,
) -> dict:
    weights = load_weights()["alternative_score"]
    combine = load_weights()["theme_similarity"]

    origin_risk = compute_risk(db, origin, d, time_slot)
    candidates = candidate_map(db, origin)
    if themes:
        candidates = {
            sid: sim for sid, sim in candidates.items()
            if set(themes) & set(spot_theme_tags(db.get(models.TouristSpot, sid)))
        }

    richness = content_richness(db)
    low_pctl = visitor_low_percentile(db)
    loads = load_map(db, list(candidates))
    precip = kma_api.get_precip_prob(origin.lat, origin.lng, d, time_slot)

    scored = []
    for spot_id, related_sim in candidates.items():
        spot = db.get(models.TouristSpot, spot_id)
        # 후보 수백 곳을 도는 루프 — 서울 실시간 HTTP는 원 관광지에서만 쓰고
        # 후보는 배치 캐시(예측 기준)로 일관되게 비교한다(응답 지연 방지)
        alt_risk = compute_risk(db, spot, d, time_slot, use_realtime=False)["risk"]
        # 혼잡 완화 효과: 원 관광지 대비 감소 비율(0~1) — 표시되는 감소율과 동일 기준
        relief_norm = (
            max(min((origin_risk["risk"] - alt_risk) / origin_risk["risk"], 1.0), -1.0)
            if origin_risk["risk"] > 0 else 0.0
        )

        kakao = kakao_api.directions(origin.lng, origin.lat, spot.lng, spot.lat)
        if kakao:
            dist_km, move_min = kakao
            mode = "차량" if dist_km > 1.2 else "도보"
        else:
            dist_km = haversine_km(origin.lat, origin.lng, spot.lat, spot.lng)
            move_min, mode = estimate_move(dist_km)

        theme = theme_similarity(
            spot_categories(origin), spot_categories(spot), related_sim,
            spot_theme_tags(origin), spot_theme_tags(spot), combine,
        )
        hidden = hidden_gem_score(low_pctl.get(spot_id, 0.5), richness.get(spot_id, 0.0))
        weather = weather_fit(spot.is_indoor, precip)
        load = loads.get(spot_id, 0.0)
        mobility = mobility_score(move_min)
        score = alternative_score(theme, relief_norm, mobility, hidden, weather,
                                  load, weights)

        decrease_pct = (
            round((origin_risk["risk"] - alt_risk) / origin_risk["risk"] * 100)
            if origin_risk["risk"] > 0 else 0
        )
        similarity_pct = round(theme * 100)
        scored.append({
            "spot": spot, "risk": alt_risk, "score": score,
            "decrease_pct": max(decrease_pct, 0),
            "travel_time_min": move_min, "travel_mode": mode, "distance_km": dist_km,
            "similarity_pct": similarity_pct,
            "hidden_gem": hidden >= HIDDEN_GEM_THRESHOLD,
            "breakdown": {
                "theme_similarity": theme, "relief": round(relief_norm, 4),
                "mobility": mobility, "hidden": hidden, "weather": weather,
                "load_penalty": round(weights["load_penalty"] * load, 4),
            },
        })

    scored.sort(key=lambda x: x["score"], reverse=True)
    # "더 한적한 곳" 원칙(F4): 원 관광지보다 낮고 '보통' 이하인 후보를 우선하고,
    # 부족할 때만 점수순으로 채운다(대안이 또 다른 과밀지가 되지 않도록)
    primary = [s for s in scored
               if s["risk"] < origin_risk["risk"] and level_of(s["risk"]) <= 3]
    rest = [s for s in scored if s not in primary]
    top = (primary + rest)[:limit]

    if log_exposure:
        for item in top:
            db.add(models.RecommendationLog(
                spot_id=item["spot"].spot_id, origin_spot_id=origin.spot_id,
                selected=False, is_seed=False,
            ))
        db.commit()

    alternatives = [
        {
            "spot_id": it["spot"].spot_id, "name": it["spot"].name,
            "image_url": it["spot"].image_url,
            "risk": it["risk"], "level": level_of(it["risk"]), "label": label_of(it["risk"]),
            "decrease_pct": it["decrease_pct"],
            "travel_time_min": it["travel_time_min"], "travel_mode": it["travel_mode"],
            "distance_km": it["distance_km"], "similarity_pct": it["similarity_pct"],
            "hidden_gem": it["hidden_gem"],
            "reason": build_reason(origin, it["spot"], it["decrease_pct"],
                                   it["travel_time_min"], it["similarity_pct"]),
            "score": it["score"], "breakdown": it["breakdown"],
        }
        for it in top
    ]

    # 경로 요약: 원 관광지에서 가까운 순서로 잇는 그리디 체인
    chain = [(origin.lat, origin.lng)]
    remaining = [(it["spot"].lat, it["spot"].lng) for it in top]
    total_km, walk_km = 0.0, 0.0
    while remaining:
        last = chain[-1]
        nearest = min(remaining, key=lambda p: haversine_km(last[0], last[1], p[0], p[1]))
        seg = haversine_km(last[0], last[1], nearest[0], nearest[1])
        total_km += seg
        if seg <= 1.2:
            walk_km += seg
        chain.append(nearest)
        remaining.remove(nearest)
    drive_min = sum(
        estimate_move(haversine_km(a[0], a[1], b[0], b[1]))[0]
        for a, b in zip(chain, chain[1:])
    )

    return {
        "origin": {
            "spot_id": origin.spot_id, "name": origin.name, "image_url": origin.image_url,
            "date": d, "time_slot": time_slot,
            "risk": origin_risk["risk"], "level": level_of(origin_risk["risk"]),
            "label": label_of(origin_risk["risk"]),
        },
        "alternatives": alternatives,
        "route_summary": {
            "total_distance_km": round(total_km, 1),
            "total_drive_min": drive_min,
            "total_walk_km": round(walk_km + 0.4 * len(top), 1),  # 장소 내 도보 산책 포함
        },
    }
