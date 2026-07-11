"""방문 후기 API — FE 후기 작성/최근 후기/신뢰 지표 카드 지원."""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app import models, schemas
from app.database import get_db
from app.services import course_service

router = APIRouter(prefix="/api/reviews", tags=["reviews"])


@router.post("", response_model=schemas.OkResponse, status_code=201)
def create_review(body: schemas.ReviewCreateRequest, db: Session = Depends(get_db)):
    if body.spot_id is None and body.course_id is None:
        raise HTTPException(status_code=422, detail="spot_id 또는 course_id가 필요해요.")
    if body.spot_id and not db.get(models.TouristSpot, body.spot_id):
        raise HTTPException(status_code=404, detail="관광지를 찾을 수 없어요.")
    if body.course_id and not db.get(models.Course, body.course_id):
        raise HTTPException(status_code=404, detail="코스를 찾을 수 없어요.")
    db.add(models.VisitReview(
        spot_id=body.spot_id, course_id=body.course_id, nickname=body.nickname,
        rating=body.rating, tags=body.tags, text=body.text, is_seed=False,
    ))
    db.commit()
    return {"ok": True, "message": "후기가 저장됐어요"}


@router.get("", response_model=schemas.CourseReviews)
def list_reviews(
    spot_id: int | None = Query(None),
    course_id: int | None = Query(None),
    limit: int = Query(5, ge=1, le=20),
    db: Session = Depends(get_db),
):
    return {
        "stats": course_service.review_stats(db, spot_id=spot_id, course_id=course_id),
        "recent": course_service.recent_reviews(db, spot_id=spot_id,
                                                course_id=course_id, limit=limit),
    }
