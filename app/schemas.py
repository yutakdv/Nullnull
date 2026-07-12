"""Pydantic 요청/응답 스키마 — FE(nullnull-travel-webapp) 화면 데이터 계약."""
from datetime import date as date_type
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

# 자유여행 슬롯 카테고리 — '여행지'는 일반 관광 명소(요청의 '여행장소')
SlotTheme = Literal["여행지", "자연", "역사", "미식", "포토스팟"]
# F1 동행 유형(라이트) — 체류시간·문구에 반영
Companion = Literal["solo", "couple", "family"]

# ── 공통/스팟 ────────────────────────────────────────────────


class SpotSummary(BaseModel):
    spot_id: int
    content_id: str | None
    name: str
    region: str
    category_name: str
    tags: list[str]
    addr: str | None
    lat: float
    lng: float
    image_url: str | None

    model_config = {"from_attributes": True}


class ReviewStats(BaseModel):
    avg_rating: float
    count: int
    tag_counts: dict[str, int]


class ProofBars(BaseModel):
    """FE ReviewProofCard — 피드백·후기 기반 신뢰 지표."""
    prediction_accuracy_pct: int
    route_comfort_pct: int
    theme_satisfaction_pct: int


class SpotDetail(SpotSummary):
    overview: str | None
    highlight: str | None
    image_count: int
    is_indoor: bool
    hidden_gem: bool
    review_stats: ReviewStats
    proof: ProofBars


class SpotListItem(SpotSummary):
    """검색 카탈로그 카드 — 스냅샷 보유 스팟만 혼잡 level이 채워진다."""
    level: int | None = None


class SpotListResponse(BaseModel):
    items: list[SpotListItem]
    total: int
    page: int
    size: int


class HomeSpot(SpotSummary):
    """홈 캐러셀용 관광지와 오늘의 혼잡 요약."""
    risk: float
    level: int
    label: str
    source: str
    based_on: str
    best_time_slot: str
    best_time_slot_label: str


class HomeSpotsResponse(BaseModel):
    """서울 TourAPI 우선 홈 추천 목록."""
    items: list[HomeSpot]
    total: int


class VisitedSpot(SpotSummary):
    """홈 '최근 방문한 장소' 카드 — 실사용 피드백·후기 기반."""
    risk: float
    level: int
    label: str
    visited_text: str             # "오늘 오후" | "어제" | "N일 전"
    visit_count: int
    last_rating: int | None       # 최근 후기 별점(없으면 null)
    last_perceived_label: str | None   # 최근 피드백 문구(없으면 null)


class VisitedSpotsResponse(BaseModel):
    items: list[VisitedSpot]
    total: int


# ── 널널도(F3) ───────────────────────────────────────────────


class WeekdayRisk(BaseModel):
    date: date_type
    day: str                      # 월~일
    risk: float
    level: int
    label: str
    is_selected: bool


class TimeSlotRisk(BaseModel):
    slot: str                     # morning|afternoon|evening
    slot_label: str               # 오전|오후|저녁
    risk: float
    level: int
    label: str
    note: str


class TimeShiftSuggestion(BaseModel):
    """행동형 시간 이동 제안 — 탭하면 해당 날짜·시간대로 전환(시간 분산 UX 1순위)."""
    kind: str                     # slot(같은 날 다른 시간대) | date(다른 요일)
    date: date_type
    time_slot: str
    slot_label: str
    risk: float
    level: int
    label: str
    decrease_pct: int
    text: str                     # 예: "같은 날 오전엔 '보통'"


class CongestionResponse(BaseModel):
    spot_id: int
    name: str
    date: date_type
    time_slot: str
    risk: float                   # 보정 반영 최종 널널도(0~100)
    raw_risk: float               # 보정 전
    adjusted: bool                # 피드백 보정 적용 여부(9-4)
    level: int
    label: str
    color: str
    source: str                   # realtime|prediction|snapshot|heuristic|cache
    based_on: str                 # 데이터 출처·예측 기준 고지(데이터 정직성 원칙)
    window_from: date_type
    window_to: date_type
    tip: str
    weekday_comparison: list[WeekdayRisk]
    time_slots: list[TimeSlotRisk]
    time_shift_suggestions: list[TimeShiftSuggestion] = []


