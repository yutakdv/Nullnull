# syntax=docker/dockerfile:1
# Build context is the repository root. Stages: build, test, runtime. External FROM lines are digest-pinned.

FROM python:3.13-slim@sha256:9d2e5553305c7c7b0097999bb17187c69b921ccd6bc9d40e4bb5ebe652c00285 AS build
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/workspace/apps/ai/.venv
WORKDIR /workspace/apps/ai
RUN pip install --no-cache-dir uv==0.12.10
COPY apps/ai/pyproject.toml apps/ai/uv.lock apps/ai/README.md ./
# Resolve every dependency (runtime + dev) while the network is available; later stages run offline.
RUN uv sync --frozen --no-install-project
COPY apps/ai/src ./src
COPY apps/ai/tests ./tests
COPY apps/ai/contracts ./contracts
RUN uv sync --frozen

FROM build AS test
ENV NULLNULL_ENV=test NULLNULL_AI_REPORT_DIR=/workspace/apps/ai/build/reports/recommendation
CMD ["uv", "run", "--frozen", "--offline", "pytest"]

FROM python:3.13-slim@sha256:9d2e5553305c7c7b0097999bb17187c69b921ccd6bc9d40e4bb5ebe652c00285 AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PATH=/app/.venv/bin:$PATH
RUN groupadd --system nullnull && useradd --system --gid nullnull --no-create-home nullnull
WORKDIR /app
COPY --from=build /workspace/apps/ai/.venv /app/.venv
COPY --from=build /workspace/apps/ai/src /app/src
ENV PYTHONPATH=/app/src NULLNULL_AI_BIND_HOST=0.0.0.0 NULLNULL_AI_PORT=8090
USER nullnull
EXPOSE 8090
CMD ["python", "-m", "nullnull_ai.main"]
