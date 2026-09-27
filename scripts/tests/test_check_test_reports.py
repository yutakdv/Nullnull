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
# implementedTestIds is part of the report contract now: the manifest's pytest rows are checked
# against it, so a report without it is incomplete evidence rather than a silent pass.
EVALUATION = {"corpus": {"partial": False}, "safety": {"failures": []},
              "implementedTestIds": ["REC-DATA-02"]}


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

    def test_a_clause_the_other_role_owns_is_not_required_but_is_announced(self):
        """A clause marked externalOwner stops being required here - and is printed, because an
        exemption nobody sees is how a gate quietly stops asking. The plan validator enforces the
        marker's shape (role, reason, tracking issue); this only honours it."""
        plan = {"tasks": [{"id": "BA-099", "status": "integration-ready", "tests": [
            {"id": "BA-099-T1"},
            {"id": "BA-099-T2", "externalOwner": {"role": "FE", "reason": "Playwright",
                                                  "issue": "#233"}}]}]}
        (self.root / "plan.json").write_text(json.dumps(plan))
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("acceptance_ids_owned_elsewhere=BA-099-T2(FE,#233)", result.stdout)

    def test_a_clause_proven_by_the_rec_corpus_is_not_required_but_is_announced(self):
        """pytest produces no JUnit testcase name and the manifest vocabulary cannot hold a BA ID,
        so such a clause could never appear here. It is exempt and PRINTED; validate_backend_plan.py
        is what makes the citation real by requiring the IDs to exist in the manifest."""
        plan = {"tasks": [{"id": "BA-099", "status": "integration-ready", "tests": [
            {"id": "BA-099-T1"},
            {"id": "BA-099-T2", "recCoverage": {"ids": ["REC-OPT-01", "REC-ARCH-01"],
                                                "reason": "apps/ai REC corpus"}}]}]}
        (self.root / "plan.json").write_text(json.dumps(plan))
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("acceptance_ids_covered_by_rec=BA-099-T2(REC-OPT-01,REC-ARCH-01)", result.stdout)

    def test_an_empty_rec_coverage_does_not_exempt(self):
        """The exemption is the citation. A marker with nothing in it must not buy silence."""
        plan = {"tasks": [{"id": "BA-099", "status": "integration-ready", "tests": [
            {"id": "BA-099-T1"},
            {"id": "BA-099-T2", "recCoverage": {"ids": [], "reason": "x"}}]}]}
        (self.root / "plan.json").write_text(json.dumps(plan))
        self.rejected(self.check(), "BA-099-T2 missing")

    def test_a_vitest_report_is_only_counted_against_the_apps_test_files(self):
        """Without the app root there is nothing to say whose report it is, so the pair is required."""
        report = self.root / "vitest/unit/results.xml"
        report.parent.mkdir(parents=True)
        report.write_text('<testsuites><testsuite name="src/a.test.ts" tests="1" failures="0" errors="0" '
                          'skipped="0"><testcase classname="src/a.test.ts" name="BA-099-T1 a"/></testsuite></testsuites>')
        self.rejected(self.check("--vitest-junit-dir", self.root / "vitest"),
                      "--vitest-junit-dir needs --vitest-source-dir")
        app = self.root / "web"
        (app / "src").mkdir(parents=True)
        (app / "src/a.test.ts").write_text("")
        self.assertEqual(0, self.check("--vitest-junit-dir", self.root / "vitest",
                                       "--vitest-source-dir", app).returncode)

    def test_an_unmarked_missing_clause_still_fails(self):
        """The exemption must not become a way to stop asking about everything else."""
        plan = {"tasks": [{"id": "BA-099", "status": "integration-ready", "tests": [
            {"id": "BA-099-T1"},
            {"id": "BA-099-T2"}]}]}
        (self.root / "plan.json").write_text(json.dumps(plan))
        self.rejected(self.check(), "BA-099-T2 missing")

    def test_BA_004_T1_failure_error_skip_counts_and_children(self):
        """BA-004-T1 실패·오류·skip이 든 report를 evidence checker가 거부한다"""
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
        """BA-004-T2 acceptance ID가 없는 suite라도 0건 실행이면 거부한다"""
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

    def test_a_verified_card_cannot_name_a_testcase_no_report_contains(self):
        """`provenBy` is what separates `verified` from `integration-ready`, so it has to be real."""
        plan = json.loads(json.dumps(PLAN))
        plan['tasks'][0].update(status='verified', evidence={'provenBy': {
            'BA-099-T1': ['BA-099-T1 a testcase nobody ran']}})
        (self.root / 'plan.json').write_text(json.dumps(plan))
        self.rejected(self.check(), "names a testcase that no report contains")

    def test_a_verified_card_naming_the_real_testcase_passes(self):
        """The negative above proves nothing on its own - an always-failing check would pass it."""
        plan = json.loads(json.dumps(PLAN))
        plan['tasks'][0].update(status='verified', evidence={'provenBy': {
            'BA-099-T1': ['BA-099-T1 REC-DATA-02 passes']}})
        (self.root / 'plan.json').write_text(json.dumps(plan))
        self.assertEqual(0, self.check().returncode, self.check().stderr)

    def test_planned_card_does_not_claim_execution(self):
        (self.root / 'plan.json').write_text(json.dumps({'tasks': [
            {'status': 'planned', 'tests': [{'id': 'BA-098-T1'}]}]}))
        self.assertEqual(0, self.check().returncode)

    def test_manifest_checks_the_declared_gradle_suite(self):
        self.target.write_text(xml(name='BA-099-T1'))
        self.rejected(self.check(), 'REC-DATA-02 missing from gradle:test')
        # A pytest row used to pass here with no evidence at all - the suite was skipped outright.
        # It now needs the evaluation report to be checked against, and says so when it has none.
        (self.root / 'manifest.json').write_text(json.dumps({'implementedTestIds': [
            {'id': 'REC-OPT-01', 'suite': 'pytest'}]}))
        self.rejected(self.check(), 'needs --evaluation to be checked')
        (self.root / 'evaluation.json').write_text(json.dumps(
            {**EVALUATION, 'implementedTestIds': ['REC-OPT-01']}))
        self.assertEqual(
            0, self.check('--evaluation', self.root / 'evaluation.json').returncode)
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

    def test_a_pytest_row_the_report_does_not_carry_is_rejected(self):
        """The hole this closed: `suite: pytest` rows used to be skipped outright.

        `check_evaluation_report` reads only corpus.partial and safety.failures, so nothing
        outside the container ever looked at a test ID. A manifest could claim a pytest ID the
        corpus never exercises and every gate stayed green.
        """
        (self.root / "manifest.json").write_text(json.dumps({"implementedTestIds": [
            {"id": "REC-DATA-02", "suite": "gradle:test"},
            {"id": "REC-OPT-01", "suite": "pytest"}]}))
        self.rejected(self.check("--evaluation", self.root / "evaluation.json"),
                      "REC-OPT-01 claims the pytest suite but evaluation.json does not report it")

    def test_a_pytest_row_the_report_does_carry_passes(self):
        (self.root / "manifest.json").write_text(json.dumps({"implementedTestIds": [
            {"id": "REC-DATA-02", "suite": "gradle:test"},
            {"id": "REC-OPT-01", "suite": "pytest"}]}))
        (self.root / "evaluation.json").write_text(json.dumps(
            {**EVALUATION, "implementedTestIds": ["REC-DATA-02", "REC-OPT-01"]}))
        result = self.check("--evaluation", self.root / "evaluation.json")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_coverage_the_report_claims_must_be_registered_in_the_manifest(self):
        # The other direction, and AGENTS.md rule 1: a REC ID that ran but is not declared is
        # coverage nobody registered, so the table and the manifest stop describing the suite.
        (self.root / "evaluation.json").write_text(json.dumps(
            {**EVALUATION, "implementedTestIds": ["REC-DATA-02", "REC-LLM-01"]}))
        self.rejected(self.check("--evaluation", self.root / "evaluation.json"),
                      "evaluation.json reports REC-LLM-01 as implemented but the manifest does not")

    def test_a_pytest_row_without_the_evaluation_report_is_not_checked_silently(self):
        # Without --evaluation there is nothing to check a pytest row against. Passing anyway is
        # exactly the "green because nothing ran" shape; it says so instead.
        (self.root / "manifest.json").write_text(json.dumps({"implementedTestIds": [
            {"id": "REC-DATA-02", "suite": "gradle:test"},
            {"id": "REC-OPT-01", "suite": "pytest"}]}))
        self.rejected(self.check(), "needs --evaluation to be checked")

    def test_a_report_without_implementedTestIds_is_incomplete_evidence(self):
        (self.root / "evaluation.json").write_text(json.dumps(
            {"corpus": {"partial": False}, "safety": {"failures": []}}))
        self.rejected(self.check("--evaluation", self.root / "evaluation.json"),
                      "implementedTestIds must be a list of strings")

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
            for filename in ('check_test_reports.py', 'check_evaluation_report.py', 'check_npm_audit_report.py',
                             'check_infra_report.py', 'check_egress_report.py',
                             # Copied, not stubbed: it reads the fake probe's real output and
                             # must refuse when the verdict is absent, which is the property
                             # that keeps it from being a rubber stamp.
                             'record_gate_evidence.py',
                             # Copied for the same reason: the wrapper calls it after the browser
                             # suite, and `set -Eeuo pipefail` makes a missing script abort the run.
                             # This list is the second declaration of what the wrapper needs, and
                             # adding a script without it fails here - which is how this one was
                             # caught rather than in the gate.
                             'check_e2e_flaky.py'):
                shutil.copy2(ROOT / 'scripts' / filename, root / 'scripts' / filename)
            (root / 'scripts/verify_target_stack.py').write_text('')
            # Stubbed, not copied: the real runner discovers scripts/tests, and running it from
            # inside one of those tests would re-enter the suite. What it must do here is what the
            # fake docker does for the Gradle suites - leave a report the checker can read. That the
            # real runner produces a correct one is test_run_script_tests.py's job, not this file's.
            (root / 'scripts/run_script_tests.py').write_text(f'''#!{sys.executable}
import pathlib, sys
out = pathlib.Path(sys.argv[sys.argv.index('--out') + 1]) / 'scriptTests'
out.mkdir(parents=True, exist_ok=True)
(out / 'TEST-scriptTests.xml').write_text(
    '<testsuite name="scriptTests" tests="1" failures="0" errors="0" skipped="0">'
    '<testcase name="scriptTests.stub"/></testsuite>')
''')
            for relative in ('.nullnull-target-stack', 'apps/api/Dockerfile', 'apps/api/gradlew',
                             'apps/api/gradle/wrapper/gradle-wrapper.jar',
                             'apps/api/gradle/wrapper/gradle-wrapper.properties',
                             'apps/web/Dockerfile', 'apps/web/package.json', 'package.json',
                             'package-lock.json', 'compose.integration.yml', 'docs/api/openapi.yaml',
                             'scripts/e2e/catalog-seed.sql',
                             # The web suite's test files: the checker holds the Vitest report to them.
                             'apps/web/src/app/shell.test.tsx', 'apps/web/src/app/feed.test.ts'):
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
elif 'run' in args and 'security-scan' in args:
    path = root / 'audit/npm-audit.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({{'metadata': {{'vulnerabilities': {{k: 0 for k in ('critical', 'high', 'moderate', 'low', 'info')}}}}}}))
