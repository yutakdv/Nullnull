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
# Written by run_script_tests.py. Separate from GRADLE_SUITES so a caller that passes only
# --junit-dir keeps failing on a missing Gradle suite rather than gaining an optional one.
SCRIPT_SUITES = ("scriptTests",)
# Written by record_gate_evidence.py, and only when the gate stated its verdict. Separate
# again: this evidence exists only inside the full Compose run, so a caller without it must
# not be told a suite is missing.
GATE_SUITES = ("gateChecks",)
# Playwright's JUnit reporter. An acceptance ID owned by FE - BA-040-T4 is the keyboard and
# focus E2E - is proven by a real test that this reader could not see, because it reads JUnit
# and Playwright was configured with the line reporter. Moving such an ID to the frontend plan
# would make it weaker, not visible: validate_frontend_plan.py compares a card's evidence to
# the card's own test IDs and never opens a report (#208). So the ID stays where it is and
# this reads the report instead.
E2E_SUITES = ("e2e",)
TEST_ID = re.compile(r"(?<![A-Za-z0-9_-])(?:BA-\d{3}-T\d+|REC-[A-Z]+-\d+)(?![A-Za-z0-9_-])")


def read_object(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path}: JSON root must be an object")
    return value


def fresh(path: Path, run_start: Path | None) -> None:
    if run_start is not None and path.stat().st_mtime_ns < run_start.stat().st_mtime_ns:
        raise ValueError(f"{path}: stale report predates run-start")


def read_junit(directory: Path, run_start: Path | None, errors: list[str],
               names: set[str] | None = None,
               suites: tuple[str, ...] = GRADLE_SUITES) -> dict[str, set[str]]:
    """Acceptance IDs per suite, and - when `names` is given - every testcase name seen.

    The names are what a `verified` card's `provenBy` is checked against. Extracted IDs cannot
    serve: they answer "is this ID somewhere", and the second pass is about WHICH testcase.
    """
    ids: dict[str, set[str]] = {}
    for suite in suites:
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
                    if names is not None:
                        names.add(name)
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


def check_proven_by(plan: dict, names: set[str], errors: list[str]) -> None:
    """Every testcase a `verified` card names in `provenBy` must actually exist in the reports.

    validate_backend_plan.py checks the SHAPE of provenBy - one entry per acceptance ID, each
    naming testcases that carry that ID. It cannot check that those testcases were run, because it
    never reads a JUnit report. This does, and the two together are what makes `verified` mean more
    than `integration-ready`: the card points at a specific testcase, and the report says that
    testcase ran and passed.

    A name is matched exactly. Substring matching was rejected: "BA-030-T1" would match every one of
    its twenty siblings, which is precisely the looseness the second pass exists to remove.
    """
    for task in plan.get("tasks", []):
        if not isinstance(task, dict) or task.get("status") != "verified":
            continue
        proven = task.get("evidence", {}).get("provenBy")
        if not isinstance(proven, dict):
            continue  # Shape is validate_backend_plan.py's to report; do not double-report it.
        for ident, claimed in sorted(proven.items()):
            for name in claimed if isinstance(claimed, list) else []:
                if name not in names:
                    errors.append(f"{task.get('id')}: provenBy[{ident}] names a testcase that no "
                                  f"report contains: {name!r}")


def reported_implemented_ids(path: Path, errors: list[str]) -> set[str] | None:
    """`implementedTestIds` out of the evaluation report, or None when it cannot be read.

    None rather than an empty set on purpose: an empty set would silently satisfy every
    "claimed and reported" comparison below, which is the failure mode this whole file is about.
    """
    report = read_object(path)
    value = report.get("implementedTestIds")
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        errors.append(f"{path}: implementedTestIds must be a list of strings")
        return None
    return set(value)


