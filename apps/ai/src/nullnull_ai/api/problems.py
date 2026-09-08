"""Problem responses and exception handlers for the internal API."""

from __future__ import annotations

from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from nullnull_ai.api.schemas import Problem

ProblemCode = Literal["INVALID_REQUEST", "VALIDATION_FAILED", "NOT_FOUND", "INTERNAL_ERROR", "SORT_VERSION_UNSUPPORTED"]
MEDIA_TYPE = "application/problem+json"


class ApiProblemError(Exception):
    def __init__(self, code: ProblemCode, status: int, detail: str, retryable: bool = False) -> None:
        super().__init__(detail)
        self.code = code
        self.status = status
        self.detail = detail
        self.retryable = retryable


def problem_response(request: Request, code: ProblemCode, status: int, detail: str, retryable: bool) -> JSONResponse:
    body = Problem(
        type=f"/problems/{code.lower().replace('_', '-')}",
        title=code.replace("_", " ").capitalize(),
        status=status,
        code=code,
        detail=detail,
        instance=request.url.path,
        request_id=getattr(request.state, "request_id", "unassigned"),
        retryable=retryable,
    )
    return JSONResponse(status_code=status, content=body.model_dump(by_alias=True), media_type=MEDIA_TYPE)


def install_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiProblemError)
    async def _api_problem(request: Request, error: ApiProblemError) -> JSONResponse:
        return problem_response(request, error.code, error.status, error.detail, error.retryable)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, error: RequestValidationError) -> JSONResponse:
        return problem_response(request, "VALIDATION_FAILED", 422, "The request body violates the contract.", False)

    @app.exception_handler(HTTPException)
    async def _http(request: Request, error: HTTPException) -> JSONResponse:
        code: ProblemCode = "NOT_FOUND" if error.status_code == 404 else "INVALID_REQUEST"
        if error.status_code >= 500:
            code = "INTERNAL_ERROR"
        return problem_response(request, code, error.status_code, "The request could not be completed.", False)

    @app.exception_handler(Exception)
    async def _unexpected(request: Request, error: Exception) -> JSONResponse:
        return problem_response(request, "INTERNAL_ERROR", 500, "An unexpected error occurred.", False)
