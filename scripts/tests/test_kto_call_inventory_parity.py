"""The KTO call inventory and the actual-call gate must agree on which environments are deployed.

`KtoCallInventoryMain` (apps/api) decides whether its list counts as evidence; `check_actual_call_evidence.py`
decides whether a release's actual-call report does. Both say "a local database proves the code path, not
the deployed service", and if their environment sets drift, one of them starts accepting what the other
refuses. The Java side is read from source rather than run, so this stays a script test with no JVM.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "apps/api/src/main/java/io/nullnull/crowd/infrastructure/audit/KtoCallInventoryMain.java"

import sys
sys.path.insert(0, str(ROOT / "scripts"))
import check_actual_call_evidence as gate  # noqa: E402


class KtoCallInventoryParityTests(unittest.TestCase):

    def test_both_sides_name_the_same_deployed_environments(self):
        source = MAIN.read_text(encoding="utf-8")
        declared = re.search(r'DEPLOYED_ENVIRONMENTS\s*=\s*Set\.of\(([^)]*)\)', source)
        self.assertIsNotNone(declared, "KtoCallInventoryMain no longer declares DEPLOYED_ENVIRONMENTS")
        java = set(re.findall(r'"([a-z]+)"', declared.group(1)))
        self.assertTrue(java, "the Java set was found but parsed empty")
        self.assertEqual(gate.DEPLOYED_ENVIRONMENTS, java)


if __name__ == "__main__":
    unittest.main()
