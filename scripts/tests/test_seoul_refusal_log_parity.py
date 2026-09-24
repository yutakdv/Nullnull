"""The Seoul refusal line and the operator's echo allowlist must agree on its words.

`SeoulLiveCollectMain` prints why a collection was refused as `seoul_live_validation outcome=<Outcome> rule=<rule>`
(BA-091-T27): the validator's outcome, and the check that fired - `SeoulCityDataValidator.Rule` in lower case with
hyphens. The `seoul-live-collect` ops task echoes only lines `OPS_LOG_LINE` matches, so an outcome or a rule whose
word the pattern does not take would vanish from the operator's output without a sound. Both vocabularies are read
from source rather than run, so this stays a script test with no JVM.
"""

import importlib.util
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "apps/api/src/main/java/io/nullnull/crowd/application/SeoulCityDataValidator.java"
OUTCOMES = ROOT / "apps/api/src/main/java/io/nullnull/shared/provider/ProviderResponseValidator.java"

spec = importlib.util.spec_from_file_location("nullnull_aws_operator_seoul", ROOT / "scripts/aws/staging_operator.py")
ops = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ops)


def enum_constants(path, name):
    declared = re.search(r"enum " + name + r"\s*\{([^;}]*)", path.read_text(encoding="utf-8"))
    return [constant.strip() for constant in declared.group(1).split(",") if constant.strip()] if declared else []


class SeoulRefusalLogParityTests(unittest.TestCase):

    def test_every_outcome_and_rule_the_collection_can_print_is_echoed(self):
        rules = [name.lower().replace("_", "-") for name in enum_constants(VALIDATOR, "Rule")]
        outcomes = [name for name in enum_constants(OUTCOMES, "Outcome") if name != "OK"]
        # The same fourteen checks the Java table covers (BA-090-T23); a parse that found fewer is not a pass.
        self.assertEqual(14, len(rules), rules)
        self.assertEqual(6, len(outcomes), outcomes)
        for outcome in outcomes:
            for rule in rules:
                line = f"seoul_live_validation outcome={outcome} rule={rule}"
                self.assertTrue(ops.OPS_LOG_LINE.match(line), line)

    def test_a_refusal_line_carrying_anything_else_is_not_echoed(self):
        for line in ["seoul_live_validation outcome=SCHEMA_DRIFT rule=area-mismatch area=서울숲공원",
                     "seoul_live_validation outcome=SCHEMA_DRIFT rule=서울숲공원",
                     "seoul_live_validation outcome=ERROR-500 rule=result-code",
                     "seoul_live_validation outcome=SCHEMA_DRIFT rule=area mismatch",
                     "seoul_live_validation outcome=SCHEMA_DRIFT rule=",
                     "seoul_live_validation outcome=SCHEMA_DRIFT rule=-area",
                     "seoul_live_validation outcome=SCHEMA_DRIFT"]:
            self.assertFalse(ops.OPS_LOG_LINE.match(line), line)


if __name__ == "__main__":
    unittest.main()
