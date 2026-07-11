"""한국관광공사 빅데이터 지역별 방문자수(KT·SKT 데이터 기반) — 필수 활용 ③.

역할(기획서 8-2): 같은 지역 전체가 붐비는지 보정(가중치 0.20),
숨은 명소성의 '방문자수 하위' 판정.
※ 오퍼레이션명은 활용신청 승인 후 Swagger에서 최종 확인 필요.
"""
from datetime import date

from app.config import get_settings
from app.external.base import DataGoKrClient


class DataLabApiClient(DataGoKrClient):
    def __init__(self, key: str):
        super().__init__("B551011/DataLabService", key)

    def metro_visitors(self, start: date, end: date) -> list[dict] | None:
        """광역지자체 일자별 방문자수 — 전 시도가 반환되므로 호출부에서 지역 필터.

        실측(2026-07): areaCd 파라미터를 넘기면 오히려 빈 응답이 온다(기간만 지정).
        집계가 약 한 달 지연되므로 과거 구간으로 조회할 것.
        """
        return self.get_paged(
            "metcoRegnVisitrDDList",
            startYmd=start.strftime("%Y%m%d"), endYmd=end.strftime("%Y%m%d"),
        )

    def local_visitors(self, start: date, end: date,
                       signgu_code: str | None = None) -> list[dict] | None:
        """기초지자체 일자별 방문자수."""
        params: dict = {
            "startYmd": start.strftime("%Y%m%d"), "endYmd": end.strftime("%Y%m%d"),
        }
        if signgu_code:
            params["signguCd"] = signgu_code
        return self.get("locgoRegnVisitrDDList", **params)


def get_client() -> DataLabApiClient:
    settings = get_settings()
    return DataLabApiClient("" if settings.is_demo else settings.kto_api_key)
