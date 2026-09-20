"""REC-ARCH-01 (Python side): the decision packages hold no clock, randomness, environment or I/O.

The Java plan enforces this with ArchUnit; here the same rule is an AST scan, so a violation fails
before the pipeline can produce a result that a rerun would not reproduce.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src" / "nullnull_ai"
PURE_PACKAGES = ("domain", "item", "slot", "related", "explain", "feed", "pipeline", "draft", "preference")

# Deliberately a second literal, not `set(PURE_PACKAGES)`: the packages that decide a result may
# never leave the scan, neither by being dropped from the tuple nor by being re-declared impure.
DECISION_PACKAGES = frozenset(
    {"domain", "item", "slot", "related", "explain", "feed", "pipeline", "draft", "preference"}
)

# Package -> why the purity scan does not cover it. Every directory under `src/nullnull_ai` is
# either scanned or listed here with its reason, so a new package cannot arrive unclassified.
IMPURE_PACKAGES = {
    "api": "the FastAPI transport boundary: request/response, app state, uuid4 request ids and "
    "datetime.now in the health routes",
    "evaluation": "reads fixture bytes from disk, reads the environment and stamps the wall clock into evaluation.json",
    "policy": "resource-only package shipping policy-v1.yaml, loaded through importlib.resources "
    "by nullnull_ai.domain.policy",
    "provider": "the model adapter: the one network call this service makes, and only when "
    "AI_PROVIDER names a provider. Exempt because it performs I/O, not because its answer is "
    "trusted - whatever it returns still passes explain.validator before anything renders it",
}

FORBIDDEN_MODULES = frozenset(
    {
        # Randomness. The clock has its own rules in FORBIDDEN_ATTRIBUTES below.
        "random",
        # Network, in every shape this service could reach for one. `urllib` is here because it is what
        # nullnull_ai.provider.openai actually uses: without it, moving that adapter into a decision
        # package would have passed this scan, and apps/ai/CLAUDE.md claimed the opposite.
        "socket",
        "ssl",
        "http",
        "urllib",
        "urllib3",
        "ftplib",
        "smtplib",
        "webbrowser",
        "requests",
        "httpx",
        "aiohttp",
        "openai",
        "anthropic",
        # Data stores and other processes.
        "sqlalchemy",
        "psycopg",
        "psycopg2",
        "pymysql",
        "redis",
        "boto3",
        "subprocess",
        "multiprocessing",
        # The filesystem. `importlib` is deliberately NOT here: nullnull_ai.domain.policy reads its
        # packaged policy YAML through importlib.resources, which is a resource shipped inside the
        # wheel, not a path this service chooses at runtime.
        #
        # `pathlib` is blocked at the import rather than at its write methods because `Path('x').read_text()`
        # is a call on a call, which `_dotted` cannot follow - the same reason `uuid` is allowed while
        # `uuid4` is named. A decision package has no filesystem, so it has no use for a path type either;
        # a package that needs one is a package that belongs in IMPURE_PACKAGES with a written reason.
        "pathlib",
        "shutil",
        "tempfile",
        "fileinput",
    }
)
FORBIDDEN_CALLS = frozenset({"open"})
"""Builtins, which arrive as a call rather than an import and so are invisible to the scan above."""
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
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in FORBIDDEN_CALLS:
            found.append(f"line {node.lineno}: {node.func.id}()")
    return found


def _import_target(node: ast.ImportFrom, module: Path) -> str:
    """The absolute module an `ImportFrom` names, resolving a relative one against its location."""
    level = node.level or 0
    package = ("nullnull_ai", *module.relative_to(SRC).parts[:-1])
    if level == 0:
        return node.module or ""
    base = package[: len(package) - (level - 1)]
    return ".".join((*base, node.module) if node.module else base)


def _boundary_reaches(tree: ast.AST, module: Path) -> list[str]:
    """Imports that take a decision package into one this file exempts in IMPURE_PACKAGES.

    `_violations` above asks whether a module performs I/O *itself*, and for
    `from nullnull_ai.provider.openai import OpenAiExplanationPort` the honest answer is no: the
    urllib call stays in the adapter, so nothing forbidden appears in the importer's own tree.

    **Measured on 2026-09-20, because the gap and the control look identical from outside.** That
    exact line added to `explain/service.py` left the whole suite green - 572 passed, red=0 - while
    `import urllib.request` in the same file turned
    `test_module_is_free_of_clocks_randomness_environment_and_io[explain/service.py]` red on its
    own. So the scan did cover the file, and what it had no rule for was a decision package
    *reaching* a boundary one.

    That is the half of BA-084-T6 the existing device does not reach. `DECISION_PACKAGES <=
    PURE_PACKAGES` plus the urllib rule makes the adapter impossible to MOVE into `explain`; being
    unreachable FROM `explain` is a different claim, and apps/ai/CLAUDE.md states the first where
    the card asks for the second.

    Relative imports are resolved although the tree has none today: a rule that reads only absolute
    paths is got round by a change of style rather than by an argument.
    """
    found: list[str] = []
    for node in ast.walk(tree):
        named: list[tuple[int, str]] = []
        if isinstance(node, ast.Import):
            named = [(node.lineno, alias.name) for alias in node.names]
        elif isinstance(node, ast.ImportFrom):
            named = [(node.lineno, _import_target(node, module))]
        for lineno, name in named:
            parts = name.split(".")
            if len(parts) >= 2 and parts[0] == "nullnull_ai" and parts[1] in IMPURE_PACKAGES:
                found.append(f"line {lineno}: {name}")
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


@pytest.mark.parametrize("module", _pure_modules(), ids=lambda path: str(path.relative_to(SRC)))
def test_module_does_not_reach_into_a_boundary_package(module: Path) -> None:
    """BA-084-T6, second clause: the adapter is not merely elsewhere, it is unreachable from here."""
    tree = ast.parse(module.read_text(encoding="utf-8"), filename=str(module))
    assert _boundary_reaches(tree, module) == [], (
        f"{module.relative_to(SRC)} must not import a package exempted in IMPURE_PACKAGES"
    )


@pytest.mark.parametrize(
    ("planted", "reported"),
    [
        ("from nullnull_ai.provider.openai import OpenAiExplanationPort", "nullnull_ai.provider.openai"),
        ("import nullnull_ai.provider.openai", "nullnull_ai.provider.openai"),
        ("from ..provider.openai import OpenAiExplanationPort", "nullnull_ai.provider.openai"),
        ("from nullnull_ai.api.schemas import Anything", "nullnull_ai.api.schemas"),
        ("from nullnull_ai.evaluation.report import Anything", "nullnull_ai.evaluation.report"),
    ],
    ids=["adapter from", "adapter import", "adapter relative", "transport", "evaluation"],
)
def test_the_boundary_rule_sees_a_decision_package_reaching_one(planted: str, reported: str) -> None:
    """The adapter case is the one that was measured as a hole; the others keep the rule general.

    Planted rather than derived from IMPURE_PACKAGES for the reason the I/O cases give above: a
    test that looped over that mapping would agree with whatever it happens to contain.
    """
    reaches = _boundary_reaches(ast.parse(planted), SRC / "explain" / "service.py")
    assert any(reported in message for message in reaches), f"{planted!r} must be reported as {reported!r}"


def test_the_boundary_rule_leaves_the_allowed_directions_alone() -> None:
    """A rule that also refused these would be traded for the one it replaced.

    Decision packages import each other, and the policy YAML is reached as a packaged *resource*
    through a string - which is why `nullnull_ai.domain.policy` can load it without importing the
    `policy` package, and why a rule about imports must not claim to have seen that access.
    """
    allowed = (
        "from nullnull_ai.explain.templates import render\n"
        "from nullnull_ai.domain.policy import load_default\n"
        "from .facts import ExplanationFacts\n"
        "from importlib import resources\n"
        "def f():\n"
        "    return resources.files('nullnull_ai.policy'), render, load_default, ExplanationFacts\n"
    )
    assert _boundary_reaches(ast.parse(allowed), SRC / "explain" / "service.py") == []


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


@pytest.mark.parametrize(
    ("planted", "reported"),
    [
        ("import urllib.request", "import urllib.request"),
        ("from urllib.request import urlopen", "from urllib.request import ..."),
        ("import socket", "import socket"),
        ("import subprocess", "import subprocess"),
        ("import httpx", "import httpx"),
        ("from pathlib import Path", "from pathlib import ..."),
        ("def f():\n    return open('/etc/hosts').read()\n", "open()"),
    ],
    ids=["urllib", "urllib from", "socket", "subprocess", "httpx", "pathlib", "open builtin"],
)
def test_the_scan_sees_io_and_not_only_randomness_and_the_clock(planted: str, reported: str) -> None:
    """Every line here was measured as a HOLE before it was a rule.

    On 2026-09-20 an audit said this scan had no rule that could see I/O. It was right: with
    `random` and `datetime.now` both firing as controls, a decision package could `import
    urllib.request`, `import socket`, `import subprocess`, call `open()` or write through `pathlib`
    and the scan reported nothing - five mutations, `red=0` each. `urllib` is the one that mattered:
    it is what `nullnull_ai.provider.openai` uses, so moving that adapter into `explain` would have
    passed, while apps/ai/CLAUDE.md claimed the AST scan would stop it.

    The cases are planted rather than derived from FORBIDDEN_MODULES on purpose. A test that looped
    over that set would agree with whatever the set happens to contain and could never report a gap
    in it - which is exactly the shape that let the gap live.
    """
    assert any(reported in message for message in _violations(ast.parse(planted))), (
        f"{planted!r} must be reported as {reported!r}"
    )


def test_the_scan_allows_the_datetime_time_type_and_the_domain_time_module() -> None:
    allowed = (
        "from datetime import UTC, date, datetime, time\n"
        "from nullnull_ai.domain.time import resolve\n"
        "def f(t: time) -> object:\n"
        "    return resolve(date(2026, 9, 12), t, None), datetime(2026, 9, 12, tzinfo=UTC)\n"
    )
    assert _violations(ast.parse(allowed)) == []
