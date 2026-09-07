"""REC-CI-6 evaluation report: safety counters collected during the run, written once at the end.

The report is the merge evidence, so it is written even when the suite is red - a missing
`evaluation.json` is itself a gate failure in `scripts/integration-test.sh`. Quality metrics stay
`NOT_EVALUATED` until a judged corpus exists; an empty result set can pass the safety counters, so
`positiveFixtureCoverage` is reported next to them and a zero denominator is never 100%.

A green artifact must also mean the corpus actually ran: the ids the manifest declares are compared
with the ids that were recorded, and any test that failed or was skipped is a failure reason too, so
a partial or skipped run cannot look like a passing one. `NULLNULL_AI_PARTIAL_RUN=1` waives only the
completeness check, for local runs that deliberately select a subset, and stamps `corpus.partial`
in the artifact; the safety counters keep gating.

This is the only module in the package allowed to read the clock or the environment: the start and
end timestamps and `APP_GIT_SHA` are report metadata, never inputs to a decision. The code SHA is
read from the environment (`docs/operations/ENVIRONMENT.md`) and never from a `git` subprocess, so
the report cannot silently describe a different tree than the one CI built.
"""

from __future__ import annotations

import json
import os
import platform
import sys
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from nullnull_ai.domain.policy import RecommendationPolicy, sha256_hex

REPORT_DIRECTORY_ENV = "NULLNULL_AI_REPORT_DIR"
DEFAULT_REPORT_DIRECTORY = "build/reports/recommendation"
CODE_SHA_ENV = "APP_GIT_SHA"
UNKNOWN_CODE_SHA = "unknown"
PARTIAL_RUN_ENV = "NULLNULL_AI_PARTIAL_RUN"


@dataclass(frozen=True, slots=True)
class FixtureResult:
    fixture_id: str
    outcome: str
    hard_violations: int
    unsupported_comparisons: int
    deterministic_mismatches: int
    expect_proposals: bool
    got_proposals: bool
    violations: tuple[str, ...]

    def to_json(self) -> dict[str, Any]:
        """The artifact keys are camelCase; the Python fields stay snake_case."""
        return {
            "id": self.fixture_id,
            "outcome": self.outcome,
            "hardViolations": self.hard_violations,
            "unsupportedComparisons": self.unsupported_comparisons,
            "deterministicMismatches": self.deterministic_mismatches,
            "expectProposals": self.expect_proposals,
            "gotProposals": self.got_proposals,
            "violations": list(self.violations),
        }


@dataclass(frozen=True, slots=True)
class Coverage:
    numerator: int
    denominator: int

    def satisfied(self) -> bool:
        """A zero denominator satisfies this trivially, so `failures()` only consults it above zero.

        Whether an empty denominator is itself a configuration error belongs to the suite that owns
        the corpus: `tests/recommendation/test_item_fixtures.py` fails at import when it is empty.
        """
        return self.numerator == self.denominator


@dataclass
class SessionCounts:
    executed: int = 0
    failed: int = 0
    skipped: int = 0


