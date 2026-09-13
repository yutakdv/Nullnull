#!/usr/bin/env python3
"""Run scripts/tests and write JUnit XML, so Python evidence is visible to the aggregator.

check_test_reports.py reads JUnit XML from four Gradle suites and nothing else. A large share of
this repository's acceptance criteria are not provable in Java at all - the build toolchain pin, the
target-stack marker, the CI wrapper's own negative behaviour, the document validators - and their
tests live here, in Python. AGENTS.md recorded that gap as "답은 아직 없다": an ID proven only by a
Python test can never appear in the aggregator, so its card can never be promoted, so its issue can
never close. This is the answer.

Naming is the part that needs care. A Java test claims an acceptance ID through @DisplayName. The
equivalent here is the docstring, but a docstring that merely MENTIONS an ID in prose must not
become a claim - test_backend_plan.py has one that names BA-002-T3 while proving something else
entirely. So a docstring becomes the testcase name only when it STARTS with an acceptance ID;
anything else is reported under its qualified method name, where the regex finds nothing.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import time
import unittest
from xml.etree import ElementTree as ET

LEADING_ID = re.compile(r"^(?:BA-\d{3}-T\d+|REC-[A-Z]+-\d+)(?![A-Za-z0-9_-])")


def case_name(test: unittest.TestCase) -> str:
    """The docstring when it opens with an acceptance ID, else the qualified method name."""
    description = (test.shortDescription() or "").strip()
    if description and LEADING_ID.match(description):
        return description
    return f"{type(test).__name__}.{test._testMethodName}"


class NamingResult(unittest.TextTestResult):
    """Collects (name, outcome) so the XML says what ran, not only how many."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.collected: list[tuple[str, str | None]] = []

    def addSuccess(self, test):
        super().addSuccess(test)
        self.collected.append((case_name(test), None))

    def _record(self, test, tag, err):
        self.collected.append((case_name(test), tag))

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._record(test, "failure", err)

    def addError(self, test, err):
        super().addError(test, err)
        self._record(test, "error", err)

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._record(test, "skipped", reason)


def write_report(path: Path, collected: list[tuple[str, str | None]]) -> None:
    suite = ET.Element("testsuite", {
        "name": "scriptTests",
        "tests": str(len(collected)),
        "failures": str(sum(1 for _, tag in collected if tag == "failure")),
        "errors": str(sum(1 for _, tag in collected if tag == "error")),
        "skipped": str(sum(1 for _, tag in collected if tag == "skipped")),
        "time": "0",
    })
    for name, tag in collected:
        case = ET.SubElement(suite, "testcase", {"name": name, "classname": "scriptTests"})
        if tag is not None:
            ET.SubElement(case, tag)
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True,
                        help="Directory to write scriptTests/TEST-scriptTests.xml under")
    parser.add_argument("--start", type=Path,
                        help="Touched before running, so freshness checks have a marker")
    arguments = parser.parse_args()
    if arguments.start is not None:
        arguments.start.parent.mkdir(parents=True, exist_ok=True)
        arguments.start.write_text(str(time.time()))
    tests = unittest.TestLoader().discover(str(Path(__file__).resolve().parent / "tests"), "test_*.py")
    runner = unittest.TextTestRunner(resultclass=NamingResult, verbosity=1)
    result = runner.run(tests)
    collected = result.collected
    # A suite that runs nothing is not a check - the same rule AGENTS.md 5 applies to CI.
    if not collected:
        print("script-tests error: zero executed tests", file=sys.stderr)
        return 1
    write_report(arguments.out / "scriptTests" / "TEST-scriptTests.xml", collected)
    print(f"script_tests=ran count={len(collected)} "
          f"acceptance={sorted({m for name, _ in collected for m in LEADING_ID.findall(name)})}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
