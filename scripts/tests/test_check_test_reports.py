"""BA-004-T1/T2: negative evidence and execution tests for the real CI wrapper."""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check_test_reports.py"
SUITES = ("test", "integrationTest", "openapiContractTest", "recommendationTest")
PLAN = {"tasks": [{"id": "BA-099", "status": "integration-ready",
                   "tests": [{"id": "BA-099-T1"}]}]}
MANIFEST = {"implementedTestIds": [{"id": "REC-DATA-02", "suite": "gradle:test"}]}
EVALUATION = {"corpus": {"partial": False}, "safety": {"failures": []}}


def xml(name="BA-099-T1 REC-DATA-02 passes", child="", **counts):
    attributes = {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, **counts}
    attrs = " ".join(f'{key}="{value}"' for key, value in attributes.items())
    return f'<testsuite name="fixture" {attrs}><testcase name="{name}">{child}</testcase></testsuite>'


def write_reports(root: Path):
    for suite in SUITES:
        path = root / suite / "TEST-fixture.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml())


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        write_reports(self.root)
        self.target = self.root / "test/TEST-fixture.xml"
        for name, data in (("plan", PLAN), ("manifest", MANIFEST), ("evaluation", EVALUATION)):
            (self.root / f"{name}.json").write_text(json.dumps(data))

    def run_check(self, *flags):
        return subprocess.run([sys.executable, str(CHECKER), *map(str, flags)],
                              capture_output=True, text=True, timeout=10)

    def check(self, *extra):
        return self.run_check("--junit-dir", self.root, "--backend-plan", self.root / "plan.json",
                              "--manifest", self.root / "manifest.json", *extra)

    def rejected(self, result, message):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(message, result.stderr)

    def test_complete_evidence_passes(self):
        result = self.check("--evaluation", self.root / "evaluation.json")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("recommendationTest: testcases=1", result.stdout)

    def test_individual_inputs_and_missing_dependencies(self):
        for flags in (("--junit-dir", self.root), ("--evaluation", self.root / "evaluation.json")):
            self.assertEqual(0, self.run_check(*flags).returncode)
        self.rejected(self.run_check(), "at least one evidence input")
        self.rejected(self.run_check("--backend-plan", self.root / "plan.json"), "BA-099-T1 missing")
        self.rejected(self.run_check("--manifest", self.root / "manifest.json"), "REC-DATA-02 missing")

    def test_BA_004_T1_failure_error_skip_counts_and_children(self):
        for tag, counter in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            with self.subTest(tag=tag, plane="summary"):
                self.target.write_text(xml(**{counter: 1}))
                self.rejected(self.check(), f"{counter}=1")
            with self.subTest(tag=tag, plane="testcase"):
                self.target.write_text(xml(child=f"<{tag}/>"))
                self.rejected(self.check(), f"testcase {tag}")
        for tag in ("flakyFailure", "rerunFailure", "flakyError", "rerunError"):
            self.target.write_text(xml(child=f"<{tag}/>"))
            self.rejected(self.check(), f"testcase {tag}")

    def test_BA_004_T2_each_suite_is_required_even_without_acceptance_ids(self):
        for suite in SUITES:
            with self.subTest(suite=suite):
                path = self.root / suite / "TEST-fixture.xml"
                path.unlink()
                self.rejected(self.check(), f"{suite}: missing JUnit XML")
                path.write_text(xml())

    def test_empty_malformed_and_inconsistent_xml_are_not_execution(self):
        variants = [('', 'no element found'), ('<report/>', 'invalid JUnit root'),
                    ('<testsuites/>', 'no testsuite'),
                    ('<testsuite tests="0" failures="0" errors="0" skipped="0"/>', 'empty testsuite'),
                    (xml(tests=2), 'tests=2'), (xml(tests=-1), 'invalid tests'),
                    (xml().replace('skipped="0"', ''), 'invalid skipped'),
                    (xml(name=""), 'testcase has no name')]
        for body, message in variants:
            with self.subTest(body=body):
                self.target.write_text(body)
                self.rejected(self.check(), message)

    def test_nested_testsuites_count_actual_cases_once(self):
        self.target.write_text('<testsuites>' + xml() + '</testsuites>')
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("test: testcases=1", result.stdout)

    def test_testsuites_aggregate_cannot_hide_failure_or_wrong_count(self):
        for key, value in (('tests', '2'), ('failures', '1'), ('errors', '1'),
                           ('skipped', '1'), ('tests', 'invalid')):
            self.target.write_text(f'<testsuites {key}="{value}">' + xml() + '</testsuites>')
            self.rejected(self.check(), f'aggregate {key}=')

    def test_acceptance_ids_must_be_complete_tokens_in_testcase_names(self):
        for task_status in ("integration-ready", "verified"):
            plan = json.loads(json.dumps(PLAN))
            plan['tasks'][0]['status'] = task_status
            (self.root / 'plan.json').write_text(json.dumps(plan))
            for bad_name in ("BA-099-T10", "XBA-099-T1", "BA-099-T1-extra", "unrelated"):
                for suite in SUITES:
                    body = xml(name=bad_name + ' REC-DATA-02').replace(
                        '</testsuite>', '<system-out>BA-099-T1</system-out></testsuite>')
                    (self.root / suite / 'TEST-fixture.xml').write_text(body)
                self.rejected(self.check(), "BA-099-T1 missing")

    def test_planned_card_does_not_claim_execution(self):
        (self.root / 'plan.json').write_text(json.dumps({'tasks': [
            {'status': 'planned', 'tests': [{'id': 'BA-098-T1'}]}]}))
        self.assertEqual(0, self.check().returncode)

    def test_manifest_checks_the_declared_gradle_suite(self):
        self.target.write_text(xml(name='BA-099-T1'))
        self.rejected(self.check(), 'REC-DATA-02 missing from gradle:test')
        (self.root / 'manifest.json').write_text(json.dumps({'implementedTestIds': [
            {'id': 'REC-OPT-01', 'suite': 'pytest'}]}))
        self.assertEqual(0, self.check().returncode)
        for suite in ('gradle:typo', 'gradle', 'typo'):
            (self.root / 'manifest.json').write_text(json.dumps({'implementedTestIds': [
                {'id': 'REC-DATA-02', 'suite': suite}]}))
            self.rejected(self.check(), 'unknown suite')

    def test_invalid_json_and_missing_files_fail(self):
        for file in ('plan', 'manifest', 'evaluation'):
            flag = '--backend-plan' if file == 'plan' else f'--{file}'
            for data in ('[]', '{}', '{', '\ufffd'):
                path = self.root / f'{file}.json'
                path.write_text(data)
                self.assertNotEqual(0, self.run_check(flag, path).returncode)
            self.assertNotEqual(0, self.run_check(flag, self.root / 'absent').returncode)

    def test_evaluation_content_is_validated_by_aggregate_runner(self):
        for document, message in (({'corpus': {'partial': True}, 'safety': {'failures': []}}, 'partial corpus'),
                                  ({'corpus': {'partial': False}, 'safety': {'failures': ['broken']}}, 'safety failures')):
            path = self.root / 'evaluation.json'
            path.write_text(json.dumps(document))
            self.rejected(self.check('--evaluation', path), message)

    def test_old_xml_and_evaluation_cannot_cover_a_suppressed_command(self):
        start = self.root / 'run-start'
        start.touch()
        os.utime(start, (100, 100))
        self.assertEqual(0, self.check('--run-start', start).returncode)
        os.utime(self.target, (99, 99))
        self.rejected(self.check('--run-start', start), 'stale report')
        self.target.write_text(xml())
        report = self.root / 'evaluation.json'
        os.utime(report, (99, 99))
        self.rejected(self.check('--run-start', start, '--evaluation', report), 'stale report')
        self.rejected(self.check('--run-start', self.root / 'absent'), 'No such file')


