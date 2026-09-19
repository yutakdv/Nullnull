"""Offline tests for scripts/aws/staging-iam.py (the one-time IAM foundation); no AWS calls."""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('nullnull_staging_iam', ROOT / 'scripts/aws/staging-iam.py')
iam = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iam)
ACCOUNT = '1' * 12


class RenderTest(unittest.TestCase):
    def test_every_placeholder_is_rendered_for_the_calling_account(self):
        bundle = iam.render(ACCOUNT)
        text = iam.canonical(bundle)
        self.assertNotIn('${', text)
        self.assertIn(f'arn:aws:iam::{ACCOUNT}:policy/NullnullStgRoleBoundary', text)
        self.assertEqual({'NullnullStgRoleBoundary', 'NullnullStgCfnExecution', 'NullnullStgCfnExecutionNetworkGuards',
                          'NullnullStgCfnExecutionServiceGuards', 'NullnullStgOperator'}, set(bundle['policies']))

    def test_every_managed_policy_fits_the_iam_size_limit(self):
        # Measured 2026-09-19: one 13,981-character execution policy was refused by create-policy-version.
        for name, document in iam.render(ACCOUNT)['policies'].items():
            with self.subTest(policy=name):
                self.assertLessEqual(len(iam.canonical(document)), 6144)

    def test_an_oversized_policy_is_refused_at_plan_time(self):
        original = iam.POLICIES
        with tempfile.TemporaryDirectory() as d:
            big = {'Version': '2012-10-17', 'Statement': [{'Sid': 'S' + str(i), 'Effect': 'Deny', 'Action': 'ec2:RunInstances', 'Resource': '*'}
                                                          for i in range(200)]}
            (Path(d) / 'big.json').write_text(json.dumps(big))
            (Path(d) / 'operator-trust.json').write_text((iam.IAM_DIR / 'operator-trust.json').read_text())
            with patch.object(iam, 'IAM_DIR', Path(d)), patch.object(iam, 'POLICIES', {'Big': 'big.json'}):
                with self.assertRaisesRegex(iam.IamError, 'policy-too-large-Big'):
                    iam.render(ACCOUNT)
        self.assertIs(original, iam.POLICIES)

    def test_the_operator_is_trusted_by_exactly_one_principal(self):
        statements = iam.render(ACCOUNT)['operatorTrust']['Statement']
        self.assertEqual(1, len(statements))
        self.assertEqual({'AWS': f'arn:aws:iam::{ACCOUNT}:user/Yutak_trading'}, statements[0]['Principal'])

    def test_malformed_account_is_refused(self):
        with self.assertRaises(iam.IamError):
            iam.render('12345')


