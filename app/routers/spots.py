"""관광지 검색/상세/널널도/대안지 API — 기획서 12장."""
from datetime import date as date_type
import re

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import and_, func, or_, select
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.services import course_service
from app.services.congestion_service import (
    SLOT_LABELS,
    compute_risk,
    default_visit_date,
    get_congestion_view,
    source_notice,
    validate_visit_date,
)
from app.services.impact_service import HIDDEN_POPULARITY_MAX
from app.services.recommend_service import (
    get_alternatives,
    spot_theme_tags,
    theme_filter,
)
from app.scoring.congestion import label_of, level_of

router = APIRouter(prefix="/api/spots", tags=["spots"])

TIME_SLOT_PATTERN = "^(morning|afternoon|evening)$"
PAST_YEAR_PATTERN = re.compile(r"(?<!\d)(20\d{2})(?!\d)")


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
    category: str | None = Query(None, description="카테고리명 또는 코드(cat1~3)"),
    keyword: str | None = Query(None, description="이름/개요 키워드"),
    page: int = Query(1, ge=1),
    size: int = Query(20, ge=1, le=100),
    db: Session = Depends(get_db),
):
    query = select(models.TouristSpot)
    if region:
        query = query.where(models.TouristSpot.region.contains(region))
    if category:
        query = query.where(or_(
            models.TouristSpot.category_name.contains(category),
            models.TouristSpot.cat1 == category,
            models.TouristSpot.cat2 == category,
            models.TouristSpot.cat3 == category,
        ))
    if keyword:
        query = query.where(or_(
            models.TouristSpot.name.contains(keyword),
            models.TouristSpot.overview.contains(keyword),
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
    return {"items": items, "total": total, "page": page, "size": size}


@router.get("/home", response_model=schemas.HomeSpotsResponse)
def home_spots(
    region: str = Query("서울", description="홈에 노출할 지역명"),
    date: date_type | None = Query(None, description="방문 예정일(기본: 오늘)"),
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    themes: str | None = Query(None, description="테마 필터(쉼표 구분: 자연,역사,미식,포토스팟)"),
    limit: int = Query(6, ge=1, le=12),
    db: Session = Depends(get_db),
):
    """홈 화면용 TourAPI 관광지와 선택한 날짜·테마의 널널도를 한 번에 제공한다."""
    visit_date = date or date_type.today()
    validate_visit_date(visit_date)
    theme_list = [theme.strip() for theme in themes.split(",") if theme.strip()] if themes else []
    selected_theme_filter = theme_filter(theme_list)
    base_filter = models.TouristSpot.region.contains(region)
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

    has_image = models.TouristSpot.image_url.is_not(None)
    has_overview = models.TouristSpot.overview.is_not(None)
    candidates = db.scalars(
        query.order_by(
            has_image.desc(),
            has_overview.desc(),
            models.TouristSpot.base_popularity.desc(),
            models.TouristSpot.name.asc(),
        ).limit(limit * 5)
    ).all()
    candidates = [spot for spot in candidates if not is_past_event_listing(spot)]

    scored = []
    for spot in candidates:
        # 홈 카드 여러 장을 그릴 때 서울시 API를 연속 호출하지 않는다.
        # 실시간 조회는 선택한 장소의 상세 화면에서 수행하고, 홈은 배치 캐시로 즉시 응답한다.
        slot_risks = {
            slot: compute_risk(db, spot, visit_date, slot, use_realtime=False)
            for slot in ("morning", "afternoon", "evening")
        }
        current = slot_risks[time_slot]
        best_slot = min(slot_risks, key=lambda slot: slot_risks[slot]["risk"])
        tags = spot_theme_tags(spot)
        content_quality = sum((
            bool(spot.tags),
            bool(spot.overview_len),
            spot.category_name != "관광지",
        ))
        scored.append((spot, current, best_slot, content_quality, tags))

    # TourAPI 세부 정보가 있는 장소를 먼저 보여주고, 그 안에서 널널한 순으로 정리한다.
    scored.sort(
        key=lambda item: (
            -item[3],
            item[1]["risk"],
            -(item[0].overview_len or 0),
            -item[0].base_popularity,
            item[0].name,
        )
    )
    items = []
    for spot, current, best_slot, _, tags in scored[:limit]:
        items.append({
            **schemas.SpotSummary.model_validate(spot).model_dump(),
            "tags": tags,
            "risk": current["risk"],
            "level": level_of(current["risk"]),
            "label": label_of(current["risk"]),
            "source": current["source"],
            "based_on": source_notice(current["source"]),
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


@router.get("/{spot_id}/alternatives", response_model=schemas.AlternativesResponse)
def spot_alternatives(
    spot_id: int,
    date: date_type | None = Query(None),
    time_slot: str = Query("afternoon", pattern=TIME_SLOT_PATTERN),
    themes: str | None = Query(None, description="테마 필터(쉼표 구분: 역사,자연,미식,포토스팟)"),
    limit: int = Query(3, ge=1, le=5),
    db: Session = Depends(get_db),
):
    """대안지 추천(F4). 노출 로그를 기록해 추천 부하(F8)에 반영한다."""
    spot = get_spot_or_404(db, spot_id)
    visit_date = date or default_visit_date()
    validate_visit_date(visit_date)
    theme_list = [t.strip() for t in themes.split(",") if t.strip()] if themes else None
    return get_alternatives(db, spot, visit_date, time_slot, theme_list, limit)
