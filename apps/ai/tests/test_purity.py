"""REC-ARCH-01 (Python side): the decision packages hold no clock, randomness, environment or I/O.

The Java plan enforces this with ArchUnit; here the same rule is an AST scan, so a violation fails
before the pipeline can produce a result that a rerun would not reproduce.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src" / "nullnull_ai"
PURE_PACKAGES = ("domain", "item", "slot", "related", "explain", "feed", "pipeline")

# Deliberately a second literal, not `set(PURE_PACKAGES)`: the packages that decide a result may
# never leave the scan, neither by being dropped from the tuple nor by being re-declared impure.
DECISION_PACKAGES = frozenset({"domain", "item", "slot", "related", "explain", "feed", "pipeline"})

# Package -> why the purity scan does not cover it. Every directory under `src/nullnull_ai` is
# either scanned or listed here with its reason, so a new package cannot arrive unclassified.
IMPURE_PACKAGES = {
    "api": "the FastAPI transport boundary: request/response, app state, uuid4 request ids and "
    "datetime.now in the health routes",
    "evaluation": "reads fixture bytes from disk, reads the environment and stamps the wall clock into evaluation.json",
    "policy": "resource-only package shipping policy-v1.yaml, loaded through importlib.resources "
    "by nullnull_ai.domain.policy",
}

FORBIDDEN_MODULES = frozenset({"random", "requests", "httpx", "sqlalchemy"})
FORBIDDEN_ATTRIBUTES = (
    ("time", "time"),
    ("datetime", "now"),
    ("datetime", "utcnow"),
    ("uuid", "uuid4"),
    ("os", "environ"),
)


def _dotted(node: ast.AST) -> tuple[str, ...]:
    """Attribute/Name chain as a tuple, e.g. datetime.datetime.now -> ('datetime', 'datetime', 'now')."""
    parts: list[str] = []
    current = node
    while isinstance(current, ast.Attribute):
        parts.append(current.attr)
        current = current.value
    if not isinstance(current, ast.Name):
        return ()
    parts.append(current.id)
    return tuple(reversed(parts))


def _violations(tree: ast.AST) -> list[str]:
    found: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split(".")[0] in FORBIDDEN_MODULES:
                    found.append(f"line {node.lineno}: import {alias.name}")
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if module.split(".")[0] in FORBIDDEN_MODULES:
                found.append(f"line {node.lineno}: from {module} import ...")
                continue
            for alias in node.names:
                if (module, alias.name) in FORBIDDEN_ATTRIBUTES:
                    found.append(f"line {node.lineno}: from {module} import {alias.name}")
        elif isinstance(node, ast.Attribute):
            path = _dotted(node)
            if len(path) >= 2 and path[-2:] in FORBIDDEN_ATTRIBUTES:
                found.append(f"line {node.lineno}: {'.'.join(path)}")
    return found


def _pure_modules() -> list[Path]:
    return sorted(path for package in PURE_PACKAGES for path in (SRC / package).rglob("*.py"))


def _source_packages() -> set[str]:
    """Every package directory that actually exists, independent of what this file declares.

    Only build artefacts are skipped: an underscore-prefixed package is still a package, so it has to be
    named in PURE_PACKAGES or IMPURE_PACKAGES instead of escaping the classification and the scan.
    """
    return {
        path.name
        for path in SRC.iterdir()
        if path.is_dir() and path.name != "__pycache__" and not path.name.startswith(".")
    }


def test_every_package_under_src_is_classified_as_pure_or_exempt() -> None:
    """The filesystem is the source of truth: an unlisted package fails instead of going unscanned."""
    unclassified = _source_packages() - set(PURE_PACKAGES) - set(IMPURE_PACKAGES)
    assert unclassified == set(), f"classify {sorted(unclassified)} in PURE_PACKAGES or IMPURE_PACKAGES"
    assert not set(PURE_PACKAGES) & set(IMPURE_PACKAGES), "a package cannot be both scanned and exempt"
    assert DECISION_PACKAGES <= set(PURE_PACKAGES), "a decision package may never be dropped from the scan"
    assert all(reason.strip() for reason in IMPURE_PACKAGES.values()), "every exemption needs a written reason"


def test_the_scan_actually_covers_the_decision_packages() -> None:
    """A package that stops contributing modules would otherwise leave the scan silently narrower."""
    modules = _pure_modules()
    assert len(modules) >= 5, "no pure modules were scanned; check PURE_PACKAGES"
    packages = {path.relative_to(SRC).parts[0] for path in modules}
    assert DECISION_PACKAGES <= packages, "every decision package must contribute a scanned module"
    assert packages == set(PURE_PACKAGES), "every package in PURE_PACKAGES must contribute a scanned module"


@pytest.mark.parametrize("module", _pure_modules(), ids=lambda path: str(path.relative_to(SRC)))
def test_module_is_free_of_clocks_randomness_environment_and_io(module: Path) -> None:
    tree = ast.parse(module.read_text(encoding="utf-8"), filename=str(module))
    assert _violations(tree) == [], f"{module.relative_to(SRC)} must stay deterministic and I/O free"


def test_the_scan_detects_a_planted_violation() -> None:
    planted = (
        "import random\n"
        "import sqlalchemy.orm\n"
        "from httpx import Client\n"
        "from datetime import datetime\n"
        "from os import environ\n"
        "from uuid import uuid4\n"
        "import datetime as dt\n"
        "import time\n"
        "def f():\n"
        "    return datetime.now(), dt.datetime.utcnow(), time.time(), environ, uuid4(), random, Client\n"
    )
    reported = _violations(ast.parse(planted))
    assert [message.split(": ", 1)[1] for message in reported] == [
        "import random",
        "import sqlalchemy.orm",
        "from httpx import ...",
        "from os import environ",
        "from uuid import uuid4",
        "datetime.now",
        "dt.datetime.utcnow",
        "time.time",
    ]


def test_the_scan_allows_the_datetime_time_type_and_the_domain_time_module() -> None:
    allowed = (
        "from datetime import UTC, date, datetime, time\n"
        "from nullnull_ai.domain.time import resolve\n"
        "def f(t: time) -> object:\n"
        "    return resolve(date(2026, 9, 12), t, None), datetime(2026, 9, 12, tzinfo=UTC)\n"
    )
    assert _violations(ast.parse(allowed)) == []
