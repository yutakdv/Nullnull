"""코스 생성/조회 API(F5·F6) — 기획서 12장."""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.services import course_service
from app.services.congestion_service import validate_visit_date

router = APIRouter(prefix="/api/courses", tags=["courses"])


@router.get("/popular", response_model=list[schemas.PopularCourse])
def popular(limit: int = Query(3, ge=1, le=10), db: Session = Depends(get_db)):
    """홈 '인기 널널 코스' 캐러셀."""
    return course_service.popular_courses(db, limit)


@router.post("", response_model=schemas.CourseDetail, status_code=201)
def create_course(body: schemas.CourseCreateRequest, db: Session = Depends(get_db)):
    """추천/커스텀 코스 생성·저장 — 선택 로그(F8) 기록."""
    if not db.get(models.TouristSpot, body.origin_spot_id):
        raise HTTPException(status_code=404, detail="원 관광지를 찾을 수 없어요.")
    for sid in body.spot_ids:
        if not db.get(models.TouristSpot, sid):
            raise HTTPException(status_code=404, detail=f"관광지({sid})를 찾을 수 없어요.")
    if body.date:
        validate_visit_date(body.date)
    course = course_service.create_course(
        db, body.origin_spot_id, body.spot_ids, body.date, body.time_slot, body.title,
        companion=body.companion,
    )
    return course_service.course_detail(db, course)


@router.post("/recommend", response_model=schemas.CourseDetail, status_code=201)
def recommend_course(body: schemas.CourseRecommendRequest, db: Session = Depends(get_db)):
    """자유여행 코스 추천 — 슬롯 카테고리 시퀀스(여행지→미식→포토스팟 등 모든 조합).

    theme_sequence 생략 시 기본 자유여행(여행지→미식→포토스팟)으로 구성한다.
    """
    origin = db.get(models.TouristSpot, body.origin_spot_id)
    if not origin:
        raise HTTPException(status_code=404, detail="원 관광지를 찾을 수 없어요.")
    if body.date:
        validate_visit_date(body.date)
    try:
        course = course_service.recommend_course(
            db, origin, body.theme_sequence, body.date, body.time_slot, body.title,
            companion=body.companion,
        )
    except course_service.NoSlotCandidateError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    return course_service.course_detail(db, course)


def _get_course_or_404(db: Session, course_id: int) -> models.Course:
    course = db.get(models.Course, course_id)
    if not course:
        raise HTTPException(status_code=404, detail="코스를 찾을 수 없어요.")
    return course


@router.get("/{course_id}/alternatives", response_model=schemas.CourseAlternativesResponse)
def course_alternatives(
    course_id: int,
    limit: int = Query(2, ge=1, le=3),
    db: Session = Depends(get_db),
):
    """코스 슬롯별 교체 후보 — 노출 로그를 기록해 추천 부하(F8)에 반영한다."""
    course = _get_course_or_404(db, course_id)
    return course_service.course_alternatives(db, course, limit=limit)


@router.post("/{course_id}/swap", response_model=schemas.CourseDetail, status_code=201)
def swap_course_item(
    course_id: int, body: schemas.CourseSwapRequest, db: Session = Depends(get_db),
):
    """슬롯 교체 — 원본은 남기고 교체본 새 코스를 만들어 돌려준다."""
    course = _get_course_or_404(db, course_id)
    try:
        new_course = course_service.swap_course_item(
            db, course, body.order_no, body.new_spot_id
        )
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except ValueError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return course_service.course_detail(db, new_course)


@router.post("/{course_id}/reroll", response_model=schemas.CourseDetail, status_code=201)
def reroll_course(course_id: int, db: Session = Depends(get_db)):
    """같은 조건에서 추천 부하를 반영한 다른 조합의 새 코스를 만든다."""
    course = _get_course_or_404(db, course_id)
    try:
        new_course = course_service.reroll_course(db, course)
    except LookupError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except course_service.NoSlotCandidateError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    return course_service.course_detail(db, new_course)


@router.post("/{course_id}/share", response_model=schemas.CourseDetail)
def share_course(course_id: int, db: Session = Depends(get_db)):
    """코스 공개 — 홈 '인기 널널 코스'에 노출된다(F9 코스 공유)."""
    course = _get_course_or_404(db, course_id)
    course.is_shared = True
    db.commit()
    return course_service.course_detail(db, course)


@router.get("/{course_id}", response_model=schemas.CourseDetail)
def get_course(course_id: int, db: Session = Depends(get_db)):
    """코스 상세 + 추천 근거 + 개인 임팩트 카드."""
    course = _get_course_or_404(db, course_id)
    return course_service.course_detail(db, course)