@dataclass
class EvaluationReport:
    started_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    hard_violations: int = 0
    unsupported_comparisons: int = 0
    # No code path in this service writes a trip, so this counter can only stay 0 here; REC-CI-4
    # still requires it in the artifact, and a future writing path must increment it.
    unauthorized_mutations: int = 0
    deterministic_mismatches: int = 0
    positive_expected: int = 0
    positive_satisfied: int = 0
    provenance_checked: int = 0
    provenance_satisfied: int = 0
    session: SessionCounts = field(default_factory=SessionCounts)
    fixtures: list[FixtureResult] = field(default_factory=list)
    expected_fixture_ids: tuple[str, ...] = ()
    partial: bool = False

    def record_fixture(
        self,
        *,
        fixture_id: str,
        outcome: str,
        expect_proposals: bool,
        got_proposals: bool,
        violations: Sequence[str],
        unsupported: int,
        mismatches: int,
        provenance_satisfied: int = 0,
        provenance_checked: int = 0,
    ) -> None:
        self.hard_violations += len(violations)
        self.unsupported_comparisons += unsupported
        self.deterministic_mismatches += mismatches
        self.provenance_satisfied += provenance_satisfied
        self.provenance_checked += provenance_checked
        if expect_proposals:
            self.positive_expected += 1
            if got_proposals:
                self.positive_satisfied += 1
        self.fixtures.append(
            FixtureResult(
                fixture_id=fixture_id,
                outcome=outcome,
                hard_violations=len(violations),
                unsupported_comparisons=unsupported,
                deterministic_mismatches=mismatches,
                expect_proposals=expect_proposals,
                got_proposals=got_proposals,
                violations=tuple(violations),
            )
        )

    def record_session(self, *, executed: int, failed: int, skipped: int) -> None:
        self.session = SessionCounts(executed, failed, skipped)

    def record_expected_fixtures(self, ids: Iterable[str], *, partial: bool = False) -> None:
        """The corpus the manifest requires this run to cover; `partial` waives only that check."""
        self.expected_fixture_ids = tuple(sorted(set(ids)))
        self.partial = partial

    def missing_fixture_ids(self) -> tuple[str, ...]:
        recorded = {result.fixture_id for result in self.fixtures}
        return tuple(id_ for id_ in self.expected_fixture_ids if id_ not in recorded)

    def positive_fixture_coverage(self) -> Coverage:
        return Coverage(self.positive_satisfied, self.positive_expected)

    def required_provenance_coverage(self) -> Coverage:
        return Coverage(self.provenance_satisfied, self.provenance_checked)

    def failures(self) -> tuple[str, ...]:
        """Every reason the run must not be merged; empty means the safety gate passed."""
        reasons: list[str] = []
        for name, value in (
            ("hardViolations", self.hard_violations),
            ("unsupportedComparisons", self.unsupported_comparisons),
            ("unauthorizedMutations", self.unauthorized_mutations),
            ("deterministicMismatches", self.deterministic_mismatches),
        ):
            if value != 0:
                reasons.append(f"{name}={value} (gate is 0)")
        for name, coverage in (
            ("positiveFixtureCoverage", self.positive_fixture_coverage()),
            ("requiredProvenanceCoverage", self.required_provenance_coverage()),
        ):
            if coverage.denominator > 0 and not coverage.satisfied():
                reasons.append(f"{name}={coverage.numerator}/{coverage.denominator} (gate is 100%)")
        if self.session.failed != 0:
            reasons.append(f"tests.failed={self.session.failed} (gate is 0)")
        if self.session.skipped != 0:
            reasons.append(f"tests.skipped={self.session.skipped} (gate is 0; REC-CI-6 merge condition 3)")
        missing = self.missing_fixture_ids()
        if missing and not self.partial:
            reasons.append(
                f"corpus incomplete: {len(missing)} of {len(self.expected_fixture_ids)} declared ITEM fixtures "
                f"did not run {list(missing)} (set NULLNULL_AI_PARTIAL_RUN=1 for a deliberate local subset)"
            )
        return tuple(reasons)

    def write(self, directory: Path, manifest_path: Path, policy: RecommendationPolicy) -> Path:
        """Writes `evaluation.json` plus a copy of the manifest and returns the report path."""
        manifest_bytes = manifest_path.read_bytes()
        manifest: Mapping[str, Any] = json.loads(manifest_bytes.decode("utf-8"))
        required = sorted(manifest["requiredTestIds"])
        implemented = sorted({entry["id"] for entry in manifest["implementedTestIds"]})
        document = {
            "codeSha": os.environ.get(CODE_SHA_ENV) or UNKNOWN_CODE_SHA,
            "service": manifest["service"],
            "policyVersion": policy.version,
            "policyHash": policy.hash,
            "fixtureVersion": manifest["fixtureVersion"],
            "manifestSha256": sha256_hex(manifest_bytes),
            "evaluationMode": manifest["evaluationMode"],
            "fixedClock": manifest["fixedClock"],
            "randomSeeds": manifest["randomSeeds"],
            "databaseMode": "NONE",
            "runner": {
                "python": platform.python_version(),
                "platform": platform.platform(),
                "executable": Path(sys.executable).name,
            },
            "startedAt": self.started_at.isoformat(),
            "finishedAt": datetime.now(UTC).isoformat(),
            "tests": asdict(self.session),
            "fixtureCount": len(manifest["fixtures"]),
            "corpus": {
                "expected": len(self.expected_fixture_ids),
                "executed": len(self.fixtures),
                "missing": list(self.missing_fixture_ids()),
                "partial": self.partial,
            },
            "fixtureResults": [result.to_json() for result in self.fixtures],
            "requiredTestIds": required,
            "implementedTestIds": implemented,
            "missingTestIds": sorted(set(required) - set(implemented)),
            "safety": {
                "hardViolations": self.hard_violations,
                "unsupportedComparisons": self.unsupported_comparisons,
                "unauthorizedMutations": self.unauthorized_mutations,
                "deterministicMismatches": self.deterministic_mismatches,
                "positiveFixtureCoverage": asdict(self.positive_fixture_coverage()),
                "requiredProvenanceCoverage": asdict(self.required_provenance_coverage()),
                "failures": list(self.failures()),
            },
            "quality": {
                "candidateRecallAt100": "NOT_EVALUATED",
                "ndcgAt10": "NOT_EVALUATED",
                "segmentNdcg": "NOT_EVALUATED",
                "reason": "no judged corpus; RULES_P0 evaluates safety only",
            },
        }
        directory.mkdir(parents=True, exist_ok=True)
        report_path = directory / "evaluation.json"
        report_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (directory / "manifest.json").write_bytes(manifest_bytes)
        return report_path


def report_directory() -> Path:
    return Path(os.environ.get(REPORT_DIRECTORY_ENV) or DEFAULT_REPORT_DIRECTORY)


def partial_run() -> bool:
    """True only for `NULLNULL_AI_PARTIAL_RUN=1`; any other value keeps the completeness gate on."""
    return os.environ.get(PARTIAL_RUN_ENV) == "1"


REPORT = EvaluationReport()
"""Process-wide collector; the pytest session writes it once in `pytest_sessionfinish`."""