elif 'run' in args and 'infra-plan' in args:
    # The real infra-plan service runs `npm run infra:check`, which states its outcome as a
    # machine token. A stub that printed nothing would make check_infra_report.py fail, which
    # is the point of that checker: a step producing no outcome is not a pass.
    print('infra_check=blocked reason=infra-not-scaffolded owner=BA-006')
elif 'run' in args and 'egress-denied' in args:
    # Same reason as infra-plan above: the probe states a verdict token, and a stub that printed
    # nothing would fail check_egress_report.py - which is exactly what that checker is for.
    print('outbound_network=denied')
elif 'exec' in args and 'postgres' in args:
    # The seed step's verdict is its read-back line (#253); psql exiting 0 is not one.
    print('e2e_catalog_seed=places:3,published_posts:1,attributed_places:1')
elif 'run' in args and 'web-quality' in args:
    # The web unit suite's JUnit, in the shape Vitest 3.2 writes it - measured on this repository's
    # suite: testsuites carries tests, failures and errors; each testsuite carries all four counts;
    # a skipped test is a <skipped/> child. A run with no test files writes a bare testsuites. Each
    # testsuite is named by its file under apps/web, which is what the checker holds against the tree.
    if mode != 'vitest-missing':
        path = root / 'vitest/unit/results.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        if mode == 'vitest-empty':
            path.write_text('<testsuites name="vitest tests" tests="0" failures="0" errors="0"></testsuites>')
        else:
            files = ['src/app/shell.test.tsx', 'src/app/feed.test.ts']
            if mode == 'vitest-partial': files = files[:1]
            if mode == 'vitest-foreign': files = ['e2e/shell.spec.ts']
            body = ''
            for index, name in enumerate(files):
                skipped = 1 if mode == 'vitest-skip' and index == 0 else 0
                case = f'<testcase classname="{{name}}" name="FE-101-T1 {{name}} &gt; renders"/>'
                if skipped:
                    case += f'<testcase classname="{{name}}" name="a skipped unit test"><skipped/></testcase>'
                body += (f'<testsuite name="{{name}}" tests="{{1 + skipped}}" failures="0" errors="0" '
                         f'skipped="{{skipped}}">{{case}}</testsuite>')
            path.write_text(f'<testsuites name="vitest tests" failures="0" errors="0">{{body}}</testsuites>')
        if mode == 'vitest-stale': os.utime(path, (1, 1))
