"""The auto-merge gate (owner decision N2, 2026-09-19): a PR that changes what deploys to AWS is merged by the owner."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/auto-merge.yml').read_text()


class AutoMergeGateTest(unittest.TestCase):
    def gated(self):
        # The step greps the PR's file list with this pattern; the POSIX ERE it uses is also a Python regex.
        match = re.search(r"^\s*gated='([^']+)'$", WORKFLOW, re.M)
        self.assertIsNotNone(match, 'gated pattern not found')
        return re.compile(match.group(1))

    def test_the_aws_cd_path_is_gated_and_nothing_else(self):
        gated = self.gated()
        for path in ['.github/workflows/staging-release.yml', '.github/workflows/staging-reconcile.yml',
                     '.github/workflows/auto-merge.yml', '.github/actions/setup-aws-cli/action.yml',
                     'infra/src/staging.ts', 'infra/iam/operator.json', 'scripts/aws/staging_operator.py']:
            self.assertTrue(gated.search(path), path)
        for path in ['apps/api/src/main/java/io/nullnull/NullnullApplication.java', 'apps/web/src/main.tsx',
                     '.github/workflows/integration.yml', 'scripts/tests/test_aws_operator.py', 'scripts/aws_notes.md',
                     'infrastructure/README.md', 'docs/operations/STAGING_DEPLOYMENT_RUNBOOK.md']:
            self.assertFalse(gated.search(path), path)

    def test_the_gate_runs_as_main_has_it_and_never_runs_pr_code(self):
        # On pull_request a PR runs its own copy of this file and could drop the gate for itself.
        self.assertRegex(WORKFLOW, r'(?m)^  pull_request_target:$')
        self.assertNotRegex(WORKFLOW, r'(?m)^  pull_request:$')
        self.assertNotIn('actions/checkout', WORKFLOW)
        self.assertNotIn('github.event.pull_request.head.sha', WORKFLOW)

    def test_renames_and_oversized_prs_are_gated(self):
        self.assertIn('.previous_filename // empty', WORKFLOW)
        self.assertIn('"$CHANGED_FILES" -gt 3000', WORKFLOW)
        self.assertIn('gh pr merge "$PR_URL" --disable-auto', WORKFLOW)


if __name__ == '__main__':
    unittest.main()
