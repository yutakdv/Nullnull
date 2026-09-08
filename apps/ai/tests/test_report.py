"""REC-CI-6: the merge gate itself, tested directly instead of only through the session hook.

`tests/conftest.py` fails the session on `EvaluationReport.failures()` and `scripts/check_evaluation_report.py`
re-checks the written artifact, so a gate that silently stopped reporting a reason - or an artifact
that stopped carrying one - would turn a red run green. Every case below is a way that could happen.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from pathlib import Path

import pytest

from nullnull_ai.domain.policy import load_default
from nullnull_ai.evaluation.report import (
    DEFAULT_REPORT_DIRECTORY,
    PARTIAL_RUN_ENV,
    REPORT_DIRECTORY_ENV,
    EvaluationReport,
    partial_run,
    report_directory,
)

MANIFEST = Path(__file__).parent / "recommendation" / "manifest.json"
FIXTURE_ID = "temporal-same-issue"
SAFETY_COUNTERS = ("hardViolations", "unsupportedComparisons", "unauthorizedMutations", "deterministicMismatches")


def _record(
    report: EvaluationReport,
    *,
    fixture_id: str = FIXTURE_ID,
    violations: Sequence[str] = (),
    unsupported: int = 0,
    mismatches: int = 0,
    expect_proposals: bool = True,
    got_proposals: bool = True,
    provenance_checked: int = 0,
    provenance_satisfied: int = 0,
) -> None:
    """One recorded fixture; the defaults are a clean one so each test states only its own defect."""
    report.record_fixture(
        fixture_id=fixture_id,
        outcome="PROPOSALS",
        expect_proposals=expect_proposals,
        got_proposals=got_proposals,
        violations=violations,
        unsupported=unsupported,
        mismatches=mismatches,
        provenance_checked=provenance_checked,
        provenance_satisfied=provenance_satisfied,
    )


def _unauthorized_mutation(report: EvaluationReport) -> None:
    """The one counter without a recorder: no code path in this service writes a trip."""
    report.unauthorized_mutations = 1


def test_a_clean_run_reports_no_reason_to_block_the_merge() -> None:
    report = EvaluationReport()
    _record(report)
    report.record_session(executed=1, failed=0, skipped=0)
    report.record_expected_fixtures([FIXTURE_ID])
    assert report.failures() == ()


def test_a_declared_fixture_that_never_ran_fails_the_gate() -> None:
    report = EvaluationReport()
    _record(report)
    report.record_expected_fixtures([FIXTURE_ID, "merged-slot-day-and-hour"])
    reasons = report.failures()
    assert len(reasons) == 1
    assert reasons[0].startswith("corpus incomplete: 1 of 2 declared ITEM fixtures did not run")
    assert "merged-slot-day-and-hour" in reasons[0]
    assert report.missing_fixture_ids() == ("merged-slot-day-and-hour",)


def test_a_partial_run_waives_the_completeness_check_only() -> None:
    report = EvaluationReport()
    _record(report)
    report.record_expected_fixtures([FIXTURE_ID, "merged-slot-day-and-hour"], partial=True)
    assert report.missing_fixture_ids() == ("merged-slot-day-and-hour",)
    assert report.failures() == ()
    _unauthorized_mutation(report)
    assert report.failures() == ("unauthorizedMutations=1 (gate is 0)",)


@pytest.mark.parametrize(
    ("failed", "skipped", "reason"),
    [
        (1, 0, "tests.failed=1 (gate is 0)"),
        (0, 1, "tests.skipped=1 (gate is 0; REC-CI-6 merge condition 3)"),
    ],
)
def test_a_failed_or_skipped_test_fails_the_gate(failed: int, skipped: int, reason: str) -> None:
    report = EvaluationReport()
    _record(report)
    report.record_session(executed=3, failed=failed, skipped=skipped)
    report.record_expected_fixtures([FIXTURE_ID])
    assert report.failures() == (reason,)


@pytest.mark.parametrize(
    ("name", "mutate"),
    [
        ("hardViolations", lambda report: _record(report, violations=("the place is closed on that date",))),
        ("unsupportedComparisons", lambda report: _record(report, unsupported=1)),
        ("deterministicMismatches", lambda report: _record(report, mismatches=1)),
        ("unauthorizedMutations", _unauthorized_mutation),
    ],
)
def test_a_non_zero_safety_counter_fails_the_gate(name: str, mutate: Callable[[EvaluationReport], None]) -> None:
    report = EvaluationReport()
    assert report.failures() == ()
    mutate(report)
    assert f"{name}=1 (gate is 0)" in report.failures()


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (
            lambda report: _record(report, expect_proposals=True, got_proposals=False),
            "positiveFixtureCoverage=0/1 (gate is 100%)",
        ),
        (
            lambda report: _record(report, provenance_checked=2, provenance_satisfied=1),
            "requiredProvenanceCoverage=1/2 (gate is 100%)",
        ),
    ],
)
def test_coverage_below_100_percent_fails_the_gate(mutate: Callable[[EvaluationReport], None], reason: str) -> None:
    report = EvaluationReport()
    mutate(report)
    assert report.failures() == (reason,)


def test_a_zero_denominator_is_never_reported_as_100_percent() -> None:
    """An empty result set must not pass as coverage; only the suite that owns the corpus can fail it."""
    report = EvaluationReport()
    assert report.positive_fixture_coverage().denominator == 0
    assert report.required_provenance_coverage().denominator == 0
    assert report.failures() == ()


def test_write_emits_the_keys_the_merge_gate_reads(tmp_path: Path) -> None:
    report = EvaluationReport()
    _record(report)
    report.record_session(executed=1, failed=0, skipped=0)
    report.record_expected_fixtures([FIXTURE_ID])
    path = report.write(tmp_path / "reports", MANIFEST, load_default())
    assert path == tmp_path / "reports" / "evaluation.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    assert document["corpus"] == {"expected": 1, "executed": 1, "missing": [], "partial": False}
    assert [document["safety"][name] for name in SAFETY_COUNTERS] == [0, 0, 0, 0]
    assert document["safety"]["failures"] == []
    assert document["safety"]["positiveFixtureCoverage"] == {"numerator": 1, "denominator": 1}
    assert document["tests"] == {"executed": 1, "failed": 0, "skipped": 0}
    assert document["fixtureResults"][0]["id"] == FIXTURE_ID
    assert (tmp_path / "reports" / "manifest.json").read_bytes() == MANIFEST.read_bytes()


def test_write_records_the_failures_of_a_red_run(tmp_path: Path) -> None:
    """The artifact is written even when the run is red, so the reasons have to survive into it."""
    report = EvaluationReport()
    # Four distinct counts, so a counter hardcoded to 0 - or written into the wrong key - goes red.
    _record(report, violations=("the place is closed on that date",), unsupported=2, mismatches=3)
    _unauthorized_mutation(report)
    report.record_session(executed=1, failed=1, skipped=0)
    report.record_expected_fixtures([FIXTURE_ID, "merged-slot-day-and-hour"], partial=True)
    document = json.loads(report.write(tmp_path, MANIFEST, load_default()).read_text(encoding="utf-8"))
    assert document["corpus"]["partial"] is True
    assert document["corpus"]["missing"] == ["merged-slot-day-and-hour"]
    assert [document["safety"][name] for name in SAFETY_COUNTERS] == [1, 2, 1, 3]
    assert document["safety"]["failures"] == [
        "hardViolations=1 (gate is 0)",
        "unsupportedComparisons=2 (gate is 0)",
        "unauthorizedMutations=1 (gate is 0)",
        "deterministicMismatches=3 (gate is 0)",
        "tests.failed=1 (gate is 0)",
    ]


@pytest.mark.parametrize(("value", "waived"), [("1", True), ("0", False), ("true", False), ("", False)])
def test_only_the_exact_partial_run_flag_waives_the_completeness_check(
    monkeypatch: pytest.MonkeyPatch, value: str, waived: bool
) -> None:
    monkeypatch.setenv(PARTIAL_RUN_ENV, value)
    assert partial_run() is waived


def test_partial_run_is_off_when_the_variable_is_absent(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(PARTIAL_RUN_ENV, raising=False)
    assert partial_run() is False


def test_the_report_directory_follows_the_environment(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.delenv(REPORT_DIRECTORY_ENV, raising=False)
    assert report_directory() == Path(DEFAULT_REPORT_DIRECTORY)
    monkeypatch.setenv(REPORT_DIRECTORY_ENV, str(tmp_path))
    assert report_directory() == tmp_path
