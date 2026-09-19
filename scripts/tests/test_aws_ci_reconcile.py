"""Decision tests for the staging CD gates (scripts/aws/ci_reconcile.py); no network."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('nullnull_ci_reconcile', ROOT / 'scripts/aws/ci_reconcile.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

SHA = 'a' * 40
OTHER = 'b' * 40


def run(status='completed', conclusion='success', event='workflow_dispatch', sha=SHA, branch='main'):
    return {'status': status, 'conclusion': conclusion, 'event': event, 'head_sha': sha, 'head_branch': branch}


class WorkflowStateTest(unittest.TestCase):
    def test_only_this_sha_on_main_from_push_or_dispatch_counts(self):
        self.assertEqual('success', gate.workflow_state([run(event='push')], SHA))
        self.assertEqual('absent', gate.workflow_state([run(event='pull_request')], SHA))
        self.assertEqual('absent', gate.workflow_state([run(sha=OTHER)], SHA))
        self.assertEqual('absent', gate.workflow_state([run(branch='backend')], SHA))

    def test_cancelled_is_not_a_verdict(self):
        # Both required workflows cancel in progress per ref; a superseded run proves nothing either way.
        self.assertEqual('absent', gate.workflow_state([run(conclusion='cancelled')], SHA))

    def test_running_wins_and_failure_is_final(self):
        self.assertEqual('running', gate.workflow_state([run(), run(status='queued', conclusion=None)], SHA))
        self.assertEqual('failed', gate.workflow_state([run(conclusion='failure')], SHA))
        self.assertEqual('success', gate.workflow_state([run(conclusion='failure'), run()], SHA))


class ReconcileDecisionTest(unittest.TestCase):
    OK = {'docs-contract.yml': 'success', 'integration.yml': 'success'}

    def test_untested_head_gets_its_checks_dispatched(self):
        states = {'docs-contract.yml': 'absent', 'integration.yml': 'success'}
        self.assertEqual(('dispatch-ci', 'docs-contract.yml'), gate.reconcile_decision(states, [], SHA))

    def test_never_dispatches_while_checks_run(self):
        states = {'docs-contract.yml': 'running', 'integration.yml': 'absent'}
        self.assertEqual('wait', gate.reconcile_decision(states, [], SHA)[0])

    def test_a_failed_check_stops_the_pipeline(self):
        states = {'docs-contract.yml': 'failed', 'integration.yml': 'success'}
        self.assertEqual('stop', gate.reconcile_decision(states, [], SHA)[0])

    def test_green_head_is_released_exactly_once(self):
        self.assertEqual(('dispatch-release', SHA), gate.reconcile_decision(self.OK, [], SHA))
        self.assertEqual('wait', gate.reconcile_decision(self.OK, [run(status='in_progress', conclusion=None)], SHA)[0])
        for conclusion in ['success', 'failure']:
            self.assertEqual('done', gate.reconcile_decision(self.OK, [run(conclusion=conclusion)], SHA)[0])
        # A cancelled release (replaced while pending) did not happen; release again.
        self.assertEqual('dispatch-release', gate.reconcile_decision(self.OK, [run(conclusion='cancelled')], SHA)[0])
        self.assertEqual('dispatch-release', gate.reconcile_decision(self.OK, [run(sha=OTHER)], SHA)[0])


class VerifyTest(unittest.TestCase):
    ENV = {'GITHUB_REPOSITORY': 'o/r', 'GITHUB_SHA': SHA, 'GITHUB_REF': 'refs/heads/main', 'GH_TOKEN': 't'}

    def args(self, sha=SHA, action='deploy'):
        from types import SimpleNamespace
        return SimpleNamespace(sha=sha, action=action)

    def test_deploy_requires_this_runs_commit_and_both_checks(self):
        with patch.dict(os.environ, self.ENV), patch.object(gate, 'runs_for', return_value=[run()]):
            gate.verify(self.args())
        with patch.dict(os.environ, self.ENV), patch.object(gate, 'runs_for', return_value=[run()]):
            with self.assertRaisesRegex(gate.GateError, 'expected-sha-is-not-this-runs-commit'):
                gate.verify(self.args(sha=OTHER))
        with patch.dict(os.environ, self.ENV), patch.object(gate, 'runs_for', return_value=[run(event='pull_request')]):
            with self.assertRaisesRegex(gate.GateError, 'required-ci-not-successful'):
                gate.verify(self.args())

    def test_only_main_may_release(self):
        with patch.dict(os.environ, {**self.ENV, 'GITHUB_REF': 'refs/heads/backend'}):
            with self.assertRaisesRegex(gate.GateError, 'release-runs-only-on-main'):
                gate.verify(self.args(action='rollback'))

    def test_reconcile_is_off_unless_switched_on(self):
        with patch.dict(os.environ, {**self.ENV, 'STAGING_AUTO_DEPLOY': ''}), patch.object(gate, 'api') as api:
            gate.reconcile(None)
            api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
