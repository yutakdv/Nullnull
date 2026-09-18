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

Markdown is read too (#251), because the place that cites the most test classes is AGENTS.md's CI
table, and a typo there sends a reader grepping for a name that is not there - the failure this
check exists for. The files are the ones git tracks, so a local run reads what CI reads and an
untracked draft is not judged before it is committed; outside a git work tree (the negative
controls) every *.md under the root is read except node_modules. The whole file counts - inline
code, plain prose and fenced blocks alike: measured on 59 tracked files, the plain-prose mentions
were real citations too (29 of 30 resolved), and restricting to inline code would have let a typo
in plain text through. One directory is excluded: docs/superpowers/plans/. An implementation plan
names the classes its steps are going to create, so citing a class that does not exist yet is what
a plan is for. BACKEND_AI_PLAYBOOK.md is not excluded even though its doc_type is also "plan" - its
cards cite evidence that exists, 161 times.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

# A cited name: CamelCase ending in Test or IT, at least two segments so "IT" alone is not one.
# The char before the suffix must be lower-case or a digit, or every SCREAMING_CASE word ending
# in IT is a citation - COMMIT was the first false positive this produced.
# An `@` in front means the name is an ANNOTATION, not a class this repository was supposed to
# declare. `@SpringBootTest` is the one that actually broke the gate: seven files gained a comment
# explaining that each distinct `@SpringBootTest` configuration gets its own container, and this
# check reported all seven as citing a class nobody built. A check that cries wolf is worse than no
# check, because the next real finding reads as another false one - and this was the SECOND such
# false positive in one day from a check written to stop exactly that.
CITATION = re.compile(r"(?<![@\w])([A-Z][A-Za-z0-9]*[a-z0-9](?:Test|IT))\b")

# Comment bodies only. Code that REFERENCES a class cannot be wrong about it - the compiler already
# refused. Only prose can name something that is not there.
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")

# Names that are shaped like a citation but are not classes this repository owns.
ALLOWED_PROSE = {
    "IT",  # the bare word, filtered by the pattern already but kept explicit
    # Spring's own test annotations. They are shaped exactly like a citation and this repository
    # will never declare them, so a comment naming one is prose about the framework, not a claim
    # that a device exists here. Listed rather than pattern-matched: the list is short, and a
    # pattern wide enough to cover it would start swallowing real citations.
    "SpringBootTest", "DataJpaTest", "WebMvcTest", "JsonTest", "JdbcTest", "RestClientTest",
    "ParameterizedTest", "RepeatedTest", "TestcontainersTest",
}


def java_sources(roots: list[Path]) -> list[Path]:
    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.java")))
    return files


# Relative to the repository top. See the module docstring for why plans alone are left out.
PLANS = "docs/superpowers/plans/"


def markdown_sources(roots: list[Path]) -> list[Path]:
    """Tracked *.md under each root; every *.md but node_modules when a root is not in a git tree."""
    files: set[Path] = set()
    for root in roots:
        top = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=False)
        if top.returncode != 0:
            if "not a git repository" not in top.stderr:
                # git is there and failed: reading the file system instead would judge untracked
                # drafts locally and not in CI, so the two runs would disagree without saying so.
                raise RuntimeError(f"git could not list markdown under {root}: {top.stderr.strip()}")
            for path in sorted(root.rglob("*.md")):
                relative = path.relative_to(root).as_posix()
                if "node_modules" not in path.parts and not relative.startswith(PLANS):
                    files.add(path)
            continue
        listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--full-name", "--", "*.md"],
                                capture_output=True, text=True, check=True)
        base = Path(top.stdout.strip())
        files.update(base / name for name in listed.stdout.split("\0")
                     if name and not name.startswith(PLANS))
    return sorted(files)


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

    try:
        documents = markdown_sources(arguments.root)
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"cited_tests=blocked {error}", file=sys.stderr)
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
    markdown_citations = 0
    for path in documents:
        cited = set(CITATION.findall(path.read_text(encoding="utf-8")))
        for name in sorted(cited - ALLOWED_PROSE):
            markdown_citations += 1
            if name not in known:
                problems.append(f"{path}: names {name}, which this repository does not declare")

    if problems:
        for problem in problems:
            print(f"cited_tests=missing {problem}", file=sys.stderr)
        return 1

    print(f"cited_tests=resolved citations={citations} files={len(files)} types={len(known)} "
          f"markdown_citations={markdown_citations} markdown_files={len(documents)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
