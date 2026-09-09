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
from validate_frontend_plan import PLAN, validate


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

    def test_duplicate_task_ids_are_rejected(self):
        self.check_mutation(
            lambda plan: plan['tasks'].append(copy.deepcopy(plan['tasks'][5])),
            'duplicate task IDs')


if __name__ == '__main__':
    unittest.main()
