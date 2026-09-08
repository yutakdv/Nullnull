"""Export the internal OpenAPI document so the Spring client can be contract-tested against a frozen copy.

uv run python -m nullnull_ai.contracts export   # rewrites contracts/recommendation-internal-v1.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from nullnull_ai.main import create_app
from nullnull_ai.settings import Settings

CONTRACT_PATH = Path(__file__).resolve().parents[2] / "contracts" / "recommendation-internal-v1.json"


def current_document() -> dict[str, Any]:
    app = create_app(Settings(NULLNULL_ENV="test"))
    return app.openapi()


def render(document: dict[str, Any]) -> str:
    return json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv: list[str]) -> int:
    if argv[1:] != ["export"]:
        print("usage: python -m nullnull_ai.contracts export", file=sys.stderr)
        return 2
    CONTRACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT_PATH.write_text(render(current_document()), encoding="utf-8")
    print(f"wrote {CONTRACT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
