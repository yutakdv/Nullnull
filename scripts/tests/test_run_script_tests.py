"""Tests for the Python-evidence runner, whose whole risk is naming something it did not prove."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from run_script_tests import case_name, write_report


class Specimen(unittest.TestCase):
    """Not run as a suite; instances are built by hand to read their names."""

    def claims_an_id(self):
        """BA-999-T1 이 docstring은 acceptance ID로 시작한다"""

    def merely_mentions_an_id(self):
        """An ID with nothing to point at stops the promotion - see BA-002-T3's second clause."""

    def has_no_docstring(self):
        pass


class CaseNameTests(unittest.TestCase):
    def test_a_docstring_opening_with_an_id_becomes_the_claim(self):
        self.assertTrue(case_name(Specimen('claims_an_id')).startswith('BA-999-T1'))

    def test_a_docstring_that_only_mentions_an_id_is_not_a_claim(self):
        """The reason the ID must LEAD: this exact docstring shape lives in test_backend_plan.py.

        Reported under the method name, the acceptance regex finds nothing in it - which is the
        difference between describing an ID and claiming to prove it.
        """
        name = case_name(Specimen('merely_mentions_an_id'))
        self.assertEqual('Specimen.merely_mentions_an_id', name)
        self.assertNotIn('BA-002-T3', name)

    def test_a_test_without_a_docstring_reports_its_method_name(self):
        self.assertEqual('Specimen.has_no_docstring', case_name(Specimen('has_no_docstring')))


class ReportTests(unittest.TestCase):
    def test_the_report_counts_outcomes_so_a_failure_cannot_read_as_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'out.xml'
            write_report(path, [('a', None), ('b', 'failure'), ('c', 'skipped')])
            root = ET.parse(path).getroot()
            self.assertEqual(('3', '1', '0', '1'), (root.get('tests'), root.get('failures'),
                                                    root.get('errors'), root.get('skipped')))
            self.assertEqual(1, len(root.findall("testcase[@name='b']/failure")))


if __name__ == '__main__':
    unittest.main()
