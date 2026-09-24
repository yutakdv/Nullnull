"""staging-release can stop after the plan (plan_only), so an app plan never deploys itself with the edge closed.

An app-kind plan runs deploy-app at once (the `staging` environment has no reviewer) and that job executes the default
deploy, which closes the public API edge. While the public service must stay open, the operator needs the plan without
that job: dispatch with plan_only=true, review the plan, then execute it locally with --preserve-open-edge.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/staging-release.yml').read_text()


def job_block(name):
    match = re.search(rf'(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)', WORKFLOW)
    if match is None:
        raise AssertionError(f'job {name} not found')
    return match.group(1)


class ReleasePlanOnlyTest(unittest.TestCase):
    def test_plan_only_is_an_optional_input_that_defaults_to_deploying(self):
        # The reconciler dispatches with action and expected_sha only; its runs must keep deploying as before.
        block = re.search(r'(?ms)^      plan_only:\n(.*?)(?=^      [a-z_]+:\n|^[a-z])', WORKFLOW)
        self.assertIsNotNone(block, 'plan_only input not found')
        self.assertRegex(block.group(1), r'(?m)^        type: boolean$')
        self.assertRegex(block.group(1), r'(?m)^        default: false$')

    def test_no_deploy_job_runs_when_plan_only(self):
        for job in ('deploy-app', 'deploy-infra'):
            condition = re.search(r'(?m)^    if: (.+)$', job_block(job))
            self.assertIsNotNone(condition, job)
            self.assertIn('!inputs.plan_only', condition.group(1), job)

    def test_the_plan_job_still_runs_when_plan_only(self):
        condition = re.search(r'(?ms)^    if: >-\n(.*?)^    runs-on:', job_block('plan'))
        self.assertIsNotNone(condition)
        self.assertNotIn('plan_only', condition.group(1))


if __name__ == '__main__':
    unittest.main()
