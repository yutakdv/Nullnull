#!/usr/bin/env python3
"""One-time IAM foundation for Nullnull staging, applied with an IAM-capable profile (not the operator role).

Creates/updates, from the reviewable documents in infra/iam/:
  NullnullStgRoleBoundary   permissions boundary every role of the CDK app must carry
  NullnullStgCfnExecution*  the three policies of the CDK CloudFormation execution role (cdk bootstrap
                            --cloudformation-execution-policies, one flag each; IAM caps a policy at 6,144 characters)
  NullnullStgOperator       policy of role nullnull-stg-operator (trust: infra/iam/operator-trust.json)

plan    renders the documents for the calling account and prints the bundle hash; no AWS writes.
execute requires --plan, --approved-sha256 of that exact bundle and --execute. Never prints the account id.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
IAM_DIR = ROOT / 'infra' / 'iam'
POLICIES = {'NullnullStgRoleBoundary': 'role-boundary.json', 'NullnullStgCfnExecution': 'cfn-execution.json',
            'NullnullStgCfnExecutionNetworkGuards': 'cfn-execution-network-guards.json',
            'NullnullStgCfnExecutionServiceGuards': 'cfn-execution-service-guards.json',
            'NullnullStgOperator': 'operator.json'}
OPERATOR_ROLE = 'nullnull-stg-operator'
MANAGED_POLICY_LIMIT = 6144
TAGS = [{'Key': 'Project', 'Value': 'Nullnull'}, {'Key': 'Environment', 'Value': 'staging'},
        {'Key': 'ManagedBy', 'Value': 'staging-iam.py'}, {'Key': 'Expiry', 'Value': '2026-10-25'}]

class IamError(Exception):
    pass

def require(ok, reason):
    if not ok:
        raise IamError(reason)

def aws(service, operation, **parameters):
    profile = os.environ.get('NULLNULL_IAM_ADMIN_PROFILE', 'default')
    env = {k: v for k, v in os.environ.items() if k not in ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')}
    # A private file, not a pipe: AWS CLI v2 reads file:///dev/stdin as empty (measured 2026-09-18).
    with tempfile.TemporaryDirectory(prefix='nullnull-iam-') as private:
        path = Path(private) / 'input.json'
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(parameters, f)
        p = subprocess.run(['aws', '--no-cli-pager', '--profile', profile, '--region', 'us-east-1', service, operation,
                            '--output', 'json', '--cli-input-json', 'file://' + str(path)],
                           text=True, capture_output=True, env=env, timeout=120)
    if p.returncode and 'NoSuchEntity' in p.stderr:
        return None
    require(p.returncode == 0, f'aws-failed-{service}-{operation}')
    return json.loads(p.stdout or '{}')

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'))

def render(account):
    require(len(account) == 12 and account.isdigit(), 'invalid-account')
    documents = {name: json.loads((IAM_DIR / file).read_text().replace('${Account}', account))
                 for name, file in POLICIES.items()}
    trust = json.loads((IAM_DIR / 'operator-trust.json').read_text().replace('${Account}', account))
    require('${' not in canonical([documents, trust]), 'unrendered-placeholder')
    for name, document in documents.items():
        # IAM refuses a managed policy over 6,144 characters (whitespace excluded); fail at plan time, before
        # any policy is written, instead of halfway through an execute.
        require(len(canonical(document)) <= MANAGED_POLICY_LIMIT, 'policy-too-large-' + name)
    return {'version': 1, 'policies': documents, 'operatorTrust': trust, 'operatorRole': OPERATOR_ROLE}

def plan(_args):
    identity = aws('sts', 'get-caller-identity')
    bundle = render(identity['Account'])
    directory = ROOT / '.artifacts/aws/iam' / str(uuid.uuid4())
    directory.mkdir(parents=True, mode=0o700)
    path = directory / 'bundle.json'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(canonical({**bundle, 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat()}))
    print('iam_action=plan aws_writes=0 policies=' + ','.join(POLICIES) + ' role=' + OPERATOR_ROLE)
    print('plan_path=' + str(path))
    print('approved_sha256=' + hashlib.sha256(path.read_bytes()).hexdigest())

def policy_document(arn):
    policy = aws('iam', 'get-policy', PolicyArn=arn)
    if policy is None:
        return None, None
    version = policy['Policy']['DefaultVersionId']
    document = aws('iam', 'get-policy-version', PolicyArn=arn, VersionId=version)['PolicyVersion']['Document']
    return policy['Policy'], json.loads(document) if isinstance(document, str) else document

def apply_policy(account, name, document):
    arn = f'arn:aws:iam::{account}:policy/{name}'
    current, live = policy_document(arn)
    if current is None:
        aws('iam', 'create-policy', PolicyName=name, PolicyDocument=canonical(document), Tags=TAGS,
            Description='Nullnull staging; source infra/iam, applied by scripts/aws/staging-iam.py')
        return 'created'
    if canonical(live) == canonical(document):
        return 'unchanged'
    versions = aws('iam', 'list-policy-versions', PolicyArn=arn)['Versions']
    if len(versions) >= 5:
        oldest = sorted((v for v in versions if not v['IsDefaultVersion']), key=lambda v: v['CreateDate'])[0]
        aws('iam', 'delete-policy-version', PolicyArn=arn, VersionId=oldest['VersionId'])
    aws('iam', 'create-policy-version', PolicyArn=arn, PolicyDocument=canonical(document), SetAsDefault=True)
    return 'updated'

def apply_role(account, trust):
    role = aws('iam', 'get-role', RoleName=OPERATOR_ROLE)
    if role is None:
        aws('iam', 'create-role', RoleName=OPERATOR_ROLE, AssumeRolePolicyDocument=canonical(trust),
            MaxSessionDuration=14400, Tags=TAGS,
            Description='Nullnull staging operator (local CLI). Policy NullnullStgOperator only.')
        state = 'created'
    else:
        live = role['Role']['AssumeRolePolicyDocument']
        live = json.loads(live) if isinstance(live, str) else live
        state = 'unchanged'
        if canonical(live) != canonical(trust):
            aws('iam', 'update-assume-role-policy', RoleName=OPERATOR_ROLE, PolicyDocument=canonical(trust))
            state = 'trust-updated'
    operator_arn = f'arn:aws:iam::{account}:policy/NullnullStgOperator'
    attached = [p['PolicyArn'] for p in aws('iam', 'list-attached-role-policies', RoleName=OPERATOR_ROLE)['AttachedPolicies']]
    inline = aws('iam', 'list-role-policies', RoleName=OPERATOR_ROLE)['PolicyNames']
    # The role is exactly one reviewed policy. Anything else attached is drift, not something to merge.
    require(not inline and set(attached) <= {operator_arn}, 'operator-role-has-unreviewed-policies')
    if operator_arn not in attached:
        aws('iam', 'attach-role-policy', RoleName=OPERATOR_ROLE, PolicyArn=operator_arn)
    return state

def execute(args):
    require(args.plan and args.approved_sha256, 'execute-requires-plan-and-approved-sha256')
    path = Path(args.plan).resolve()
    require(hashlib.sha256(path.read_bytes()).hexdigest() == args.approved_sha256, 'reviewed-plan-does-not-match')
    stored = json.loads(path.read_text())
    created = dt.datetime.fromisoformat(stored.pop('createdAt'))
    require(dt.timedelta(0) <= dt.datetime.now(dt.timezone.utc) - created <= dt.timedelta(hours=24), 'plan-older-than-24-hours')
    identity = aws('sts', 'get-caller-identity')
    account = identity['Account']
    # Re-render from today's files for this account: the approved bundle must still be what the repo says.
    require(canonical(render(account)) == canonical(stored), 'iam-documents-changed-since-plan')
    # Every policy the approved bundle carries, in POLICIES order; a hand-kept list here once skipped two.
    require(set(stored['policies']) == set(POLICIES), 'bundle-policy-set-differs')
    for name in POLICIES:
        print(f'policy={name} result={apply_policy(account, name, stored["policies"][name])}')
    print(f'role={OPERATOR_ROLE} result={apply_role(account, stored["operatorTrust"])}')

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--plan'); parser.add_argument('--approved-sha256'); parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        execute(args) if args.execute else plan(args)
    except (IamError, OSError, KeyError, ValueError) as error:
        print('staging_iam=failed reason=' + (str(error) if isinstance(error, IamError) else 'invalid-input-or-local-operation'),
              file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
