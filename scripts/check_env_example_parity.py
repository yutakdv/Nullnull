#!/usr/bin/env python3
"""A variable an example file tells an operator to set must be a variable something reads.

docs/operations/ENVIRONMENT.md said, in prose, that CI compares the example files against the real
configuration binding to catch missing and obsolete variables. No such check existed and none ever
had; meanwhile SEOUL_API_KEY, SEOUL_BASE_URL, AI_API_KEY and AI_MODEL_ID sat in example files that
nothing in the repository reads. This is that check, so the sentence can name an artefact instead of
an intention.

`check_cited_tests.py` closed the same gap for a comment that names a CLASS, because a class name is
checkable. A sentence saying "CI verifies this" names nothing, so nothing could look at it - which is
why that sentence outlived the thing it described.

ONE DIRECTION ONLY: a key in an example file must have a binding. The reverse direction - names
documented in ENVIRONMENT.md that no code reads - was measured at 13+ plus five VITE_*, nearly all of
them legitimately ahead of their card. A check whose first run is twenty legitimate findings teaches
people to ignore the next real one.

BINDING IS LOOKED FOR REPOSITORY-WIDE, NOT PER APP. apps/api/.env.example carries AI_PROVIDER, which
only apps/ai reads (pydantic alias). The operator has one shell and one .env.local; whichever process
reads it is the one that binds. Scoping the search per app would report that as a finding and be
wrong.

No acceptance ID leads this docstring. No card owns this check, and borrowing one is the shape #195
is about. It runs because the gate runs it.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# A key line in a .env file: NAME=... . Leading export, comments and blanks are not keys.
KEY = re.compile(r"^(?:export\s+)?([A-Z_][A-Z0-9_]*)=")

# Directories that hold no configuration binding and would dominate the walk.
#
# TEST TREES ARE EXCLUDED, AND THAT IS THE LOAD-BEARING PART. Evidence that a variable is real has
# to come from code that runs in production; a name appearing in a test fixture proves only that
# someone typed it. This check learned that about itself on its first run against the repository:
# it reported SEOUL_BASE_URL as bound, citing a placeholder that lives in
# scripts/tests/test_env_example_parity.py - the fixture this very check uses to prove that a stale
# allowlist row is caught. The guard tripped over its own test material and called a variable real
# that nothing reads.
#
# It is the fourth shape of false positive this repository has recorded in a checker (after the
# single quote inside SQL, the `" + "` concatenation, and jsonb's escaped double quote), and the
# first where the check read ITSELF. The three earlier ones were all "where does this string end";
# this one is "whose code is this".
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", "dist", ".venv", "__pycache__",
             ".uv-bootstrap", "target", ".idea", "coverage", "test-results", ".pytest_cache",
             "test", "tests", "integrationTest", "testFixtures"}

# Read anything textual. Being generous here costs a walk and buys correctness: a name this scan
# cannot see becomes a false finding, and a false finding is how a check loses its audience.
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".jar",
                 ".class", ".woff", ".woff2", ".ttf", ".otf", ".mp4", ".lock"}
MAX_BYTES = 2_000_000


def example_keys(path: Path) -> list[tuple[str, int]]:
    keys: list[tuple[str, int]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        found = KEY.match(stripped)
        if found:
            keys.append((found.group(1), number))
    return keys


def source_text(roots: list[Path]) -> str:
    chunks: list[str] = []
    for root in roots:
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            if any(part in SKIP_DIRS for part in path.parts):
                continue
            if path.suffix.lower() in SKIP_SUFFIXES:
                continue
            try:
                if path.stat().st_size > MAX_BYTES:
                    continue
                chunks.append(path.read_text(encoding="utf-8", errors="ignore"))
            except OSError:
                continue
    return "\n".join(chunks)


def dotted(name: str) -> str:
    """The relaxed-binding spelling Spring maps an environment variable to."""
    return name.lower().replace("_", ".")


def binding_of(name: str, text: str) -> str | None:
    """Why this name counts as read, or None. The four shapes a binding takes in this repository."""
    # 1. A Spring placeholder, in yaml or in a Java @Value. Half of the names that matter are only
    #    in Java, so scanning src/main/resources alone reports them all as unbound.
    if re.search(r"\$\{" + re.escape(name) + r"[:}]", text):
        return "placeholder"
    # 2. Relaxed binding: no placeholder anywhere, the dotted property is named directly.
    #    NULLNULL_TEST_DATABASE -> "nullnull.test.database" in a @ConditionalOnProperty.
    key = dotted(name)
    if re.search(r"""["']""" + re.escape(key) + r"""["']""", text):
        return "relaxed-binding"
    #    There is deliberately NO "falls under a declared @ConfigurationProperties prefix" rule.
    #    It was here, and a mutation removed it: a variable nothing reads was planted in
    #    apps/api/.env.example and the check stayed green, reporting it as
    #    "relaxed-binding under prefix nullnull". One of the extracted prefixes is the bare string
    #    `nullnull`, so EVERY name beginning with NULLNULL_ - which is most of the names in this
    #    repository - satisfied it. The check could not have failed for that whole family; it was an
    #    assertion that cannot fire, dressed as coverage.
    #    Removing it costs nothing, measured: of the 50 distinct keys in the two example files, the
    #    number that passed ONLY because of the prefix rule was zero. A prefix says which properties
    #    a class MAY bind, never which ones exist, so it was never evidence in the first place.
    # 3. pydantic-settings in apps/ai names the variable in an alias.
    if re.search(r"""alias\s*=\s*["']""" + re.escape(name) + r"""["']""", text):
        return "pydantic alias"
    # 4. Read straight from the process environment, bypassing Spring entirely.
    if re.search(r"""(?:getenv|environmentVariable|environ(?:\.get)?)\s*[(\[]\s*["']"""
                 + re.escape(name) + r"""["']""", text):
        return "process environment"
    return None


