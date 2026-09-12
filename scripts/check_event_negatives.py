#!/usr/bin/env python3
"""Judge the analytics-event negative suite: every fixture ran, and every one was rejected.

`ajv test --invalid` reports a file as "passed test" when the schema REJECTED it, which is what
these fixtures are for. What it will not tell you is that it ran nothing: a glob matching zero
files exits 0 with no output, so deleting the directory turns the check green. That is the same
shape as the 0-match holes `verify:ci` already guards, and it is why this judges the output
against the directory listing instead of trusting the exit code.

Usage: check_event_negatives.py <ajv-output-file> <fixture-dir>
"""

import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: check_event_negatives.py <ajv-output> <fixture-dir>", file=sys.stderr)
        return 2
    output_path, fixture_dir = Path(argv[1]), Path(argv[2])
    if not fixture_dir.is_dir():
        print(f"event_negatives=missing dir={fixture_dir}", file=sys.stderr)
        return 1
    fixtures = sorted(p.name for p in fixture_dir.glob("*.json"))
    if not fixtures:
        print(f"event_negatives=empty dir={fixture_dir} (a suite that runs nothing is not a check)",
              file=sys.stderr)
        return 1
    output = output_path.read_text(encoding="utf-8") if output_path.exists() else ""

    missing = [name for name in fixtures if f"{fixture_dir.name}/{name} passed test" not in output]
    # "failed test" means ajv ACCEPTED a batch the schema is supposed to reject.
    accepted = [name for name in fixtures if f"{fixture_dir.name}/{name} failed test" in output]
    for name in accepted:
        print(f"event negative fixture was ACCEPTED by the schema: {name}", file=sys.stderr)
    for name in missing:
        if name not in accepted:
            print(f"event negative fixture was never run: {name}", file=sys.stderr)
    if missing or accepted:
        return 1
    print(f"event_negatives=rejected count={len(fixtures)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
