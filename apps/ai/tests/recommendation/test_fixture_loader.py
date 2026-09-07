"""The fixture parser is part of the gate, so its strictness is tested, not assumed.

A loader that quietly defaults a missing key would let a fixture describe one scenario and evaluate
another; every case below is a way that could happen.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from nullnull_ai.evaluation.fixtures import (
    FixtureEntry,
    FixtureError,
    load_item_fixture,
    of_kind,
    read_entries,
    read_verified,
)

APP_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("manifest.json")
manifest: dict[str, Any] = json.loads(MANIFEST.read_text(encoding="utf-8"))
ENTRIES = read_entries(manifest)
ITEM_ENTRIES = of_kind(ENTRIES, "ITEM")
FEED_ENTRIES = of_kind(ENTRIES, "FEED")


def _entry(path: Path, *, id_: str = "temporal-same-issue") -> FixtureEntry:
    return FixtureEntry(
        id=id_,
        path=path.name,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        kind="ITEM",
        data_origin="SYNTHETIC",
        fixture_version=manifest["fixtureVersion"],
    )


def _mutated(tmp_path: Path, change: Callable[[dict[str, Any]], None]) -> Path:
    document = json.loads((APP_ROOT / ITEM_ENTRIES[0].path).read_text(encoding="utf-8"))
    change(document)
    path = tmp_path / "mutated.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    return path


@pytest.mark.parametrize("entry", ITEM_ENTRIES, ids=lambda entry: entry.id)
def test_every_declared_item_fixture_parses_and_agrees_with_the_manifest(entry: FixtureEntry) -> None:
    fixture = load_item_fixture(APP_ROOT, entry)
    # Read from disk rather than from `fixture.fixture_version`: the loader copies that value out of the
    # verified manifest entry, so comparing it to the manifest would only restate the loader.
    declared_version = json.loads((APP_ROOT / entry.path).read_text(encoding="utf-8"))["fixtureVersion"]
    assert fixture.id == entry.id
    assert declared_version == manifest["fixtureVersion"], f"{entry.path} declares fixtureVersion {declared_version}"
    assert fixture.policy_version == manifest["policyVersion"]
    assert fixture.timezone == manifest["timezone"]
    assert fixture.fixed_clock.isoformat() == manifest["fixedClock"].replace("Z", "+00:00")
    assert fixture.derivation.strip(), "every expected number needs a written hand calculation"


def test_a_checksum_mismatch_fails_instead_of_evaluating_edited_bytes(tmp_path: Path) -> None:
    path = _mutated(tmp_path, lambda document: document.__setitem__("note", "edited"))
    entry = _entry(path)
    tampered = FixtureEntry(entry.id, entry.path, "0" * 64, entry.kind, entry.data_origin, entry.fixture_version)
    with pytest.raises(FixtureError, match="does not match manifest"):
        load_item_fixture(tmp_path, tampered)


@pytest.mark.parametrize(
    ("name", "change", "message"),
    [
        ("unknown top-level key", lambda d: d.__setitem__("extra", 1), "unknown keys"),
        ("missing key", lambda d: d.pop("derivation"), "missing"),
        ("wrong data origin", lambda d: d.__setitem__("dataOrigin", "REAL"), "dataOrigin=SYNTHETIC"),
        ("wrong kind", lambda d: d.__setitem__("kind", "FEED"), "kind=ITEM"),
        (
            "float metric value",
            lambda d: d["input"]["candidates"][0].__setitem__("beforeValue", 80.0),
            "non-blank string",
        ),
        ("boolean trip version", lambda d: d["input"].__setitem__("tripVersion", True), "must be an integer"),
        ("unknown lock type", lambda d: d["input"]["locks"].append({"type": "WEATHER"}), "not a known lock type"),
        (
            "stray lock field",
            lambda d: d["input"]["locks"].append({"type": "MUST_VISIT", "date": "2026-09-12"}),
            "unknown keys",
        ),
        (
            "unknown opening value",
            lambda d: d["input"]["openingHours"].__setitem__("2026-09-13", "MAYBE"),
            "must be CLOSED",
        ),
        ("unknown outcome", lambda d: d["expected"].__setitem__("outcome", "MAYBE"), "outcome must be one of"),
        (
            "unknown resolution",
            lambda d: d["input"]["candidates"][0].__setitem__("resolution", "MINUTE"),
            "must be DAY or HOUR",
        ),
        ("naive fixed clock", lambda d: d.__setitem__("fixedClock", "2026-09-06T00:00:00"), "must carry a UTC offset"),
        ("unknown time zone", lambda d: d.__setitem__("timezone", "Mars/Olympus"), "IANA time zone"),
        (
            "zero rejection count",
            lambda d: d["expected"]["rejectedByReason"].__setitem__("NO_CHANGE", 0),
            "positive integer",
        ),
    ],
)
def test_a_malformed_fixture_is_rejected(
    tmp_path: Path, name: str, change: Callable[[dict[str, Any]], None], message: str
) -> None:
    path = _mutated(tmp_path, change)
    with pytest.raises(FixtureError, match=message):
        load_item_fixture(tmp_path, _entry(path))


def test_proposals_and_outcome_must_agree(tmp_path: Path) -> None:
    path = _mutated(tmp_path, lambda d: d["expected"].__setitem__("proposals", []))
    with pytest.raises(FixtureError, match="PROPOSALS needs proposals"):
        load_item_fixture(tmp_path, _entry(path))


def test_a_fixture_id_that_disagrees_with_the_manifest_is_rejected(tmp_path: Path) -> None:
    path = _mutated(tmp_path, lambda d: None)
    with pytest.raises(FixtureError, match="declares id"):
        load_item_fixture(tmp_path, _entry(path, id_="other"))


def test_a_fixture_version_that_disagrees_with_the_manifest_is_rejected(tmp_path: Path) -> None:
    path = _mutated(tmp_path, lambda d: d.__setitem__("fixtureVersion", "0.0.2"))
    with pytest.raises(FixtureError, match=f"must declare fixtureVersion={manifest['fixtureVersion']}"):
        load_item_fixture(tmp_path, _entry(path))


def test_a_feed_fixture_version_that_disagrees_with_the_manifest_is_rejected(tmp_path: Path) -> None:
    """The drift check sits in `read_verified`, so the FEED corpus inherits it as the ITEM loader does."""
    entry = FEED_ENTRIES[0]
    document = json.loads((APP_ROOT / entry.path).read_text(encoding="utf-8"))
    document["fixtureVersion"] = "0.0.2"
    path = tmp_path / "mutated-feed.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    drifted = FixtureEntry(
        id=entry.id,
        path=path.name,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        kind="FEED",
        data_origin="SYNTHETIC",
        fixture_version=entry.fixture_version,
    )
    with pytest.raises(FixtureError, match=f"must declare fixtureVersion={manifest['fixtureVersion']}"):
        read_verified(tmp_path, drifted)


def test_manifest_entries_reject_a_duplicate_id() -> None:
    duplicated = dict(manifest)
    duplicated["fixtures"] = [manifest["fixtures"][0], manifest["fixtures"][0]]
    with pytest.raises(FixtureError, match="must be unique"):
        read_entries(duplicated)
