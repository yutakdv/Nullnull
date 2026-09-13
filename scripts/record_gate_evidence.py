#!/usr/bin/env python3
"""Record a CI gate's verdict as collectable JUnit evidence, but only when the verdict is there.

Some acceptance criteria are not provable by a testcase. BA-004-T3 asks that outbound network
denial be reproduced in the real Compose run; what proves it is the egress-denied service printing
`outbound_network=denied`, judged by check_egress_report.py. check_test_reports.py reads JUnit and
nothing else, so that proof could never reach the aggregator and its card could never be promoted -
the same gap run_script_tests.py closed for the Python suite, one step further out.

The danger here is different from that one, and it is the reason this script refuses rather than
records by default. run_script_tests.py runs the tests; this only witnesses a file. If it wrote a
passing testcase whenever it was called, the name would say an acceptance ID was proven because a
line in a shell script executed - a rubber stamp, and the exact shape ("a check that passes without
proving") this repository keeps removing. So the required token must be present in the named report
or this exits non-zero, and the report it reads is the one the real gate produced.

It also never writes a failing testcase. A gate that failed has already stopped the wrapper; a
report claiming a failure would be evidence of a run that did not finish.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET

ACCEPTANCE = re.compile(r"^(?:BA-\d{3}-T\d+|REC-[A-Z]+-\d+)(?![A-Za-z0-9_-])")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="Directory to write gateChecks/ under")
    parser.add_argument("--report", type=Path, required=True, help="The gate's own output file")
    parser.add_argument("--require", required=True, help="Verdict token that must appear in it")
    parser.add_argument("--name", required=True,
                        help="Testcase name; must START with the acceptance ID it claims")
    arguments = parser.parse_args()

    if not ACCEPTANCE.match(arguments.name):
        print(f"gate-evidence error: name must start with an acceptance ID: {arguments.name!r}",
              file=sys.stderr)
        return 2
    try:
        text = arguments.report.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"gate-evidence error: cannot read {arguments.report}: {error}", file=sys.stderr)
        return 1
    if arguments.require not in text:
        print(f"gate-evidence error: {arguments.report} does not state {arguments.require!r}; "
              "refusing to record evidence for a verdict that was not given", file=sys.stderr)
        return 1

    suite = ET.Element("testsuite", {"name": "gateChecks", "tests": "1", "failures": "0",
                                     "errors": "0", "skipped": "0", "time": "0"})
    ET.SubElement(suite, "testcase", {"name": arguments.name, "classname": "gateChecks"})
    path = arguments.out / "gateChecks" / f"TEST-{arguments.name.split()[0]}.xml"
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)
    print(f"gate_evidence=recorded id={arguments.name.split()[0]} verdict={arguments.require}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
