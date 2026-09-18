#!/usr/bin/env python3
"""BA-073-T3: the submission's feature and API lists must be what the release actually does.

The function PDF may list only what the final service implements and uses (CMP-SUB-008), and its
KTO API list must equal the operations the release actually called (CMP-KTO-006). Both lists are
typed by people. This compares them with what the release reports about itself:

* the ledger - the PDF's lists as data (JSON, format below). Typed by people; it is the claim.
* the readiness answer - GET /api/v1/demo/readiness saved at the release. It carries no release
  field, so this script cannot bind it to one; capturing it on the release being submitted is the
  operator's step in SUBMISSION_RUNBOOK.
* the KTO call inventory - `./gradlew ktoCallInventory` output for the same release. Only an
  output that says `counts_as_evidence=true` is accepted.

Every listed feature must be a P0 functional-inventory ID; a feature tied to a capability must have
that capability READY; the KTO operations must equal the inventory in both directions; every
`usedBy` must name a listed feature; the ledger and the inventory must name the same release.

Ledger format (draft, pending owner/FE agreement):

    {"submissionInventory": {
       "releaseVersion": "<release>",
       "features": [{"featureIds": ["FR-TRP-01"], "pdfLabel": "...", "capability": null}],
       "ktoOperations": [{"source": "KTO_KOR_SERVICE_2", "endpoint": "...",
                          "pdfLabel": "...", "usedBy": ["FR-PLC-01"]}]}}
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY_ROW = re.compile(r"^\| ((?:FR|NFR)-[A-Z0-9]+-\d+) \| (P\d) \|", re.M)
OPERATION_LINE = re.compile(r"^kto_operation source=(\S+) endpoint=(\S+) ", re.M)
HEADER_LINE = re.compile(r"^kto_inventory target=\S+ environment=\S+ release=(\S+)$", re.M)


def feature_priorities(functional_inventory: str) -> dict[str, str]:
    return dict(INVENTORY_ROW.findall(functional_inventory))


def judge(ledger: dict, readiness: dict, inventory: str, priorities: dict[str, str]) -> list[str]:
    """Every way the submission's lists differ from what the release reports, in a stable order."""
    errors: list[str] = []
    submission = ledger.get("submissionInventory")
    if not isinstance(submission, dict):
        return ["ledger has no submissionInventory object"]
    release = submission.get("releaseVersion")
    features = submission.get("features")
    operations = submission.get("ktoOperations")
    if not isinstance(release, str) or not release.strip():
        errors.append("ledger releaseVersion is missing")
    if not isinstance(features, list) or not features:
        errors.append("ledger lists no features")
        features = []
    if not isinstance(operations, list) or not operations:
        errors.append("ledger lists no KTO operations; CMP-KTO-001 requires at least one")
        operations = []

    statuses = {c.get("name"): c.get("status") for c in readiness.get("capabilities", []) if isinstance(c, dict)}
    listed: set[str] = set()
    for feature in features:
        ids = feature.get("featureIds") if isinstance(feature, dict) else None
        label = feature.get("pdfLabel", "?") if isinstance(feature, dict) else "?"
        if not isinstance(ids, list) or not ids:
            errors.append(f"feature {label!r} names no feature ID")
            continue
        for ident in ids:
            listed.add(ident)
            priority = priorities.get(ident)
            if priority is None:
                errors.append(f"feature {label!r}: {ident} is not in FUNCTIONAL_INVENTORY")
            elif priority != "P0":
                errors.append(f"feature {label!r}: {ident} is {priority}; the PDF lists only P0 (CMP-SUB-008)")
        capability = feature.get("capability")
        if capability is not None and statuses.get(capability) != "READY":
            errors.append(f"feature {label!r} depends on capability {capability!r}, which the release reports as "
                          f"{statuses.get(capability, 'absent')}; a feature that is not READY is not listed (CMP-SUB-008)")

    if "counts_as_evidence=true" not in inventory:
        errors.append("the KTO call inventory does not count as evidence (counts_as_evidence=true is required)")
    header = HEADER_LINE.search(inventory)
    if header is None:
        errors.append("the KTO call inventory has no header line")
    elif isinstance(release, str) and header.group(1) != release:
        errors.append(f"the ledger names release {release!r} but the inventory is for {header.group(1)!r}")
    called = set(OPERATION_LINE.findall(inventory))
    claimed = set()
    for operation in operations:
        if not isinstance(operation, dict):
            errors.append("a KTO operation entry is not an object")
            continue
        claimed.add((operation.get("source"), operation.get("endpoint")))
        for ident in operation.get("usedBy") or []:
            if ident not in listed:
                errors.append(f"KTO operation {operation.get('endpoint')!r} is used by {ident}, which no listed feature names")
        if not operation.get("usedBy"):
            errors.append(f"KTO operation {operation.get('endpoint')!r} names no feature that uses it")
    for source, endpoint in sorted(claimed - called):
        errors.append(f"the PDF lists {source}/{endpoint}, which the release never called usably (CMP-KTO-006)")
    for source, endpoint in sorted(called - claimed):
        errors.append(f"the release called {source}/{endpoint}, which the PDF does not list (CMP-KTO-006)")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--readiness", type=Path, required=True, help="saved GET /api/v1/demo/readiness body")
    parser.add_argument("--inventory", type=Path, required=True, help="saved ktoCallInventory output")
    parser.add_argument("--functional-inventory", type=Path, default=ROOT / "docs/product/FUNCTIONAL_INVENTORY.md")
    arguments = parser.parse_args(argv)
    try:
        ledger = json.loads(arguments.ledger.read_text(encoding="utf-8"))
        readiness = json.loads(arguments.readiness.read_text(encoding="utf-8"))
        inventory = arguments.inventory.read_text(encoding="utf-8")
        priorities = feature_priorities(arguments.functional_inventory.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        print(f"submission_inventory=error cannot read an input: {failure}", file=sys.stderr)
        return 1
    errors = judge(ledger if isinstance(ledger, dict) else {}, readiness if isinstance(readiness, dict) else {},
                   inventory, priorities)
    for error in errors:
        print(f"submission_inventory=error {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"submission_inventory=verified release={ledger['submissionInventory']['releaseVersion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
