"""Strict parser for the REC-CI-4 synthetic fixture corpus (`tests/recommendation/fixtures/`).

Nothing is defaulted and no unknown key is tolerated: a fixture that drifts from this schema fails
loudly instead of silently evaluating a different scenario. The same files are read by the Java
parity tests (`LockChecksTest`, `ProposalRevalidatorTest`), so the schema below is a cross-language
contract - change it in both languages or not at all.

```jsonc
{
  "id": "temporal-same-issue",          // matches manifest.fixtures[].id
  "kind": "ITEM",                       // ITEM fixtures load through load_item_fixture
  "dataOrigin": "SYNTHETIC",            // required, and required to be exactly SYNTHETIC
  "fixtureVersion": "0.0.1",            // must equal manifest.fixtureVersion
  "policyVersion": "policy-v1",         // the policy the expected values were derived from
  "fixedClock": "2026-09-06T00:00:00Z", // RecommendationContext.evaluated_at for the run
  "timezone": "Asia/Seoul",             // corpus timezone; input.tripZone is the trip's own zone
  "note": "one line of scenario intent",
  "derivation": "hand calculation behind every expected number",
  "input": {
    "tripId": "<uuid>", "tripVersion": 7,
    "tripStart": "2026-09-12", "tripEnd": "2026-09-14", "tripZone": "Asia/Seoul",
    "target": {"itemId": "<uuid>", "placeId": "<uuid>", "date": "2026-09-12",
               "startTime": "10:00"|null, "durationMinutes": 90|null, "position": 1},
    "locks": [{"type": "MUST_VISIT"}, {"type": "DATE", "date": "2026-09-12"},
              {"type": "TIME", "startTime": "10:00", "toleranceMinutes": 180},
              {"type": "RESERVATION", "date": "2026-09-12", "startTime": "10:00",
               "endTime": "11:30"|null}],
    "neighbours": [{"itemId": "<uuid>", "date": "2026-09-14", "position": 2,
                    "startTime": "14:00"|null, "durationMinutes": 60|null}],
    "openingHours": {"2026-09-12": {"open": "09:00", "close": "18:00"},
                     "2026-09-13": "CLOSED", "2026-09-14": "UNKNOWN"},
    "routeEvidence": "NONE"|"VERIFIED",
    "candidates": [{"placeId": "<uuid>",           // optional; defaults to the target place
                    "date": "2026-09-12", "time": "11:00"|null,
                    "resolution": "HOUR"|"DAY",
                    "beforeValue": "80", "afterValue": "60",   // decimal strings, never JSON floats
                    "metricCode": "KTO_RELATIVE_CONCENTRATION_INDEX",
                    "verdict": {"eligible": true, "reasonCode": "SAME_METRIC_AND_ISSUE"},
                    "beforeSnapshotId": "<uuid>", "afterSnapshotId": "<uuid>"}]
  },
  "expected": {
    "outcome": "PROPOSALS"|"LOCK_CONFLICT"|"ROUTE_UNAVAILABLE"|"DATA_INSUFFICIENT"|"NO_IMPROVEMENT",
    "expectProposals": true,            // REC-CI-4 positive fixture coverage
    "proposals": [{"date": "2026-09-12", "startTime": "12:00"|null,
                   "score": "0.140000", "improvement": "30", "changeCost": "0.500000"}],
    "rejectedByReason": {"IMPROVEMENT_BELOW_MINIMUM": 1}   // complete, not a subset
  }
}
```
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, time
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from nullnull_ai.domain.types import CandidateKey
from nullnull_ai.item.types import (
    Closed,
    ComparisonVerdict,
    DateLock,
    ForecastResolution,
    ItemLock,
    ItemOptimizationInput,
    MustVisitLock,
    NeighbourItem,
    OpeningWindow,
    OpenWindow,
    ReservationLock,
    RouteEvidence,
    TargetItem,
    TemporalCandidate,
    TimeLock,
    UnknownHours,
)

DATA_ORIGIN = "SYNTHETIC"
OUTCOMES = frozenset({"PROPOSALS", "LOCK_CONFLICT", "ROUTE_UNAVAILABLE", "DATA_INSUFFICIENT", "NO_IMPROVEMENT"})
KINDS = frozenset({"ITEM", "FEED"})


class FixtureError(ValueError):
    """Raised when a fixture file or its manifest entry violates the schema above."""


@dataclass(frozen=True, slots=True)
class FixtureEntry:
    """One `manifest.fixtures[]` row. `path` is relative to `apps/ai`."""

    id: str
    path: str
    sha256: str
    kind: str
    data_origin: str


@dataclass(frozen=True, slots=True)
class ExpectedProposal:
    date: date
    start_time: time | None
    score: str
    improvement: str
    change_cost: str


@dataclass(frozen=True, slots=True)
class Expected:
    outcome: str
    expect_proposals: bool
    proposals: tuple[ExpectedProposal, ...]
    rejected_by_reason: Mapping[str, int]


@dataclass(frozen=True, slots=True)
class ItemFixture:
    id: str
    path: str
    note: str
    derivation: str
    fixture_version: str
    policy_version: str
    fixed_clock: datetime
    timezone: str
    input: ItemOptimizationInput
    expected: Expected


def read_entries(manifest: Mapping[str, Any]) -> tuple[FixtureEntry, ...]:
    rows = manifest.get("fixtures")
    if not isinstance(rows, list):
        raise FixtureError("manifest.fixtures must be a list")
    entries: list[FixtureEntry] = []
    for index, row in enumerate(rows):
        node = _object(row, f"fixtures[{index}]", {"id", "path", "sha256", "kind", "dataOrigin"}, set())
        kind = _string(node, "kind", f"fixtures[{index}]")
        if kind not in KINDS:
            raise FixtureError(f"fixtures[{index}].kind must be one of {sorted(KINDS)}")
        origin = _string(node, "dataOrigin", f"fixtures[{index}]")
        if origin != DATA_ORIGIN:
            raise FixtureError(f"fixtures[{index}].dataOrigin must be {DATA_ORIGIN}")
        entries.append(
            FixtureEntry(
                id=_string(node, "id", f"fixtures[{index}]"),
                path=_string(node, "path", f"fixtures[{index}]"),
                sha256=_string(node, "sha256", f"fixtures[{index}]"),
                kind=kind,
                data_origin=origin,
            )
        )
    ids = [entry.id for entry in entries]
    if len(set(ids)) != len(ids):
        raise FixtureError("manifest.fixtures[].id must be unique")
    return tuple(entries)


def read_verified(root: Path, entry: FixtureEntry) -> dict[str, Any]:
    """Reads the fixture bytes and fails when they do not hash to the manifest checksum."""
    path = root / entry.path
    if not path.is_file():
        raise FixtureError(f"fixture missing: {entry.path}")
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != entry.sha256:
        raise FixtureError(f"{entry.path} sha256 {digest} does not match manifest {entry.sha256}")
    document = json.loads(raw.decode("utf-8"))
    if not isinstance(document, dict):
        raise FixtureError(f"{entry.path} must contain a JSON object")
    return document


def load_item_fixture(root: Path, entry: FixtureEntry) -> ItemFixture:
    if entry.kind != "ITEM":
        raise FixtureError(f"{entry.path} is a {entry.kind} fixture, not ITEM")
    document = read_verified(root, entry)
    node = _object(
        document,
        entry.path,
        {
            "id",
            "kind",
            "dataOrigin",
            "fixtureVersion",
            "policyVersion",
            "fixedClock",
            "timezone",
            "note",
            "derivation",
            "input",
            "expected",
        },
        set(),
    )
    if _string(node, "dataOrigin", entry.path) != DATA_ORIGIN:
        raise FixtureError(f"{entry.path} must declare dataOrigin={DATA_ORIGIN}")
    if _string(node, "kind", entry.path) != "ITEM":
        raise FixtureError(f"{entry.path} must declare kind=ITEM")
    identifier = _string(node, "id", entry.path)
    if identifier != entry.id:
        raise FixtureError(f"{entry.path} declares id {identifier} but the manifest says {entry.id}")
    return ItemFixture(
        id=identifier,
        path=entry.path,
        note=_string(node, "note", entry.path),
        derivation=_string(node, "derivation", entry.path),
        fixture_version=_string(node, "fixtureVersion", entry.path),
        policy_version=_string(node, "policyVersion", entry.path),
        fixed_clock=_instant(_string(node, "fixedClock", entry.path), f"{entry.path}.fixedClock"),
        timezone=_zone_name(_string(node, "timezone", entry.path), f"{entry.path}.timezone"),
        input=_input(node["input"], f"{entry.path}.input"),
        expected=_expected(node["expected"], f"{entry.path}.expected"),
    )


def _input(value: Any, path: str) -> ItemOptimizationInput:
    node = _object(
        value,
        path,
        {
            "tripId",
            "tripVersion",
            "tripStart",
            "tripEnd",
            "tripZone",
            "target",
            "locks",
            "neighbours",
            "openingHours",
            "routeEvidence",
            "candidates",
        },
        set(),
    )
    target = _target(node["target"], f"{path}.target")
    evidence = _string(node, "routeEvidence", path)
    if evidence not in {item.value for item in RouteEvidence}:
        raise FixtureError(f"{path}.routeEvidence must be NONE or VERIFIED")
    try:
        return ItemOptimizationInput(
            trip_id=_uuid(node, "tripId", path),
            trip_version=_int(node, "tripVersion", path),
            trip_start=_date(node, "tripStart", path),
            trip_end=_date(node, "tripEnd", path),
            trip_zone=ZoneInfo(_zone_name(_string(node, "tripZone", path), f"{path}.tripZone")),
            target=target,
            locks=tuple(_lock(item, f"{path}.locks[{index}]") for index, item in enumerate(_list(node, "locks", path))),
            neighbours=tuple(
                _neighbour(item, f"{path}.neighbours[{index}]")
                for index, item in enumerate(_list(node, "neighbours", path))
            ),
            opening_hours=_opening_hours(node["openingHours"], f"{path}.openingHours"),
            route_evidence=RouteEvidence(evidence),
            candidates=tuple(
                _candidate(item, target.place_id, f"{path}.candidates[{index}]")
                for index, item in enumerate(_list(node, "candidates", path))
            ),
        )
    except ValueError as error:
        if isinstance(error, FixtureError):
            raise
        raise FixtureError(f"{path} violates a domain rule: {error}") from error


def _target(value: Any, path: str) -> TargetItem:
    node = _object(value, path, {"itemId", "placeId", "date", "startTime", "durationMinutes", "position"}, set())
    try:
        return TargetItem(
            item_id=_uuid(node, "itemId", path),
            place_id=_uuid(node, "placeId", path),
            date=_date(node, "date", path),
            start_time=_optional_time(node, "startTime", path),
            duration_minutes=_optional_int(node, "durationMinutes", path),
            position=_int(node, "position", path),
        )
    except ValueError as error:
        raise FixtureError(f"{path} violates a domain rule: {error}") from error


def _lock(value: Any, path: str) -> ItemLock:
    kind = _string(_object(value, path, {"type"}, {"date", "startTime", "endTime", "toleranceMinutes"}), "type", path)
    try:
        match kind:
            case "MUST_VISIT":
                _object(value, path, {"type"}, set())
                return MustVisitLock()
            case "DATE":
                node = _object(value, path, {"type", "date"}, set())
                return DateLock(_date(node, "date", path))
            case "TIME":
                node = _object(value, path, {"type", "startTime", "toleranceMinutes"}, set())
                return TimeLock(_time(node, "startTime", path), _int(node, "toleranceMinutes", path))
            case "RESERVATION":
                node = _object(value, path, {"type", "date", "startTime", "endTime"}, set())
                return ReservationLock(
                    _date(node, "date", path), _time(node, "startTime", path), _optional_time(node, "endTime", path)
                )
            case _:
                raise FixtureError(f"{path}.type is not a known lock type: {kind}")
    except ValueError as error:
        if isinstance(error, FixtureError):
            raise
        raise FixtureError(f"{path} violates a domain rule: {error}") from error


def _neighbour(value: Any, path: str) -> NeighbourItem:
    node = _object(value, path, {"itemId", "date", "position", "startTime", "durationMinutes"}, set())
    return NeighbourItem(
        item_id=_uuid(node, "itemId", path),
        date=_date(node, "date", path),
        position=_int(node, "position", path),
        start_time=_optional_time(node, "startTime", path),
        duration_minutes=_optional_int(node, "durationMinutes", path),
    )


def _opening_hours(value: Any, path: str) -> dict[date, OpeningWindow]:
    if not isinstance(value, dict):
        raise FixtureError(f"{path} must be an object keyed by ISO date")
    hours: dict[date, OpeningWindow] = {}
    for key, window in value.items():
        day = _parse_date(key, f"{path} key")
        if day in hours:
            raise FixtureError(f"{path} repeats the date {key}")
        hours[day] = _window(window, f"{path}[{key}]")
    return hours


def _window(value: Any, path: str) -> OpeningWindow:
    if isinstance(value, str):
        match value:
            case "CLOSED":
                return Closed()
            case "UNKNOWN":
                return UnknownHours()
            case _:
                raise FixtureError(f"{path} must be CLOSED, UNKNOWN or an open window object")
    node = _object(value, path, {"open", "close"}, set())
    try:
        return OpenWindow(_time(node, "open", path), _time(node, "close", path))
    except ValueError as error:
        raise FixtureError(f"{path} violates a domain rule: {error}") from error


def _candidate(value: Any, default_place_id: UUID, path: str) -> TemporalCandidate:
    node = _object(
        value,
        path,
        {
            "date",
            "time",
            "resolution",
            "beforeValue",
            "afterValue",
            "metricCode",
            "verdict",
            "beforeSnapshotId",
            "afterSnapshotId",
        },
        {"placeId"},
    )
    resolution = _string(node, "resolution", path)
    if resolution not in {item.value for item in ForecastResolution}:
        raise FixtureError(f"{path}.resolution must be DAY or HOUR")
    verdict = _object(node["verdict"], f"{path}.verdict", {"eligible", "reasonCode"}, set())
    place_id = _uuid(node, "placeId", path) if "placeId" in node else default_place_id
    try:
        return TemporalCandidate(
            key=CandidateKey(place_id, _date(node, "date", path), _optional_time(node, "time", path)),
            resolution=ForecastResolution(resolution),
            before_value=_decimal(node, "beforeValue", path),
            after_value=_decimal(node, "afterValue", path),
            metric_code=_string(node, "metricCode", path),
            verdict=ComparisonVerdict(
                _bool(verdict, "eligible", f"{path}.verdict"), _string(verdict, "reasonCode", f"{path}.verdict")
            ),
            before_snapshot_id=_uuid(node, "beforeSnapshotId", path),
            after_snapshot_id=_uuid(node, "afterSnapshotId", path),
        )
    except ValueError as error:
        if isinstance(error, FixtureError):
            raise
        raise FixtureError(f"{path} violates a domain rule: {error}") from error


def _expected(value: Any, path: str) -> Expected:
    node = _object(value, path, {"outcome", "expectProposals", "proposals", "rejectedByReason"}, set())
    outcome = _string(node, "outcome", path)
    if outcome not in OUTCOMES:
        raise FixtureError(f"{path}.outcome must be one of {sorted(OUTCOMES)}")
    proposals = tuple(
        _expected_proposal(item, f"{path}.proposals[{index}]")
        for index, item in enumerate(_list(node, "proposals", path))
    )
    if (outcome == "PROPOSALS") != bool(proposals):
        raise FixtureError(f"{path}: PROPOSALS needs proposals and every other outcome needs none")
    rejected = node["rejectedByReason"]
    if not isinstance(rejected, dict):
        raise FixtureError(f"{path}.rejectedByReason must be an object")
    counts: dict[str, int] = {}
    for code, count in rejected.items():
        if not isinstance(code, str) or not code:
            raise FixtureError(f"{path}.rejectedByReason has a blank reason code")
        if isinstance(count, bool) or not isinstance(count, int) or count < 1:
            raise FixtureError(f"{path}.rejectedByReason.{code} must be a positive integer")
        counts[code] = count
    return Expected(outcome, _bool(node, "expectProposals", path), proposals, counts)


def _expected_proposal(value: Any, path: str) -> ExpectedProposal:
    node = _object(value, path, {"date", "startTime", "score", "improvement", "changeCost"}, set())
    return ExpectedProposal(
        date=_date(node, "date", path),
        start_time=_optional_time(node, "startTime", path),
        score=_decimal_string(node, "score", path),
        improvement=_decimal_string(node, "improvement", path),
        change_cost=_decimal_string(node, "changeCost", path),
    )


def _object(value: Any, path: str, required: set[str], optional: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise FixtureError(f"{path} must be a JSON object")
    keys = set(value)
    missing = sorted(required - keys)
    unknown = sorted(keys - required - optional)
    if missing:
        raise FixtureError(f"{path} is missing {missing}")
    if unknown:
        raise FixtureError(f"{path} has unknown keys {unknown}")
    return value


def _list(node: dict[str, Any], key: str, path: str) -> list[Any]:
    value = node[key]
    if not isinstance(value, list):
        raise FixtureError(f"{path}.{key} must be a list")
    return value


def _string(node: dict[str, Any], key: str, path: str) -> str:
    value = node.get(key)
    if not isinstance(value, str) or not value.strip():
        raise FixtureError(f"{path}.{key} must be a non-blank string")
    return value


def _bool(node: dict[str, Any], key: str, path: str) -> bool:
    value = node.get(key)
    if not isinstance(value, bool):
        raise FixtureError(f"{path}.{key} must be a boolean")
    return value


def _int(node: dict[str, Any], key: str, path: str) -> int:
    value = node.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise FixtureError(f"{path}.{key} must be an integer")
    return value


def _optional_int(node: dict[str, Any], key: str, path: str) -> int | None:
    return None if node.get(key) is None else _int(node, key, path)


def _uuid(node: dict[str, Any], key: str, path: str) -> UUID:
    try:
        return UUID(_string(node, key, path))
    except ValueError as error:
        raise FixtureError(f"{path}.{key} must be a UUID") from error


def _decimal(node: dict[str, Any], key: str, path: str) -> Decimal:
    """Decimals arrive as strings; a JSON float would silently lose the exact fixed-point value."""
    try:
        return Decimal(_string(node, key, path))
    except InvalidOperation as error:
        raise FixtureError(f"{path}.{key} must be a decimal string") from error


def _decimal_string(node: dict[str, Any], key: str, path: str) -> str:
    text = _string(node, key, path)
    try:
        Decimal(text)
    except InvalidOperation as error:
        raise FixtureError(f"{path}.{key} must be a decimal string") from error
    return text


def _date(node: dict[str, Any], key: str, path: str) -> date:
    return _parse_date(_string(node, key, path), f"{path}.{key}")


def _parse_date(text: str, path: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise FixtureError(f"{path} must be an ISO date") from error


def _time(node: dict[str, Any], key: str, path: str) -> time:
    try:
        return time.fromisoformat(_string(node, key, path))
    except ValueError as error:
        raise FixtureError(f"{path}.{key} must be an ISO local time") from error


def _optional_time(node: dict[str, Any], key: str, path: str) -> time | None:
    return None if node.get(key) is None else _time(node, key, path)


def _instant(text: str, path: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as error:
        raise FixtureError(f"{path} must be an ISO instant") from error
    if parsed.tzinfo is None:
        raise FixtureError(f"{path} must carry a UTC offset")
    return parsed.astimezone(UTC)


def _zone_name(name: str, path: str) -> str:
    try:
        ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise FixtureError(f"{path} must be an IANA time zone") from error
    return name


def of_kind(entries: Sequence[FixtureEntry], kind: str) -> tuple[FixtureEntry, ...]:
    if kind not in KINDS:
        raise FixtureError(f"unknown fixture kind {kind}")
    return tuple(entry for entry in entries if entry.kind == kind)
