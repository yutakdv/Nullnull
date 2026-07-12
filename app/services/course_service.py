"""코스 생성/조회 서비스(F5·F6) — 동선과 누적 혼잡을 함께 고려한 통합 코스.

- 동선: 원 관광지에서 시작하는 nearest-neighbor 그리디 정렬(테마 유지형)
  / 슬롯 카테고리 시퀀스 순서 보존(자유여행형)
- 선택 로그(selected=True) 기록 → F8 추천 부하에 반영
- 추천 근거(recommendation_evidence)와 임팩트 카드 수치 저장(F6)
"""
from datetime import date, datetime, timedelta

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app import models
from app.external import kakao_api, kma_api, openai_api
from app.geo import estimate_move, haversine_km
from app.scoring.alternative import (
    alternative_score,
    companion_fit,
    hidden_gem_score,
    mobility_score,
    theme_similarity,
    weather_fit,
)
from app.scoring.congestion import LEVEL_LABELS, label_of, level_of
from app.scoring.course import course_score
from app.scoring.weights import load_weights
from app.services.congestion_service import bulk_risks, compute_risk, default_visit_date
from app.services.recommend_service import (
    FREE_TRAVEL_DEFAULT_SEQUENCE,
    build_reason,
    content_richness,
    load_map,
    seeded_jitter,
    slot_theme_fit,
    spot_categories,
    spot_theme_tags,
    spots_with_congestion_data,
    visitor_low_percentile,
)

MAX_LEG_KM = 8.0   # 코스 구간 거리 상한 — 이내 후보를 우선해 동선을 컴팩트하게 유지

STAY_MIN_BY_CATEGORY = {
    "궁궐": 70, "박물관": 80, "근대가옥": 45, "궁집": 50, "향교·서원": 40,
    "사찰": 55, "공원": 60, "성곽길": 60, "한옥마을": 60, "전망대": 50,
    "쇼핑거리": 60, "문화거리": 60, "전통시장": 55, "예술골목": 60, "한옥골목": 50,
}
META_VERB_BY_CATEGORY = {
    "궁궐": "관람", "박물관": "관람", "근대가옥": "관람", "궁집": "관람",
    "향교·서원": "관람", "전망대": "전망",
    "사찰": "산책", "공원": "산책", "성곽길": "산책", "한옥마을": "산책",
    "쇼핑거리": "미식", "문화거리": "미식", "전통시장": "미식",
    "예술골목": "골목 산책", "한옥골목": "골목 산책",
}
TITLE_BY_THEME = {
    "역사": "고궁과 골목을 잇는 느린 반나절",
    "자연": "숲과 성곽을 잇는 여유 한나절",
    "미식": "골목 미식 느린 산책",
    "포토스팟": "한적한 포토 스팟 반나절",
}
# F1 동행 유형(라이트) — 체류시간 배율 + 코스 설명 문구에 반영
COMPANION_STAY_FACTOR = {"solo": 0.85, "couple": 1.0, "family": 1.25}
COMPANION_LABELS = {"solo": "혼자", "couple": "둘이서", "family": "가족과"}


def _related_sim(db: Session, a_id: int, b_id: int) -> float | None:
    return db.scalar(
        select(models.RelatedSpot.similarity_score).where(
            models.RelatedSpot.spot_id == a_id,
            models.RelatedSpot.related_spot_id == b_id,
        )
    )


def _greedy_order(origin: models.TouristSpot,
                  spots: list[models.TouristSpot]) -> list[models.TouristSpot]:
    ordered, remaining = [], list(spots)
    cur_lat, cur_lng = origin.lat, origin.lng
    while remaining:
        nearest = min(remaining,
                      key=lambda s: haversine_km(cur_lat, cur_lng, s.lat, s.lng))
        ordered.append(nearest)
        remaining.remove(nearest)
        cur_lat, cur_lng = nearest.lat, nearest.lng
    return ordered


def _move_between(a_lat: float, a_lng: float, s: models.TouristSpot) -> tuple[int, str, float]:
    kakao = kakao_api.directions(a_lng, a_lat, s.lng, s.lat)
    if kakao:
        dist_km, move_min = kakao
        return move_min, ("차량" if dist_km > 1.2 else "도보"), dist_km
    dist_km = haversine_km(a_lat, a_lng, s.lat, s.lng)
    move_min, mode = estimate_move(dist_km)
    return move_min, mode, dist_km


def create_course(
    db: Session,
    origin_spot_id: int,
    spot_ids: list[int],
    d: date | None,
    time_slot: str = "afternoon",
    title: str | None = None,
    companion: str | None = None,
) -> models.Course:
    """테마 유지형 코스(F5) — 대안지들을 동선 그리디로 정렬해 저장."""
    origin = db.get(models.TouristSpot, origin_spot_id)
    spots = [db.get(models.TouristSpot, sid) for sid in spot_ids]
    visit_date = d or default_visit_date()

    ordered = _greedy_order(origin, spots)

    origin_tags = spot_theme_tags(origin)
    main_tags = [t for t in origin_tags if any(t in spot_theme_tags(s) for s in ordered)]
    theme_name = main_tags[0] if main_tags else (origin_tags or ["여유"])[0]
    default_title = TITLE_BY_THEME.get(theme_name, f"{theme_name} 테마 널널 코스")
    description = (
        f"{origin.name}의 매력은 그대로 두고, 같은 {theme_name} 테마의 "
        f"한적한 장소들로 혼잡만 덜어낸 코스예요."
    )
    return _build_course(
        db, origin, ordered, visit_date, time_slot,
        title=title or default_title, description=description,
        companion=companion,
    )