class CalendarDay(BaseModel):
    date: date_type
    day: str                      # 월~일
    risk: float
    level: int
    label: str
    is_holiday: bool


class SpotCalendarResponse(BaseModel):
    """30일 널널 캘린더 히트맵(F3) — 예측 창 전체."""
    spot_id: int
    name: str
    time_slot: str
    window_from: date_type
    window_to: date_type
    days: list[CalendarDay]


# ── 대안지 추천(F4·F6·F8) ────────────────────────────────────


class ScoreBreakdown(BaseModel):
    theme_similarity: float
    relief: float
    mobility: float
    hidden: float
    weather: float | None
    load_penalty: float


class AlternativeItem(BaseModel):
    spot_id: int
    name: str
    image_url: str | None
    lat: float                    # 지도 마커·경로용 좌표
    lng: float
    risk: float
    level: int
    label: str
    decrease_pct: int             # 혼잡 감소율(%)
    travel_time_min: int
    travel_mode: str
    distance_km: float
    similarity_pct: int           # 테마 유사도(%)
    hidden_gem: bool
    reason: str
    score: float
    breakdown: ScoreBreakdown


class OriginSpot(BaseModel):
    spot_id: int
    name: str
    image_url: str | None
    lat: float
    lng: float
    date: date_type
    time_slot: str
    risk: float
    level: int
    label: str


class RouteSummary(BaseModel):
    total_distance_km: float
    total_drive_min: int
    total_walk_km: float


class AlternativesResponse(BaseModel):
    origin: OriginSpot
    alternatives: list[AlternativeItem]
    route_summary: RouteSummary


# ── 코스(F5) ─────────────────────────────────────────────────


class CourseCreateRequest(BaseModel):
    origin_spot_id: int
    spot_ids: list[int] = Field(min_length=1, max_length=4)   # 대안지 2~4곳(시연 편의로 1곳 허용)
    date: date_type | None = None
    time_slot: str = "afternoon"
    title: str | None = None
    companion: Companion | None = None


class CourseRecommendRequest(BaseModel):
    """자유여행 코스 추천 — 슬롯별 카테고리 시퀀스(어떤 조합·순서든 허용)."""
    origin_spot_id: int
    date: date_type | None = None
    time_slot: str = Field("afternoon", pattern="^(morning|afternoon|evening)$")
    theme_sequence: list[SlotTheme] | None = Field(
        None, min_length=1, max_length=4,
        description="슬롯 카테고리 순서(예: 여행지→미식→포토스팟). 생략 시 자유여행 기본값",
    )
    title: str | None = None
    companion: Companion | None = None


class CourseSwapRequest(BaseModel):
    """코스 슬롯 교체 — 원본은 보존하고 교체본 새 코스를 만든다."""
    order_no: int = Field(ge=1)
    new_spot_id: int


class TimelineItem(BaseModel):
    order_no: int
    spot_id: int
    place: str
    meta: str                     # 예: "관람 70분"
    move: str                     # 예: "차량 18분" | "마무리"
    note: str | None
    image_url: str | None
    level: int
    slot_theme: str | None = None  # 자유여행 코스의 슬롯 카테고리(테마 코스는 null)


class CourseSummaryMetrics(BaseModel):
    relief_pct: int               # 예상 혼잡 감소
    theme_keep_pct: int           # 테마 유지율
    total_move_min: int           # 총 이동시간
    total_distance_km: float = 0  # 총 이동거리


class CourseMapPoint(BaseModel):
    """코스 경로 지도 마커 — order_no 0은 출발지(원 관광지)."""
    order_no: int
    name: str
    lat: float
    lng: float


class EvidenceItem(BaseModel):
    spot_id: int
    name: str
    theme_sim: float
    relief_effect: float
    travel_time: int
    hidden_score: float


class ReviewOut(BaseModel):
    nickname: str
    rating: int
    tags: list[str]
    text: str | None
    date_text: str
    created_at: datetime


class CourseReviews(BaseModel):
    stats: ReviewStats
    recent: list[ReviewOut]


