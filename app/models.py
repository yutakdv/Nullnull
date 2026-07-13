"""ORM 모델 — 기획서 11장 ERD + region_stat_daily(산식 데이터 소스) + visit_review(FE 후기 UI)."""
from datetime import date, datetime

from sqlalchemy import (
    JSON,
    Boolean,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class TouristSpot(Base):
    """TourAPI 동기화 대상 관광지 마스터(콘텐츠 풍부도 필드 포함)."""

    __tablename__ = "tourist_spot"

    spot_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    content_id: Mapped[str | None] = mapped_column(String(20), unique=True)  # TourAPI contentid
    name: Mapped[str] = mapped_column(String(100), index=True)
    region: Mapped[str] = mapped_column(String(50), default="서울")           # 표시용 지역명
    area_code: Mapped[int] = mapped_column(Integer, default=1)                # TourAPI areaCode(서울=1)
    sigungu_code: Mapped[int | None] = mapped_column(Integer)
    cat1: Mapped[str | None] = mapped_column(String(10))                      # 대분류(A01 자연, A02 인문 …)
    cat2: Mapped[str | None] = mapped_column(String(10))
    cat3: Mapped[str | None] = mapped_column(String(12))
    category_name: Mapped[str] = mapped_column(String(50), default="관광지")
    tags: Mapped[list] = mapped_column(JSON, default=list)                    # 테마 태그(자연/역사/미식/포토스팟)
    addr: Mapped[str | None] = mapped_column(String(200))
    lat: Mapped[float] = mapped_column(Float)
    lng: Mapped[float] = mapped_column(Float)
    image_url: Mapped[str | None] = mapped_column(String(300))
    image_count: Mapped[int] = mapped_column(Integer, default=0)              # 콘텐츠 풍부도 항
    overview: Mapped[str | None] = mapped_column(Text)
    overview_len: Mapped[int] = mapped_column(Integer, default=0)             # 콘텐츠 풍부도 항
    highlight: Mapped[str | None] = mapped_column(String(200))                # 추천 이유 카피 재료
    is_indoor: Mapped[bool] = mapped_column(Boolean, default=False)           # 날씨/운영 적합성 항
    base_popularity: Mapped[float] = mapped_column(Float, default=50.0)       # 방문자 규모 프록시(0~100)

    snapshots: Mapped[list["CongestionSnapshot"]] = relationship(back_populates="spot")


class CongestionSnapshot(Base):
    """배치 수집 결과 — 데모 모드·장기 추정 폴백의 데이터 소스."""

    __tablename__ = "congestion_snapshot"
    __table_args__ = (UniqueConstraint("spot_id", "date", "time_slot", name="uq_snapshot"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    date: Mapped[date] = mapped_column(Date, index=True)
    time_slot: Mapped[str] = mapped_column(String(10), default="afternoon")   # morning|afternoon|evening
    congestion_score: Mapped[float] = mapped_column(Float)                    # 집중률 예측값(0~100)
    visitor_count: Mapped[int | None] = mapped_column(Integer)
    source: Mapped[str] = mapped_column(String(20), default="seed")           # tats|seoul|seed
    collected_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)

    spot: Mapped[TouristSpot] = relationship(back_populates="snapshots")


class RelatedSpot(Base):
    """관광지별 연관 관광지 정보(티맵 데이터 기반 API) 캐시."""

    __tablename__ = "related_spot"
    __table_args__ = (UniqueConstraint("spot_id", "related_spot_id", name="uq_related"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    related_spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"))
    similarity_score: Mapped[float] = mapped_column(Float, default=0.5)       # 0~1 정규화


class SpotExternalRef(Base):
    """외부 API 식별자 ↔ 스팟 매핑(집중률 tAtsNm / 서울 area명 / 연관 이름)."""

    __tablename__ = "spot_external_ref"
    __table_args__ = (UniqueConstraint("source", "ext_key", name="uq_ext_ref"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    source: Mapped[str] = mapped_column(String(20), index=True)     # seoul|tats|related
    ext_key: Mapped[str] = mapped_column(String(120), index=True)   # 정규화 키
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    confidence: Mapped[float] = mapped_column(Float, default=1.0)
    method: Mapped[str] = mapped_column(String(10), default="seed")  # seed|name|coord


class Course(Base):
    __tablename__ = "course"

    course_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(100))
    description: Mapped[str | None] = mapped_column(Text)
    region: Mapped[str] = mapped_column(String(50), default="서울")
    base_spot_id: Mapped[int | None] = mapped_column(ForeignKey("tourist_spot.spot_id"))
    date: Mapped[date | None] = mapped_column(Date)
    time_slot: Mapped[str] = mapped_column(String(10), default="afternoon")   # 생성 기준 시간대
    level: Mapped[int] = mapped_column(Integer, default=2)                    # 널널도 1~5
    relief_pct: Mapped[float] = mapped_column(Float, default=0.0)             # 예상 혼잡 감소율(%)
    theme_keep_pct: Mapped[float] = mapped_column(Float, default=0.0)         # 테마 유지율(%)
    total_move_min: Mapped[int] = mapped_column(Integer, default=0)
    total_distance_km: Mapped[float] = mapped_column(Float, default=0.0)
    mode: Mapped[str] = mapped_column(String(10), default="theme")             # theme|free(자유여행)
    slot_themes: Mapped[list | None] = mapped_column(JSON, default=None)       # 자유여행 슬롯 카테고리 순서
    companion: Mapped[str | None] = mapped_column(String(10))                  # solo|couple|family(F1)
    is_seed: Mapped[bool] = mapped_column(Boolean, default=False)
    is_shared: Mapped[bool] = mapped_column(Boolean, default=False)            # 사용자가 공개한 코스(홈 인기 노출)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)

    items: Mapped[list["CourseItem"]] = relationship(
        back_populates="course", order_by="CourseItem.order_no"
    )
    evidences: Mapped[list["RecommendationEvidence"]] = relationship(back_populates="course")


class CourseItem(Base):
    __tablename__ = "course_item"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("course.course_id"), index=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"))
    order_no: Mapped[int] = mapped_column(Integer)
    stay_min: Mapped[int] = mapped_column(Integer, default=60)
    move_min: Mapped[int] = mapped_column(Integer, default=0)                 # 다음 장소까지
    move_mode: Mapped[str] = mapped_column(String(10), default="차량")        # 도보|차량|마무리
    reason_text: Mapped[str | None] = mapped_column(String(300))

    course: Mapped[Course] = relationship(back_populates="items")
    spot: Mapped[TouristSpot] = relationship()


class SpotScoreDaily(Base):
    """장소별·날짜별 점수 캐시(보정치 포함) — 조회 시 추가 연산 없음(9-4)."""

    __tablename__ = "spot_score_daily"
    __table_args__ = (UniqueConstraint("spot_id", "date", "time_slot", name="uq_score"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    date: Mapped[date] = mapped_column(Date, index=True)
    time_slot: Mapped[str] = mapped_column(String(10), default="afternoon")
    congestion_risk: Mapped[float] = mapped_column(Float)
    adjusted_risk: Mapped[float | None] = mapped_column(Float)                # 피드백 보정 후(미적용 시 NULL)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )


class RecommendationEvidence(Base):
    """추천 근거 카드 표시 수치(F6)."""

    __tablename__ = "recommendation_evidence"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    course_id: Mapped[int] = mapped_column(ForeignKey("course.course_id"), index=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"))
    theme_sim: Mapped[float] = mapped_column(Float, default=0.0)
    relief_effect: Mapped[float] = mapped_column(Float, default=0.0)          # 원 대비 risk 감소분
    travel_time: Mapped[int] = mapped_column(Integer, default=0)              # 분
    hidden_score: Mapped[float] = mapped_column(Float, default=0.0)

    course: Mapped[Course] = relationship(back_populates="evidences")
    spot: Mapped[TouristSpot] = relationship()


class RecommendationLog(Base):
    """대안지 추천 노출·선택 기록 → 추천 부하(F8) 산출. 합성 시드는 is_seed=true."""

    __tablename__ = "recommendation_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    origin_spot_id: Mapped[int | None] = mapped_column(ForeignKey("tourist_spot.spot_id"))
    exposed_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now, index=True)
    selected: Mapped[bool] = mapped_column(Boolean, default=False)
    is_seed: Mapped[bool] = mapped_column(Boolean, default=False)


class VisitFeedback(Base):
    """방문 후 1탭 피드백(F7) → 보정 계수(9-4)."""

    __tablename__ = "visit_feedback"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    course_id: Mapped[int | None] = mapped_column(ForeignKey("course.course_id"))
    spot_id: Mapped[int] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    perceived: Mapped[int] = mapped_column(Integer)                           # -1 한산 / 0 예상 / +1 붐빔
    is_seed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class VisitReview(Base):
    """FE 후기 UI(별점·태그·텍스트) 지원 — 기획서 ERD 확장."""

    __tablename__ = "visit_review"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    course_id: Mapped[int | None] = mapped_column(ForeignKey("course.course_id"), index=True)
    spot_id: Mapped[int | None] = mapped_column(ForeignKey("tourist_spot.spot_id"), index=True)
    nickname: Mapped[str] = mapped_column(String(30), default="익명")
    rating: Mapped[int] = mapped_column(Integer)                              # 1~5
    tags: Mapped[list] = mapped_column(JSON, default=list)
    text: Mapped[str | None] = mapped_column(Text)
    is_seed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class ApiIngestLog(Base):
    """공사 API 수집 상태 기록 — 구동 안정성 근거(15-1)."""

    __tablename__ = "api_ingest_log"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    api_name: Mapped[str] = mapped_column(String(60), index=True)
    status: Mapped[str] = mapped_column(String(20))                           # success|failed|skipped
    records: Mapped[int] = mapped_column(Integer, default=0)
    last_synced_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    error_message: Mapped[str | None] = mapped_column(Text)


class RegionStatDaily(Base):
    """빅데이터 지역별 방문자수·지역별 관광 수요 강도 — 널널도 산식 20%·15% 항."""

    __tablename__ = "region_stat_daily"
    __table_args__ = (UniqueConstraint("area_code", "date", name="uq_region_stat"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    area_code: Mapped[int] = mapped_column(Integer, index=True)
    date: Mapped[date] = mapped_column(Date, index=True)
    visitor_count: Mapped[int | None] = mapped_column(Integer)
    visitor_index: Mapped[float | None] = mapped_column(Float)                # 상대지수 0~100
    demand_intensity: Mapped[float | None] = mapped_column(Float)             # 수요 강도 0~100
    source: Mapped[str] = mapped_column(String(20), default="seed")


class AppUser(Base):
    """MVP는 로그인 없이 익명 — 확장 대비 선택 테이블(기획서 11장)."""

    __tablename__ = "app_user"

    user_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    nickname: Mapped[str] = mapped_column(String(30))