def _build_course(
    db: Session,
    origin: models.TouristSpot,
    ordered: list[models.TouristSpot],
    visit_date: date,
    time_slot: str,
    *,
    title: str,
    description: str | None,
    mode: str = "theme",
    slot_themes: list[str] | None = None,
    selected_spot_ids: set[int] | None = None,
    companion: str | None = None,
) -> models.Course:
    """정렬이 끝난 장소 목록으로 코스·근거·선택 로그를 저장 — 생성/자유여행/교체 공용 경로.

    테마 항: 테마 유지형은 원 관광지와의 유사도, 자유여행형은 슬롯 카테고리 적합도
    (theme_keep_pct도 같은 의미로 저장된다).
    선택 로그(F8): 기본은 전 장소 기록(신규 코스 생성), swap처럼 일부만 새로 선택된
    경로는 selected_spot_ids로 그 장소만 기록해 추천 부하가 부풀지 않게 한다.
    동행 유형(F1 라이트): 체류시간 배율과 설명 문구에만 반영(점수 산식 불변).
    """
    weights = load_weights()
    stay_factor = COMPANION_STAY_FACTOR.get(companion, 1.0)
    if companion and description:
        description += f" {COMPANION_LABELS[companion]} 여행 기준으로 체류 시간을 맞췄어요."

    origin_risk = compute_risk(db, origin, visit_date, time_slot)["risk"]
    richness = content_richness(db)
    low_pctl = visitor_low_percentile(db)

    # 구간 이동(원 관광지 → 첫 장소 → …), 마지막 장소는 '마무리'
    moves: list[tuple[int, str, float]] = []
    cur_lat, cur_lng = origin.lat, origin.lng
    for spot in ordered:
        moves.append(_move_between(cur_lat, cur_lng, spot))
        cur_lat, cur_lng = spot.lat, spot.lng

    theme_sims, risks, evidences = [], [], []
    for i, (spot, (move_min, _mode, _km)) in enumerate(zip(ordered, moves)):
        risk = compute_risk(db, spot, visit_date, time_slot)["risk"]
        risks.append(risk)
        if slot_themes:
            sim = slot_theme_fit(spot, slot_themes[i])
        else:
            sim = theme_similarity(
                spot_categories(origin), spot_categories(spot),
                _related_sim(db, origin.spot_id, spot.spot_id),
                spot_theme_tags(origin), spot_theme_tags(spot),
                weights["theme_similarity"],
            )
        theme_sims.append(sim)
        hidden = hidden_gem_score(low_pctl.get(spot.spot_id, 0.5),
                                  richness.get(spot.spot_id, 0.0))
        evidences.append((spot, sim, origin_risk - risk, move_min, hidden))

    avg_alt_risk = sum(risks) / len(risks)
    relief_pct = round((origin_risk - avg_alt_risk) / origin_risk * 100) if origin_risk else 0
    relief_pct = max(relief_pct, 0)
    theme_keep_pct = round(sum(theme_sims) / len(theme_sims) * 100)
    total_move_min = sum(m for m, _, _ in moves)
    total_distance_km = round(sum(km for _, _, km in moves), 1)

    course = models.Course(
        title=title, description=description,
        region=origin.region, base_spot_id=origin.spot_id, date=visit_date,
        time_slot=time_slot,
        level=level_of(avg_alt_risk), relief_pct=relief_pct,
        theme_keep_pct=theme_keep_pct, total_move_min=total_move_min,
        total_distance_km=total_distance_km, mode=mode, slot_themes=slot_themes,
        companion=companion,
        is_seed=False,
    )
    db.add(course)
    db.flush()

    for order_no, (spot, (move_min, mode_label, _km)) in enumerate(zip(ordered, moves), start=1):
        is_last = order_no == len(ordered)
        db.add(models.CourseItem(
            course_id=course.course_id, spot_id=spot.spot_id, order_no=order_no,
            stay_min=round(STAY_MIN_BY_CATEGORY.get(spot.category_name, 55)
                           * stay_factor / 5) * 5,
            move_min=0 if is_last else moves[order_no][0] if order_no < len(moves) else 0,
            move_mode="마무리" if is_last else moves[order_no][1],
            reason_text=spot.highlight,
        ))
    for spot, sim, relief_effect, move_min, hidden in evidences:
        db.add(models.RecommendationEvidence(
            course_id=course.course_id, spot_id=spot.spot_id,
            theme_sim=round(sim, 3), relief_effect=round(relief_effect, 1),
            travel_time=move_min, hidden_score=hidden,
        ))
        if selected_spot_ids is None or spot.spot_id in selected_spot_ids:
            db.add(models.RecommendationLog(
                spot_id=spot.spot_id, origin_spot_id=origin.spot_id,
                selected=True, is_seed=False,
            ))
    db.commit()
    db.refresh(course)
    return course


class NoSlotCandidateError(ValueError):
    """자유여행 슬롯에 추천할 후보가 없음(혼잡 데이터 보유 스팟 기준)."""


