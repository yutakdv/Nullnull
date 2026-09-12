#!/usr/bin/env python3
"""Fail-closed judgement of what `infra:check` actually did.

`scripts/infra-check.mjs` cannot fail the build today: `infra/` is not scaffolded and BA-006 is
blocked, so a hard exit 1 would red the default branch for a gap nobody can close yet. It
therefore exits 0 - and that is exactly the shape this repository keeps catching, where a run
that did nothing is indistinguishable from a run that passed. `docker-integration` counted
`infra-plan` as green while its own output said "Not a passing check".

So the exit code stops being the answer. The script states its outcome as a single machine token
and this checker decides what that token means:

* `infra_check=pass`    - a real synth/diff ran and agreed. Counted as a pass.
* `infra_check=blocked` - nothing ran. Recorded as blocked; NEVER counted as a pass.
* anything else, including no token at all, an empty capture, or two different tokens - failure.

The last rule is the point. Rewriting infra-check.mjs to print nothing, or to print a friendly
sentence with no token, fails here instead of quietly reverting to the old behaviour.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# The token line may carry extra key=value context (reason=, owner=), so it is anchored at the
# start of a line and ends at whitespace rather than at end-of-line.
TOKEN = re.compile(r"^infra_check=(?P<status>[a-z-]+)(?=\s|$)", re.MULTILINE)
KNOWN = {"pass", "blocked"}
# A blocked run is allowed to exist but must name who is expected to close it, so the gap stays
# attached to a work item instead of becoming permanent scenery.
OWNER = re.compile(r"^infra_check=blocked .*\bowner=(?P<owner>[A-Za-z0-9-]+)\b", re.MULTILINE)


def judge(text: str) -> tuple[str | None, list[str]]:
    errors: list[str] = []
    statuses = TOKEN.findall(text)
    if not statuses:
        errors.append(
            "infra:check produced no infra_check=<status> token; "
            "a run with no stated outcome is not a pass"
        )
        return None, errors
    if len(set(statuses)) > 1:
        errors.append(f"infra:check stated more than one outcome: {sorted(set(statuses))}")
        return None, errors
    status = statuses[0]
    if status not in KNOWN:
        errors.append(f"infra:check stated an unknown outcome {status!r}; known: {sorted(KNOWN)}")
        return None, errors
    if status == "blocked" and not OWNER.search(text):
        errors.append("infra_check=blocked must name owner=<work id> so the gap stays tracked")
        return None, errors
    return status, errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="captured stdout+stderr of the infra-plan run")
    arguments = parser.parse_args(argv)

    try:
        text = arguments.report.read_text(encoding="utf-8", errors="replace")
    except OSError as failure:
        print(f"infra_check=error cannot read {arguments.report}: {failure}", file=sys.stderr)
        return 1

    status, errors = judge(text)
    for error in errors:
        print(f"infra_check=error {error}", file=sys.stderr)
    if status is None:
        return 1

    # Printed on stdout so the integration run's own log carries the distinction, rather than it
    # living only in an exit code that reads the same either way.
    print(f"infra_check={status}")
    if status == "blocked":
        print("infra_check_counts_as_pass=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