elif 'run' in args and 'e2e' in args:
    # The browser suite's JUnit, in the shape Playwright 1.56 writes it: all four counts on both
    # testsuites and testsuite, and a skipped test as a <skipped/> child. The aggregator reads it
    # after this step (#233), so a run that leaves none, a skip, or a report older than
    # quality-run-start has to fail the gate rather than pass on the other suites.
    if mode != 'e2e-missing':
        skipped = 1 if mode == 'e2e-skip' else 0
        case = '<testcase name="BA-099-T1 shell loads" classname="shell.spec.ts"/>'
        if skipped:
            case += '<testcase name="a skipped browser test" classname="shell.spec.ts"><skipped/></testcase>'
        counts = f'tests="{{1 + skipped}}" failures="0" skipped="{{skipped}}" errors="0"'
        path = root / 'playwright/e2e/results.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuites {{counts}}><testsuite name="shell.spec.ts" {{counts}}>{{case}}</testsuite></testsuites>')
        if mode == 'e2e-stale': os.utime(path, (1, 1))
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
        """BA-004-T1 하위 command가 실패하면 실제 wrapper가 그 exit code를 전파한다"""
        result, status = self.run_wrapper('command')
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertEqual('failed', status)

    def test_BA_004_T2_actual_wrapper_rejects_bad_evidence_and_suppression(self):
        """BA-004-T2 실패·skip·report 삭제·stale·command 실패를 실제 wrapper가 거부한다"""
        for mode, message in (('failure', 'testcase failure'), ('skip', 'testcase skipped'),
                              ('missing', 'missing JUnit XML'), ('stale', 'stale report'),
                              ('command', 'missing JUnit XML')):
            with self.subTest(mode=mode):
                result, status = self.run_wrapper(mode, suppress=True)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn(message, result.stderr)
                self.assertEqual('failed', status)
                self.assertNotIn('integration_mode=full-docker', result.stdout)

    def test_BA_004_T2_actual_wrapper_rejects_a_browser_report_it_cannot_count(self):
        """BA-004-T2 browser suite의 report 부재·skip·이전 실행의 report를 실제 wrapper가 거부한다"""
        # Every other suite passes in these runs, so the only thing that can fail them is the e2e
        # report - which is exactly what counting it after the browser suite has to mean (#233).
        for mode, message in (('e2e-missing', 'e2e: missing JUnit XML'), ('e2e-skip', 'skipped=1, expected 0'),
                              ('e2e-stale', 'stale report')):
            with self.subTest(mode=mode):
                result, status = self.run_wrapper(mode)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn(message, result.stderr)
                self.assertEqual('failed', status)
                self.assertNotIn('integration_mode=full-docker', result.stdout)

    def test_BA_004_T2_actual_wrapper_rejects_a_unit_report_it_cannot_count(self):
        """BA-004-T2 web unit suite의 report 부재·skip·빈 report·이전 실행·남의 report·일부만 돈 report를 실제 wrapper가 거부한다"""
        # Every other suite passes in these runs, so the only thing that can fail them is the Vitest
        # report. The last two are valid reports with passing cases: a one-case report of another suite,
        # and the web suite with one of its two files missing - what read_junit alone would accept.
        for mode, message in (('vitest-missing', 'unit: missing JUnit XML'), ('vitest-skip', 'skipped=1, expected 0'),
                              ('vitest-empty', 'no testsuite elements'), ('vitest-stale', 'stale report'),
                              ('vitest-foreign', 'the report names e2e/shell.spec.ts'),
                              ('vitest-partial', 'test file src/app/feed.test.ts is not in the report')):
            with self.subTest(mode=mode):
                result, status = self.run_wrapper(mode)
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn(message, result.stderr)
                self.assertEqual('failed', status)
                self.assertNotIn('integration_mode=full-docker', result.stdout)


