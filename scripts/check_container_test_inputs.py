#!/usr/bin/env python3
"""A file a test reads must be inside the image that runs the test.

The suites are run two ways. `api-quality` runs them on a GitHub runner that has the whole
checkout, so a test reading `../../docs/...` finds it. `docker-integration` runs the same suites
inside an image whose Dockerfile copies a deliberate ALLOWLIST of repository files into
`/workspace`. When a test starts reading a file nobody added to that list, the first run passes and
the second dies with `NoSuchFileException` - and the two runs disagree about the same commit.

This has now happened twice. The Dockerfile still carries the comment from the first time, about
`packages/contracts/fixtures`: "which is how the native api-quality run passed while this one could
not even find the directory." The second was `docs/data/SOURCE_CATALOG.md`, added by a test that
pins a traveller-facing label to the document that decided it - a good test, reading a file the
image did not carry.

So the allowlist and the set of paths the suites actually read are TWO DECLARATIONS that have to
agree, and nothing was comparing them. That is the same shape as the validation vocabulary declared
in three places: a mismatch is invisible until the one branch that needs it runs somewhere it was
never provided. The comparison is mechanical, so a machine should do it.

Note what this does NOT check: that the file's CONTENT is right, or that the test asserts anything
useful about it. Only that a path a suite will open at runtime is a path the image holds.

No acceptance ID leads this docstring. No card owns this check, and borrowing one is the shape
#195 describes. It runs because the gate runs it.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A repository path read at test time. The Gradle working directory is `apps/api`, so `../../x`
# means the repository's `x`. Only literals are found; a path assembled at runtime is out of scope
# and would need the test to fail loudly on its own, which is the existing convention.
READ_PATH = re.compile(r'"(\.\./\.\./[^"\s]+)"')

# `COPY <src>... <dest>` - the last token is the destination. `--from=` copies come from an earlier
# stage rather than the build context, so they carry nothing from the repository.
COPY_LINE = re.compile(r"^COPY\s+(?!--from=)(.+)$")
WORKDIR_LINE = re.compile(r"^WORKDIR\s+(\S+)")
STAGE_LINE = re.compile(r"^FROM\s+\S+(?:\s+AS\s+(\S+))?", re.IGNORECASE)

# The stages that run tests. The runtime stage holds only the jar and never runs a suite, so a path
# missing there is not a finding.
TEST_STAGES = ("build", "test")


def container_paths(dockerfile: Path) -> set[str]:
    """Every absolute container path the test-running stages copy in from the repository."""
    provided: set[str] = set()
    workdir = "/"
    stage: str | None = None
    for raw in dockerfile.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        stage_match = STAGE_LINE.match(line)
        if stage_match:
            stage = stage_match.group(1)
            # A stage built `FROM build` inherits the earlier WORKDIR; this Dockerfile's test stage
            # does exactly that, so the working directory is not reset here.
            continue
        workdir_match = WORKDIR_LINE.match(line)
        if workdir_match:
            workdir = workdir_match.group(1)
            continue
        copy_match = COPY_LINE.match(line)
        if copy_match and stage in TEST_STAGES:
            destination = copy_match.group(1).split()[-1]
            if not destination.startswith("/"):
                destination = workdir.rstrip("/") + "/" + destination.lstrip("./")
            provided.add(destination.rstrip("/"))
    return provided


def read_paths(roots: list[Path]) -> dict[str, list[str]]:
    """Container path -> the test sources that open it."""
    wanted: dict[str, list[str]] = {}
    for root in roots:
        for source in sorted(root.rglob("*.java")):
            for literal in READ_PATH.findall(source.read_text(encoding="utf-8")):
                # `apps/api/../../x` is the repository's `x`, which the image holds at `/workspace/x`.
                inside = "/workspace/" + literal.removeprefix("../../")
                wanted.setdefault(inside, []).append(str(source))
    return wanted


def is_provided(path: str, provided: set[str]) -> bool:
    """A path is provided when it was copied, or sits under a directory that was."""
    candidate = path
    while candidate not in ("", "/"):
        if candidate in provided:
            return True
        candidate = candidate.rsplit("/", 1)[0]
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dockerfile", type=Path, default=Path("apps/api/Dockerfile"))
    parser.add_argument("--source-root", type=Path, nargs="+",
                        default=[Path("apps/api/src")],
                        help="test source roots to scan")
    arguments = parser.parse_args()

    if not arguments.dockerfile.is_file():
        print(f"container_test_inputs=blocked no Dockerfile at {arguments.dockerfile}",
              file=sys.stderr)
        return 1

    missing_roots = [root for root in arguments.source_root if not root.is_dir()]
    if missing_roots:
        for root in missing_roots:
            print(f"container_test_inputs=blocked source root does not exist: {root}",
                  file=sys.stderr)
        return 1

    provided = container_paths(arguments.dockerfile)
    if not provided:
        # A Dockerfile whose test stages copy nothing means the scan compared against an empty set,
        # and everything would look missing or nothing would. Neither is a verdict.
        print("container_test_inputs=blocked the test stages copy nothing from the repository",
              file=sys.stderr)
        return 1

    wanted = read_paths(arguments.source_root)
    if not wanted:
        # The suites read at least the OpenAPI document today. Zero findings here means the scan
        # read no sources, not that the sources read nothing.
        print("container_test_inputs=blocked no test source opens a repository path",
              file=sys.stderr)
        return 1

    problems = []
    for path in sorted(wanted):
        if not is_provided(path, provided):
            readers = ", ".join(sorted({Path(name).name for name in wanted[path]}))
            problems.append(f"{path} is opened by {readers} but no COPY puts it in the image")

    if problems:
        for problem in problems:
            print(f"container_test_inputs=missing {problem}", file=sys.stderr)
        print("container_test_inputs=missing add a COPY to the Dockerfile's build stage."
              " api-quality will stay green without it; docker-integration will not.",
              file=sys.stderr)
        return 1

    print(f"container_test_inputs=provided paths={len(wanted)} copies={len(provided)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