def check_manifest(manifest: dict, ids: dict[str, set[str]],
                   reported: set[str] | None, errors: list[str]) -> None:
    """Both halves of the manifest are held to evidence, not just the Gradle half.

    A `gradle:*` row is checked against the JUnit testcase names this run produced. A `pytest` row
    used to be SKIPPED, with a comment saying ai-quality and evaluation.json covered it - and
    neither did: `check_evaluation_report` only reads `corpus.partial` and `safety.failures`, so
    nothing outside the container ever looked at a test ID. A manifest could claim a pytest ID the
    corpus never exercises and every gate stayed green, which is the shape this checker exists to
    refuse.

    `reported` is `evaluation.json`'s own `implementedTestIds`. The two lists must agree exactly:
    an ID the manifest claims and the report does not is an unrun claim, and an ID the report
    carries and the manifest does not declare is coverage nobody registered (AGENTS.md rule 1).
    `missingTestIds` is deliberately NOT asserted empty - those are the REC IDs nothing implements
    yet, and that backlog is a fact, not a failure.
    """
    entries = manifest.get("implementedTestIds")
    if not isinstance(entries, list) or not entries:
        raise ValueError("manifest: non-empty implementedTestIds required")
    declared: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("suite"), str):
            raise ValueError("manifest: invalid entry/suite")
        suite = entry["suite"]
        ident = entry.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"REC-[A-Z]+-\d+", ident):
            raise ValueError("manifest: invalid REC ID")
        declared.add(ident)
        if suite == "pytest":
            if reported is None:
                errors.append(
                    f"manifest: {ident} is a pytest row, which needs --evaluation to be checked")
            elif ident not in reported:
                errors.append(
                    f"manifest: {ident} claims the pytest suite but evaluation.json does not "
                    "report it as implemented")
            continue
        if not suite.startswith("gradle:") or suite[7:] not in GRADLE_SUITES:
            raise ValueError(f"manifest: unknown suite {suite}")
        if ident not in ids.get(suite[7:], set()):
            errors.append(f"manifest: {ident} missing from {suite} testcase names")
    if reported is not None:
        for ident in sorted(reported - declared):
            errors.append(
                f"evaluation.json reports {ident} as implemented but the manifest does not "
                "declare it")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit-dir", type=Path, help="Root containing the four Gradle suite directories")
    parser.add_argument("--e2e-junit-dir", type=Path,
                        help="Root containing e2e/, written by Playwright's JUnit reporter. Only the "
                             "full Compose gate runs the browser suite.")
    parser.add_argument("--gate-junit-dir", type=Path,
                        help="Root containing gateChecks/, written by record_gate_evidence.py. Only "
                             "the full Compose gate produces it.")
    parser.add_argument("--script-junit-dir", type=Path,
                        help="Root containing scriptTests/, written by run_script_tests.py. Python "
                             "evidence counts the same as Java: an acceptance ID the build toolchain "
                             "or the CI wrapper proves is proven, and AGENTS.md's \u0027답은 아직 없다\u0027 "
                             "for that class was a gap in this reader, not in the tests.")
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
        names: set[str] = set()
        if args.junit_dir is not None:
            ids = read_junit(args.junit_dir, args.run_start, errors, names)
        if args.script_junit_dir is not None:
            ids.update(read_junit(args.script_junit_dir, args.run_start, errors, names,
                                  suites=SCRIPT_SUITES))
        if args.gate_junit_dir is not None:
            ids.update(read_junit(args.gate_junit_dir, args.run_start, errors, names,
                                  suites=GATE_SUITES))
        if args.e2e_junit_dir is not None:
            ids.update(read_junit(args.e2e_junit_dir, args.run_start, errors, names,
                                  suites=E2E_SUITES))
        if args.backend_plan is not None:
            plan = read_object(args.backend_plan)
            required = required_plan_ids(plan)
            observed = set().union(*ids.values())
            for ident in sorted(required - observed):
                errors.append(f"backend plan: {ident} missing from JUnit testcase names")
            # Only with reports in hand: without them `names` is empty and every claim would be
            # reported as missing, which would turn a plan-only run into noise.
            if args.junit_dir is not None:
                check_proven_by(plan, names, errors)
        reported: set[str] | None = None
        if args.evaluation is not None:
            fresh(args.evaluation, args.run_start)
            check_evaluation_report(args.evaluation, errors)
            reported = reported_implemented_ids(args.evaluation, errors)
        if args.manifest is not None:
            check_manifest(read_object(args.manifest), ids, reported, errors)
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
