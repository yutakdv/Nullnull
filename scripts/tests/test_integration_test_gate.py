"""Negative tests for the REC-CI-6 evaluation report gate the Docker wrapper runs."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from check_evaluation_report import check_evaluation_report

INTEGRATION_SCRIPT = ROOT / 'scripts/integration-test.sh'
AI_QUALITY_RUN = '"${compose[@]}" run --rm ai-quality'
GATE_CALL = 'python3 "${evaluation_report_checker}" "${recommendation_report}"'


def executable_lines(script: str) -> list[str]:
    """Stripped lines the shell actually runs; a commented-out call is not a call."""
    lines = []
    for raw in script.splitlines():
        line = raw.strip()
        if line and not line.startswith('#'):
            lines.append(line)
    return lines


def passing_report(**overrides) -> dict:
    report = {
        'codeSha': 'unknown',
        'corpus': {'expected': 2, 'executed': 2, 'missing': [], 'partial': False},
        'safety': {
            'hardViolations': 0,
            'unsupportedComparisons': 0,
            'unauthorizedMutations': 0,
            'deterministicMismatches': 0,
            'failures': [],
        },
    }
    report.update(overrides)
    return report


class EvaluationReportGateTests(unittest.TestCase):
    def check(self, body: str) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'evaluation.json'
            path.write_text(body, encoding='utf-8')
            check_evaluation_report(path, errors)
        return errors

    def check_json(self, report) -> list[str]:
        return self.check(json.dumps(report))

    def test_complete_corpus_without_safety_failures_passes(self):
        self.assertEqual([], self.check_json(passing_report()))

    def test_partial_corpus_fails(self):
        report = passing_report()
        report['corpus']['partial'] = True
        errors = self.check_json(report)
        self.assertTrue(
            any('records a partial corpus' in e for e in errors), errors
        )

    def test_partial_flag_that_is_not_exactly_false_fails(self):
        # NULLNULL_AI_PARTIAL_RUN stamps a boolean; a falsy stand-in must not read as complete.
        for partial in ('false', None, 0, ''):
            with self.subTest(partial=partial):
                report = passing_report()
                report['corpus']['partial'] = partial
                errors = self.check_json(report)
                self.assertTrue(
                    any('records a partial corpus' in e for e in errors), errors
                )

    def test_non_empty_safety_failures_fail(self):
        report = passing_report()
        report['safety']['failures'] = ['hardViolations=1 (gate is 0)']
        errors = self.check_json(report)
        self.assertTrue(
            any('records safety failures' in e for e in errors), errors
        )
        self.assertTrue(
            any('hardViolations=1' in e for e in errors), errors
        )

    def test_safety_failures_that_are_not_a_list_fail(self):
        report = passing_report()
        report['safety']['failures'] = 'none'
        errors = self.check_json(report)
        self.assertTrue(
            any('safety.failures must be a list' in e for e in errors), errors
        )

    def test_missing_corpus_partial_fails(self):
        report = passing_report()
        del report['corpus']['partial']
        errors = self.check_json(report)
        self.assertTrue(
            any('no corpus.partial' in e for e in errors), errors
        )

    def test_missing_safety_section_fails(self):
        report = passing_report()
        del report['safety']
        errors = self.check_json(report)
        self.assertTrue(
            any('no safety.failures' in e for e in errors), errors
        )

    def test_empty_object_fails_both_checks(self):
        errors = self.check_json({})
        self.assertTrue(any('no corpus.partial' in e for e in errors), errors)
        self.assertTrue(any('no safety.failures' in e for e in errors), errors)

    def test_malformed_json_fails(self):
        errors = self.check('{"corpus": {"partial": false},')
        self.assertTrue(
            any('Cannot read evaluation report' in e for e in errors), errors
        )

    def test_non_object_root_fails(self):
        errors = self.check('[]')
        self.assertTrue(
            any('root must be an object' in e for e in errors), errors
        )

    def test_missing_file_fails(self):
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            check_evaluation_report(Path(directory) / 'evaluation.json', errors)
        self.assertTrue(
            any('Cannot read evaluation report' in e for e in errors), errors
        )


class IntegrationScriptWiringTests(unittest.TestCase):
    def setUp(self):
        self.lines = executable_lines(
            INTEGRATION_SCRIPT.read_text(encoding='utf-8')
        )

    def test_wrapper_runs_the_gate_on_the_collected_report(self):
        # The report existed once without ever being read; the wrapper must run the checker.
        # Matching the whole file once let a `# TEMPORARILY DISABLED:` prefix keep this green,
        # so only lines the shell executes count.
        self.assertIn(GATE_CALL, self.lines)

    def test_the_gate_runs_after_the_suite_that_writes_the_report(self):
        # Hoisted above ai-quality the checker would read a stale or absent artifact.
        self.assertIn(AI_QUALITY_RUN, self.lines)
        self.assertIn(GATE_CALL, self.lines)
        self.assertLess(
            self.lines.index(AI_QUALITY_RUN), self.lines.index(GATE_CALL)
        )


if __name__ == '__main__':
    unittest.main()
