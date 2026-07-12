"""관광지 검색/상세/널널도/대안지 API — 기획서 12장."""
from datetime import date as date_type
import re

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import String, and_, cast, func, or_, select
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.services import course_service
from app.services.congestion_service import (
    SLOT_LABELS,
    bulk_risks,
    default_visit_date,
    get_calendar_view,
    get_congestion_view,
    source_notice,
    validate_visit_date,
)
from app.services.impact_service import HIDDEN_POPULARITY_MAX
from app.services.recommend_service import (
    daily_rotation_jitter,
    get_alternatives,
    spot_theme_tags,
    theme_filter,
)
from app.scoring.congestion import label_of, level_of

router = APIRouter(prefix="/api/spots", tags=["spots"])

TIME_SLOT_PATTERN = "^(morning|afternoon|evening)$"
PAST_YEAR_PATTERN = re.compile(r"(?<!\d)(20\d{2})(?!\d)")

# 검색 카탈로그 카테고리 → TourAPI 대분류(cat1) 그룹. '볼거리'가 검색 탭 기본군.
CATEGORY_CAT1_GROUPS: dict[str, tuple[str, ...]] = {
    "볼거리": ("A01", "A02", "A03"),
    "자연·공원": ("A01",),
    "문화·역사": ("A02",),
    "쇼핑": ("A04",),
    "미식": ("A05",),
}


def get_spot_or_404(db: Session, spot_id: int) -> models.TouristSpot:
    spot = db.get(models.TouristSpot, spot_id)
    if not spot:
        raise HTTPException(status_code=404, detail="관광지를 찾을 수 없어요.")
    return spot


def is_past_event_listing(spot: models.TouristSpot) -> bool:
    """TourAPI 목록에 남은 종료 행사(예: '2025 ... 페어')는 홈 추천에서 제외한다."""
    return any(int(year) < date_type.today().year for year in PAST_YEAR_PATTERN.findall(spot.name))


@router.get("", response_model=schemas.SpotListResponse)
def list_spots(
    region: str | None = Query(None, description="지역명(예: 서울)"),
    district: str | None = Query(None, description="세부 지역(구) 필터 — 주소 기준(예: 종로구)"),
    category: str | None = Query(None, description="카테고리 그룹(볼거리/문화·역사/자연·공원/미식/쇼핑) 또는 코드"),
    keyword: str | None = Query(None, description="이름/개요 키워드"),
    page: int = Query(1, ge=1),
    size: int = Query(20, ge=1, le=100),
    db: Session = Depends(get_db),
):
    query = select(models.TouristSpot)
    if region:
        query = query.where(models.TouristSpot.region.contains(region))
    if district:
        query = query.where(models.TouristSpot.addr.contains(district))
    if category:
        group = CATEGORY_CAT1_GROUPS.get(category)
        if group:
            query = query.where(models.TouristSpot.cat1.in_(group))
        else:
            query = query.where(or_(
                models.TouristSpot.category_name.contains(category),
                models.TouristSpot.cat1 == category,
                models.TouristSpot.cat2 == category,
                models.TouristSpot.cat3 == category,
            ))
    if keyword:
        # 유사 장소도 걸리도록 이름뿐 아니라 카테고리·주소·테마 태그·개요까지 매칭하고,
        # 띄어쓰기 차이(예: "서울 숲" ↔ "서울숲")도 이름 기준으로 흡수한다.
        kw = keyword.strip()
        compact = kw.replace(" ", "")
        name_compact = func.replace(models.TouristSpot.name, " ", "")
        query = query.where(or_(
            models.TouristSpot.name.contains(kw),
            name_compact.like(f"%{compact}%"),
            models.TouristSpot.category_name.contains(kw),
            models.TouristSpot.addr.contains(kw),
            cast(models.TouristSpot.tags, String).contains(kw),
            models.TouristSpot.overview.contains(kw),
        ))
    total = db.scalar(select(func.count()).select_from(query.subquery()))
    is_seed = models.TouristSpot.content_id.like("seed-%")
    items = db.scalars(
        query.order_by(
            is_seed.asc(),
            models.TouristSpot.base_popularity.desc(),
            models.TouristSpot.spot_id.desc(),
        )
        .offset((page - 1) * size).limit(size)
    ).all()

    # 반환 페이지(size≤100)에 한해 스냅샷 보유 스팟의 혼잡 level만 덧붙인다
    # (2,000행 전수 혼잡 계산을 피하고, 근거 있는 곳만 배지 표기).
    snap_ids = set(db.scalars(select(models.CongestionSnapshot.spot_id).distinct()).all())
    page_snap = [s for s in items if s.spot_id in snap_ids]
    risks = bulk_risks(db, page_snap, default_visit_date(), "afternoon") if page_snap else {}
    payload = []
    for spot in items:
        row = schemas.SpotSummary.model_validate(spot).model_dump()
        row["level"] = level_of(risks[spot.spot_id]) if spot.spot_id in risks else None
        payload.append(row)
    return {"items": payload, "total": total, "page": page, "size": size}