def recommend_course(
    db: Session,
    origin: models.TouristSpot,
    theme_sequence: list[str] | None,
    d: date | None,
    time_slot: str = "afternoon",
    title: str | None = None,
    mode: str = "free",
    companion: str | None = None,
    exclude_spot_ids: set[int] | None = None,
    variation_seed: str | None = None,
) -> models.Course:
    """자유여행 코스(카테고리 시퀀스) — 슬롯마다 해당 카테고리의 최적 장소를 고른다.

    슬롯 점수 = AlternativeScore(9-2) 변형: 테마 항을 '슬롯 카테고리 적합도'로 대체하고,
    이동 항은 직전 슬롯 위치 기준으로 계산해 시퀀스 순서(여행지→미식→…)를 보존한다.
    구간 거리 MAX_LEG_KM 이내 후보를 우선해 동선이 널뛰지 않게 한다.
    mode="theme"이면(테마 코스 reroll 경로) 시퀀스는 후보 선정에만 쓰고,
    저장은 테마 유지형(slot_themes 없음, 테마 항=원 관광지 유사도)으로 한다.
    exclude_spot_ids·variation_seed는 reroll용 — 직전 조합을 빼고, 시드가 바뀔
    때마다 근소차 후보가 교대해 누를 때마다 실제로 다른 조합이 나온다.
    """
    sequence = list(theme_sequence or FREE_TRAVEL_DEFAULT_SEQUENCE)
    visit_date = d or default_visit_date()
    weights = load_weights()["alternative_score"]
    excluded = exclude_spot_ids or set()

    origin_risk = compute_risk(db, origin, visit_date, time_slot)["risk"]
    eligible = spots_with_congestion_data(db)
    pool = [
        spot for spot in db.scalars(
            select(models.TouristSpot).where(models.TouristSpot.spot_id != origin.spot_id)
        ).all()
        if spot.spot_id in eligible and spot.spot_id not in excluded
    ]
    richness = content_richness(db)
    low_pctl = visitor_low_percentile(db)
    loads = load_map(db, [spot.spot_id for spot in pool])
    precip = kma_api.get_precip_prob(origin.lat, origin.lng, visit_date, time_slot)
    # 후보 위험도는 벌크 쿼리 1회로 산출(수천 후보 대응, F4와 동일 원칙)
    pool_risks = bulk_risks(db, pool, visit_date, time_slot)

    picked: list[models.TouristSpot] = []
    used = {origin.spot_id}
    cur_lat, cur_lng = origin.lat, origin.lng
    for theme in sequence:
        best, best_score = None, None            # 구간 상한(MAX_LEG_KM) 이내 최고점
        far_best, far_score = None, None         # 상한 밖 폴백(가까운 후보가 없을 때)
        for spot in pool:
            if spot.spot_id in used:
                continue
            fit = slot_theme_fit(spot, theme)
            if fit <= 0:
                continue
            risk = pool_risks[spot.spot_id]
            relief_norm = (
                max(min((origin_risk - risk) / origin_risk, 1.0), -1.0)
                if origin_risk > 0 else 0.0
            )
            dist_km = haversine_km(cur_lat, cur_lng, spot.lat, spot.lng)
            move_min, _ = estimate_move(dist_km)
            score = alternative_score(
                fit, relief_norm, mobility_score(move_min),
                hidden_gem_score(low_pctl.get(spot.spot_id, 0.5),
                                 richness.get(spot.spot_id, 0.0)),
                weather_fit(spot.is_indoor, precip),
                loads.get(spot.spot_id, 0.0), weights,
                companion=companion_fit(
                    companion, low_percentile=low_pctl.get(spot.spot_id, 0.5),
                    tags=spot_theme_tags(spot), is_indoor=spot.is_indoor,
                ),
            )
            if variation_seed:
                score += seeded_jitter(spot.spot_id, variation_seed, scale=0.05)
            if dist_km <= MAX_LEG_KM:
                if best_score is None or score > best_score:
                    best, best_score = spot, score
            elif far_score is None or score > far_score:
                far_best, far_score = spot, score
        best = best or far_best
        if best is None:
            raise NoSlotCandidateError(
                f"'{theme}' 카테고리에서 추천할 만한 장소를 찾지 못했어요."
            )
        picked.append(best)
        used.add(best.spot_id)
        cur_lat, cur_lng = best.lat, best.lng

    if mode == "free":
        default_title = f"{'·'.join(sequence)} 자유여행 코스"
        description = (
            f"{origin.name}에서 시작해 {' → '.join(sequence)} 순서로 "
            f"카테고리를 넘나들며 붐빔을 피하는 자유여행 코스예요."
        )
    else:
        theme_name = sequence[0]
        default_title = TITLE_BY_THEME.get(theme_name, f"{theme_name} 테마 널널 코스")
        description = (
            f"{origin.name}의 매력은 그대로 두고, 같은 {theme_name} 테마의 "
            f"한적한 장소들로 혼잡만 덜어낸 코스예요."
        )
    return _build_course(
        db, origin, picked, visit_date, time_slot,
        title=title or default_title, description=description,
        mode=mode, slot_themes=sequence if mode == "free" else None,
        companion=companion,
    )