class CeilingTest(unittest.TestCase):
    """What CloudFormation may do with the execution role; the only ceiling a green PR to main can reach."""
    EXECUTION = ['NullnullStgCfnExecution', 'NullnullStgCfnExecutionNetworkGuards', 'NullnullStgCfnExecutionServiceGuards']

    def statements(self, name=None):
        # The execution role carries the three managed policies together (IAM caps each at 6,144 characters).
        policies = iam.render(ACCOUNT)['policies']
        return policies[name]['Statement'] if name else [s for n in self.EXECUTION for s in policies[n]['Statement']]

    def by_sid(self, sid):
        return next(s for s in self.statements() if s['Sid'] == sid)

    def test_iam_writes_never_reach_the_hand_made_operator_role(self):
        import fnmatch
        operator = f'arn:aws:iam::{ACCOUNT}:role/nullnull-stg-operator'
        for statement in self.statements():
            if statement['Effect'] != 'Allow':
                continue
            actions = [statement['Action']] if isinstance(statement['Action'], str) else statement['Action']
            if not any(a.startswith('iam:') for a in actions):
                continue
            resources = [statement['Resource']] if isinstance(statement['Resource'], str) else statement['Resource']
            with self.subTest(sid=statement['Sid']):
                self.assertFalse([r for r in resources if r != '*' and fnmatch.fnmatchcase(operator, r)])

    def test_only_the_two_staging_regions(self):
        statement = self.by_sid('OnlyTheStagingRegions')
        self.assertEqual(('Deny', '*', '*'), (statement['Effect'], statement['Action'], statement['Resource']))
        self.assertEqual({'StringNotEquals': {'aws:RequestedRegion': ['ap-northeast-2', 'us-east-1']}}, statement['Condition'])

    def test_other_projects_resources_cannot_be_retagged_as_nullnull(self):
        # A RequestTag escape would let a template tag someone else's resource Project=Nullnull and then pass
        # every tag guard; tags are only allowed at creation (CreateAction) or on resources already ours.
        for sid, key in [('NoRetaggingOtherProjectsEc2', 'ec2:CreateAction'),
                         ('NoRetaggingOtherProjectsLoadBalancers', 'elasticloadbalancing:CreateAction'),
                         ('NoRetaggingOtherProjectsEcs', 'ecs:CreateAction')]:
            with self.subTest(sid=sid):
                self.assertEqual({'StringNotEquals': {'aws:ResourceTag/Project': 'Nullnull'}, 'Null': {key: 'true'}},
                                 self.by_sid(sid)['Condition'])

    def test_data_can_never_be_copied_shared_or_restored_elsewhere(self):
        unconditional = set()
        for statement in self.statements():
            if statement['Effect'] == 'Deny' and 'Condition' not in statement and statement.get('Resource') == '*':
                unconditional.update([statement['Action']] if isinstance(statement['Action'], str) else statement['Action'])
        for action in ['rds:ModifyDBSnapshotAttribute', 'rds:ModifyDBClusterSnapshotAttribute', 'rds:CopyDBSnapshot',
                       'rds:RestoreDBInstanceFromDBSnapshot', 'rds:StartExportTask', 'rds:CreateDBInstanceReadReplica',
                       'ec2:ModifySnapshotAttribute', 'ec2:ModifyImageAttribute', 'ec2:CreateTrafficMirrorSession',
                       'ec2:CreateVpcPeeringConnection', 'ec2:RunInstances', 'cloudfront:AssociateAlias']:
            self.assertIn(action, unconditional)

    def test_a_tag_guard_never_covers_the_resource_its_own_action_creates(self):
        # IAM evaluates the new resource too, and it has no tags yet: guarding it would deny our own creates.
        created = {'ec2:CreateSubnet': 'subnet/', 'ec2:CreateRouteTable': 'route-table/',
                   'ec2:CreateSecurityGroup': 'security-group/', 'ec2:CreateNetworkAcl': 'network-acl/',
                   'ec2:CreateNetworkInterface': 'network-interface/', 'rds:CreateDBSnapshot': ':snapshot:',
                   'elasticloadbalancing:CreateListener': ':listener/', 'elasticloadbalancing:CreateRule': ':listener-rule/',
                   'servicediscovery:CreateService': ':service/'}
        seen = set()
        for statement in self.statements():
            if statement['Effect'] != 'Deny' or 'Condition' not in statement:
                continue
            actions = [statement['Action']] if isinstance(statement['Action'], str) else statement['Action']
            resources = [statement['Resource']] if isinstance(statement['Resource'], str) else statement['Resource']
            for action in set(actions) & set(created):
                seen.add(action)
                with self.subTest(sid=statement['Sid'], action=action):
                    self.assertNotIn('*', resources)
                    self.assertFalse([r for r in resources if created[action] in r])
        self.assertEqual(set(created), seen)

    def test_roles_are_passed_only_to_the_services_they_trust(self):
        # Synthesized trust policies name exactly ecs-tasks (task/execution roles) and lambda (web deployment).
        passing = [s for s in self.statements() if s['Effect'] == 'Allow'
                   and 'iam:PassRole' in ([s['Action']] if isinstance(s['Action'], str) else s['Action'])]
        self.assertEqual(['PassRolesOnlyToTheirServices'], [s['Sid'] for s in passing])
        self.assertEqual({'StringEquals': {'iam:PassedToService': ['ecs-tasks.amazonaws.com', 'lambda.amazonaws.com']}},
                         passing[0]['Condition'])

    def test_github_and_app_roles_never_reach_the_cdk_lookup_role(self):
        boundary = self.statements('NullnullStgRoleBoundary')
        assume = next(s for s in boundary if s['Sid'] == 'AssumeNullnullCdkRoles')
        self.assertEqual([f'arn:aws:iam::{ACCOUNT}:role/cdk-nnstg-deploy-role-*',
                          f'arn:aws:iam::{ACCOUNT}:role/cdk-nnstg-file-publishing-role-*'], assume['Resource'])

    def test_the_operator_reaches_only_the_alarm_topic_and_subscribes_only_email(self):
        # staging-alarm-subscribe.sh runs as the operator: it lists, subscribes (email) and publishes the test alarm.
        actions = lambda s: [s['Action']] if isinstance(s['Action'], str) else s['Action']
        sns = [s for s in self.statements('NullnullStgOperator') if any(a.startswith('sns:') for a in actions(s))]
        self.assertEqual({f'arn:aws:sns:ap-northeast-2:{ACCOUNT}:NullnullStgObservability-*'}, {s['Resource'] for s in sns})
        self.assertEqual({'sns:ListSubscriptionsByTopic', 'sns:Publish', 'sns:Subscribe'}, {a for s in sns for a in actions(s)})
        subscribe = [s for s in sns if 'sns:Subscribe' in actions(s)]
        self.assertEqual([{'StringEquals': {'sns:Protocol': 'email'}}], [s.get('Condition') for s in subscribe])


