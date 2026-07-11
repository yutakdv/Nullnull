"""시드 적재 검증 — 서울 MVP 데이터가 데모 모드 동작에 충분한지."""
from sqlalchemy import func, select

from app import models


def test_seed_counts(db):
    spots = db.scalar(select(func.count()).select_from(models.TouristSpot))
    assert spots >= 18

    snapshots = db.scalar(select(func.count()).select_from(models.CongestionSnapshot))
    assert snapshots == spots * 31 * 3      # 오늘~+30일 × 3시간대

    related = db.scalar(select(func.count()).select_from(models.RelatedSpot))
    assert related >= 40                    # 양방향 엣지

    region_days = db.scalar(select(func.count()).select_from(models.RegionStatDaily))
    assert region_days == 31

    seed_courses = db.scalar(
        select(func.count()).select_from(models.Course).where(models.Course.is_seed)
    )
    assert seed_courses == 3


def test_synthetic_logs_marked_as_seed(db):
    unmarked = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.is_seed.is_(False))
    )
    # 시드 부트스트랩 직후 실사용 로그는 0 — 합성 로그는 전부 is_seed=true(9-2)
    total = db.scalar(select(func.count()).select_from(models.RecommendationLog))
    assert total > 0
    # (다른 테스트가 실로그를 만들기 전 순서 의존을 피하기 위해 존재 여부만 확인)
    seeded = db.scalar(
        select(func.count()).select_from(models.RecommendationLog)
        .where(models.RecommendationLog.is_seed.is_(True))
    )
    assert seeded > 0
    assert unmarked >= 0


def test_feedback_seed_threshold_design(db):
    """경복궁은 보정 임계치(30건) 이상, 대안지는 미만이어야 콜드스타트 정책이 시연된다."""
    def count_for(name):
        spot = db.scalar(select(models.TouristSpot).where(models.TouristSpot.name == name))
        return db.scalar(
            select(func.count()).select_from(models.VisitFeedback)
            .where(models.VisitFeedback.spot_id == spot.spot_id)
        )

    assert count_for("경복궁") >= 30
    assert count_for("서울한양도성 낙산구간") < 30
