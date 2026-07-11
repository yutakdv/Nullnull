"""한국관광공사 국문 관광정보 서비스(TourAPI KorService2) — 필수 활용 ①.

역할(기획서 8-2): 검색·상세·지도 마커·후보지 마스터 데이터, 콘텐츠 풍부도(숨은 명소성) 산출.
※ 오퍼레이션·파라미터명은 활용신청 승인 후 한국관광 콘텐츠랩 Swagger에서 최종 확인한다(19장).
"""
from app.config import get_settings
from app.external.base import DataGoKrClient


class TourApiClient(DataGoKrClient):
    def __init__(self, key: str):
        super().__init__("B551011/KorService2", key)

    def area_based_list(self, area_code: int = 1, content_type_id: int = 12,
                        sigungu_code: int | None = None,
                        max_pages: int = 4) -> list[dict] | None:
        """지역 기반 목록 — 페이지네이션 수집(타입당 최대 max_pages×1000건).

        단일 페이지(500건)로 수집하면 음식점 등 대형 타입이 잘려 후보 폭이 좁아진다.
        """
        params = {"areaCode": area_code, "contentTypeId": content_type_id,
                  "arrange": "Q"}
        if sigungu_code:
            params["sigunguCode"] = sigungu_code
        return self.get_paged("areaBasedList2", page_size=1000,
                              max_pages=max_pages, **params)

    def search_keyword(self, keyword: str, area_code: int = 1) -> list[dict] | None:
        return self.get("searchKeyword2", keyword=keyword, areaCode=area_code)

    def detail_common(self, content_id: str) -> list[dict] | None:
        return self.get("detailCommon2", contentId=content_id)

    def detail_images(self, content_id: str) -> list[dict] | None:
        return self.get("detailImage2", contentId=content_id, imageYN="Y")

    def classification_codes(self, code: str | None = None) -> list[dict] | None:
        """분류체계 코드 조회 — categoryCode2는 포털에서 '삭제예정(미사용)'이라
        대체 오퍼레이션인 lclsSystmCode2를 사용한다(활용신청 상세기능 #15)."""
        params = {"lclsSystmListYn": "Y"}
        if code:
            params["lclsSystm1"] = code
        return self.get("lclsSystmCode2", **params)


def get_client() -> TourApiClient:
    settings = get_settings()
    return TourApiClient("" if settings.is_demo else settings.kto_api_key)
