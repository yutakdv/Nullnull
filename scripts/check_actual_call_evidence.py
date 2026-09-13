#!/usr/bin/env python3
"""Fail-closed judgement of `CMP-KTO-003`: a release that never called the provider cannot ship.

`CMP-KTO-003` is an EXCLUSION row - using file data alone is not accepted as the required
OpenAPI usage - and the compliance matrix names its evidence as "a test that blocks a release
with no actual call from being deployed or submitted". That test did not exist. The requirement
was written down and nothing enforced it, which is the same shape as a check that passes while
proving nothing: here the check was simply absent, so every release satisfied it vacuously.

What this file refuses to do is read a human claim. The evidence ledger carries a status word per
row, and a gate that reads that word only proves someone typed it. So the input here is the report
the staging smoke writes - a machine artifact naming the call it made - and the rules below reject
every way that artifact can exist without meaning what it says:

* no report at all      - `actual_call=blocked`, recorded and NEVER counted as a pass. This is the
                          honest state until staging exists (BA-006), and it keeps the gap visible
                          on every run instead of letting silence read as success.
* a report with no verdict, an unknown verdict, or two verdicts - failure. A run that stated no
                          outcome is not a pass; that rule is `check_infra_report.py`'s and it
                          applies for the same reason.
* `environment` local or test - failure. This is the line the whole KTO evidence chain rests on:
                          a disposable local database proves the code path, not that the deployed
                          service called the provider. `BA-021-T3` asks for staging on purpose.
* `source` mock, replay, fixture or file - failure, and this one IS the requirement. An exclusion
                          row about file-only data must not be satisfiable by a file.
* zero accepted calls, or a call whose outcome is not OK - failure. A rejected call is evidence
                          that we reached the provider, not evidence that the service uses it.
* `--release` given and the report names a different one - failure. Evidence from an earlier
                          release is the easiest way for this gate to go green while the thing it
                          is guarding changed underneath it.

Use `--require-verified` on the deploy/submission path, where "no evidence yet" must stop the
release rather than be recorded.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

VERDICTS = {"verified"}
# Environments whose success says nothing about the deployed service.
LOCAL_ENVIRONMENTS = {"local", "test", "ci", "development"}
DEPLOYED_ENVIRONMENTS = {"staging", "production"}
# Sources that are exactly what CMP-KTO-003 excludes.
FILE_SOURCES = {"mock", "replay", "fixture", "file", "stub", "synthetic"}
REQUIRED_FIELDS = ("verdict", "environment", "source", "releaseId", "operation", "observedAt",
                   "ingestLogId", "calls")


def judge(report: dict, release: str | None) -> list[str]:
    """Every reason this report fails to prove an actual provider call, in report order."""
    errors: list[str] = []
    missing = [field for field in REQUIRED_FIELDS if field not in report]
    if missing:
        errors.append(f"report is missing required fields: {missing}")
        return errors

    verdict = report["verdict"]
    if verdict not in VERDICTS:
        errors.append(f"unknown verdict {verdict!r}; known: {sorted(VERDICTS)}")

    environment = str(report["environment"]).lower()
    if environment in LOCAL_ENVIRONMENTS:
        errors.append(
            f"environment {environment!r} is not a deployed environment; a local run proves the "
            "code path, not that the deployed service called the provider (BA-021-T3)")
    elif environment not in DEPLOYED_ENVIRONMENTS:
        errors.append(f"environment {environment!r} is not one of {sorted(DEPLOYED_ENVIRONMENTS)}")

    source = str(report["source"]).lower()
    if source in FILE_SOURCES:
        errors.append(
            f"source {source!r} is file data, which CMP-KTO-003 excludes from the required usage")

    for field in ("releaseId", "operation", "observedAt", "ingestLogId"):
        if not str(report[field]).strip():
            errors.append(f"{field} is empty; the report must name the run it came from")

    calls = report["calls"]
    if not isinstance(calls, list) or not calls:
        errors.append("calls must be a non-empty list; a report of zero calls is not evidence")
    else:
        accepted = [call for call in calls
                    if isinstance(call, dict) and str(call.get("outcome", "")).upper() == "OK"]
        if not accepted:
            errors.append(
                "no call has outcome OK; reaching the provider and being rejected is not usage")

    if release is not None and str(report["releaseId"]) != release:
        errors.append(
            f"report names release {report['releaseId']!r} but this run is {release!r}; "
            "evidence from an earlier release does not cover this one")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="actual-call report written by the staging smoke")
    parser.add_argument("--release", help="release identifier this run is gating")
    parser.add_argument("--require-verified", action="store_true",
                        help="absent evidence blocks instead of being recorded (deploy/submission)")
    arguments = parser.parse_args(argv)

    if not arguments.report.exists():
        if arguments.require_verified:
            print(f"actual_call=error no evidence at {arguments.report}; CMP-KTO-003 blocks this "
                  "release", file=sys.stderr)
            return 1
        # Blocked is the truthful state before staging exists, and it is stated rather than implied.
        print("actual_call=blocked owner=BA-021 reason=no-staging-evidence")
        print("actual_call_counts_as_pass=false")
        return 0

    try:
        report = json.loads(arguments.report.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        print(f"actual_call=error cannot read {arguments.report}: {failure}", file=sys.stderr)
        return 1
    if not isinstance(report, dict):
        print("actual_call=error report must be a JSON object", file=sys.stderr)
        return 1

    errors = judge(report, arguments.release)
    for error in errors:
        print(f"actual_call=error {error}", file=sys.stderr)
    if errors:
        return 1

    print(f"actual_call=verified release={report['releaseId']} "
          f"environment={report['environment']} operation={report['operation']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