# ── AI 코스(LLM) — 알고리즘 후보 큐레이션 → OpenAI 코스 구성 → 검증 → 실체화 ──
# 기준점(origin)은 후보 중 가장 붐비는 대표 명소로 두어, 널널한 타임라인과의
# 대비로 '혼잡 회피 %'가 양수로 산출되게 한다(서비스 컨셉: 붐빔 대신 널널 코스).
SLOT_THEME_OPTIONS = ("여행지", "자연", "역사", "미식", "포토스팟")


def _dominant_theme(spot: models.TouristSpot) -> str:
    best, best_fit = "여행지", 0.0
    for theme in SLOT_THEME_OPTIONS:
        fit = slot_theme_fit(spot, theme)
        if fit > best_fit:
            best, best_fit = theme, fit
    return best


def _curate_candidates(db, *, district, themes, visit_date, time_slot, limit=20):
    """구·테마·혼잡 실측 기준으로 후보 풀을 추린다 → (pool[가장 널널한 순], risks)."""
    q = select(models.TouristSpot).where(models.TouristSpot.region.contains("서울"))
    if district:
        q = q.where(models.TouristSpot.addr.contains(district))
    eligible = spots_with_congestion_data(db)
    pool = [s for s in db.scalars(q).all() if s.spot_id in eligible]
    slot_themes = [t for t in (themes or []) if t in SLOT_THEME_OPTIONS]
    if slot_themes:
        themed = [s for s in pool if any(slot_theme_fit(s, t) > 0 for t in slot_themes)]
        pool = themed or pool                          # 테마 후보 없으면 완화
    risks = bulk_risks(db, pool, visit_date, time_slot)
    pool.sort(key=lambda s: risks.get(s.spot_id, 1.0))
    return pool[:limit], risks


def _pick_reference(pool, risks):
    """혼잡 회피 기준점 — 후보 중 가장 붐비는(위험 높은) 대표 명소."""
    return max(pool, key=lambda s: (risks.get(s.spot_id, 0.0), s.base_popularity))


def _course_title_desc(llm_course, district, index):
    if llm_course:
        title = (llm_course.get("title") or "").strip() or f"{district or '서울'} 널널 코스"
        concept = (llm_course.get("concept") or "").strip()
        reason = (llm_course.get("reason") or "").strip()
        desc = " ".join(p for p in (concept, reason) if p) or None
        return title, desc
    return (f"{district or '서울'} 널널 코스 {index + 1}",
            "붐빔을 피해 여유롭게 이어지는 동선이에요.")


def _materialize_course(db, *, reference, ordered, visit_date, time_slot, companion,
                        llm_course, district, index):
    title, desc = _course_title_desc(llm_course, district, index)
    return _build_course(
        db, reference, ordered, visit_date, time_slot,
        title=title, description=desc, mode="free",
        slot_themes=[_dominant_theme(s) for s in ordered], companion=companion,
    )


def _build_llm_payload(pool, risks, reference, *, district, stops, companion,
                       visit_date, themes, pace, indoor_pref, count):
    def brief(s):
        return {"spot_id": s.spot_id, "name": s.name, "category": s.category_name,
                "lat": round(s.lat, 5), "lng": round(s.lng, 5),
                "risk": round(risks.get(s.spot_id, 0.0), 1),
                "is_indoor": bool(s.is_indoor), "tags": spot_theme_tags(s)}
    candidates = [brief(s) for s in pool if s.spot_id != reference.spot_id]
    return {
        "conditions": {
            "district": district or "서울 전체", "date": visit_date.isoformat(),
            "companion": companion, "interests": themes, "pace": pace,
            "indoor_preference": indoor_pref, "stops_per_course": stops,
            "course_count": count,
        },
        "avoid_reference": {"spot_id": reference.spot_id, "name": reference.name,
                            "risk": round(risks.get(reference.spot_id, 0.0), 1)},
        "candidates": candidates,
    }


def _materialize_llm_courses(db, raw, pool, reference, *, visit_date, time_slot,
                             companion, stops, district):
    """LLM 응답의 spot_id를 후보 풀로 검증(환각 제거)·중복 제거·부족분 보충 후 실체화."""
    by_id = {s.spot_id: s for s in pool if s.spot_id != reference.spot_id}
    supplement = [s for s in pool if s.spot_id != reference.spot_id]
    courses = []
    for i, lc in enumerate((raw or {}).get("courses", [])):
        seen, ordered = set(), []
        for st in lc.get("stops", []):
            sid = st.get("spot_id") if isinstance(st, dict) else st
            if sid in by_id and sid not in seen:
                ordered.append(by_id[sid])
                seen.add(sid)
        for s in supplement:                            # stops 부족분은 널널 상위로 보충
            if len(ordered) >= stops:
                break
            if s.spot_id not in seen:
                ordered.append(s)
                seen.add(s.spot_id)
        ordered = _greedy_order(reference, ordered[:stops])
        if len(ordered) < 2:
            continue
        courses.append(_materialize_course(
            db, reference=reference, ordered=ordered, visit_date=visit_date,
            time_slot=time_slot, companion=companion, llm_course=lc,
            district=district, index=i))
    return courses


