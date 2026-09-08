"""FastAPI application factory and process entry point.

The service is internal-only: the Spring API is the sole caller. It never reads a database or an
external provider; every fact arrives in the request body already hydrated and immutable.
"""

from __future__ import annotations

from collections.abc import Callable

import uvicorn
from fastapi import FastAPI

from nullnull_ai import __version__
from nullnull_ai.api.explanations import router as explanations_router
from nullnull_ai.api.feed import router as feed_router
from nullnull_ai.api.items import router as items_router
from nullnull_ai.api.problems import install_handlers
from nullnull_ai.api.related import router as related_router
from nullnull_ai.api.request_id import RequestIdMiddleware
from nullnull_ai.api.slots import router as slots_router
from nullnull_ai.api.system import router as system_router
from nullnull_ai.domain.policy import load_default
from nullnull_ai.explain.ports import LlmExplanationPort, NoopLlmExplanationPort
from nullnull_ai.explain.service import ExplanationService
from nullnull_ai.settings import Settings

_LLM_PORTS: dict[str, Callable[[], LlmExplanationPort]] = {"NONE": NoopLlmExplanationPort}
"""AI_PROVIDER=NONE (P0 default) always answers with the deterministic template. A provider adapter
arrives with its own model id, timeout and kill switch; until then any other value fails at startup
instead of silently falling back to one."""


def llm_port(settings: Settings) -> LlmExplanationPort:
    factory = _LLM_PORTS.get(settings.ai_provider)
    if factory is None:
        raise ValueError(f"unsupported AI_PROVIDER: {settings.ai_provider}")
    return factory()


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    policy = load_default()
    app = FastAPI(
        title="Nullnull recommendation internal API",
        version="1.0.0",
        description=(
            "Internal contract between the Spring API and the recommendation service. "
            "Inputs are hydrated by Spring; outputs are proposals and orderings with reasons."
        ),
        openapi_url="/internal/openapi.json",
        docs_url=None,
        redoc_url=None,
    )
    app.state.settings = settings
    app.state.policy = policy
    app.state.service_version = __version__
    app.state.explanation_service = ExplanationService(llm_port(settings))
    install_handlers(app)
    app.add_middleware(RequestIdMiddleware)
    app.include_router(system_router, prefix="/internal/v1")
    app.include_router(feed_router, prefix="/internal/v1")
    app.include_router(items_router, prefix="/internal/v1")
    app.include_router(slots_router, prefix="/internal/v1")
    app.include_router(related_router, prefix="/internal/v1")
    app.include_router(explanations_router, prefix="/internal/v1")
    return app


def run() -> None:
    settings = Settings()
    uvicorn.run(create_app(settings), host=settings.bind_host, port=settings.port, log_level="info")


if __name__ == "__main__":
    run()
