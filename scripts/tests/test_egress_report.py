"""BA-004-T3: the outbound-network probe must fail when it stops proving anything."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_egress_report.py"
RUNNER = ROOT / "scripts/integration-test.sh"


def judge(text: str | None) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory() as tmp:
        report = Path(tmp) / "egress.txt"
        if text is not None:
            report.write_text(text, encoding="utf-8")
        return subprocess.run([sys.executable, str(CHECKER), str(report)],
                              capture_output=True, text=True, timeout=10)


class EgressReportTests(unittest.TestCase):
    def test_the_denial_token_passes(self):
        self.assertEqual(0, judge("outbound_network=denied\n").returncode)

    def test_a_probe_that_stated_nothing_is_not_a_pass(self):
        # The case the exit code cannot see: a command changed to something that does not probe
        # exits 0 and would otherwise be read as a denied network.
        result = judge("Creating nullnull-pr_egress-denied_run ... done\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("stated no verdict", result.stderr)

    def test_a_reachable_network_is_reported_even_if_the_token_is_also_present(self):
        result = judge("External network unexpectedly reachable from integration network.\n"
                       "outbound_network=denied\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("reached the external network", result.stderr)

    def test_two_verdicts_mean_the_file_is_not_this_run(self):
        result = judge("outbound_network=denied\noutbound_network=denied\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("2 verdicts", result.stderr)

    def test_a_missing_report_is_not_a_pass(self):
        result = judge(None)
        self.assertEqual(1, result.returncode)
        self.assertIn("egress_check=missing", result.stderr)

    def test_the_runner_captures_and_judges_the_probe(self):
        # Guards the wiring: judging a report nobody writes would pass this file's other cases
        # while the real gate still read only the exit code.
        runner = RUNNER.read_text(encoding="utf-8")
        # The INVOCATION, not the variable that names the script. Asserting the filename alone
        # passed while the call line was commented out: the `readonly ..._checker=` line still
        # mentions it, so the check proved the script was named rather than run.
        self.assertIn('python3 "${egress_report_checker}" "${egress_report}"', runner)
        self.assertIn('run --rm egress-denied >"${egress_report}"', runner)


if __name__ == "__main__":
    unittest.main()
