"""CMP-KTO-003: every way an actual-call report can exist without proving an actual call.

The positive case is one line; the rest of this file is the negative space, because that is where
this gate earns its place. A gate whose only test is "a good report passes" is green against every
report shape nobody thought of.
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_actual_call_evidence as gate  # noqa: E402


VERIFIED = {
    "verdict": "verified",
    "environment": "staging",
    "source": "KTO_KOR_SERVICE_2",
    "releaseId": "2026.09.13-1",
    "operation": "detailCommon2",
    "observedAt": "2026-09-13T04:19:18Z",
    "ingestLogId": "018f5b10-0000-7000-8000-000000000001",
    "calls": [{"outcome": "OK", "recordsAccepted": 1}],
}


def run(report: dict | None, *extra: str) -> tuple[int, str, str]:
    """Runs the gate against a temp report; `None` means the file does not exist."""
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "actual-call.json"
        if report is not None:
            path.write_text(json.dumps(report), encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = gate.main([str(path), *extra])
        return code, out.getvalue(), err.getvalue()


class ActualCallEvidenceTest(unittest.TestCase):
    def test_a_staging_run_with_an_accepted_call_is_verified(self):
        code, out, _ = run(VERIFIED)
        self.assertEqual(code, 0)
        self.assertIn("actual_call=verified", out)
        self.assertIn("release=2026.09.13-1", out)

    def test_a_missing_report_is_blocked_and_never_counted_as_a_pass(self):
        code, out, _ = run(None)
        self.assertEqual(code, 0)
        self.assertIn("actual_call=blocked", out)
        self.assertIn("actual_call_counts_as_pass=false", out)
        self.assertNotIn("actual_call=verified", out)

    def test_a_missing_report_blocks_the_release_when_verification_is_required(self):
        code, out, err = run(None, "--require-verified")
        self.assertEqual(code, 1)
        self.assertIn("actual_call=error", err)
        self.assertNotIn("blocked", out)

    def test_a_local_run_does_not_satisfy_the_requirement(self):
        # The line the whole KTO evidence chain rests on: the local smoke succeeded today and
        # still does not answer CMP-KTO-003.
        for environment in ("local", "test", "ci", "development"):
            with self.subTest(environment=environment):
                code, _, err = run({**VERIFIED, "environment": environment})
                self.assertEqual(code, 1)
                self.assertIn("not a deployed environment", err)

    def test_file_data_cannot_satisfy_an_exclusion_about_file_data(self):
        for source in ("mock", "replay", "fixture", "file", "stub", "synthetic"):
            with self.subTest(source=source):
                code, _, err = run({**VERIFIED, "source": source})
                self.assertEqual(code, 1)
                self.assertIn("CMP-KTO-003 excludes", err)

    def test_a_report_with_no_verdict_field_fails_rather_than_passing_quietly(self):
        report = {key: value for key, value in VERIFIED.items() if key != "verdict"}
        code, _, err = run(report)
        self.assertEqual(code, 1)
        self.assertIn("missing required fields", err)

    def test_an_unknown_verdict_is_not_a_pass(self):
        code, _, err = run({**VERIFIED, "verdict": "probably"})
        self.assertEqual(code, 1)
        self.assertIn("unknown verdict", err)

    def test_zero_calls_is_not_evidence(self):
        code, _, err = run({**VERIFIED, "calls": []})
        self.assertEqual(code, 1)
        self.assertIn("non-empty list", err)

    def test_a_rejected_call_is_not_usage(self):
        code, _, err = run({**VERIFIED, "calls": [{"outcome": "PROVIDER_ERROR"}]})
        self.assertEqual(code, 1)
        self.assertIn("no call has outcome OK", err)

    def test_evidence_from_another_release_does_not_cover_this_one(self):
        code, _, err = run(VERIFIED, "--release", "2026.09.14-1")
        self.assertEqual(code, 1)
        self.assertIn("does not cover this one", err)

    def test_the_matching_release_passes_so_the_previous_case_is_not_vacuous(self):
        code, out, _ = run(VERIFIED, "--release", "2026.09.13-1")
        self.assertEqual(code, 0)
        self.assertIn("actual_call=verified", out)

    def test_an_empty_provenance_field_fails(self):
        for field in ("releaseId", "operation", "observedAt", "ingestLogId"):
            with self.subTest(field=field):
                code, _, err = run({**VERIFIED, field: "  "})
                self.assertEqual(code, 1)
                self.assertIn(f"{field} is empty", err)

    def test_a_report_that_is_not_an_object_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "actual-call.json"
            path.write_text("[]", encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = gate.main([str(path)])
        self.assertEqual(code, 1)
        self.assertIn("must be a JSON object", err.getvalue())

    def test_unreadable_json_fails_instead_of_being_treated_as_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "actual-call.json"
            path.write_text("{not json", encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = gate.main([str(path)])
        self.assertEqual(code, 1)
        self.assertIn("cannot read", err.getvalue())


if __name__ == "__main__":
    unittest.main()