def _algorithmic_multi_courses(db, pool, reference, *, stops, companion,
                               visit_date, time_slot, district, count):
    """폴백 — 후보 풀에서 시작점을 회전시켜 서로 다른 널널 코스 count개를 만든다."""
    picks = [s for s in pool if s.spot_id != reference.spot_id]
    courses = []
    for i in range(count):
        if len(picks) < 2:
            break
        rotated = picks[i % len(picks):] + picks[:i % len(picks)]
        ordered = _greedy_order(reference, rotated[:stops])
        if len(ordered) < 2:
            break
        courses.append(_materialize_course(
            db, reference=reference, ordered=ordered, visit_date=visit_date,
            time_slot=time_slot, companion=companion, llm_course=None,
            district=district, index=i))
    return courses


def ai_recommend_courses(db, *, district, stops, companion, visit_date, time_slot,
                         themes, pace, indoor_pref, count=3):
    """AI 코스 추천 — 후보 큐레이션 → LLM 구성(가능 시) → 검증/실체화, 실패 시 알고리즘 폴백.

    반환: (코스 리스트, source) — source ∈ {"llm", "algorithm"}.
    """
    pool, risks = _curate_candidates(
        db, district=district, themes=themes, visit_date=visit_date, time_slot=time_slot)
    if len(pool) < 2:
        raise NoSlotCandidateError("이 조건에서 추천할 장소를 찾지 못했어요.")
    reference = _pick_reference(pool, risks)
    payload = _build_llm_payload(
        pool, risks, reference, district=district, stops=stops, companion=companion,
        visit_date=visit_date, themes=themes, pace=pace, indoor_pref=indoor_pref, count=count)
    raw = openai_api.complete_courses(payload)
    courses = _materialize_llm_courses(
        db, raw, pool, reference, visit_date=visit_date, time_slot=time_slot,
        companion=companion, stops=stops, district=district) if raw else []
    if courses:
        return courses, "llm"
    fallback = _algorithmic_multi_courses(
        db, pool, reference, stops=stops, companion=companion, visit_date=visit_date,
        time_slot=time_slot, district=district, count=count)
    return fallback, "algorithm"


def _course_visit_date(course: models.Course) -> date:
    """지난 날짜 코스는 기본 방문일(다가오는 토요일)로 대안·교체 위험도를 계산한다."""
    if course.date and course.date >= date.today():
        return course.date
    return default_visit_date()


def course_alternatives(
    db: Session, course: models.Course, time_slot: str | None = None,
    limit: int = 2, log_exposure: bool = True,
) -> dict:
    """코스 슬롯별 교체 후보(F4 응용) — 코스 구성 장소·기준 장소는 제외.

    이동 항은 코스 내 직전 지점 기준이라 동선을 해치지 않는 대안이 우선된다.
    자유여행 코스는 슬롯 카테고리 적합도를, 테마 코스는 현 슬롯 장소와의 유사도를 쓴다.
    시간대는 기본적으로 코스 생성 시 기준(time_slot)을 따른다.
    """
    time_slot = time_slot or course.time_slot or "afternoon"
    items = db.scalars(
        select(models.CourseItem).where(models.CourseItem.course_id == course.course_id)
        .order_by(models.CourseItem.order_no)
    ).all()
    spots = [db.get(models.TouristSpot, item.spot_id) for item in items]
    base = db.get(models.TouristSpot, course.base_spot_id) if course.base_spot_id else None
    visit_date = _course_visit_date(course)

    weights = load_weights()
    eligible = spots_with_congestion_data(db)
    exclude = {item.spot_id for item in items}
    if course.base_spot_id:
        exclude.add(course.base_spot_id)
    pool = [
        spot for spot in db.scalars(
            select(models.TouristSpot).where(~models.TouristSpot.spot_id.in_(exclude))
        ).all()
        if spot.spot_id in eligible
    ]
    richness = content_richness(db)
    low_pctl = visitor_low_percentile(db)
    loads = load_map(db, [spot.spot_id for spot in pool])
    anchor = base or spots[0]
    precip = kma_api.get_precip_prob(anchor.lat, anchor.lng, visit_date, time_slot)
    pool_risks = bulk_risks(db, pool, visit_date, time_slot)

    slot_themes = course.slot_themes or [None] * len(items)
    result_items = []
    prev_lat, prev_lng = (anchor.lat, anchor.lng)
    for item, spot, slot_theme in zip(items, spots, slot_themes):
        item_risk = compute_risk(db, spot, visit_date, time_slot, use_realtime=False)["risk"]
        scored = []
        for cand in pool:
            if slot_theme:
                theme_comp = slot_theme_fit(cand, slot_theme)
                if theme_comp <= 0:
                    continue
            else:
                theme_comp = theme_similarity(
                    spot_categories(spot), spot_categories(cand),
                    _related_sim(db, spot.spot_id, cand.spot_id),
                    spot_theme_tags(spot), spot_theme_tags(cand),
                    weights["theme_similarity"],
                )
            risk = pool_risks[cand.spot_id]
            relief_norm = (
                max(min((item_risk - risk) / item_risk, 1.0), -1.0)
                if item_risk > 0 else 0.0
            )
            move_min, move_mode = estimate_move(
                haversine_km(prev_lat, prev_lng, cand.lat, cand.lng)
            )
            hidden = hidden_gem_score(low_pctl.get(cand.spot_id, 0.5),
                                      richness.get(cand.spot_id, 0.0))
            score = alternative_score(
                theme_comp, relief_norm, mobility_score(move_min), hidden,
                weather_fit(cand.is_indoor, precip),
                loads.get(cand.spot_id, 0.0), weights["alternative_score"],
                companion=companion_fit(
                    course.companion, low_percentile=low_pctl.get(cand.spot_id, 0.5),
                    tags=spot_theme_tags(cand), is_indoor=cand.is_indoor,
                ),
            )
            decrease_pct = (
                round((item_risk - risk) / item_risk * 100) if item_risk > 0 else 0
            )
            scored.append({
                "spot": cand, "risk": risk, "score": score,
                "decrease_pct": max(decrease_pct, 0),
                "move_min": move_min, "move_mode": move_mode,
                "similarity_pct": round(theme_comp * 100), "hidden": hidden,
            })

        scored.sort(key=lambda x: x["score"], reverse=True)
        # '더 한적한 곳' 원칙(F4): 현 슬롯보다 낮고 '보통' 이하인 후보를 우선
        primary = [s for s in scored
                   if s["risk"] < item_risk and level_of(s["risk"]) <= 3]
        rest = [s for s in scored if s not in primary]
        top = (primary + rest)[:limit]

        if log_exposure:
            for it in top:
                db.add(models.RecommendationLog(
                    spot_id=it["spot"].spot_id, origin_spot_id=spot.spot_id,
                    selected=False, is_seed=False,
                ))

        result_items.append({
            "order_no": item.order_no, "spot_id": spot.spot_id, "name": spot.name,
            "level": level_of(item_risk), "slot_theme": slot_theme,
            "alternatives": [
                {
                    "spot_id": it["spot"].spot_id, "name": it["spot"].name,
                    "image_url": it["spot"].image_url,
                    "risk": it["risk"], "level": level_of(it["risk"]),
                    "label": label_of(it["risk"]),
                    "decrease_pct": it["decrease_pct"],
                    "travel_time_min": it["move_min"], "travel_mode": it["move_mode"],
                    "similarity_pct": it["similarity_pct"],
                    "hidden_gem": it["hidden"] >= 0.35,
                    "reason": build_reason(spot, it["spot"], it["decrease_pct"],
                                           it["move_min"], it["similarity_pct"]),
                    "score": it["score"],
                }
                for it in top
            ],
        })
        prev_lat, prev_lng = spot.lat, spot.lng

    if log_exposure:
        db.commit()
    return {"course_id": course.course_id, "date": course.date, "items": result_items}


