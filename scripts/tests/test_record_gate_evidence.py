"""Tests for the gate-evidence recorder, whose only real risk is recording a verdict nobody gave."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/record_gate_evidence.py'
NAME = 'BA-004-T3 egress denial reproduced in the real Compose run'


class RecorderTests(unittest.TestCase):
    def run_recorder(self, body: str, name: str = NAME):
        directory = tempfile.mkdtemp()
        report = Path(directory) / 'probe.txt'
        report.write_text(body, encoding='utf-8')
        out = Path(directory) / 'evidence'
        result = subprocess.run(
            [sys.executable, str(SCRIPT), '--out', str(out), '--report', str(report),
             '--require', 'outbound_network=denied', '--name', name],
            capture_output=True, text=True, timeout=10)
        return result, out

    def test_a_stated_verdict_is_recorded_under_the_acceptance_id(self):
        result, out = self.run_recorder('probe ran\noutbound_network=denied\n')
        self.assertEqual(0, result.returncode, result.stderr)
        written = list((out / 'gateChecks').glob('*.xml'))
        self.assertEqual(1, len(written), written)
        root = ET.parse(written[0]).getroot()
        self.assertEqual(NAME, root.find('testcase').get('name'))
        self.assertEqual(('1', '0', '0', '0'), (root.get('tests'), root.get('failures'),
                                                root.get('errors'), root.get('skipped')))

    def test_a_report_without_the_verdict_records_nothing(self):
        """The whole point: called on a run that proved nothing, it must refuse rather than stamp."""
        result, out = self.run_recorder('the probe printed something else\n')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('refusing to record evidence', result.stderr)
        self.assertFalse((out / 'gateChecks').exists())

    def test_a_name_that_claims_no_acceptance_id_is_refused(self):
        """A free-text name would put an unattributable testcase into the aggregator's input."""
        result, _ = self.run_recorder('outbound_network=denied\n', name='egress looked fine')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('must start with an acceptance ID', result.stderr)

    def test_an_id_buried_mid_sentence_is_not_a_claim(self):
        """Same rule the Python runner applies: mentioning an ID is not claiming to prove it."""
        result, _ = self.run_recorder('outbound_network=denied\n',
                                      name='egress, see BA-004-T3 for context')
        self.assertNotEqual(0, result.returncode)

    def test_a_missing_report_is_not_a_pass(self):
        directory = tempfile.mkdtemp()
        result = subprocess.run(
            [sys.executable, str(SCRIPT), '--out', str(Path(directory) / 'e'),
             '--report', str(Path(directory) / 'absent.txt'),
             '--require', 'outbound_network=denied', '--name', NAME],
            capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('cannot read', result.stderr)


if __name__ == '__main__':
    unittest.main()
