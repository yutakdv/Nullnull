from __future__ import annotations

from nullnull_ai.contracts import CONTRACT_PATH, current_document, render


def test_checked_in_contract_matches_the_running_app() -> None:
    assert CONTRACT_PATH.exists(), "run: uv run python -m nullnull_ai.contracts export"
    assert CONTRACT_PATH.read_text(encoding="utf-8") == render(current_document())


def test_contract_exposes_the_v1_operations_only() -> None:
    document = current_document()
    assert set(document["paths"]) == {
        "/internal/v1/health/live",
        "/internal/v1/health/ready",
        "/internal/v1/policy",
        "/internal/v1/feed/rank",
    }
    framework_schemas = {"HTTPValidationError", "ValidationError"}
    for name, schema in document["components"]["schemas"].items():
        if name in framework_schemas:
            continue
        if schema.get("type") == "object":
            assert schema.get("additionalProperties") is False, name