@router.get("/home", response_model=schemas.HomeSpotsResponse)
def home_spots(
    region: str = Query("서울", description="홈에 노출할 지역명"),
    district: str | None = Query(None, description="세부 지역(구) 필터(예: 종로구) — 주소 기준"),
    date: date_type | None = Query(None, description="방문 예정일(기본: 오늘)"),
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    themes: str | None = Query(None, description="테마 필터(쉼표 구분: 자연,역사,미식,포토스팟)"),
    limit: int = Query(6, ge=1, le=50),
    db: Session = Depends(get_db),
):
    """홈·지역 화면용 TourAPI 관광지와 선택한 날짜·테마의 널널도를 한 번에 제공한다."""
    visit_date = date or date_type.today()
    validate_visit_date(visit_date)
    theme_list = [theme.strip() for theme in themes.split(",") if theme.strip()] if themes else []
    selected_theme_filter = theme_filter(theme_list)
    base_filter = models.TouristSpot.region.contains(region)
    if district:
        # 지역 탭의 '구' 선택 — region은 '서울'로 고정이라 주소(addr) 기준으로 좁힌다
        base_filter = and_(base_filter, models.TouristSpot.addr.contains(district))
    tourapi_filter = and_(
        models.TouristSpot.content_id.is_not(None),
        ~models.TouristSpot.content_id.like("seed-%"),
    )

    # TourAPI 장소를 우선하되, 해당 테마가 아직 수집되지 않은 경우에만 시드 큐레이션을 보완한다.
    primary_filters = [base_filter, tourapi_filter]
    if selected_theme_filter is not None:
        primary_filters.append(selected_theme_filter)
    total = db.scalar(select(func.count()).select_from(models.TouristSpot).where(*primary_filters))
    active_filters = primary_filters
    if not total:
        active_filters = [base_filter]
        if selected_theme_filter is not None:
            active_filters.append(selected_theme_filter)
        total = db.scalar(select(func.count()).select_from(models.TouristSpot).where(*active_filters))

    query = select(models.TouristSpot).where(*active_filters)

    # KT 혼잡 실측(스냅샷) 보유 스팟을 우선한다 — 근거 있는 추천이 먼저,
    # 그다음 이미지·개요 등 콘텐츠 품질 순(가나다순 잡동사니 상위 노출 방지)
    snap_ids = set(db.scalars(select(models.CongestionSnapshot.spot_id).distinct()).all())
    has_snapshot = models.TouristSpot.spot_id.in_(
        select(models.CongestionSnapshot.spot_id).distinct())
    has_image = models.TouristSpot.image_url.is_not(None)
    has_overview = models.TouristSpot.overview_len > 0
    candidates = db.scalars(
        query.order_by(
            has_snapshot.desc(),
            has_image.desc(),
            has_overview.desc(),
            models.TouristSpot.base_popularity.desc(),
            models.TouristSpot.name.asc(),
        ).limit(limit * 8)
    ).all()
    candidates = [spot for spot in candidates if not is_past_event_listing(spot)]

    # 홈 카드 위험도는 배치 캐시 벌크 조회로 즉시 응답(실시간은 상세 화면에서)
    risk_by_slot = {
        slot: bulk_risks(db, candidates, visit_date, slot)
        for slot in ("morning", "afternoon", "evening")
    }
    scored = []
    for spot in candidates:
        current = risk_by_slot[time_slot][spot.spot_id]
        best_slot = min(risk_by_slot, key=lambda slot: risk_by_slot[slot][spot.spot_id])
        tags = spot_theme_tags(spot)
        content_quality = sum((
            spot.spot_id in snap_ids,          # 혼잡 실측 근거 보유
            bool(spot.tags),
            bool(spot.overview_len),
            spot.category_name != "관광지",
        ))
        scored.append((spot, current, best_slot, content_quality, tags))

    # 품질 우선 → 널널한 순. 근소차에는 일자 지터를 더해 노출을 로테이션한다
    # (같은 날은 결정적, 날이 바뀌면 비슷한 스팟끼리 교대 — 추천 분산 원칙의 홈 버전)
    scored.sort(
        key=lambda item: (
            -item[3],
            item[1] + daily_rotation_jitter(item[0].spot_id, visit_date, scale=8.0),
            item[0].name,
        )
    )
    items = []
    for spot, current, best_slot, _, tags in scored[:limit]:
        source = "snapshot" if spot.spot_id in snap_ids else "heuristic"
        items.append({
            **schemas.SpotSummary.model_validate(spot).model_dump(),
            "tags": tags,
            "risk": current,
            "level": level_of(current),
            "label": label_of(current),
            "source": source,
            "based_on": source_notice(source),
            "best_time_slot": best_slot,
            "best_time_slot_label": SLOT_LABELS[best_slot],
        })
    return {"items": items, "total": total}


