#!/usr/bin/env python3
"""A comment that names a test must name one that exists.

`check_test_reports.py` already refuses a `verified` card whose `provenBy` points at a testcase
that is not in any report. Prose gets no such treatment, and prose is where the cheaper version of
the same lie lives: a javadoc saying "the two declarations are checked against each other by
OptimizationFailureVocabularyIT" reads exactly like a guarantee, and that class did not exist. It
was written two slices before anyone noticed, and nothing in the repository could have noticed -
the sentence is the only place the name appeared.

No acceptance ID leads this docstring on purpose: no card owns this check, and borrowing
one would be the shape #195 is about. It runs because the gate runs it, not because a card
claims it.

This is the mirror of a guard that cannot fire. There the device exists and never speaks; here the
device was never built and the comment speaks for it. The second is cheaper to create - one line -
and leaves the same residue, which is a belief that something is being checked.

What counts as a citation is deliberately narrow: an identifier ending in Test or IT, appearing in
a comment, that looks like a Java type (an initial capital, no dots). A prose word like "Testing"
is not one, and neither is a sentence about "integration tests" in general. The check asks only
whether a name shaped like a class is a class this repository has.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A cited name: CamelCase ending in Test or IT, at least two segments so "IT" alone is not one.
# The char before the suffix must be lower-case or a digit, or every SCREAMING_CASE word ending
# in IT is a citation - COMMIT was the first false positive this produced.
CITATION = re.compile(r"\b([A-Z][A-Za-z0-9]*[a-z0-9](?:Test|IT))\b")

# Comment bodies only. Code that REFERENCES a class cannot be wrong about it - the compiler already
# refused. Only prose can name something that is not there.
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")

# Names that are shaped like a citation but are not classes this repository owns.
ALLOWED_PROSE = {
    "IT",  # the bare word, filtered by the pattern already but kept explicit
}


def java_sources(roots: list[Path]) -> list[Path]:
    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.java")))
    return files


def declared_types(files: list[Path]) -> set[str]:
    """Every class/interface/record/enum this tree declares, by simple name."""
    declared: set[str] = set()
    pattern = re.compile(r"\b(?:class|interface|record|enum)\s+([A-Za-z_][A-Za-z0-9_]*)")
    for path in files:
        declared.update(pattern.findall(path.read_text(encoding="utf-8")))
    return declared


def comments(source: str) -> str:
    return "\n".join(BLOCK_COMMENT.findall(source) + LINE_COMMENT.findall(source))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="+", type=Path,
                        help="source roots to scan (main and every test source set)")
    arguments = parser.parse_args()

    missing_roots = [root for root in arguments.root if not root.is_dir()]
    if missing_roots:
        for root in missing_roots:
            print(f"cited_tests=blocked source root does not exist: {root}", file=sys.stderr)
        return 1

    files = java_sources(arguments.root)
    if not files:
        # A scan that read nothing is not a clean one.
        print("cited_tests=blocked no .java files under the given roots", file=sys.stderr)
        return 1

    known = declared_types(files)
    problems: list[str] = []
    citations = 0
    for path in files:
        cited = set(CITATION.findall(comments(path.read_text(encoding="utf-8"))))
        for name in sorted(cited - ALLOWED_PROSE):
            citations += 1
            if name not in known:
                problems.append(f"{path}: comment names {name}, which this repository does not declare")

    if problems:
        for problem in problems:
            print(f"cited_tests=missing {problem}", file=sys.stderr)
        return 1

    print(f"cited_tests=resolved citations={citations} files={len(files)} types={len(known)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