def parse_allow(path: Path) -> tuple[dict[str, str], list[str]]:
    """NAME plus a reason, one per line. The reason is the point, not decoration."""
    allowed: dict[str, str] = {}
    problems: list[str] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(None, 1)
        if len(parts) < 2 or not parts[1].strip():
            # One kind of row is temporary (a card will delete it) and one is permanent (the
            # framework consumes the name). Without the reason written down nobody can tell them
            # apart, so nobody ever deletes the temporary ones.
            problems.append(f"{path}:{number}: {parts[0]} has no reason; every entry states why")
            continue
        allowed[parts[0]] = parts[1].strip()
    return allowed, problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("example", nargs="+", type=Path, help=".env.example files to check")
    parser.add_argument("--source-root", action="append", type=Path, required=True,
                        dest="source_roots", help="tree to search for bindings (repeatable)")
    parser.add_argument("--allow", type=Path, help="names knowingly without a binding, with reasons")
    arguments = parser.parse_args()

    blocked: list[str] = []
    for path in arguments.example:
        if not path.is_file():
            blocked.append(f"example file does not exist: {path}")
    for root in arguments.source_roots:
        if not root.is_dir():
            blocked.append(f"source root does not exist: {root}")
    if blocked:
        for problem in blocked:
            print(f"env_example_parity=blocked {problem}", file=sys.stderr)
        return 1

    declared: dict[str, tuple[Path, int]] = {}
    total = 0
    for path in arguments.example:
        keys = example_keys(path)
        if not keys:
            # A scan that read nothing is not a clean one (AGENTS.md 등록 규칙 5).
            print(f"env_example_parity=blocked no keys in {path}", file=sys.stderr)
            return 1
        total += len(keys)
        for name, number in keys:
            declared.setdefault(name, (path, number))

    allowed: dict[str, str] = {}
    problems: list[str] = []
    if arguments.allow is not None:
        if not arguments.allow.is_file():
            print(f"env_example_parity=blocked allow file does not exist: {arguments.allow}",
                  file=sys.stderr)
            return 1
        allowed, problems = parse_allow(arguments.allow)

    text = source_text(arguments.source_roots)

    bound = 0
    for name, (path, number) in sorted(declared.items()):
        why = binding_of(name, text)
        if why:
            bound += 1
            if name in allowed:
                # The half of rot that matters most: the card shipped, the name is read now, and the
                # row saying "no reader yet" is a lie nobody is told about.
                problems.append(f"{path}:{number}: {name} is allowed as unbound but IS bound "
                                f"({why}); delete its row from {arguments.allow}")
            continue
        if name not in allowed:
            problems.append(f"{path}:{number}: {name} is declared here and nothing reads it")

    for name in sorted(set(allowed) - set(declared)):
        # The other half: the variable left the example file and its excuse outlived it.
        problems.append(f"{arguments.allow}: {name} is allowed but no example file declares it")

    if problems:
        for problem in problems:
            print(f"env_example_parity=unbound {problem}", file=sys.stderr)
        return 1

    print(f"env_example_parity=resolved keys={total} distinct={len(declared)} "
          f"bound={bound} allowed={len(allowed)} files={len(arguments.example)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
