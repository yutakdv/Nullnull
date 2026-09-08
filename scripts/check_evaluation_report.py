#!/usr/bin/env python3
"""Fail-closed re-check of the REC-CI-6 recommendation evaluation report."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def load_report(path: Path, errors: list[str]) -> dict[str, Any] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"Cannot read evaluation report {path}: {error}")
        return None
    if not isinstance(value, dict):
        errors.append(f"Evaluation report root must be an object: {path}")
        return None
    return value


def check_evaluation_report(path: Path, errors: list[str]) -> None:
    """The report is merge evidence only when the corpus ran whole and safety recorded nothing.

    `ai-quality` already failed the run inside the container, so this is defence in depth: an
    absent key, an unreadable file or a value that is not exactly the passing one is a failure,
    never a pass.
    """
    report = load_report(path, errors)
    if report is None:
        return

    corpus = report.get("corpus")
    if not isinstance(corpus, dict) or "partial" not in corpus:
        errors.append(f"Evaluation report has no corpus.partial: {path}")
    elif corpus["partial"] is not False:
        errors.append(
            "Evaluation report records a partial corpus: "
            f"corpus.partial={json.dumps(corpus['partial'], ensure_ascii=False)}"
        )

    safety = report.get("safety")
    if not isinstance(safety, dict) or "failures" not in safety:
        errors.append(f"Evaluation report has no safety.failures: {path}")
        return
    failures = safety["failures"]
    if not isinstance(failures, list):
        errors.append(
            "Evaluation report safety.failures must be a list: "
            f"{json.dumps(failures, ensure_ascii=False)}"
        )
    elif failures:
        errors.append(
            "Evaluation report records safety failures: "
            + "; ".join(str(failure) for failure in failures)
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "report",
        type=Path,
        help="Path to the recommendation evaluation.json collected from ai-quality.",
    )
    args = parser.parse_args()

    errors: list[str] = []
    check_evaluation_report(args.report, errors)

    if errors:
        for error in errors:
            print(f"evaluation-report error: {error}", file=sys.stderr)
        return 1

    print("evaluation_report=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
