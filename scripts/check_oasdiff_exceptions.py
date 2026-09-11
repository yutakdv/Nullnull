#!/usr/bin/env python3
"""Fail when an approved oasdiff exception no longer matches a real finding.

`test_oasdiff_exceptions.py` proves every ignore line is registered and justified.
It cannot prove the line still *does* anything: once a correction reaches main the
base moves, the message stops being reported, and the exemption silently becomes
permanent. This checker closes that gap by comparing the ignore list against the
unfiltered oasdiff output, so a stale exception fails the build until it is removed.

Usage: check_oasdiff_exceptions.py <oasdiff-output-file> <ignore-file>
"""

import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: check_oasdiff_exceptions.py <oasdiff-output> <ignore-file>", file=sys.stderr)
        return 2
    output_path, ignore_path = Path(argv[1]), Path(argv[2])
    if not ignore_path.exists():
        print("oasdiff_exceptions=none")
        return 0
    ignored = [line.strip() for line in ignore_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not ignored:
        print("oasdiff_exceptions=none")
        return 0
    # oasdiff wraps long findings, so compare on whitespace-collapsed text.
    output = " ".join(output_path.read_text(encoding="utf-8").split())
    stale = [line for line in ignored if " ".join(line.split()) not in output]
    for line in stale:
        print(f"stale oasdiff exception (no longer reported, remove it): {line}", file=sys.stderr)
    if stale:
        print(
            "Remove the line from docs/api/oasdiff-warn-ignore.txt and move its row to the "
            "expired section of docs/api/BREAKING_CHANGE_EXCEPTIONS.md.",
            file=sys.stderr,
        )
        return 1
    print(f"oasdiff_exceptions=live count={len(ignored)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
