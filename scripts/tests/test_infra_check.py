"""The two root gate scripts that were declared but enforced nothing (#119).

`infra:check` exited 0 while printing "Not a passing check", so `docker-integration` counted it
as green. `security:scan` was declared in package.json, required by verify_target_stack.py, and
called by absolutely nothing - a second, divergent `npm audit` invocation living beside the real
one. These tests pin both so neither can drift back.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from check_infra_report import judge

INFRA_CHECK = ROOT / 'scripts' / 'infra-check.mjs'


class JudgeInfraReport(unittest.TestCase):
    def test_blocked_with_an_owner_is_recorded_not_passed(self) -> None:
        status, errors = judge('infra_check=blocked reason=infra-not-scaffolded owner=BA-006\n')
        self.assertEqual(status, 'blocked')
        self.assertEqual(errors, [])

    def test_pass_is_a_pass(self) -> None:
        status, errors = judge('infra_check=pass\n')
        self.assertEqual(status, 'pass')
        self.assertEqual(errors, [])

    def test_a_friendly_sentence_with_no_token_is_not_a_pass(self) -> None:
        # The exact regression: output a human-readable line, exit 0, and be counted as green.
        status, errors = judge('infra:check skipped - infra/ not scaffolded. Not a passing check.\n')
        self.assertIsNone(status)
        self.assertTrue(any('no infra_check=' in error for error in errors), errors)

    def test_empty_output_is_not_a_pass(self) -> None:
        self.assertIsNone(judge('')[0])

    def test_unknown_status_is_not_a_pass(self) -> None:
        status, errors = judge('infra_check=skipped\n')
        self.assertIsNone(status)
        self.assertTrue(any('unknown outcome' in error for error in errors), errors)

    def test_two_different_outcomes_are_not_a_pass(self) -> None:
        status, errors = judge('infra_check=blocked owner=BA-006\ninfra_check=pass\n')
        self.assertIsNone(status)
        self.assertTrue(any('more than one outcome' in error for error in errors), errors)

    def test_blocked_without_an_owner_is_not_accepted(self) -> None:
        # A blocked gate with nobody named becomes permanent scenery.
        status, errors = judge('infra_check=blocked reason=infra-not-scaffolded\n')
        self.assertIsNone(status)
        self.assertTrue(any('owner=' in error for error in errors), errors)


class InfraCheckScript(unittest.TestCase):
    """The script has to actually emit what the checker requires, not just be documented to."""

    def run_in(self, cwd: Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable and 'node', str(INFRA_CHECK)],
            capture_output=True, text=True, cwd=cwd, check=False,
        )

    def test_states_blocked_when_infra_is_absent(self) -> None:
        result = subprocess.run(['node', str(INFRA_CHECK)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        status, errors = judge(result.stdout + result.stderr)
        self.assertEqual(status, 'blocked', errors)

    def test_fails_when_infra_exists_without_a_synth(self) -> None:
        # infra/ appearing means the gap is supposed to be closed, so blocked stops being allowed.
        # Run against a copy so the real tree is untouched.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'infra').mkdir()
            copy = root / 'scripts' / 'infra-check.mjs'
            copy.write_text(INFRA_CHECK.read_text(encoding='utf-8'), encoding='utf-8')
            result = subprocess.run(['node', str(copy)], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('infra_check=failed', result.stderr)


class SecurityScanIsTheGate(unittest.TestCase):
    def test_declared_script_runs_the_report_the_gate_judges(self) -> None:
        scripts = json.loads((ROOT / 'package.json').read_text(encoding='utf-8'))['scripts']
        self.assertIn('security:scan', scripts, 'verify_target_stack.py requires the name')
        # Not a second `npm audit` spelling: the audit scope lives in bake_npm_audit.mjs, and a
        # duplicate here is how the declared gate and the running one drifted apart.
        self.assertEqual(scripts['security:scan'], 'node scripts/bake_npm_audit.mjs')

    def test_the_declared_script_and_the_baked_gate_cannot_diverge(self) -> None:
        """The name and the gate must resolve to the same script, however each is invoked.

        apps/web/** is FE-owned (OWNERSHIP_MATRIX.md:104), so the tooling stage still calls the
        bake script by path rather than `npm run security:scan` (asked for separately). That is
        fine as long as the two cannot drift: this fails if either side starts pointing somewhere
        else, which is exactly how package.json grew a second `npm audit` nobody ran.
        """
        scripts = json.loads((ROOT / 'package.json').read_text(encoding='utf-8'))['scripts']
        dockerfile = (ROOT / 'apps' / 'web' / 'Dockerfile').read_text(encoding='utf-8')
        gate = 'scripts/bake_npm_audit.mjs'
        self.assertIn(gate, scripts['security:scan'],
                      'package.json security:scan no longer points at the baked gate')
        self.assertTrue(
            f'RUN node {gate}' in dockerfile or 'RUN npm run security:scan' in dockerfile,
            'the tooling stage no longer runs the script security:scan declares',
        )


if __name__ == '__main__':
    unittest.main()