class ExecuteGateTest(unittest.TestCase):
    def plan_file(self, directory, created=None, bundle=None):
        path = Path(directory) / 'bundle.json'
        created = created or dt.datetime.now(dt.timezone.utc)
        path.write_text(iam.canonical({**(bundle or iam.render(ACCOUNT)), 'createdAt': created.isoformat()}))
        return path, hashlib.sha256(path.read_bytes()).hexdigest()

    def args(self, path, sha):
        from types import SimpleNamespace
        return SimpleNamespace(plan=str(path), approved_sha256=sha, execute=True)

    def test_a_different_hash_is_refused_before_any_call(self):
        with tempfile.TemporaryDirectory() as d, patch.object(iam, 'aws') as aws:
            path, _ = self.plan_file(d)
            with self.assertRaisesRegex(iam.IamError, 'reviewed-plan-does-not-match'):
                iam.execute(self.args(path, 'f' * 64))
            aws.assert_not_called()

    def test_a_stale_plan_is_refused(self):
        with tempfile.TemporaryDirectory() as d, patch.object(iam, 'aws') as aws:
            path, sha = self.plan_file(d, created=dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=25))
            with self.assertRaisesRegex(iam.IamError, 'plan-older-than-24-hours'):
                iam.execute(self.args(path, sha))
            aws.assert_not_called()

    def test_documents_edited_after_the_plan_are_refused(self):
        with tempfile.TemporaryDirectory() as d:
            bundle = iam.render(ACCOUNT)
            bundle['policies']['NullnullStgOperator']['Statement'].append({'Effect': 'Allow', 'Action': '*', 'Resource': '*'})
            path, sha = self.plan_file(d, bundle=bundle)
            with patch.object(iam, 'aws', return_value={'Account': ACCOUNT}):
                with self.assertRaisesRegex(iam.IamError, 'iam-documents-changed-since-plan'):
                    iam.execute(self.args(path, sha))


class ExecuteAppliesTheWholeBundleTest(unittest.TestCase):
    def test_every_policy_in_the_approved_bundle_is_applied(self):
        with tempfile.TemporaryDirectory() as d:
            path, sha = ExecuteGateTest().plan_file(d)
            with patch.object(iam, 'aws', return_value={'Account': ACCOUNT}), \
                 patch.object(iam, 'apply_policy', return_value='unchanged') as apply_policy, \
                 patch.object(iam, 'apply_role', return_value='unchanged'):
                iam.execute(ExecuteGateTest().args(path, sha))
        self.assertEqual(list(iam.POLICIES), [c.args[1] for c in apply_policy.call_args_list])


class OperatorRoleDriftTest(unittest.TestCase):
    def test_an_extra_attached_policy_is_drift_not_something_to_merge(self):
        def fake(service, operation, **kw):
            return {'get-role': {'Role': {'AssumeRolePolicyDocument': iam.render(ACCOUNT)['operatorTrust']}},
                    'list-attached-role-policies': {'AttachedPolicies': [
                        {'PolicyArn': f'arn:aws:iam::{ACCOUNT}:policy/NullnullStgOperator'},
                        {'PolicyArn': 'arn:aws:iam::aws:policy/AdministratorAccess'}]},
                    'list-role-policies': {'PolicyNames': []}}[operation]
        with patch.object(iam, 'aws', side_effect=fake):
            with self.assertRaisesRegex(iam.IamError, 'operator-role-has-unreviewed-policies'):
                iam.apply_role(ACCOUNT, iam.render(ACCOUNT)['operatorTrust'])


if __name__ == '__main__':
    unittest.main()
