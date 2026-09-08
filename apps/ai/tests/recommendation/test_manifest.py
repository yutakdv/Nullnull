"""REC-CI-4 manifest integrity on the Python side.

No REC safety ID is claimed as passing unless the manifest lists it as implemented and the
referenced test really runs. `evaluation.json` itself is written by `evaluation/report.py` from the
session hook in `tests/conftest.py`, so a red run still produces the artifact.
"""

from __future__ import annotations

import json
from datetime import datetime
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
    "service",
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


def test_every_implemented_test_id_points_at_an_existing_file(manifest: dict) -> None:  # type: ignore[type-arg]
    """A claimed ID is only evidence while the file it names is still there, under its own root."""
    app_root = MANIFEST.parents[2]
    repo_root = MANIFEST.parents[4]
    # An empty registry would pass this loop vacuously while `missingTestIds` claimed the whole
    # required set, so the floor is pinned the way `requiredTestIds` is pinned above.
    assert len(manifest["implementedTestIds"]) >= 11, "the implemented ID registry must not shrink"
    for entry in manifest["implementedTestIds"]:
        declared = entry["path"]
        paths = declared if isinstance(declared, list) else [declared]
        assert paths, entry["id"]
        root = app_root if entry["suite"] == "pytest" else repo_root
        for relative in paths:
            assert (root / relative).is_file(), f"{entry['id']} names a missing {relative}"


def test_declared_fixtures_exist_with_matching_checksums(manifest: dict) -> None:  # type: ignore[type-arg]
    assert manifest["fixtures"], "the corpus must not be empty; a zero denominator is a configuration error"
    for fixture in manifest["fixtures"]:
        path = MANIFEST.parents[2] / fixture["path"]
        assert path.exists(), fixture["path"]
        assert sha256_hex(path.read_bytes()) == fixture["sha256"], fixture["path"]
        assert fixture["dataOrigin"] == "SYNTHETIC"
        assert fixture["kind"] in {"ITEM", "FEED"}