def swap_course_item(
    db: Session, course: models.Course, order_no: int, new_spot_id: int,
) -> models.Course:
    """슬롯 하나를 교체한 새 코스 생성 — 원본 보존, 저장된 순서 유지(재정렬 없음)."""
    items = db.scalars(
        select(models.CourseItem).where(models.CourseItem.course_id == course.course_id)
        .order_by(models.CourseItem.order_no)
    ).all()
    if order_no < 1 or order_no > len(items):
        raise LookupError("해당 순서의 장소가 코스에 없어요.")
    new_spot = db.get(models.TouristSpot, new_spot_id)
    if not new_spot:
        raise LookupError("교체할 관광지를 찾을 수 없어요.")
    if new_spot_id in {item.spot_id for item in items}:
        raise ValueError("이미 코스에 포함된 장소예요.")

    ordered = [db.get(models.TouristSpot, item.spot_id) for item in items]
    ordered[order_no - 1] = new_spot
    origin = (db.get(models.TouristSpot, course.base_spot_id)
              if course.base_spot_id else ordered[0])

    return _build_course(
        db, origin, ordered, _course_visit_date(course),
        course.time_slot or "afternoon",
        title=course.title, description=course.description,
        mode=course.mode or "theme", slot_themes=course.slot_themes,
        selected_spot_ids={new_spot_id}, companion=course.companion,
    )


def reroll_course(db: Session, course: models.Course) -> models.Course:
    """같은 출발지·날짜에서 다른 조합의 새 코스를 만든다.

    기존 코스의 선택 로그가 추천 부하에 반영되어 이미 선택된 장소는 자연스럽게 감점된다.
    원본 코스는 보존하고 새 Course 레코드를 반환한다.
    """
    origin = db.get(models.TouristSpot, course.base_spot_id) if course.base_spot_id else None
    if not origin:
        raise LookupError("코스의 출발 관광지를 찾을 수 없어요.")

    items = db.scalars(
        select(models.CourseItem).where(models.CourseItem.course_id == course.course_id)
        .order_by(models.CourseItem.order_no)
    ).all()
    if not items:
        raise LookupError("다시 추천할 코스 장소가 없어요.")

    if course.slot_themes:
        sequence = list(course.slot_themes)
    else:
        theme = next(
            (tag for tag in spot_theme_tags(origin) if tag in TITLE_BY_THEME),
            "여행지",
        )
        sequence = [theme] * len(items)

    # 직전 조합은 후보에서 제외하고, 코스 ID를 변주 시드로 써서 누를 때마다
    # 실제로 다른 조합이 나온다. 제목은 원제 유지(접미사 누적 방지 — 과거
    # ' 다른 조합' 접미사가 붙은 코스도 원제로 되돌린다).
    base_title = course.title
    while base_title.endswith(" 다른 조합"):
        base_title = base_title[: -len(" 다른 조합")]

    return recommend_course(
        db, origin, sequence, _course_visit_date(course),
        course.time_slot or "afternoon",
        title=base_title, mode=course.mode or "theme",
        companion=course.companion,
        exclude_spot_ids={item.spot_id for item in items},
        variation_seed=str(course.course_id),
    )


