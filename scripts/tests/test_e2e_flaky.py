"""check_e2e_flaky.py records a retry that JUnit cannot express, and never invents a zero."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts" / "check_e2e_flaky.py"

# The shape Playwright 1.56's json reporter actually writes, reduced to the fields read. Captured
# from a real run rather than imagined: a spec that failed once and passed on the retry carries
# status "flaky" with two results, and the same run's JUnit has failures="0" and no <failure>.
REPORT = {
    "stats": {"expected": 1, "unexpected": 0, "flaky": 1, "skipped": 0},
    "suites": [{
        "title": "flaky.spec.ts",
        "specs": [
            {"title": "a test that fails once and passes on retry",
             "tests": [{"status": "flaky", "results": [{}, {}]}]},
            {"title": "a test that always passes",
             "tests": [{"status": "expected", "results": [{}]}]},
        ],
    }],
}


def run(report: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(CHECKER), str(report)],
                          capture_output=True, text=True, check=False)


class E2eFlakyTest(unittest.TestCase):

    def test_a_retried_test_is_counted_and_named(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.json"
            report.write_text(json.dumps(REPORT), encoding="utf-8")
            result = run(report)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("e2e_flaky=1", result.stdout)
        self.assertIn("e2e_flaky_test=a test that fails once and passes on retry", result.stdout)

    def test_a_clean_run_reports_zero(self):
        clean = json.loads(json.dumps(REPORT))
        clean["suites"][0]["specs"][0]["tests"][0]["status"] = "expected"
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.json"
            report.write_text(json.dumps(clean), encoding="utf-8")
            result = run(report)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("e2e_flaky=0", result.stdout)

    def test_a_missing_report_is_none_and_never_zero(self):
        # The distinction this file exists for. "nothing asked" read as "asked and got zero" is how
        # a check stops being evidence while still printing something reassuring.
        with tempfile.TemporaryDirectory() as directory:
            result = run(Path(directory) / "absent.json")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("e2e_flaky=none", result.stdout)
        self.assertNotIn("e2e_flaky=0", result.stdout)

    def test_the_recorder_does_not_fail_the_build(self):
        # Recording, not judging: promoting this to a gate failure needs a measured pass rate first.
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "results.json"
            report.write_text(json.dumps(REPORT), encoding="utf-8")
            self.assertEqual(run(report).returncode, 0)


if __name__ == "__main__":
    unittest.main()
