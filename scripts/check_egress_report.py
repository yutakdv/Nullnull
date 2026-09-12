#!/usr/bin/env python3
"""BA-004-T3: judge the outbound-network probe by what it stated, not by its exit code.

`egress-denied` curls an external host from the internal compose network and exits non-zero if it
succeeds. That exit code is real evidence while the probe is intact - and says nothing at all if
the command is ever changed to something that does not probe. The service already prints a verdict
token for exactly this reason; nothing was reading it.

This is the same shape as `check_infra_report.py`: capture the output, require the token, and
refuse anything else. A probe that ran and stated nothing is not a pass.

Usage: check_egress_report.py <probe-output-file>
"""

import sys
from pathlib import Path

DENIED = "outbound_network=denied"
# The probe prints this on stderr before exiting non-zero. If it ever reaches this checker, the
# run must fail loudly rather than be read as "no denial token, therefore inconclusive".
REACHABLE = "External network unexpectedly reachable"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: check_egress_report.py <probe-output>", file=sys.stderr)
        return 2
    path = Path(argv[1])
    if not path.exists():
        print(f"egress_check=missing file={path}", file=sys.stderr)
        return 1
    text = path.read_text(encoding="utf-8", errors="replace")
    if REACHABLE in text:
        print("egress probe reached the external network from the internal compose network",
              file=sys.stderr)
        return 1
    occurrences = text.count(DENIED)
    if occurrences == 0:
        print(f"egress probe stated no verdict; expected a {DENIED!r} line in {path}",
              file=sys.stderr)
        return 1
    if occurrences > 1:
        # Two verdicts mean the output was concatenated from more than one run, and the file no
        # longer says which one this build did.
        print(f"egress probe stated {occurrences} verdicts; {path} must hold exactly one",
              file=sys.stderr)
        return 1
    print("egress_check=denied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