PERCEIVED_LABELS = {-1: "생각보다 한산했어요", 0: "예상과 비슷했어요", 1: "생각보다 붐볐어요"}


def visited_spots(db: Session, limit: int = 6) -> dict:
    """홈 '최근 방문한 장소' — 실사용(비시드) 피드백·후기를 장소별로 합산."""
    records: dict[int, dict] = {}

    def record_of(spot_id: int) -> dict:
        return records.setdefault(spot_id, {
            "count": 0, "last_at": None,
            "last_rating": None, "last_rating_at": None,
            "last_perceived": None, "last_perceived_at": None,
        })

    feedback_rows = db.execute(
        select(models.VisitFeedback.spot_id, models.VisitFeedback.created_at,
               models.VisitFeedback.perceived)
        .where(models.VisitFeedback.is_seed.is_(False))
    ).all()
    for spot_id, at, perceived in feedback_rows:
        rec = record_of(spot_id)
        rec["count"] += 1
        rec["last_at"] = max(rec["last_at"], at) if rec["last_at"] else at
        if rec["last_perceived_at"] is None or at >= rec["last_perceived_at"]:
            rec["last_perceived"], rec["last_perceived_at"] = perceived, at

    review_rows = db.execute(
        select(models.VisitReview.spot_id, models.VisitReview.created_at,
               models.VisitReview.rating)
        .where(models.VisitReview.is_seed.is_(False),
               models.VisitReview.spot_id.is_not(None))
    ).all()
    for spot_id, at, rating in review_rows:
        rec = record_of(spot_id)
        rec["count"] += 1
        rec["last_at"] = max(rec["last_at"], at) if rec["last_at"] else at
        if rec["last_rating_at"] is None or at >= rec["last_rating_at"]:
            rec["last_rating"], rec["last_rating_at"] = rating, at

    ordered = sorted(records.items(), key=lambda kv: kv[1]["last_at"], reverse=True)
    today = date.today()
    items = []
    for spot_id, rec in ordered[:limit]:
        spot = db.get(models.TouristSpot, spot_id)
        if not spot:
            continue
        risk = compute_risk(db, spot, today, "afternoon", use_realtime=False)["risk"]
        items.append({
            "spot_id": spot.spot_id, "content_id": spot.content_id, "name": spot.name,
            "region": spot.region, "category_name": spot.category_name,
            "tags": spot.tags or [], "addr": spot.addr,
            "lat": spot.lat, "lng": spot.lng, "image_url": spot.image_url,
            "risk": risk, "level": level_of(risk), "label": label_of(risk),
            "visited_text": _date_text(rec["last_at"]), "visit_count": rec["count"],
            "last_rating": rec["last_rating"],
            "last_perceived_label": (
                PERCEIVED_LABELS.get(rec["last_perceived"])
                if rec["last_perceived"] is not None else None
            ),
        })
    return {"items": items, "total": len(records)}


def _date_text(dt: datetime) -> str:
    now = datetime.now()
    days = (now.date() - dt.date()).days
    if days <= 0:
        return "오늘 오전" if dt.hour < 12 else "오늘 오후"
    if days == 1:
        return "어제"
    return f"{days}일 전"


def review_stats(db: Session, spot_id: int | None = None,
                 course_id: int | None = None) -> dict:
    query = select(models.VisitReview)
    if spot_id is not None:
        query = query.where(models.VisitReview.spot_id == spot_id)
    if course_id is not None:
        query = query.where(models.VisitReview.course_id == course_id)
    reviews = db.scalars(query).all()
    if not reviews:
        return {"avg_rating": 0.0, "count": 0, "tag_counts": {}}
    tag_counts: dict[str, int] = {}
    for r in reviews:
        for tag in r.tags or []:
            tag_counts[tag] = tag_counts.get(tag, 0) + 1
    return {
        "avg_rating": round(sum(r.rating for r in reviews) / len(reviews), 1),
        "count": len(reviews),
        "tag_counts": dict(sorted(tag_counts.items(), key=lambda x: -x[1])),
    }


def recent_reviews(db: Session, spot_id: int | None = None,
                   course_id: int | None = None, limit: int = 5) -> list[dict]:
    query = select(models.VisitReview).order_by(models.VisitReview.created_at.desc())
    if spot_id is not None:
        query = query.where(models.VisitReview.spot_id == spot_id)
    if course_id is not None:
        query = query.where(models.VisitReview.course_id == course_id)
    return [
        {
            "nickname": r.nickname, "rating": r.rating, "tags": r.tags or [],
            "text": r.text, "date_text": _date_text(r.created_at),
            "created_at": r.created_at,
        }
        for r in db.scalars(query.limit(limit)).all()
    ]


