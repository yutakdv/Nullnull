"""널널(Nullnull) API 서버 — 오버투어리즘 분산 코스 추천 서비스 백엔드.

기동 시 테이블 생성 + (비어 있으면) 서울 MVP 시드 자동 적재 → 키 없이도 즉시 시연 가능.
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from sqlalchemy import inspect, text

from app import seed_data
from app.config import get_settings
from app.database import Base, SessionLocal, engine
from app.routers import admin, courses, feedback, impact, reviews, spots
from app.services.congestion_service import ForecastWindowError

# create_all은 기존 테이블에 컬럼을 추가하지 않는다 — 배포된 DB를 위한 경량 마이그레이션
_COLUMN_MIGRATIONS = {
    "course": {
        "mode": "VARCHAR(10) DEFAULT 'theme'",
        "slot_themes": "JSON",
        "time_slot": "VARCHAR(10) DEFAULT 'afternoon'",
        "companion": "VARCHAR(10)",
        "is_shared": "BOOLEAN DEFAULT 0",
    },
}


def apply_column_migrations() -> None:
    inspector = inspect(engine)
    with engine.begin() as conn:
        for table, columns in _COLUMN_MIGRATIONS.items():
            existing = {col["name"] for col in inspector.get_columns(table)}
            for name, ddl in columns.items():
                if name not in existing:
                    conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}"))


@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    apply_column_migrations()
    with SessionLocal() as db:
        seed_data.run(db)   # 멱등 — 데이터가 있으면 건너뜀
        seed_data.sync_seed_images(db)
    yield


settings = get_settings()
app = FastAPI(
    title="널널(Nullnull) API",
    description="붐비는 곳 말고, 널널한 여행 — 실시간·예측 기반 혼잡 분산 코스 추천",
    version="0.1.0",
    lifespan=lifespan,
)

# 쿠키를 쓰지 않으므로 credentials 없이 개방 — CORS_ORIGINS=* 로 전체 허용 가능
_origins = settings.cors_origin_list
app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials="*" not in _origins,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(ForecastWindowError)
async def forecast_window_handler(request: Request, exc: ForecastWindowError):
    # 30일 예측 창(8-1) — UI 제한과 별개로 API 레벨에서도 400 방어(17장)
    return JSONResponse(status_code=400, content={"detail": str(exc)})


app.include_router(spots.router)
app.include_router(courses.router)
app.include_router(feedback.router)
app.include_router(reviews.router)
app.include_router(impact.router)
app.include_router(admin.router)


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse("/docs")


@app.get("/api/health", tags=["health"])
def health():
    return {"status": "ok", "service": "nullnull-api", "demo_mode": settings.is_demo}
