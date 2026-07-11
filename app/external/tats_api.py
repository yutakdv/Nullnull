"""한국관광공사 관광지 집중률 방문자 추이 예측 정보(KT 데이터 기반) — 필수 활용 ②.

역할(기획서 8-2): 널널도 산출의 핵심 지표(가중치 0.55), 요일 분산 비교.
제공 범위: 조회 시점 기준 향후 30일 예측(8-1) → API 레벨 400 방어와 짝을 이룬다.
※ 서비스 경로·오퍼레이션명은 활용신청 승인 후 Swagger에서 최종 확인 필요.
"""
from app.config import get_settings
from app.external.base import DataGoKrClient


class TatsApiClient(DataGoKrClient):
    def __init__(self, key: str):
        super().__init__("B551011/TatsCnctrRateService", key)

    def concentration_forecast(self, area_code: str = "11",
                               signgu_code: str | None = None,
                               spot_name: str | None = None) -> list[dict] | None:
        """관광지별 향후 30일 집중률 예측 목록.

        실측(2026-07): areaCd는 법정동 시도코드(서울=11)이고 signguCd가 있어야
        데이터가 반환된다. 구 하나에 3천+건이라 페이지네이션 수집.
        """
        params: dict = {"areaCd": area_code}
        if signgu_code:
            params["signguCd"] = signgu_code
        if spot_name:
            params["tAtsNm"] = spot_name
        return self.get_paged("tatsCnctrRatedList", **params)


def get_client() -> TatsApiClient:
    settings = get_settings()
    return TatsApiClient("" if settings.is_demo else settings.kto_api_key)