def course_detail(db: Session, course: models.Course) -> dict:
    items = db.scalars(
        select(models.CourseItem).where(models.CourseItem.course_id == course.course_id)
        .order_by(models.CourseItem.order_no)
    ).all()
    evidences = db.scalars(
        select(models.RecommendationEvidence)
        .where(models.RecommendationEvidence.course_id == course.course_id)
    ).all()

    slot_themes = course.slot_themes or []
    course_slot = course.time_slot or "afternoon"
    base_spot = (db.get(models.TouristSpot, course.base_spot_id)
                 if course.base_spot_id else None)
    timeline, map_points = [], []
    if base_spot:
        map_points.append({"order_no": 0, "name": base_spot.name,
                           "lat": base_spot.lat, "lng": base_spot.lng})
    for item in items:
        spot = db.get(models.TouristSpot, item.spot_id)
        verb = META_VERB_BY_CATEGORY.get(spot.category_name, "관람")
        risk = (compute_risk(db, spot, course.date, course_slot)["risk"]
                if course.date else 30.0)
        timeline.append({
            "order_no": item.order_no, "spot_id": spot.spot_id, "place": spot.name,
            "meta": f"{verb} {item.stay_min}분",
            "move": "마무리" if item.move_mode == "마무리" else f"{item.move_mode} {item.move_min}분",
            "note": item.reason_text, "image_url": spot.image_url,
            "level": level_of(risk),
            "slot_theme": (slot_themes[item.order_no - 1]
                           if item.order_no <= len(slot_themes) else None),
        })
        map_points.append({"order_no": item.order_no, "name": spot.name,
                           "lat": spot.lat, "lng": spot.lng})

    # 코스 점수(9-3): 근거 수치로 대안 점수를 재구성해 산출
    weights = load_weights()
    alt_scores = [
        alternative_score(
            ev.theme_sim, max(min(ev.relief_effect / 100, 1.0), -1.0),
            mobility_score(ev.travel_time), ev.hidden_score, None, 0.0,
            weights["alternative_score"],
        )
        for ev in evidences
    ] or [0.5]
    spots = [db.get(models.TouristSpot, i.spot_id) for i in items]
    category_repeats = len(spots) - len({s.category_name for s in spots})
    distinct_zones = len({s.sigungu_code for s in spots})
    score = course_score(alt_scores, course.total_move_min, category_repeats,
                         distinct_zones, weights["course_score"])

    return {
        "course_id": course.course_id, "title": course.title,
        "description": course.description, "region": course.region,
        "date": course.date, "time_slot": course_slot, "level": course.level,
        "label": LEVEL_LABELS[course.level - 1],
        "mode": course.mode or "theme", "slot_themes": course.slot_themes,
        "companion": course.companion,
        "companion_label": COMPANION_LABELS.get(course.companion),
        "is_shared": bool(course.is_shared),
        "course_score": score,
        "timeline": timeline,
        "map_points": map_points,
        "summary": {
            "relief_pct": round(course.relief_pct),
            "theme_keep_pct": round(course.theme_keep_pct),
            "total_move_min": course.total_move_min,
            "total_distance_km": round(course.total_distance_km or 0, 1),
        },
        "impact_text": f"이 선택으로 예상 혼잡 {round(course.relief_pct)}%를 회피했어요",
        "evidence": [
            {
                "spot_id": ev.spot_id,
                "name": db.get(models.TouristSpot, ev.spot_id).name,
                "theme_sim": ev.theme_sim, "relief_effect": ev.relief_effect,
                "travel_time": ev.travel_time, "hidden_score": ev.hidden_score,
            }
            for ev in evidences
        ],
        "reviews": {
            "stats": review_stats(db, course_id=course.course_id),
            "recent": recent_reviews(db, course_id=course.course_id),
        },
    }


def popular_courses(db: Session, limit: int = 3) -> list[dict]:
    """홈 캐러셀 — 사용자가 공유한 코스 우선, 부족하면 시드 코스로 채운다(콜드스타트 안전).

    공유(is_shared)하지 않은 개인 코스는 노출하지 않는다 — 공유 버튼이 게시 행위다.
    """
    courses = db.scalars(
        select(models.Course)
        .where(or_(models.Course.is_shared.is_(True), models.Course.is_seed.is_(True)))
        .order_by(models.Course.is_seed.asc(), models.Course.relief_pct.desc(),
                  models.Course.created_at.desc())
        .limit(limit)
    ).all()
    result = []
    for course in courses:
        items = db.scalars(
            select(models.CourseItem)
            .where(models.CourseItem.course_id == course.course_id)
            .order_by(models.CourseItem.order_no)
        ).all()
        first_spot = db.get(models.TouristSpot, items[0].spot_id) if items else None
        base_spot = db.get(models.TouristSpot, course.base_spot_id) if course.base_spot_id else None
        total_min = sum(i.stay_min + i.move_min for i in items) or course.total_move_min
        hours, mins = divmod(total_min, 60)
        duration_text = f"{hours}시간 {mins}분" if hours else f"{mins}분"
        tag_source = (base_spot.tags if base_spot and base_spot.tags else
                      (first_spot.tags if first_spot and first_spot.tags else ["여유"]))
        tag = ("자유여행 코스" if (course.mode or "theme") == "free"
               else f"{tag_source[0]} 코스")
        result.append({
            "course_id": course.course_id, "title": course.title,
            "location": f"{course.region} · {base_spot.name} 일대" if base_spot else course.region,
            "image_url": first_spot.image_url if first_spot else None,
            "rate_pct": round(course.relief_pct), "duration_text": duration_text,
            "tag": tag, "level": course.level,
        })
    return result
