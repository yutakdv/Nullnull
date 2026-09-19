"""The submission's feature and API lists against what the release reports (check_submission_inventory.py).

Feature IDs come from the real FUNCTIONAL_INVENTORY, so a case that relies on "FR-TRP-01 is P0" fails loudly
the day the inventory changes rather than passing on a made-up table. Each refusal is its own case: a checker
that returns the first error would pass a test that only feeds it one fault.
"""

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import check_submission_inventory as checker  # noqa: E402

PRIORITIES = checker.feature_priorities((ROOT / "docs/product/FUNCTIONAL_INVENTORY.md").read_text(encoding="utf-8"))

LEDGER = {"submissionInventory": {
    "releaseVersion": "2026.09.21-1",
    "features": [
        {"featureIds": ["FR-TRP-01"], "pdfLabel": "여행 만들기", "capability": None},
        {"featureIds": ["FR-PLC-01"], "pdfLabel": "장소 찾기", "capability": None},
        {"featureIds": ["FR-OPT-01"], "pdfLabel": "일정 최적화", "capability": "optimization"},
    ],
    "ktoOperations": [
        {"source": "KTO_KOR_SERVICE_2", "endpoint": "KOR_SERVICE_2_DETAIL_COMMON",
         "pdfLabel": "국문 관광정보 서비스 / detailCommon2", "usedBy": ["FR-PLC-01"]},
    ],
}}
READINESS = {"overall": "DEGRADED", "checkedAt": "2026-09-21T01:00:00Z",
             "capabilities": [{"name": "optimization", "status": "READY"},
                              {"name": "live", "status": "UNAVAILABLE"}]}
INVENTORY = "\n".join([
    "kto_inventory target=postgresql://db.internal:5432/nullnull environment=staging release=2026.09.21-1",
    "kto_operation source=KTO_KOR_SERVICE_2 endpoint=KOR_SERVICE_2_DETAIL_COMMON calls=3"
    " first=2026-09-21T00:00:00Z last=2026-09-21T00:30:00Z",
    "kto_inventory_excluded rejected=0 replay=0",
    "kto_inventory operations=1 counts_as_evidence=true",
])


class SubmissionInventoryTests(unittest.TestCase):

    def setUp(self):
        for ident, priority in (("FR-TRP-01", "P0"), ("FR-PLC-01", "P0"), ("FR-OPT-01", "P0"), ("FR-OPT-02", "P1")):
            self.assertEqual(PRIORITIES.get(ident), priority, f"the specimen {ident} must be {priority}")

    def errors(self, ledger=None, readiness=None, inventory=None):
        return checker.judge(LEDGER if ledger is None else ledger, READINESS if readiness is None else readiness,
                             INVENTORY if inventory is None else inventory, PRIORITIES)

    def edited(self):
        return copy.deepcopy(LEDGER)

    def test_lists_that_match_the_release_pass(self):
        self.assertEqual([], self.errors())

    def test_a_p1_feature_is_refused(self):
        ledger = self.edited()
        ledger["submissionInventory"]["features"][0]["featureIds"] = ["FR-OPT-02"]
        self.assertTrue(any("is P1" in e for e in self.errors(ledger=ledger)))

    def test_an_unknown_feature_id_is_refused(self):
        ledger = self.edited()
        ledger["submissionInventory"]["features"][0]["featureIds"] = ["FR-XXX-99"]
        self.assertTrue(any("not in FUNCTIONAL_INVENTORY" in e for e in self.errors(ledger=ledger)))

    def test_a_feature_whose_capability_is_not_ready_is_refused(self):
        readiness = copy.deepcopy(READINESS)
        readiness["capabilities"][0]["status"] = "UNAVAILABLE"
        self.assertTrue(any("capability 'optimization'" in e for e in self.errors(readiness=readiness)))
        missing = {"capabilities": []}
        self.assertTrue(any("absent" in e for e in self.errors(readiness=missing)))

    def test_the_kto_list_must_equal_the_inventory_in_both_directions(self):
        ledger = self.edited()
        ledger["submissionInventory"]["ktoOperations"][0]["endpoint"] = "KOR_SERVICE_2_AREA_BASED"
        errors = self.errors(ledger=ledger)
        self.assertTrue(any("never called usably" in e for e in errors), errors)
        self.assertTrue(any("does not list" in e for e in errors), errors)

    def test_an_inventory_that_is_not_evidence_is_refused(self):
        inventory = INVENTORY.replace("counts_as_evidence=true",
                                      "counts_as_evidence=false reason=environment-not-deployed")
        self.assertTrue(any("does not count as evidence" in e for e in self.errors(inventory=inventory)))

    def test_the_ledger_and_the_inventory_must_name_one_release(self):
        inventory = INVENTORY.replace("release=2026.09.21-1", "release=2026.09.20-9")
        self.assertTrue(any("names release" in e for e in self.errors(inventory=inventory)))

    def test_an_operation_must_be_used_by_a_listed_feature(self):
        ledger = self.edited()
        ledger["submissionInventory"]["ktoOperations"][0]["usedBy"] = ["FR-TRP-02"]
        self.assertTrue(any("no listed feature names" in e for e in self.errors(ledger=ledger)))
        ledger["submissionInventory"]["ktoOperations"][0]["usedBy"] = []
        self.assertTrue(any("names no feature" in e for e in self.errors(ledger=ledger)))

    def test_the_command_line_verifies_and_refuses_through_the_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "ledger.json").write_text(json.dumps(LEDGER), encoding="utf-8")
            (base / "readiness.json").write_text(json.dumps(READINESS), encoding="utf-8")
            (base / "inventory.txt").write_text(INVENTORY, encoding="utf-8")
            (base / "empty.md").write_text("no rows here", encoding="utf-8")
            args = ["--ledger", str(base / "ledger.json"), "--readiness", str(base / "readiness.json"),
                    "--inventory", str(base / "inventory.txt")]
            self.assertEqual(0, checker.main(args))
            self.assertEqual(1, checker.main(args + ["--functional-inventory", str(base / "empty.md")]),
                             "an inventory read as zero rows must not pass: every listed ID is then unknown")
            (base / "inventory.txt").write_text(INVENTORY.replace("calls=3", "calls=3").replace(
                "counts_as_evidence=true", "counts_as_evidence=false reason=no-usable-call"), encoding="utf-8")
            self.assertEqual(1, checker.main(args))


if __name__ == "__main__":
    unittest.main()
