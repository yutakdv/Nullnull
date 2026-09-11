#!/usr/bin/env python3
"""BA-004: validate collected Gradle JUnit evidence, plan IDs and REC evidence.

Inputs are optional individually; plan/Gradle-manifest checks need JUnit evidence.
This checks reports, not the correctness of their assertions. CI must still propagate
command failures. --run-start rejects artifacts left over from an earlier run.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from check_evaluation_report import check_evaluation_report

GRADLE_SUITES = ("test", "integrationTest", "openapiContractTest", "recommendationTest")
TEST_ID = re.compile(r"(?<![A-Za-z0-9_-])(?:BA-\d{3}-T\d+|REC-[A-Z]+-\d+)(?![A-Za-z0-9_-])")


def read_object(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: JSON root must be an object")
    return value


def fresh(path: Path, run_start: Path | None) -> None:
    if run_start is not None and path.stat().st_mtime_ns < run_start.stat().st_mtime_ns:
        raise ValueError(f"{path}: stale report predates run-start")


def read_junit(directory: Path, run_start: Path | None, errors: list[str]) -> dict[str, set[str]]:
    ids: dict[str, set[str]] = {}
    for suite in GRADLE_SUITES:
        found: set[str] = set()
        count = 0
        files = sorted((directory / suite).glob("*.xml"))
        if not files:
            errors.append(f"{suite}: missing JUnit XML reports in {directory / suite}")
        for path in files:
            try:
                fresh(path, run_start)
                root = ET.parse(path).getroot()
                if root.tag not in {"testsuite", "testsuites"}:
                    raise ValueError(f"{path}: invalid JUnit root {root.tag}")
                suites = list(root.iter("testsuite"))
                if not suites:
                    raise ValueError(f"{path}: no testsuite elements")
                for node in suites:
                    cases = list(node.iter("testcase"))
                    if not cases:
                        raise ValueError(f"{path}: empty testsuite")
                    for key in ("tests", "failures", "errors", "skipped"):
                        raw = node.get(key, "")
                        if not re.fullmatch(r"\d+", raw):
                            raise ValueError(f"{path}: missing/invalid {key} count")
                        expected = len(cases) if key == "tests" else 0
                        if int(raw) != expected:
                            raise ValueError(f"{path}: {key}={raw}, expected {expected}")
                # Inspect actual testcase children as well as summary counts. Logs,
                # suite names and properties cannot satisfy a missing acceptance ID.
                cases = list(root.iter("testcase"))
                if root.tag == "testsuites":
                    for key in ("tests", "failures", "errors", "skipped"):
                        raw = root.get(key)
                        if raw is not None:
                            expected = len(cases) if key == "tests" else 0
                            if not re.fullmatch(r"\d+", raw) or int(raw) != expected:
                                raise ValueError(f"{path}: aggregate {key}={raw}, expected {expected}")
                for case in cases:
                    name = case.get("name", "")
                    if not name.strip():
                        raise ValueError(f"{path}: testcase has no name")
                    for tag in ("failure", "error", "skipped", "flakyFailure", "rerunFailure", "flakyError", "rerunError"):
                        if case.find(tag) is not None:
                            raise ValueError(f"{path}: testcase {tag}: {name}")
                    found.update(TEST_ID.findall(name))
                count += len(cases)
            except (OSError, ValueError, ET.ParseError) as error:
                errors.append(str(error))
        if not count:
            errors.append(f"{suite}: zero executed testcases")
        ids[suite] = found
        print(f"{suite}: testcases={count}")
    return ids


def required_plan_ids(plan: dict) -> set[str]:
    tasks = plan.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("backend plan: non-empty tasks list required")
    result: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict) or not isinstance(task.get("status"), str):
            raise ValueError("backend plan: invalid task/status")
        if task["status"] not in {"integration-ready", "verified"}:
            continue
        tests = task.get("tests")
        if not isinstance(tests, list) or not tests:
            raise ValueError(f"{task.get('id')}: non-empty tests required")
        for test in tests:
            ident = test.get("id") if isinstance(test, dict) else None
            if not isinstance(ident, str) or not re.fullmatch(r"BA-\d{3}-T\d+", ident):
                raise ValueError("backend plan: invalid acceptance ID")
            result.add(ident)
    return result


def check_manifest(manifest: dict, ids: dict[str, set[str]], errors: list[str]) -> None:
    entries = manifest.get("implementedTestIds")
    if not isinstance(entries, list) or not entries:
        raise ValueError("manifest: non-empty implementedTestIds required")
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("suite"), str):
            raise ValueError("manifest: invalid entry/suite")
        suite = entry["suite"]
        ident = entry.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"REC-[A-Z]+-\d+", ident):
            raise ValueError("manifest: invalid REC ID")
        if suite == "pytest":
            continue  # pytest corpus coverage is checked by ai-quality and evaluation.json.
        if not suite.startswith("gradle:") or suite[7:] not in GRADLE_SUITES:
            raise ValueError(f"manifest: unknown suite {suite}")
        if ident not in ids.get(suite[7:], set()):
            errors.append(f"manifest: {ident} missing from {suite} testcase names")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit-dir", type=Path, help="Root containing the four Gradle suite directories")
    parser.add_argument("--evaluation", type=Path)
    parser.add_argument("--backend-plan", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--run-start", type=Path, help="File touched before the producing commands")
    args = parser.parse_args()
    if not any((args.junit_dir, args.evaluation, args.backend_plan, args.manifest)):
        parser.error("at least one evidence input is required")
    errors: list[str] = []
    ids: dict[str, set[str]] = {}
    try:
        if args.run_start is not None:
            args.run_start.stat()  # A missing start marker must not disable freshness checks.
        if args.junit_dir is not None:
            ids = read_junit(args.junit_dir, args.run_start, errors)
        if args.backend_plan is not None:
            required = required_plan_ids(read_object(args.backend_plan))
            observed = set().union(*ids.values())
            for ident in sorted(required - observed):
                errors.append(f"backend plan: {ident} missing from JUnit testcase names")
        if args.manifest is not None:
            check_manifest(read_object(args.manifest), ids, errors)
        if args.evaluation is not None:
            fresh(args.evaluation, args.run_start)
            check_evaluation_report(args.evaluation, errors)
    except (OSError, ValueError) as error:
        errors.append(str(error))
    for error in errors:
        print(f"test-report error: {error}", file=sys.stderr)
    if errors:
        return 1
    print("test_reports=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
