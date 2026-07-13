"""분산 임팩트 집계(기획서 5장·16-1) — "이번 주 널널이 만든 분산" 카운터.

집계 시 is_seed=true(합성 시드)는 제외한다(데이터 정직성 원칙).
실사용 데이터가 아직 없으면 시드 포함값으로 폴백하되 includes_seed로 명시 고지한다.
"""
from datetime import date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app import models

HIDDEN_POPULARITY_MAX = 40.0    # 이 이하 방문 규모면 '숨은 명소'로 집계


def _aggregate(db: Session, since: datetime, include_seed: bool) -> dict:
    course_filter = [models.Course.created_at >= since]
    log_filter = [
        models.RecommendationLog.exposed_at >= since,
        models.RecommendationLog.selected.is_(True),
    ]
    if not include_seed:
        course_filter.append(models.Course.is_seed.is_(False))
        log_filter.append(models.RecommendationLog.is_seed.is_(False))

    courses_created, avg_relief = db.execute(
        select(func.count(), func.avg(models.Course.relief_pct)).where(*course_filter)
    ).one()
    hidden_picks = db.scalar(
        select(func.count())
        .select_from(models.RecommendationLog)
        .join(models.TouristSpot,
              models.TouristSpot.spot_id == models.RecommendationLog.spot_id)
        .where(*log_filter,
               models.TouristSpot.base_popularity <= HIDDEN_POPULARITY_MAX)
    )

    # 분산 리프트 — 노출 대비 선택 전환율 + 선택된 대안의 예상 혼잡 감소율(실현치) 평균
    exposure_filter = [models.RecommendationLog.exposed_at >= since]
    if not include_seed:
        exposure_filter.append(models.RecommendationLog.is_seed.is_(False))
    exposed = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(*exposure_filter)
    )
    selected_count, avg_decrease = db.execute(
        select(func.count(), func.avg(models.RecommendationLog.decrease_pct))
        .where(*exposure_filter, models.RecommendationLog.selected.is_(True))
    ).one()
    return {
        "courses_created": int(courses_created or 0),
        "avoid_rate_avg_pct": round(avg_relief or 0),
        "hidden_pick_count": int(hidden_picks or 0),
        "dispersion_lift": {
            "exposed": int(exposed or 0),
            "selected": int(selected_count or 0),
            "conversion_pct": round(selected_count / exposed * 100) if exposed else 0,
            "avg_realized_decrease_pct": round(avg_decrease or 0),
        },
    }


def weekly_summary(db: Session) -> dict:
    today = date.today()
    since = datetime.now() - timedelta(days=7)
    result = _aggregate(db, since, include_seed=False)
    includes_seed = False
    if result["courses_created"] == 0 and result["hidden_pick_count"] == 0:
        # 콜드스타트: 시드 포함값으로 폴백하고 플래그로 고지(9-2 시연 프레이밍 이중화)
        result = _aggregate(db, since, include_seed=True)
        includes_seed = True
    return {
        "week_start": today - timedelta(days=7), "week_end": today,
        **result, "includes_seed": includes_seed,
    }
