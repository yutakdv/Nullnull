from __future__ import annotations

from collections import Counter
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from nullnull_ai.domain.policy import load_default
from nullnull_ai.evaluation.report import REPORT, report_directory
from nullnull_ai.main import create_app
from nullnull_ai.settings import Settings

MANIFEST = Path(__file__).parent / "recommendation" / "manifest.json"

_RESULTS: dict[str, str] = {}


@pytest.fixture(scope="session")
def client() -> Iterator[TestClient]:
    settings = Settings(NULLNULL_ENV="test", NULLNULL_CATALOG_VERSION="catalog-test-1")
    with TestClient(create_app(settings), raise_server_exceptions=False) as test_client:
        yield test_client


def pytest_runtest_logreport(report: pytest.TestReport) -> None:
    """One outcome per test: a failure in any phase wins, a skip beats the phases that passed."""
    previous = _RESULTS.get(report.nodeid)
    if report.outcome == "failed" or previous is None or (report.outcome == "skipped" and previous == "passed"):
        _RESULTS[report.nodeid] = report.outcome


def pytest_sessionfinish(session: pytest.Session, exitstatus: int) -> None:
    """REC-CI-6 fail-closed gate.

    `evaluation.json` is written even when the run is already red - a missing report is itself a
    merge blocker in `scripts/integration-test.sh`. A non-zero safety counter, or a positive-fixture
    or provenance coverage below 100% with a non-zero denominator, fails the session on its own even
    if every assertion happened to pass.
    """
    del exitstatus
    outcomes = Counter(_RESULTS.values())
    REPORT.record_session(executed=len(_RESULTS), failed=outcomes["failed"], skipped=outcomes["skipped"])
    path = REPORT.write(report_directory(), MANIFEST, load_default())
    failures = REPORT.failures()
    if failures:
        print(f"\nREC-CI-4 safety gate failed ({path}):")
        for reason in failures:
            print(f"  - {reason}")
        session.exitstatus = 1
