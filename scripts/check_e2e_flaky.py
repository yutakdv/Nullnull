#!/usr/bin/env python3
"""Record how many E2E tests only passed on a retry. Records; does not judge.

`playwright.config.ts` retries once in CI, and a test that fails then passes is written to JUnit as
a plain `<testcase>` with no `<failure>`, inside `<testsuites failures="0">`, with the run exiting
0 - measured with Playwright 1.56. `check_test_reports.py` requires `failures=0`, so that condition
is satisfied by a run that was red on its first attempt, and nothing anywhere keeps the fact. The
`line` reporter prints "1 flaky" to the gate log, and nothing parses it.

This reads the json reporter's output instead, where the same run carries `status: "flaky"`.

**It does not fail the build.** Turning flakiness into a gate failure without first knowing today's
count would stand up a required check with an unknown pass rate. The count is recorded first and
can be promoted to a requirement once a full run has reported one - the order `infra_check` and
`actual_call` used.

A missing or unreadable report prints `e2e_flaky=none`, never `e2e_flaky=0`: "nothing asked" and
"asked and the answer was zero" are different facts, and only the second is evidence.
"""

import argparse
import json
import sys
from pathlib import Path


def flaky_titles(report: Path) -> list[str] | None:
    """Titles that needed a retry, or None when the report cannot answer."""
    try:
        document = json.loads(report.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    titles: list[str] = []

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for spec in node.get("specs", []) or []:
                for test in spec.get("tests", []) or []:
                    if test.get("status") == "flaky":
                        titles.append(str(spec.get("title", "")).strip() or "<untitled>")
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(document)
    return titles


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="Playwright json reporter output")
    arguments = parser.parse_args(argv[1:])

    titles = flaky_titles(arguments.report)
    if titles is None:
        print(f"e2e_flaky=none report={arguments.report} (unreadable or absent)")
        return 0
    for title in sorted(titles):
        print(f"e2e_flaky_test={title}")
    print(f"e2e_flaky={len(titles)} report={arguments.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