class WorkflowWiringTests(unittest.TestCase):
    def test_the_required_gate_reads_the_python_evidence_it_produces(self):
        """The sandbox cannot see this: removing the flag leaves every wrapper test green.

        Evidence for the Python-proven acceptance IDs used to be produced in two places and read in
        one - api-quality fed it to the checker and integration-test.sh did not - so a card resting
        on it went green on the path-filtered workflow and red on the required gate. The wiring that
        fixed it is asserted here because nothing else fails when it goes away.
        """
        wrapper = (ROOT / 'scripts/integration-test.sh').read_text()
        self.assertIn('run_script_tests.py', wrapper,
                      'the required gate must run the Python suite, not only api-quality')
        self.assertIn('--script-junit-dir', wrapper,
                      'producing the report is not reading it')

    def test_BA_004_T2_shipping_wrapper_does_not_suppress_quality_commands(self):
        """BA-004-T2 출고되는 wrapper가 quality command의 실패를 은폐하지 않는다"""
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
                     '--run-start ../../.artifacts/api-quality-start'):
            self.assertIn(line, [s.strip() for s in check.splitlines()])
        # --backend-plan is deliberately absent here and asserted on the required gate below.
        # This job does not start the full Compose run, so it cannot produce the evidence for
        # BA-004-T3; asking it about card completeness would report a card as incomplete on
        # evidence this job structurally cannot make. The question did not disappear - it moved
        # to the gate that can answer it, and the next assertion is what keeps it from vanishing.
        command = [line.strip() for line in check.splitlines()
                   if not line.strip().startswith('#')]
        self.assertNotIn('--backend-plan ../../docs/engineering/backend-plan.json', command)
        self.assertNotIn('||', check)
        self.assertNotIn('continue-on-error', check)
        run = next(s for s in steps if './gradlew --no-daemon test' in s)
        self.assertIn('touch ../../.artifacts/api-quality-start', run)
        for suite in SUITES:
            self.assertIn(f'{suite} --rerun', run)
        self.assertLess(steps.index(run), steps.index(check))
        for path in ('scripts/check_test_reports.py', 'docs/engineering/backend-plan.json'):
            self.assertEqual(2, content.count(f'      - "{path}"'))

    def test_the_required_gate_is_the_one_that_asks_about_card_completeness(self):
        """Dropping --backend-plan from api-quality must not drop the question from the repository.

        It asks a question only the full Compose run can answer, and every kind of evidence reaches
        the wrapper: Gradle JUnit, the Python suite, and the gate verdicts recorded from the probes.
        """
        wrapper = (ROOT / 'scripts/integration-test.sh').read_text()
        for flag in ('--backend-plan', '--junit-dir', '--script-junit-dir', '--gate-junit-dir', '--e2e-junit-dir',
                     '--vitest-junit-dir'):
            self.assertIn(flag, wrapper, f'the required gate must pass {flag}')
        self.assertIn('record_gate_evidence.py', wrapper)
        # Evidence before aggregation: recording a verdict after the checker read the directory
        # leaves a file that looks like evidence and is never counted.
        self.assertLess(wrapper.index('record_gate_evidence.py'),
                        wrapper.index('--gate-junit-dir'),
                        'the gate verdict must be recorded before the checker reads it')
        # The same rule for the browser suite (#233): aggregated above the e2e run, the checker could
        # only read a report this run had not produced yet. Executed lines only, so moving the call
        # into a comment does not satisfy it.
        lines = [line.strip() for line in wrapper.splitlines() if line.strip() and not line.strip().startswith('#')]
        checker = [i for i, line in enumerate(lines) if line == 'python3 "${test_report_checker}" \\']
        self.assertEqual(1, len(checker), 'the wrapper must aggregate exactly once')
        self.assertLess(lines.index('"${compose[@]}" run --rm e2e'), checker[0],
                        'the browser suite must write its report before the checker reads it')
        self.assertIn('--e2e-junit-dir "${artifact_dir}/playwright" \\', lines[checker[0]:])
        self.assertLess(lines.index('"${compose[@]}" run --rm web-quality'), checker[0],
                        'the web suite must write its report before the checker reads it')
        self.assertIn('--vitest-junit-dir "${artifact_dir}/vitest" \\', lines[checker[0]:])
        self.assertIn('--vitest-source-dir "${project_root}/apps/web" \\', lines[checker[0]:])

    def test_the_web_suite_writes_its_junit_where_the_gate_reads_it(self):
        """Three declarations name one path, and the sandbox above fakes all three.

        The reporter path is in apps/web/vite.config.ts, the bind that carries it out of the container
        is in compose.integration.yml, and the directory the checker reads is in the wrapper. The
        wrapper tests write the report where the checker looks, so any one of the three can drift and
        they stay green; the gate would then fail on a missing report, or - worse - a reporter switched
        off by a lost CI variable would be caught only by that same failure. So the three are held
        against each other here.
        """
        compose = (ROOT / 'compose.integration.yml').read_text()
        block = compose[compose.index('\n  web-quality:\n'):]
        block = block[:block.index('\n\n')]
        self.assertIn('CI: "true"', block, 'the reporter is switched on by CI')
        self.assertIn('- ./.artifacts/integration/vitest:/workspace/apps/web/vitest-report', block)
        config = (ROOT / 'apps/web/vite.config.ts').read_text()
        self.assertIn("reporters: process.env.CI ? ['default', 'junit'] : ['default'],", config)
        self.assertIn("junit: 'vitest-report/unit/results.xml'", config)
        # And the files the checker holds the report to are the files vitest runs.
        self.assertIn("include: ['src/**/*.test.{ts,tsx}'],", config)
        checker = (ROOT / 'scripts/check_test_reports.py').read_text()
        self.assertIn('VITEST_TEST_FILES = ("src/**/*.test.ts", "src/**/*.test.tsx")', checker)
        self.assertIn('--vitest-junit-dir "${artifact_dir}/vitest"', (ROOT / 'scripts/integration-test.sh').read_text())


if __name__ == '__main__':
    unittest.main()
