# Nullnull recommendation service (`apps/ai`)

Python 3.13 + FastAPI service that computes recommendations (feed order, related places, candidate slots, ITEM proposals, explanations) over inputs hydrated by the Spring API. It owns the recommendation policy (`src/nullnull_ai/policy/policy-v1.yaml`) and the REC safety corpus. It never reads the database or an external provider and never mutates a trip; the Spring API keeps sessions, trips, locks, optimization runs, APPLY/REVERT transactions, KTO/crowd adapters and comparison eligibility.

Decision record: `docs/superpowers/plans/2026-09-07-recommendation-python-service.md` (D-REC-6, 2026-09-07). Structure follows the stage split of `xai-org/x-algorithm` (commit `902a06f`, structure only; no code or model is reused).

## Toolchain

| Tool | Value | Pinned in |
| --- | --- | --- |
| Python | 3.13 (local 3.13.13, image `python:3.13-slim` digest) | `.python-version`, `Dockerfile` |
| uv | 0.12.10 | `README.md` bootstrap, `Dockerfile` |
| FastAPI / pydantic | 0.141.1 / 2.13.5 | `pyproject.toml`, `uv.lock` |

## Run locally

```bash
cd apps/ai
python3.13 -m venv .uv-bootstrap && .uv-bootstrap/bin/pip install uv==0.12.10   # once
.uv-bootstrap/bin/uv sync --frozen                                                 # creates .venv from uv.lock
.uv-bootstrap/bin/uv run python -m nullnull_ai.main                               # http://127.0.0.1:8090/internal/v1/health/ready
```

## Verify

```bash
.uv-bootstrap/bin/uv run ruff check .
.uv-bootstrap/bin/uv run ruff format --check .
.uv-bootstrap/bin/uv run mypy
.uv-bootstrap/bin/uv run pytest            # writes build/reports/recommendation/evaluation.json
.uv-bootstrap/bin/uv run python -m nullnull_ai.contracts export   # after changing any endpoint/schema
```

## Internal contract v1

`contracts/recommendation-internal-v1.json` is the frozen OpenAPI document served at `/internal/openapi.json`. A test fails when the running app and the file differ. Operations: `GET /internal/v1/health/live`, `GET /internal/v1/health/ready`, `GET /internal/v1/policy`, `POST /internal/v1/feed/rank`. Requests carry only place/post identifiers, publish state and instants; owner ids, session tokens, hidden/saved state, raw itinerary text and coordinates never reach this service.

## Layout

```text
src/nullnull_ai/
  main.py            app factory, entry point
  settings.py        fail-loud runtime settings (NULLNULL_ENV, AI_PROVIDER=NONE, ...)
  api/               request-id middleware, Problem handlers, system/feed routes, contract schemas
  domain/            immutable value types, strict policy loader (SHA-256 policyHash)
  pipeline/          stage protocols (source, hydrator, filter, scorer, selector) and deterministic runner
  feed/              P0 fixed-order feed pipeline (FixedOrderScorer, TopKSelector)
  policy/            policy-v1.yaml
tests/               pytest; tests/recommendation/manifest.json + evaluation.json writer
contracts/           frozen internal OpenAPI v1
```
