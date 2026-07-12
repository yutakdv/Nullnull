"""앱 설정 — 환경변수(.env) 기반. API 키는 전부 선택값이며 없으면 데모 모드로 동작한다."""
from datetime import date
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent

# 2026년 대한민국 공휴일(대체공휴일 포함) — 널널도 산식의 요일/공휴일 보정 항(9-1)에 사용
KR_HOLIDAYS: frozenset[date] = frozenset(
    date.fromisoformat(d)
    for d in [
        "2026-01-01",                               # 신정
        "2026-02-16", "2026-02-17", "2026-02-18",   # 설날 연휴
        "2026-03-01", "2026-03-02",                 # 삼일절·대체
        "2026-05-05",                               # 어린이날
        "2026-05-24", "2026-05-25",                 # 부처님오신날·대체
        "2026-06-06",                               # 현충일
        "2026-08-15", "2026-08-17",                 # 광복절·대체
        "2026-09-24", "2026-09-25", "2026-09-26",   # 추석 연휴
        "2026-10-03", "2026-10-05",                 # 개천절·대체
        "2026-10-09",                               # 한글날
        "2026-12-25",                               # 성탄절
    ]
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=BASE_DIR / ".env", env_file_encoding="utf-8", extra="ignore"
    )

    database_url: str = f"sqlite:///{BASE_DIR / 'nullnull.db'}"

    # 미설정(None)이면 KTO 키 유무로 자동 판단
    demo_mode: bool | None = None

    # 한국관광공사 OpenAPI 공통 인증키(필수 활용 5종)
    kto_api_key: str = ""
    # 지역별 관광 수요 강도 API — End Point 실측 확정(2026-07)
    kto_demand_service_path: str = "B551011/AreaTarDemDsService"
    # 보조 외부 API(전부 무료 · 선택)
    kma_api_key: str = ""
    seoul_api_key: str = ""
    kakao_rest_api_key: str = ""

    # OpenAI — AI 코스 추천. 키가 없으면 알고리즘 다중 코스로 폴백(오프라인 심사 대비)
    openai_api_key: str = ""
    openai_model: str = "gpt-4o-mini"

    admin_token: str = "nullnull-admin"
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"

    forecast_window_days: int = 30      # 집중률 예측 제공 범위(8-1)
    weather_forecast_days: int = 3      # 기상청 단기예보 적용 범위(8-3)
    weights_path: str = str(BASE_DIR / "weights.yaml")

    @property
    def is_demo(self) -> bool:
        if self.demo_mode is not None:
            return self.demo_mode
        return not self.kto_api_key

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
