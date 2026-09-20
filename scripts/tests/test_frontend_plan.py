"""Negative tests for the frontend plan validator.

Each mutation writes a broken plan into a throwaway copy of the manifest and
asserts the validator rejects it. A validator that only ever sees a correct file
proves nothing, so the repository's own plan is checked too.
"""
from __future__ import annotations

import copy
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from validate_frontend_plan import PLAN, TASK_ID_RE, validate


class FrontendPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = json.loads((ROOT / PLAN).read_text(encoding='utf-8'))

    def check_mutation(self, mutate, expected):
        """Run the validator over a temp root whose plan has been broken."""
        plan = copy.deepcopy(self.plan)
        mutate(plan)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in (
                'docs/product/FUNCTIONAL_INVENTORY.md',
                'docs/design/FIGMA_HANDOFF.md',
                'docs/api/openapi.yaml',
                'docs/engineering/backend-plan.json',
            ):
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, destination)
            (root / PLAN).write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
            errors: list[str] = []
            validate(root, errors)
        self.assertTrue(any(expected in error for error in errors), errors)

    def test_current_repository(self):
        errors: list[str] = []
        validate(ROOT, errors)
        self.assertEqual([], errors)

    def test_p1_task_id_shape_is_accepted(self):
        self.assertIsNotNone(TASK_ID_RE.fullmatch('FE-P1-101'))

    def test_unapproved_task_id_shapes_are_rejected(self):
        for task_id in ('FE-P2-101', 'FE-P1-10', 'FE-P1-X01'):
            with self.subTest(task_id=task_id):
                self.assertIsNone(TASK_ID_RE.fullmatch(task_id))

    def test_unverified_figma_node_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(figmaNodes=['999:999']),
            'unverified Figma node')

    def test_unknown_feature_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(featureIds=['FR-NOPE-99']),
            'unknown feature ID')

    def test_optional_feature_cannot_sit_in_a_p0_task(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(featureIds=['FR-AUT-01']),
            'non-P0 feature in a P0 task')

    def test_operation_outside_the_contract_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(operations=['notAnOperation']),
            'operation not in OpenAPI')

    def test_missing_backend_dependency_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(dependsOn=['BA-999']),
            'missing backend dependency')

    def test_dependency_on_a_later_phase_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(dependsOn=['FE-601']),
            'dependency points to a later phase')

    def test_dependency_cycle_is_detected(self):
        def mutate(plan):
            by_id = {task['id']: task for task in plan['tasks']}
            by_id['FE-101']['dependsOn'] = ['FE-105']
            by_id['FE-105']['dependsOn'] = ['FE-101']
        self.check_mutation(mutate, 'dependency cycle')

    def test_ownership_must_stay_explicit(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(owner='BE_AI_DRI'),
            'Frontend ownership and BE/AI review must be explicit')

    def test_empty_steps_are_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(steps=[]),
            'implementation steps are empty')

    def test_unknown_status_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(status='done'),
            'invalid priority or status')

    def test_live_work_must_stay_in_the_final_phase(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(featureIds=['FR-LIV-01']),
            'Live work must be in final B10 phase')

    def test_verified_without_evidence_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(status='verified'),
            'verified requires report')

    def check_report(self, report_body, expected, *, filename='report.xml'):
        """A verified card whose evidence points at `report_body`, written to the temp root.

        check_mutation cannot serve this: it copies a fixed list of canonical files
        and the report has to be a new one, varying per test.
        """
        plan = copy.deepcopy(self.plan)
        task = plan['tasks'][5]
        task['status'] = 'verified'
        task['evidence'] = {
            'report': filename,
            'contractSha': 'a' * 40,
            'reviewer': 'FE_DRI',
            'testIds': [t['id'] for t in task['tests']],
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in (
                'docs/product/FUNCTIONAL_INVENTORY.md',
                'docs/design/FIGMA_HANDOFF.md',
                'docs/api/openapi.yaml',
                'docs/engineering/backend-plan.json',
            ):
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, destination)
            (root / PLAN).write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
            (root / filename).write_text(report_body, encoding='utf-8')
            errors: list[str] = []
            validate(root, errors)
        if expected is None:
            self.assertEqual([], errors)
        else:
            self.assertTrue(any(expected in error for error in errors), errors)

    def test_verified_report_must_be_a_junit_report(self):
        # #208: the path resolving is not the same as the path being a report. A
        # card pointed at frontend-plan.json itself passed the whole gate.
        self.check_report('{"not": "junit"}', 'not a JUnit report with testcases')

    def test_verified_report_with_no_testcases_is_rejected(self):
        # Well-formed XML and zero testcases is the shape a skipped or crashed run
        # leaves behind, and it would otherwise satisfy every ID lookup vacuously.
        self.check_report('<testsuite name="empty" tests="0"></testsuite>',
                          'not a JUnit report with testcases')

    def test_verified_report_must_name_the_claimed_tests(self):
        # The asymmetry #208 reported: evidence.testIds was only ever compared with
        # the card's own tests[], and both live in the same file.
        self.check_report(
            '<testsuite><testcase classname="other" name="something else"/></testsuite>',
            'is not named by any testcase')

    def test_verified_report_naming_the_tests_is_accepted(self):
        # The positive control. Without it the three above could pass because the
        # check rejects everything, which would be a different bug.
        plan_task = self.plan['tasks'][5]
        cases = ''.join(
            f'<testcase classname="suite" name="{t["id"]} does the thing"/>'
            for t in plan_task['tests'])
        self.check_report(f'<testsuite>{cases}</testsuite>', None)

    def test_blocked_without_reason_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(status='blocked'),
            'blocked/deferred requires reason')

    def test_branch_must_declare_frontend(self):
        self.check_mutation(
            lambda plan: plan.update(branch='backend'),
            'branch=frontend')

    def test_required_checks_cannot_be_widened(self):
        self.check_mutation(
            lambda plan: plan.update(requiredChecks=['docs-contract']),
            'two stable required checks')

    def test_missing_acceptance_tests_are_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(tests=[]),
            'non-empty acceptance tests required')

    def test_test_id_must_belong_to_its_task(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(tests=[{'id': 'FE-999-T1', 'assertion': 'x'}]),
            'invalid test ID')

    def test_empty_assertion_is_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'][5].update(tests=[{'id': 'FE-101-T1', 'assertion': '  '}]),
            'missing assertion')

    def test_verified_evidence_must_cover_every_test(self):
        def mutate(plan):
            task = plan['tasks'][5]
            task['status'] = 'verified'
            task['evidence'] = {'report': 'docs/README.md', 'contractSha': 'abc',
                                'reviewer': 'BE_AI_DRI', 'testIds': ['FE-101-T1']}
        self.check_mutation(mutate, 'evidence does not cover all required tests')

    def _verified_with_report(self, report):
        """A card claiming `verified` with every other evidence field in order."""
        def mutate(plan):
            task = plan['tasks'][5]
            task['status'] = 'verified'
            task['evidence'] = {
                'report': report,
                'contractSha': 'abc',
                'reviewer': 'FE_DRI',
                # Copied from the card itself, which is exactly how this used to
                # slip through: evidence.testIds is compared against the card's
                # own required list and both live in the same file.
                'testIds': [t['id'] for t in task['tests'] if t.get('required', True)],
            }
        return mutate

    def test_verified_report_must_name_a_file_that_exists(self):
        # #208: this passed the whole gate with a report that was never written.
        # The three evidence fields were only checked for being non-empty.
        self.check_mutation(
            self._verified_with_report('완전히 지어낸 경로.xml'),
            'missing vault target')

    def test_verified_report_url_must_be_a_run_of_this_gate(self):
        # resolve_link returns early for http(s) because it exists to check vault
        # paths, so any URL would otherwise read as proof.
        self.check_mutation(
            self._verified_with_report('https://example.com'),
            'must be a GitHub Actions run URL')

    def test_a_real_run_url_is_accepted(self):
        # The positive half, and it asserts rather than skipping: the two negatives
        # above would both pass against a checker that refused every report, so
        # without this the pair proves nothing.
        plan = copy.deepcopy(self.plan)
        self._verified_with_report(
            'https://github.com/yutakdv/Nullnull/actions/runs/34755521603')(plan)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in (
                'docs/product/FUNCTIONAL_INVENTORY.md',
                'docs/design/FIGMA_HANDOFF.md',
                'docs/api/openapi.yaml',
                'docs/engineering/backend-plan.json',
            ):
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, destination)
            (root / PLAN).write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
            errors: list[str] = []
            validate(root, errors)
        self.assertEqual(
            [], [e for e in errors if 'evidence.report' in e or 'vault target' in e], errors)

    def test_duplicate_task_ids_are_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'].append(copy.deepcopy(plan['tasks'][5])),
            'duplicate task IDs')


if __name__ == '__main__':
    unittest.main()