@router.get("/visited", response_model=schemas.VisitedSpotsResponse)
def visited_spots(
    limit: int = Query(6, ge=1, le=12),
    db: Session = Depends(get_db),
):
    """홈 '최근 방문한 장소' — 실사용 피드백(F7)·후기 기록 기반(시드 제외)."""
    return course_service.visited_spots(db, limit)


@router.get("/{spot_id}", response_model=schemas.SpotDetail)
def spot_detail(spot_id: int, db: Session = Depends(get_db)):
    spot = get_spot_or_404(db, spot_id)
    stats = course_service.review_stats(db, spot_id=spot_id)

    feedbacks = db.scalars(
        select(models.VisitFeedback.perceived)
        .where(models.VisitFeedback.spot_id == spot_id)
    ).all()
    not_worse = sum(1 for p in feedbacks if p <= 0)
    reviews_total = stats["count"]
    comfort_tagged = stats["tag_counts"].get("동선이 편해요", 0)

    return {
        **schemas.SpotSummary.model_validate(spot).model_dump(),
        "overview": spot.overview, "highlight": spot.highlight,
        "image_count": spot.image_count, "is_indoor": spot.is_indoor,
        "hidden_gem": spot.base_popularity <= HIDDEN_POPULARITY_MAX,
        "review_stats": stats,
        "proof": {
            "prediction_accuracy_pct":
                round(not_worse / len(feedbacks) * 100) if feedbacks else 0,
            "route_comfort_pct":
                round(comfort_tagged / reviews_total * 100) if reviews_total else 0,
            "theme_satisfaction_pct":
                round(stats["avg_rating"] / 5 * 100) if reviews_total else 0,
        },
    }


@router.get("/{spot_id}/congestion", response_model=schemas.CongestionResponse)
def spot_congestion(
    spot_id: int,
    date: date_type | None = Query(None, description="방문 예정일(기본: 다가오는 토요일)"),
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    db: Session = Depends(get_db),
):
    """널널도 조회(F3). date가 오늘~+30일 밖이면 400 (기획서 8-1·12장)."""
    spot = get_spot_or_404(db, spot_id)
    visit_date = date or default_visit_date()
    validate_visit_date(visit_date)
    return get_congestion_view(db, spot, visit_date, time_slot)


@router.get("/{spot_id}/calendar", response_model=schemas.SpotCalendarResponse)
def spot_calendar(
    spot_id: int,
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    db: Session = Depends(get_db),
):
    """30일 널널 캘린더(F3 시간 분산 히트맵) — 예측 창 전체의 일별 널널도."""
    spot = get_spot_or_404(db, spot_id)
    return get_calendar_view(db, spot, time_slot)


@router.get("/{spot_id}/alternatives", response_model=schemas.AlternativesResponse)
def spot_alternatives(
    spot_id: int,
    date: date_type | None = Query(None),
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    themes: str | None = Query(None, description="테마 필터(쉼표 구분: 역사,자연,미식,포토스팟)"),
    limit: int = Query(3, ge=1, le=5),
    log_exposure: bool = Query(
        True, description="노출 로그 기록 여부 — FE 프리페치는 false로 호출(F8 부하 왜곡 방지)"
    ),
    companion: str | None = Query(
        None, pattern="^(solo|couple|family)$",
        description="동행 유형(F1) — 지정 시 추천 순서를 그 동행에 맞게 소프트 우선정렬",
    ),
    db: Session = Depends(get_db),
):
    """대안지 추천(F4). 사용자에게 실제 노출될 때 로그를 기록해 추천 부하(F8)에 반영한다."""
    spot = get_spot_or_404(db, spot_id)
    visit_date = date or default_visit_date()
    validate_visit_date(visit_date)
    theme_list = [t.strip() for t in themes.split(",") if t.strip()] if themes else None
    return get_alternatives(db, spot, visit_date, time_slot, theme_list, limit,
                            log_exposure=log_exposure, companion=companion)
