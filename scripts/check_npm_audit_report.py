#!/usr/bin/env python3
"""Fail-closed judgement of the npm audit report baked into the tooling image.

`npm audit` needs the registry's advisory endpoint, but `security-scan` runs on
`integration-internal` (`internal: true`) and cannot reach it. The report is therefore
produced at image build time, where the network exists, and judged here offline.

The build writes the report with `|| true` because `npm audit` exits non-zero as soon as it
finds anything. That collapses "the audit ran and found vulnerabilities" and "the audit never
produced a report" into the same exit code, so this checker has to tell them apart: an absent,
empty, truncated or wrongly shaped report is a failure, never a pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

# docs/api/README.md has no say here; `npm audit --audit-level=high` is what the gate promises,
# so high and critical are the blocking severities and the rest are recorded, not enforced.
BLOCKING_SEVERITIES = ("critical", "high")
COUNTED_SEVERITIES = ("critical", "high", "moderate", "low", "info")


def load_report(path: Path, errors: list[str]) -> dict[str, Any] | None:
    try:
        raw = path.read_bytes()
    except OSError as error:
        errors.append(f"Cannot read npm audit report {path}: {error}")
        return None
    # A registry failure during the build leaves the redirect target empty or half written.
    if not raw.strip():
        errors.append(f"npm audit report is empty: {path}")
        return None
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        errors.append(f"npm audit report is not valid JSON {path}: {error}")
        return None
    if not isinstance(value, dict):
        errors.append(f"npm audit report root must be an object: {path}")
        return None
    return value


def check_npm_audit_report(path: Path, errors: list[str]) -> None:
    """The report is evidence only when it carries a complete severity count that is all zero."""
    report = load_report(path, errors)
    if report is None:
        return

    metadata = report.get("metadata")
    if not isinstance(metadata, dict):
        errors.append(f"npm audit report has no metadata object: {path}")
        return

    counts = metadata.get("vulnerabilities")
    if not isinstance(counts, dict):
        errors.append(f"npm audit report has no metadata.vulnerabilities object: {path}")
        return

    # A missing severity key would otherwise read as "nothing found".
    missing = [name for name in COUNTED_SEVERITIES if name not in counts]
    if missing:
        errors.append(
            "npm audit report is missing severity counts: " + ", ".join(sorted(missing))
        )
        return

    for name in COUNTED_SEVERITIES:
        count = counts[name]
        # bool is an int subclass; `true` is not a count.
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            errors.append(
                f"npm audit report severity {name} must be a non-negative integer: "
                f"{json.dumps(count, ensure_ascii=False)}"
            )
            return

    blocking = [(name, counts[name]) for name in BLOCKING_SEVERITIES if counts[name] > 0]
    if blocking:
        errors.append(
            "npm audit reports blocking vulnerabilities: "
            + ", ".join(f"{name}={count}" for name, count in blocking)
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "report",
        type=Path,
        help="Path to the npm audit JSON report baked into the tooling image.",
    )
    args = parser.parse_args()

    errors: list[str] = []
    check_npm_audit_report(args.report, errors)

    if errors:
        for error in errors:
            print(f"npm-audit-report error: {error}", file=sys.stderr)
        return 1

    print("npm_audit_report=clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
