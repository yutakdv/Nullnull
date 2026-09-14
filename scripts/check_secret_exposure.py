#!/usr/bin/env python3
"""BA-006-T2: the build outputs must not carry the secrets the runtime is given.

The npm audit that `security-scan` exports answers a different question - which dependencies have
known vulnerabilities - and nothing in the gate has been asking whether a key ended up inside the
frontend bundle, a docker image layer or a log. Those are the three places a runtime secret
escapes to without anyone writing it down: `VITE_`-prefixed variables are inlined into the bundle
by design, `ARG`/`ENV` values survive in layer metadata, and a structured log that starts echoing a
header carries the key on every request.

The check looks for the ACTUAL values, not for patterns. A pattern scanner reports high-entropy
strings and is wrong in both directions: it flags a hash in a lockfile and it misses a short key.
Here the caller names the environment variables that hold secrets, this reads their values from the
environment, and the scan asks whether those exact bytes appear in the targets. That yields no
false positives and cannot miss a secret it was told about.

Nothing prints a secret. A finding names the VARIABLE and the file, never the value - a check that
leaks what it is guarding would be its own defect, and CI logs are read by more people than the
environment is.

A scan with nothing to look for, or nothing to look in, fails rather than passes. Both are the
shape this repository keeps meeting: `ajv test --invalid` exiting 0 on a glob that matched no
files, and a probe that stopped being a probe. An empty scan is not a clean one.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# Read as bytes: a bundle is UTF-8 but an image layer is a tar and a log may hold anything. Decoding
# first would make the scan depend on the file being text, which is exactly the file it must not skip.
CHUNK = 1 << 20

# Values this short are not secrets and would match everywhere. An empty or one-character variable is
# a variable that was not set, and treating it as a secret would fail every scan for the wrong reason.
MIN_SECRET_LENGTH = 8


def secret_values(names: list[str], environment: dict[str, str]) -> dict[str, bytes]:
    """The secrets to look for, keyed by variable name. Values never leave this process."""
    found: dict[str, bytes] = {}
    for name in names:
        raw = environment.get(name, "")
        if len(raw) >= MIN_SECRET_LENGTH:
            found[name] = raw.encode("utf-8")
    return found


def scan_file(path: Path, secrets: dict[str, bytes]) -> set[str]:
    """Variable names whose value appears in this file."""
    hits: set[str] = set()
    longest = max(len(value) for value in secrets.values())
    with path.open("rb") as handle:
        carry = b""
        while True:
            block = handle.read(CHUNK)
            if not block:
                break
            # Overlap by the longest secret so a value split across two reads is still found.
            window = carry + block
            for name, value in secrets.items():
                if name not in hits and value in window:
                    hits.add(name)
            carry = window[-longest:] if longest else b""
    return hits


def targets(paths: list[Path], problems: list[str]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if not path.exists():
            problems.append(f"scan target does not exist: {path}")
            continue
        if path.is_file():
            files.append(path)
            continue
        found = [child for child in path.rglob("*") if child.is_file()]
        if not found:
            problems.append(f"scan target holds no files: {path}")
        files.extend(found)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", nargs="+", type=Path,
                        help="files or directories to scan (bundle, exported layers, logs)")
    parser.add_argument("--secret-env", action="append", default=[], metavar="NAME",
                        help="environment variable holding a secret; repeatable")
    arguments = parser.parse_args()

    problems: list[str] = []
    secrets = secret_values(arguments.secret_env, dict(os.environ))
    if not secrets:
        # The caller asked for a scan and named nothing to find, or named variables that are unset.
        # Passing here would report "no secrets leaked" on the strength of having looked for none.
        problems.append(
            "no secret values to search for: "
            f"none of {sorted(arguments.secret_env) or ['(nothing named)']} "
            f"is set to at least {MIN_SECRET_LENGTH} characters")

    files = targets(arguments.target, problems)
    if not files:
        problems.append("no files to scan")

    if problems:
        for problem in problems:
            print(f"secret_exposure=blocked {problem}", file=sys.stderr)
        return 1

    leaked: dict[str, list[str]] = {}
    for path in files:
        for name in scan_file(path, secrets):
            leaked.setdefault(name, []).append(str(path))

    if leaked:
        for name, where in sorted(leaked.items()):
            # The variable name and the files. Never the value.
            print(f"secret_exposure=leaked variable={name} files={sorted(where)}", file=sys.stderr)
        return 1

    print(f"secret_exposure=clean variables={len(secrets)} files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
