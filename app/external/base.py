"""공공데이터포털(data.go.kr) 공통 클라이언트.

- 키가 없으면 enabled=False → 모든 호출이 None을 반환하고 상위에서 시드/휴리스틱으로 폴백
- 오류는 ExternalApiError로 올려 배치가 api_ingest_log에 failed로 기록(구동 안정성 근거)
"""
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import httpx


class ExternalApiError(Exception):
    pass


class DataGoKrClient:
    BASE = "https://apis.data.go.kr"

    def __init__(self, service_path: str, service_key: str, mobile_app: str = "nullnull"):
        self.service_path = service_path.strip("/")
        self.service_key = service_key
        self.mobile_app = mobile_app

    @property
    def enabled(self) -> bool:
        return bool(self.service_key)

    @staticmethod
    def _redact_url(url: str) -> str:
        parts = urlsplit(url)
        query = urlencode([
            (key, "<redacted>" if key.lower() == "servicekey" else value)
            for key, value in parse_qsl(parts.query, keep_blank_values=True)
        ])
        return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))

    def get(self, operation: str, **params: Any) -> list[dict] | None:
        """단일 페이지 조회 — 응답(response.body.items.item)을 리스트로 정규화."""
        if not self.enabled:
            return None
        items, _total = self._request(operation, **params)
        return items

    def get_paged(self, operation: str, page_size: int = 1000,
                  max_pages: int = 20, **params: Any) -> list[dict] | None:
        """totalCount 기준 전 페이지 수집 — 집중률(구당 3천+건) 같은 대용량 목록용."""
        if not self.enabled:
            return None
        collected: list[dict] = []
        for page in range(1, max_pages + 1):
            items, total = self._request(operation, numOfRows=page_size,
                                         pageNo=page, **params)
            collected.extend(items)
            if total is not None and len(collected) >= total:
                break
            if len(items) < page_size:
                break
        return collected

    def _request(self, operation: str, **params: Any) -> tuple[list[dict], int | None]:
        query = {
            "serviceKey": self.service_key,
            "MobileOS": "ETC",
            "MobileApp": self.mobile_app,
            "_type": "json",
            "numOfRows": params.pop("numOfRows", 500),
            "pageNo": params.pop("pageNo", 1),
            **params,
        }
        try:
            resp = httpx.get(
                f"{self.BASE}/{self.service_path}/{operation}", params=query, timeout=15
            )
            resp.raise_for_status()
            data = resp.json()
        except httpx.HTTPStatusError as e:
            status = e.response.status_code
            # data.go.kr 게이트웨이: 403=서비스는 존재하나 이 키로 활용신청 미승인,
            # 500 "Unexpected errors"=해당 서비스 경로 자체가 없음
            if status == 403:
                hint = " → 공공데이터포털 마이페이지에서 이 API의 활용신청 승인 여부를 확인하세요"
            elif status == 500 and "Unexpected errors" in e.response.text:
                hint = " → 서비스명/버전이 다릅니다. 승인된 API 상세페이지의 엔드포인트로 수정하세요"
            elif status == 404:
                hint = " → 서비스는 있으나 이 오퍼레이션이 없습니다. 상세기능정보의 경로로 수정하세요"
            else:
                hint = ""
            raise ExternalApiError(
                f"{self.service_path}/{operation}: HTTP {status}{hint}"
            ) from e
        except httpx.HTTPError as e:
            message = str(e)
            if getattr(e, "request", None) is not None:
                message = message.replace(str(e.request.url), self._redact_url(str(e.request.url)))
            raise ExternalApiError(f"{self.service_path}/{operation}: {message}") from e
        except ValueError as e:  # 쿼터 초과 등은 XML로 응답됨
            raise ExternalApiError(
                f"{self.service_path}/{operation}: JSON 아님(쿼터/키 오류 가능) {e}"
            ) from e

        # 일부 서비스(AreaTarDemDs 등)는 파라미터 오류를 평면 JSON으로 반환한다
        if "response" not in data and data.get("resultCode") not in (None, "0000", "00"):
            raise ExternalApiError(
                f"{self.service_path}/{operation}: {data.get('resultMsg')}"
            )
        header = data.get("response", {}).get("header", {})
        if header.get("resultCode") not in (None, "0000", "00"):
            raise ExternalApiError(
                f"{self.service_path}/{operation}: {header.get('resultMsg')}"
            )
        body = data.get("response", {}).get("body", {})
        try:
            total = int(body.get("totalCount"))
        except (TypeError, ValueError):
            total = None
        items = body.get("items")
        if not items or items in ("", {}):
            return [], total
        item = items.get("item", [])
        return (item if isinstance(item, list) else [item]), total
