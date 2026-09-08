"""Negative tests for the offline npm audit judgement the security-scan service runs.

The build writes the report with `|| true`, so "vulnerabilities found" and "no report was
produced" arrive with the same exit code. Every way the report can fail to exist has to be a
failure here, or the gate passes on a scan that never ran.
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from check_npm_audit_report import check_npm_audit_report


def counts(**overrides) -> dict:
    value = {'info': 0, 'low': 0, 'moderate': 0, 'high': 0, 'critical': 0, 'total': 0}
    value.update(overrides)
    return value


def report(**overrides) -> dict:
    return {'auditReportVersion': 2, 'vulnerabilities': {}, 'metadata': {'vulnerabilities': counts(**overrides)}}


class NpmAuditReportTests(unittest.TestCase):
    def check_bytes(self, body: bytes) -> list[str]:
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'npm-audit.json'
            path.write_bytes(body)
            check_npm_audit_report(path, errors)
        return errors

    def check(self, document) -> list[str]:
        return self.check_bytes(json.dumps(document).encode('utf-8'))

    def test_all_zero_counts_pass(self):
        self.assertEqual([], self.check(report()))

    def test_high_severity_fails(self):
        errors = self.check(report(high=1, total=1))
        self.assertTrue(any('blocking vulnerabilities' in e and 'high=1' in e for e in errors), errors)

    def test_critical_severity_fails(self):
        errors = self.check(report(critical=2, total=2))
        self.assertTrue(any('critical=2' in e for e in errors), errors)

    def test_moderate_alone_passes(self):
        # The gate promises --audit-level=high; moderate findings are counted, not enforced.
        self.assertEqual([], self.check(report(moderate=5, total=5)))

    def test_missing_file_fails(self):
        errors: list[str] = []
        with tempfile.TemporaryDirectory() as directory:
            check_npm_audit_report(Path(directory) / 'absent.json', errors)
        self.assertTrue(any('Cannot read npm audit report' in e for e in errors), errors)

    def test_zero_byte_file_fails(self):
        # A registry failure during the build leaves the redirect target at zero bytes.
        errors = self.check_bytes(b'')
        self.assertTrue(any('is empty' in e for e in errors), errors)

    def test_whitespace_only_file_fails(self):
        errors = self.check_bytes(b'  \n\t ')
        self.assertTrue(any('is empty' in e for e in errors), errors)

    def test_truncated_json_fails(self):
        errors = self.check_bytes(b'{"metadata": {"vulnerabilities": {"high": 0,')
        self.assertTrue(any('not valid JSON' in e for e in errors), errors)

    def test_non_object_root_fails(self):
        errors = self.check([])
        self.assertTrue(any('root must be an object' in e for e in errors), errors)

    def test_npm_error_payload_fails(self):
        # npm writes an error object instead of a report when the audit endpoint refuses.
        errors = self.check({'error': {'code': 'ENOTFOUND', 'summary': 'audit endpoint returned an error'}})
        self.assertTrue(any('no metadata object' in e for e in errors), errors)

    def test_metadata_without_vulnerabilities_fails(self):
        errors = self.check({'metadata': {'dependencies': {}}})
        self.assertTrue(any('no metadata.vulnerabilities object' in e for e in errors), errors)

    def test_missing_severity_key_fails(self):
        document = report()
        del document['metadata']['vulnerabilities']['high']
        errors = self.check(document)
        self.assertTrue(any('missing severity counts' in e and 'high' in e for e in errors), errors)

    def test_non_integer_count_fails(self):
        for value in ('1', None, 1.5, [], {}):
            with self.subTest(value=value):
                errors = self.check(report(high=value))
                self.assertTrue(
                    any('must be a non-negative integer' in e for e in errors), errors
                )

    def test_boolean_count_fails(self):
        # bool is an int subclass, so `true` would otherwise read as a count of one.
        errors = self.check(report(high=True))
        self.assertTrue(any('must be a non-negative integer' in e for e in errors), errors)

    def test_negative_count_fails(self):
        errors = self.check(report(high=-1))
        self.assertTrue(any('must be a non-negative integer' in e for e in errors), errors)


if __name__ == '__main__':
    unittest.main()
