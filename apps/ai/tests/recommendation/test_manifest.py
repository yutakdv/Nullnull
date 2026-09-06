"""REC-CI-4/6 foundation on the Python side: manifest integrity and the evaluation report.

Quality metrics stay NOT_EVALUATED until a judged corpus exists. No REC safety ID is claimed as
passing unless the manifest lists it as implemented and the referenced test really runs.
"""

from __future__ import annotations

import json
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest

from nullnull_ai.domain.policy import load_default, sha256_hex

MANIFEST = Path(__file__).with_name("manifest.json")
REQUIRED_FIELDS = (
    "fixtureVersion",
    "policyVersion",
    "policyResource",
    "policyHash",
    "evaluationMode",
    "dataOrigin",
    "fixedClock",
    "timezone",
    "randomSeeds",
    "schemaVersion",
    "normalizationVersion",
    "taxonomyVersion",
    "sourceRegistryVersion",
    "requiredTestIds",
    "implementedTestIds",
    "fixtures",
)


@pytest.fixture(scope="module")
def manifest() -> dict:  # type: ignore[type-arg]
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def test_manifest_declares_every_required_field(manifest: dict) -> None:  # type: ignore[type-arg]
    for field in REQUIRED_FIELDS:
        assert field in manifest, field
    assert manifest["evaluationMode"] == "RULES_P0"
    assert manifest["dataOrigin"] == "SYNTHETIC"
    assert manifest["randomSeeds"]
    datetime.fromisoformat(manifest["fixedClock"].replace("Z", "+00:00"))


def test_policy_hash_matches_shipped_policy(manifest: dict) -> None:  # type: ignore[type-arg]
    policy = load_default()
    assert manifest["policyVersion"] == policy.version
    assert manifest["policyHash"] == policy.hash, "update manifest.policyHash whenever policy-v1.yaml changes"


def test_implemented_ids_are_a_subset_of_required_ids(manifest: dict) -> None:  # type: ignore[type-arg]
    required = set(manifest["requiredTestIds"])
    implemented = {entry["id"] for entry in manifest["implementedTestIds"]}
    assert len(required) >= 30
    assert implemented <= required


def test_declared_fixtures_exist_with_matching_checksums(manifest: dict) -> None:  # type: ignore[type-arg]
    for fixture in manifest["fixtures"]:
        path = MANIFEST.parent / "fixtures" / fixture["file"]
        assert path.exists(), fixture["file"]
        assert sha256_hex(path.read_bytes()) == fixture["sha256"], fixture["file"]
        assert fixture["dataOrigin"] == "SYNTHETIC"


def test_evaluation_report_is_written(manifest: dict) -> None:  # type: ignore[type-arg]
    directory = Path(os.environ.get("NULLNULL_AI_REPORT_DIR", "build/reports/recommendation"))
    directory.mkdir(parents=True, exist_ok=True)
    policy = load_default()
    required = sorted(manifest["requiredTestIds"])
    implemented = sorted(entry["id"] for entry in manifest["implementedTestIds"])
    report = {
        "codeSha": os.environ.get("APP_GIT_SHA", "UNKNOWN"),
        "service": "apps/ai",
        "policyVersion": policy.version,
        "policyHash": policy.hash,
        "fixtureVersion": manifest["fixtureVersion"],
        "manifestSha256": sha256_hex(MANIFEST.read_bytes()),
        "evaluationMode": manifest["evaluationMode"],
        "fixedClock": manifest["fixedClock"],
        "randomSeeds": manifest["randomSeeds"],
        "finishedAt": datetime.now(UTC).isoformat(),
        "fixtureCount": len(manifest["fixtures"]),
        "requiredTestIds": required,
        "implementedTestIds": implemented,
        "missingTestIds": sorted(set(required) - set(implemented)),
        "safety": {
            "hardViolations": "NOT_EVALUATED",
            "unsupportedComparisons": "NOT_EVALUATED",
            "deterministicMismatches": "NOT_EVALUATED",
            "reason": "no recommendation fixtures implemented yet",
        },
        "quality": {"candidateRecallAt100": "NOT_EVALUATED", "ndcgAt10": "NOT_EVALUATED", "reason": "no judged corpus"},
    }
    (directory / "evaluation.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (directory / "manifest.json").write_bytes(MANIFEST.read_bytes())
    assert (directory / "evaluation.json").exists()
