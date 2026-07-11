"""한국관광공사 관광지별 연관 관광지 정보(티맵 모빌리티 데이터 기반) — 필수 활용 ④.

역할(기획서 8-2): 대안 후보군 생성, 테마 유사도 결합 항(0.4).
※ 오퍼레이션명·연관도 필드는 활용신청 승인 후 Swagger에서 최종 확인 필요.
"""
from app.config import get_settings
from app.external.base import DataGoKrClient


class RelatedApiClient(DataGoKrClient):
    def __init__(self, key: str):
        # 게이트웨이 탐색 결과 실제 서비스명은 TarRlteTarService1 (2026-07 확인)
        super().__init__("B551011/TarRlteTarService1", key)

    def related_spots(self, base_ym: str, area_code: str = "11",
                      signgu_code: str | None = None,
                      keyword: str | None = None) -> list[dict] | None:
        """관광지별 연관 관광지 목록(월 단위·시군구 기반 — 상세기능 /areaBasedList1)."""
        params: dict = {"baseYm": base_ym, "areaCd": area_code}
        if signgu_code:
            params["signguCd"] = signgu_code
        if keyword:
            params["keyword"] = keyword
        return self.get("areaBasedList1", **params)


def get_client() -> RelatedApiClient:
    settings = get_settings()
    return RelatedApiClient("" if settings.is_demo else settings.kto_api_key)
