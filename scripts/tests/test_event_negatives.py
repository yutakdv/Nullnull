"""The analytics-event negative suite must fail when it stops proving anything.

`ajv test --invalid` exits 0 on a glob that matches nothing, so the suite's own success is not
evidence that it ran. These cases pin the three ways it can go quiet.
"""

import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from check_event_negatives import main  # noqa: E402

REAL_DIR = ROOT / "docs/contracts/events-negative"


def run(output: str, directory: Path) -> int:
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "ajv.txt"
        out.write_text(output, encoding="utf-8")
        return main(["check_event_negatives.py", str(out), str(directory)])


def passing_output(directory: Path) -> str:
    return "\n".join(f"{directory.name}/{p.name} passed test"
                     for p in sorted(directory.glob("*.json")))


class EventNegativesTest(unittest.TestCase):
    def test_the_repository_suite_is_currently_judged_green(self):
        self.assertEqual(0, run(passing_output(REAL_DIR), REAL_DIR))

    def test_the_repository_actually_has_fixtures(self):
        # Guards the test above: with an empty directory it would pass vacuously.
        self.assertGreaterEqual(len(list(REAL_DIR.glob("*.json"))), 5)

    def test_an_empty_directory_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertEqual(1, run("", Path(empty)))

    def test_a_missing_directory_is_not_a_pass(self):
        self.assertEqual(1, run("", ROOT / "docs/contracts/does-not-exist"))

    def test_a_fixture_that_never_ran_fails(self):
        lines = passing_output(REAL_DIR).splitlines()
        self.assertEqual(1, run("\n".join(lines[1:]), REAL_DIR))

    def test_a_fixture_the_schema_accepted_fails(self):
        lines = passing_output(REAL_DIR).splitlines()
        lines[0] = lines[0].replace("passed test", "failed test")
        self.assertEqual(1, run("\n".join(lines), REAL_DIR))


if __name__ == "__main__":
    unittest.main()
