"""한국관광공사 지역별 관광 수요 강도(AreaTarDemDsService) — 필수 활용 ⑤.

역할(기획서 8-2): 지역 과밀 보정(가중치 0.15) 및 숨은 지역 가점.

실측 스펙(2026-07 확인):
  - End Point: https://apis.data.go.kr/B551011/AreaTarDemDsService
  - /areaTarSjrnDsList : 지역별 관광 체류 강도(Tourism Mobility Strength)
  - /areaTarExpDsList  : 지역별 관광 소비 강도(Tourism Expenditure Strength)
  - 필수 파라미터: areaCd(법정동 시도, 서울=11) + baseYm(YYYYMM)
  - ⚠️ 2026-07 기준 전 지역·전 월 totalCount=0 — 공급처 데이터 미적재 상태.
    데이터가 적재되면 일배치가 자동 수집한다(그 전까지 산식은 재정규화로 흡수).
"""
from app.config import get_settings
from app.external.base import DataGoKrClient


class DemandApiClient(DataGoKrClient):
    def stay_intensity(self, base_ym: str, area_code: str = "11") -> list[dict] | None:
        """지역별 관광 체류 강도 목록 — 수요 강도의 주 지표."""
        return self.get_paged("areaTarSjrnDsList", areaCd=area_code, baseYm=base_ym)

    def expenditure_intensity(self, base_ym: str, area_code: str = "11") -> list[dict] | None:
        """지역별 관광 소비 강도 목록 — 체류 강도 결측 시 보조 지표."""
        return self.get_paged("areaTarExpDsList", areaCd=area_code, baseYm=base_ym)


def get_client() -> DemandApiClient:
    settings = get_settings()
    return DemandApiClient(
        settings.kto_demand_service_path,
        "" if settings.is_demo else settings.kto_api_key,
    )