class WrapperExecutionTests(unittest.TestCase):
    """Execute the shipping Bash wrapper; only Docker and scaffold are test doubles.

    This proves shell exit propagation and artifact checking, not Compose isolation.
    BA-004-T3 is intentionally not claimed here.
    """
    def run_wrapper(self, mode, suppress=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ('scripts', 'bin', 'docs/engineering', 'apps/ai/tests/recommendation'):
                (root / relative).mkdir(parents=True)
            script = (ROOT / 'scripts/integration-test.sh').read_text()
            if suppress:
                script = script.replace('"${compose[@]}" run --rm api-quality',
                                        '"${compose[@]}" run --rm api-quality || true')
            (root / 'scripts/integration-test.sh').write_text(script)
            for filename in ('check_test_reports.py', 'check_evaluation_report.py'):
                shutil.copy2(ROOT / 'scripts' / filename, root / 'scripts' / filename)
            (root / 'scripts/verify_target_stack.py').write_text('')
            for relative in ('.nullnull-target-stack', 'apps/api/Dockerfile', 'apps/api/gradlew',
                             'apps/api/gradle/wrapper/gradle-wrapper.jar',
                             'apps/api/gradle/wrapper/gradle-wrapper.properties',
                             'apps/web/Dockerfile', 'apps/web/package.json', 'package.json',
                             'package-lock.json', 'compose.integration.yml', 'docs/api/openapi.yaml'):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            (root / 'docs/engineering/backend-plan.json').write_text(json.dumps(PLAN))
            (root / 'apps/ai/tests/recommendation/manifest.json').write_text(json.dumps(MANIFEST))
            fake = root / 'bin/docker'
            fake.write_text(f'''#!{sys.executable}
import json, os, pathlib, sys
root = pathlib.Path.cwd() / '.artifacts/integration'
args = sys.argv
mode = os.environ['REPORT_MODE']
if 'run' in args and 'api-quality' in args:
    if mode == 'command': sys.exit(42)
    for suite in {SUITES!r}:
        path = root / 'api-test-results' / suite / 'TEST-fixture.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        body = {xml()!r}
        if suite == 'test':
            if mode == 'failure': body = {xml(child='<failure/>')!r}
            if mode == 'skip': body = {xml(child='<skipped/>')!r}
            if mode == 'missing': continue
        path.write_text(body)
        if mode == 'stale': os.utime(path, (1, 1))
    if mode == 'stale': sys.exit(42)
elif 'run' in args and 'ai-quality' in args:
    path = root / 'recommendation-ai/evaluation.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text({json.dumps(EVALUATION)!r})
elif 'exec' in args:
    print('{{"status":"READY"}}')
else:
    print('{{}}')
''')
            fake.chmod(0o755)
            result = subprocess.run(['bash', 'scripts/integration-test.sh'], cwd=root,
                                    env={**os.environ, 'PATH': f'{root / "bin"}:{os.environ["PATH"]}',
                                         'REPORT_MODE': mode}, capture_output=True, text=True, timeout=15)
            status = (root / '.artifacts/integration/status.txt').read_text().strip()
            return result, status

    def test_complete_wrapper_reaches_full_docker_only_after_evidence(self):
        result, status = self.run_wrapper('pass')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('success', status)
        self.assertIn('test_reports=valid', result.stdout)
        self.assertIn('integration_mode=full-docker', result.stdout)

    def test_BA_004_T1_actual_wrapper_propagates_command_failure(self):
        result, status = self.run_wrapper('command')
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertEqual('failed', status)

    def test_BA_004_T2_actual_wrapper_rejects_bad_evidence_and_suppression(self):
        for mode, message in (('failure', 'testcase failure'), ('skip', 'testcase skipped'),
                              ('missing', 'missing JUnit XML'), ('stale', 'stale report'),
                              ('command', 'missing JUnit XML')):
            with self.subTest(mode=mode):
                result, status = self.run_wrapper(mode, suppress=True)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn(message, result.stderr)
                self.assertEqual('failed', status)
                self.assertNotIn('integration_mode=full-docker', result.stdout)


class WorkflowWiringTests(unittest.TestCase):
    def test_BA_004_T2_shipping_wrapper_does_not_suppress_quality_commands(self):
        lines = [line.strip() for line in (ROOT / 'scripts/integration-test.sh').read_text().splitlines()
                 if line.strip() and not line.lstrip().startswith('#')]
        for service in ('api-quality', 'ai-quality'):
            self.assertIn(f'"${{compose[@]}}" run --rm {service}', lines)
        self.assertIn('python3 "${test_report_checker}" \\', lines)
        self.assertIn('--run-start "${artifact_dir}/quality-run-start"', lines)
        self.assertLess(lines.index('touch "${artifact_dir}/quality-run-start"'),
                        lines.index('"${compose[@]}" run --rm api-quality'))

    def test_openapi_diff_is_pinned_pr_only_and_enforcing(self):
        content = (ROOT / '.github/workflows/docs-contract.yml').read_text()
        steps = re.split(r'^      - ', content, flags=re.M)
        action = next(s for s in steps if 'uses: oasdiff/' in s)
        self.assertRegex(action, r'uses: oasdiff/oasdiff-action/breaking@[a-f0-9]{40} # v\d')
        for line in ("if: github.event_name == 'pull_request'", 'base: origin/main:docs/api/openapi.yaml',
                     'revision: docs/api/openapi.yaml', 'fail-on: WARN', 'review: "false"', 'github-token: ""'):
            self.assertIn(line, [s.strip() for s in action.splitlines()])
        self.assertNotIn('continue-on-error', action)
        fetch = next(s for s in steps if 'run: git fetch' in s)
        self.assertIn("if: github.event_name == 'pull_request'", fetch)
        self.assertIn('origin main:refs/remotes/origin/main', fetch)
        self.assertLess(steps.index(fetch), steps.index(action))

    def test_native_workflow_checks_fresh_evidence_even_after_gradle_failure(self):
        content = (ROOT / '.github/workflows/api-quality.yml').read_text()
        steps = re.split(r'^      - ', content, flags=re.M)
        check = next(s for s in steps if 'python3 ../../scripts/check_test_reports.py' in s)
        for line in ('if: always()', 'python3 ../../scripts/check_test_reports.py',
                     '--junit-dir build/test-results',
                     '--backend-plan ../../docs/engineering/backend-plan.json',
                     '--run-start ../../.artifacts/api-quality-start'):
            self.assertIn(line, [s.strip() for s in check.splitlines()])
        self.assertNotIn('||', check)
        self.assertNotIn('continue-on-error', check)
        run = next(s for s in steps if './gradlew --no-daemon test' in s)
        self.assertIn('touch ../../.artifacts/api-quality-start', run)
        for suite in SUITES:
            self.assertIn(f'{suite} --rerun', run)
        self.assertLess(steps.index(run), steps.index(check))
        for path in ('scripts/check_test_reports.py', 'docs/engineering/backend-plan.json'):
            self.assertEqual(2, content.count(f'      - "{path}"'))


if __name__ == '__main__':
    unittest.main()