class CourseDetail(BaseModel):
    course_id: int
    title: str
    description: str | None
    region: str
    date: date_type | None
    time_slot: str = "afternoon"  # 코스 생성 기준 시간대(대안·교체 재계산에도 사용)
    level: int
    label: str
    mode: str                     # theme(테마 유지) | free(자유여행)
    slot_themes: list[str] | None
    companion: str | None = None            # F1 동행 유형
    companion_label: str | None = None      # 혼자|둘이서|가족과
    is_shared: bool = False                 # 공개(홈 인기 노출) 여부
    course_score: float
    timeline: list[TimelineItem]
    map_points: list[CourseMapPoint] = []
    summary: CourseSummaryMetrics
    impact_text: str              # "이 선택으로 예상 혼잡 42%를 회피했어요"
    evidence: list[EvidenceItem]
    reviews: CourseReviews


class AiCourseRequest(BaseModel):
    """AI 코스 추천 조건 — 알고리즘이 후보를 추리고 LLM(가능 시)이 코스를 구성한다."""
    district: str | None = None
    stops: int = Field(3, ge=2, le=5, description="코스당 방문 장소 수")
    companion: Companion | None = None
    date: date_type | None = None
    time_slot: str = Field("afternoon", pattern="^(morning|afternoon|evening)$")
    themes: list[str] = Field(default_factory=list, description="관심 테마(다중)")
    pace: Literal["여유", "보통"] = "여유"
    indoor_pref: Literal["상관없음", "실내", "실외"] = "상관없음"
    transport: Literal["walk", "car"] | None = Field(
        None, description="이동 방식 — walk면 도보권 후보로 좁히고 도보 시간, car면 차량 기준",
    )


class AiCourseResponse(BaseModel):
    source: Literal["llm", "algorithm"]     # 코스 생성 출처(폴백 투명 고지)
    courses: list[CourseDetail]


class CourseSlotAlternative(BaseModel):
    """코스 슬롯 교체 후보 — F4 대안 카드의 경량판."""
    spot_id: int
    name: str
    image_url: str | None
    risk: float
    level: int
    label: str
    decrease_pct: int             # 현재 슬롯 장소 대비 혼잡 감소율(%)
    travel_time_min: int          # 코스 내 직전 지점 기준 이동시간
    travel_mode: str
    similarity_pct: int
    hidden_gem: bool
    reason: str
    score: float


class CourseItemAlternatives(BaseModel):
    order_no: int
    spot_id: int
    name: str
    level: int
    slot_theme: str | None
    alternatives: list[CourseSlotAlternative]


class CourseAlternativesResponse(BaseModel):
    course_id: int
    date: date_type | None
    items: list[CourseItemAlternatives]


class PopularCourse(BaseModel):
    course_id: int
    title: str
    location: str
    image_url: str | None
    rate_pct: int                 # 혼잡 회피율
    duration_text: str            # "3시간 20분"
    tag: str
    level: int


# ── 피드백(F7)·후기 ──────────────────────────────────────────


class FeedbackRequest(BaseModel):
    spot_id: int
    course_id: int | None = None
    perceived: int = Field(ge=-1, le=1)   # -1 한산 / 0 예상대로 / +1 붐빔


class ReviewCreateRequest(BaseModel):
    spot_id: int | None = None
    course_id: int | None = None
    nickname: str = "익명"
    rating: int = Field(ge=1, le=5)
    tags: list[str] = []
    text: str | None = None


class OkResponse(BaseModel):
    ok: bool = True
    message: str


# ── 임팩트(분산 지표)·관리자 ─────────────────────────────────


class ImpactSummary(BaseModel):
    week_start: date_type
    week_end: date_type
    avoid_rate_avg_pct: int       # 평균 혼잡 회피율
    hidden_pick_count: int        # 숨은 명소 선택 수
    courses_created: int
    includes_seed: bool           # 합성 시드 포함 여부 고지(데이터 정직성 원칙)


class AdminSeedRequest(BaseModel):
    exposures: int = Field(default=60, ge=0, le=2000)
    selections: int = Field(default=15, ge=0, le=500)
    feedbacks: int = Field(default=30, ge=0, le=500)


class IngestLogEntry(BaseModel):
    api_name: str
    status: str
    records: int
    last_synced_at: datetime
    error_message: str | None

    model_config = {"from_attributes": True}


class LoadDistributionEntry(BaseModel):
    spot_id: int
    name: str
    exposures: int
    selections: int
    load: float


class IngestLogResponse(BaseModel):
    ingest: list[IngestLogEntry]
    load_distribution: list[LoadDistributionEntry]
