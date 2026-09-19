#!/usr/bin/env python3
"""BA-071: immutable local plans and guarded AWS execution. Never prints AWS error bodies or secret values."""
from __future__ import annotations
import argparse
import base64
import datetime as dt
import difflib
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
REGION = 'ap-northeast-2'
PREFIX = 'NullnullStg'
TOOLKIT_STACK = 'NullnullStgCDKToolkit'
LOCK_TABLE = 'nullnull-stg-deployment-lock'
KTO_SECRET = 'nullnull-stg/kto-service-key'
EXPIRY = dt.datetime(2026, 10, 25, 14, 59, 59, tzinfo=dt.timezone.utc)
PROTECTED = ['Foundation', 'Network', 'Data', 'Platform', 'GlobalWaf', 'Observability']
# Foundation leads: only the runtime-phase Foundation template carries the exports Migration/Services
# import (the bootstrap-phase one has no consumers, so no exports).
INFRA_ORDER = ['Foundation', 'Network', 'GlobalWaf', 'Data', 'Platform', 'Observability']
# What an app release (and every rollback) deploys; everything else is infra.
APP_STACKS = ['Migration', 'WebEdge', 'Services']
STACKS = PROTECTED + APP_STACKS
# A release's own markers inside the app-stack templates: the '@sha256:<digest>' tail of an ECR image URI and
# APP_RELEASE_VERSION (format owned by validate-release-manifest.mjs). Nothing else is ever normalized away.
IMAGE_DIGEST_PART = re.compile(r'@sha256:[0-9a-f]{64}')
RELEASE_VERSION = re.compile(r'v0\.[0-9]+\.[0-9]+(?:-rc\.[0-9]+)?')
ACCOUNT_DIGITS = re.compile(r'(?<![0-9])[0-9]{12}(?![0-9])')
# Who may execute: the local operator role (profile auth) and the GitHub deploy role (OIDC session in env).
ROLES = {'profile': 'nullnull-stg-operator', 'ambient': 'nullnull-stg-github-deploy'}
CREDENTIAL_VARIABLES = ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN',
                        'AWS_SECURITY_TOKEN', 'AWS_WEB_IDENTITY_TOKEN_FILE', 'AWS_ROLE_ARN']
# Operator one-off commands (ops task definition). The main class is fixed here, never taken from input.
OPS_TASKS = {
    'kto-smoke': ('io.nullnull.catalog.infrastructure.kto.KtoSmokeMain', 'NULLNULL_KTO_SMOKE_APPROVED',
                  {'NULLNULL_KTO_SMOKE_CONTENT_ID': 'content_id', 'NULLNULL_KTO_SMOKE_CONTENT_TYPE_ID': 'content_type_id'}),
    'kto-ingest': ('io.nullnull.catalog.infrastructure.kto.KtoCanonicalIngestMain', None,
                   {'NULLNULL_KTO_INGEST_CONTENT_ID': 'content_id', 'NULLNULL_KTO_INGEST_CONTENT_TYPE_ID': 'content_type_id'}),
    'kto-forecast-smoke': ('io.nullnull.catalog.infrastructure.kto.KtoForecastSmokeMain',
                           'NULLNULL_KTO_FORECAST_SMOKE_APPROVED', {'NULLNULL_KTO_FORECAST_SMOKE_PLACE_ID': 'place_id'}),
    # Demo place refresh (c8's contract): detail first, it creates the canonical places the forecast needs.
    'kto-demo-detail': ('io.nullnull.catalog.infrastructure.kto.KtoDemoDetailRefreshMain', 'NULLNULL_KTO_SMOKE_APPROVED',
                        {'NULLNULL_DEMO_PLACES': 'places'}),
    'kto-demo-forecast': ('io.nullnull.catalog.infrastructure.kto.KtoDemoForecastRefreshMain',
                          'NULLNULL_KTO_FORECAST_SMOKE_APPROVED', {'NULLNULL_DEMO_PLACES': 'places'}),
    # Curated opening hours (A-031/A-032). No KTO call, so no KTO approval variable: the owner approves the exact
    # plan bytes instead (CURATION_PLANS below), and records who did with --owner-approval.
    'curate-hours': ('io.nullnull.catalog.infrastructure.curation.CuratedHoursImportMain', None, {}),
    # Curated feed posts (A-031, #183), the same way: no provider call, the owner approves the plan's bytes.
    'curate-posts': ('io.nullnull.social.infrastructure.curation.CuratedPostImportMain', None, {}),
}
# A curate task's plan cannot be a file in the task: it runs the release's image with a read-only root, and baking
# the plan into the image would make every plan edit a release (the hours re-observation before 2026-10-13 falls in
# judging, and each release closes the edge). So the operator reads the local plan file, the owner approves its
# sha256, and the exact bytes travel gzip+base64 in one override variable with that sha; the main refuses any other
# bytes (io.nullnull.OperationsPlan) and prints the sha it imported, which is compared here after the task stops.
# The override is visible to anyone who can describe the task and is kept by CloudTrail, so a plan must never carry
# anything sensitive; an hours plan holds public notice URLs, place ids and opening times.
# Per task: where the bytes travel, the sha line the main echoes, the prefix of every line it prints, the plan's list
# of items, and the line that says all of them landed ({n} is the item count). The hours record every place; the posts
# are published or were already there, so a rerun of an approved plan still succeeds with "0 of 5".
CURATION_PLANS = {'curate-hours': {'inline': 'NULLNULL_HOURS_PLAN_GZIP_BASE64', 'sha256': 'NULLNULL_HOURS_PLAN_SHA256',
                                   'echo': 'curated_hours_plan', 'lines': 'curated_hours', 'items': 'places',
                                   'done': r'curated_hours_recorded={n}', 'failed': 'curated_hours_failed ',
                                   'incomplete': 'curation-not-all-places-recorded'},
                  'curate-posts': {'inline': 'NULLNULL_POSTS_PLAN_GZIP_BASE64', 'sha256': 'NULLNULL_POSTS_PLAN_SHA256',
                                   'echo': 'curated_posts_plan', 'lines': 'curated_post', 'items': 'posts',
                                   'done': r'curated_posts_published=[0-9]{{1,4}} of {n}', 'failed': 'curated_posts_failed ',
                                   'incomplete': 'curation-not-all-posts-published'}}
PLAN_MAX_BYTES = 1 << 20  # io.nullnull.OperationsPlan.MAX_BYTES
# RunTask refuses overrides past a size AWS documents as 8192 characters for the whole overrides object; that figure is
# not recorded in this repository and was not measured, so these bounds keep well under it rather than at it.
PLAN_INLINE_MAX_CHARS = 6000
OVERRIDES_MAX_CHARS = 7500
PLACE_ID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
# Input shapes per ops argument. A demo place list is `contentId:contentTypeId`, comma separated.
OPS_INPUT = {'content_id': r'[0-9a-f-]{1,40}', 'content_type_id': r'[0-9a-f-]{1,40}', 'place_id': r'[0-9a-f-]{1,40}',
             'places': r'[1-9][0-9]{0,29}:[1-9][0-9]{0,29}(,[1-9][0-9]{0,29}:[1-9][0-9]{0,29})*'}
# Every task above writes. From the release carrying OperationsContext (#183) a writing tool in staging runs only
# when this names the database its datasource URL points to; an older image ignores it.
OPERATIONS_TARGET = 'NULLNULL_OPERATIONS_TARGET'
# Log lines an ops task may echo: the mains' own redacted evidence and settings-origin lines, OperationsContext's
# target line (no user, password or query), and the failure code they throw. Anything else stays in CloudWatch.
OPS_LOG_LINE = re.compile(r'^(KTO_[A-Z_]+ [A-Za-z0-9_ =:.,()<>/+-]{0,400}|.*Exception: KTO [a-z ]+ failed: [A-Za-z_ ()]{1,80}'
                          # The hours import (CuratedHoursImportMain): the sha it imported, ids and counts, a failure's
                          # code. Never the evidence URL, which stays in the plan file (CuratedHoursImportMainTest mirrors
                          # these four and prints the lines the tests below feed back).
                          r'|curated_hours_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}'
                          r'|curated_hours [0-9a-f-]{36} (RECORDED|REPLACED) \(windows=[0-9]{1,4}\)'
                          r'|curated_hours_recorded=[0-9]{1,4}|curated_hours_failed reason=[A-Za-z_]{1,80}'
                          # The posts import (CuratedPostImportMain), the same four shapes: ids, outcomes and counts, never
                          # a title, body or cover URL (CuratedPostImportMainTest mirrors these four).
                          r'|curated_posts_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}'
                          r'|curated_post [0-9a-f-]{36} (PUBLISHED \([0-9]{1,3} place\(s\)\)|ALREADY_PRESENT \(left as it is\))'
                          r'|curated_posts_published=[0-9]{1,4} of [0-9]{1,4}|curated_posts_failed reason=[A-Za-z_]{1,80}'
                          r'|operations target=(postgresql://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9_]+|unknown)'
                          r' environment=[a-z]+ access=(read|write) schema=(migrate|validate|unchecked))$')

class OpsError(Exception):
    pass

class MissingStack(OpsError):
    pass

def require(ok, reason):
    if not ok:
        raise OpsError(reason)

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def tree_digest(directory):
    directory = Path(directory)
    files = sorted(directory.rglob('*'))
    require(not any(p.is_symlink() for p in files), 'symlink-in-artifact')
    return hashlib.sha256(canonical({p.relative_to(directory).as_posix(): digest(p)
                                   for p in files if p.is_file()})).hexdigest()

def write_private(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_suffix('.tmp')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(value, f, sort_keys=True, indent=2)
    os.replace(temp, path)

def auth_mode():
    mode = os.environ.get('NULLNULL_AWS_AUTH', 'profile')
    require(mode in ROLES, 'invalid-NULLNULL_AWS_AUTH')
    return mode

def child_env():
    env = os.environ.copy()
    if auth_mode() == 'profile':
        # A selected profile must not silently inherit another principal's ambient credentials.
        for key in CREDENTIAL_VARIABLES:
            env.pop(key, None)
    else:
        # GitHub OIDC: short-lived session credentials in the environment and never a named profile, so
        # a runner-local profile cannot stand in for the role the workflow actually assumed.
        require(env.get('GITHUB_ACTIONS') == 'true', 'ambient-auth-outside-github-actions')
        require(all(env.get(k) for k in CREDENTIAL_VARIABLES[:3]), 'ambient-auth-requires-session-credentials')
        for key in ['AWS_PROFILE', 'AWS_DEFAULT_PROFILE']:
            env.pop(key, None)
    env['AWS_REGION'] = REGION
    env['AWS_DEFAULT_REGION'] = REGION
    env['AWS_EC2_METADATA_DISABLED'] = 'true'
    return env

def aws_base(region=REGION):
    args = ['aws', '--no-cli-pager', '--region', region]
    if auth_mode() == 'profile':
        profile = os.environ.get('AWS_PROFILE')
        require(bool(profile), 'missing-AWS_PROFILE')
        args += ['--profile', profile]
    return args

def run(args, cwd=ROOT, timeout=3600, log=None, drop_env=()):
    env = child_env()
    for key in drop_env:
        env.pop(key, None)
    try:
        p = subprocess.run(list(map(str, args)), cwd=cwd, env=env, text=True,
                           capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise OpsError('command-outcome-unknown-lock-retained') from exc
    if p.returncode and log:
        # Full tool output (CloudFormation reasons, ARNs) goes to a private file, never to stdout.
        fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(p.stdout + '\n' + p.stderr)
        print('command_log=' + str(log), file=sys.stderr)
    require(p.returncode == 0, 'command-failed-' + Path(str(args[0])).name)
    return p.stdout

def aws(service, operation, *, region=REGION, **parameters):
    args = aws_base(region) + [service, operation, '--output', 'json']
    # Parameters travel in a private file, never argv: contacts/secrets must not become command-line
    # arguments. A pipe does not work - AWS CLI v2 reads file:///dev/stdin as empty (measured 2026-09-18).
    with tempfile.TemporaryDirectory(prefix='nullnull-aws-') as private:
        path = Path(private)/'input.json'
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            json.dump(parameters, f)
        try:
            p = subprocess.run(args + ['--cli-input-json', 'file://' + str(path)], text=True, capture_output=True,
                               timeout=120, env=child_env())
        except subprocess.TimeoutExpired as exc:
            raise OpsError('aws-outcome-unknown-lock-retained') from exc
    if p.returncode and service == 'cloudformation' and 'does not exist' in p.stderr and 'ValidationError' in p.stderr:
        raise MissingStack('stack-not-created')
    require(p.returncode == 0, 'aws-failed-' + service + '-' + operation)
    return json.loads(p.stdout or '{}')

def identity(account):
    result = aws('sts', 'get-caller-identity')
    require(result.get('Account') == account, 'unexpected-aws-account')
    arn = result.get('Arn', '')
    require(not arn.endswith(':root'), 'root-principal-forbidden')
    require(arn.startswith(f'arn:aws:sts::{account}:assumed-role/{ROLES[auth_mode()]}/'), 'unexpected-aws-principal')

def output(stack, key, region=REGION):
    data = aws('cloudformation', 'describe-stacks', region=region, StackName=PREFIX+stack)
    values = {v['OutputKey']:v['OutputValue'] for v in data['Stacks'][0].get('Outputs',[])}
    require(key in values, 'missing-stack-output-' + key)
    return values[key]

def validate_manifest(path, recorded=False):
    path = Path(path).resolve()
    # A rollback redeploys a release this operator already recorded after an approved deploy. Its commit is
    # by definition not this run's commit, so the validator's expected-SHA check must not see this run's SHA;
    # the shape checks still apply. Only a fresh CI build has to prove it is exactly the verified commit.
    run(['node', ROOT/'scripts/aws/validate-release-manifest.mjs', path],
        drop_env=['NULLNULL_EXPECTED_GIT_SHA'] if recorded else ())
    manifest = json.loads(Path(path).read_text())
    if auth_mode() == 'ambient' and not recorded:
        # CI deploys only a clean, CI-verified commit; the validator's SHA check must not be skippable.
        require(os.environ.get('NULLNULL_EXPECTED_GIT_SHA') == manifest['gitSha'], 'ci-requires-expected-git-sha')
        require(manifest.get('sourceState') == 'clean', 'ci-requires-clean-source')
    return manifest

def check_artifacts(manifest, web):
    for field, path in [('openApiSha256', ROOT/'docs/api/openapi.yaml'),
                        ('eventSchemaSha256', ROOT/'docs/contracts/events.schema.json')]:
        require(manifest[field] == 'sha256:'+digest(path), 'artifact-mismatch-'+field)
    require(manifest['webArtifactSha256'] == 'sha256:'+tree_digest(web), 'web-artifact-mismatch')
    migrations = sorted((ROOT/'apps/api/src/main/resources/db/migration').glob('V*.sql'))
    require(manifest['flywayChecksums'] == [p.name+':'+digest(p) for p in migrations], 'migration-checksum-mismatch')

def image_tag(manifest):
    """The immutable ECR tag that binds a digest to its source. Overlay builds can never look clean."""
    tag = 'sha-' + manifest['gitSha']
    if manifest.get('sourceState') == 'overlay':
        tag += '-ovl-' + manifest['sourceOverlaySha256'].split(':')[1][:12]
    return tag

def cdk(args, log=None):
    return run([ROOT/'infra/node_modules/.bin/cdk', *args, '--no-notices'], cwd=ROOT/'infra', log=log)

def verifier_hash():
    token = os.environ.get('NULLNULL_VERIFIER_TOKEN', '')
    # Without it the gate opens to no one: the release would deploy and only then fail every check behind it.
    require(bool(token), 'verifier-token-required')
    # 32 random bytes, base64url or hex. Only the hash ever leaves this process.
    require(re.fullmatch(r'[A-Za-z0-9_-]{43,128}', token) is not None, 'weak-or-malformed-verifier-token')
    return hashlib.sha256(token.encode()).hexdigest()

def rollback_plan(args):
    require(args.previous_plan and args.previous_plan_sha256, 'rollback-requires-previous-approved-plan')
    source=Path(args.previous_plan).resolve()
    require(digest(source)==args.previous_plan_sha256, 'previous-plan-hash-mismatch')
    previous=json.loads(source.read_text())
    require(previous.get('action') in ['deploy','rollback'], 'cannot-rollback-bootstrap')
    require(previous['account']==os.environ.get('NULLNULL_AWS_ACCOUNT_ID'), 'previous-account-mismatch')
    require(tree_digest(source.parent/'assembly')==previous['assemblySha256'], 'previous-assembly-changed')
    require(digest(source.parent/'release.json')==previous['releaseSha256'], 'previous-release-changed')
    require(digest(source.parent/'cost-basis.txt')==previous['costBasisSha256'], 'previous-cost-basis-changed')
    # Roll back the original assembly, not today's CDK code evaluated with an old image tag.
    directory=ROOT/'.artifacts/aws/plans'/str(uuid.uuid4())
    directory.mkdir(parents=True,mode=0o700)
    for name in ['release.json','cost-basis.txt']:shutil.copyfile(source.parent/name,directory/name)
    shutil.copytree(source.parent/'assembly',directory/'assembly')
    now=dt.datetime.now(dt.timezone.utc)
    previous.update(action='rollback',createdAt=now.isoformat(),rolledBackFromPlanSha256=args.previous_plan_sha256,
                    expiresAt=min(now+dt.timedelta(days=14),EXPIRY).isoformat(),verifierTokenSha256=verifier_hash(),
                    acceptNewerSchema=bool(args.accept_newer_schema))
    write_private(directory/'plan.json',previous)
    print('rollback_action=plan aws_writes=0 database_down_migration=false')
    print('plan_path='+str(directory/'plan.json'))
    print('approved_plan_sha256='+digest(directory/'plan.json'))

def stage_covers(target):
    """#183: copy the curated posts' cover photos into the plan, from the one place the repository keeps them.

    They become an asset of the assembly, so the sha the owner approves for the release covers their bytes; there is
    no separate upload to approve or to forget. Only the .jpg files - the README beside them is not content - and
    nothing that is a link, for the same reason tree_digest refuses one.
    """
    files = sorted(p for p in (ROOT/'docs/contest/covers').glob('*.jpg'))
    require(files, 'cover-photos-missing')
    require(all(p.is_file() and not p.is_symlink() for p in files), 'cover-photo-not-a-file')
    target.mkdir(mode=0o700)
    for source in files:
        shutil.copyfile(source, target/source.name)
    return [p.name for p in files]

def plan(args):
    if args.action=='rollback':
        return rollback_plan(args)
    bootstrap = args.action == 'bootstrap'
    require(bootstrap or (args.manifest and args.web_dir), 'manifest-and-web-dir-required')
    account = os.environ.get('NULLNULL_AWS_ACCOUNT_ID', '')
    require(len(account)==12 and account.isdigit(), 'invalid-account-id')
    manifest = {'kind':'foundation-bootstrap'} if bootstrap else validate_manifest(args.manifest)
    if not bootstrap: check_artifacts(manifest, args.web_dir)
    require(1 <= args.days <= 14, 'invalid-operating-days')
    now = dt.datetime.now(dt.timezone.utc)
    ends = min(now + dt.timedelta(days=args.days), EXPIRY)
    require(ends > now, 'staging-expired')
    # This is an operator-approved estimate, not a price/billing oracle or a hard AWS cap.
    require(args.estimated_total is not None and 0 < args.estimated_total <= 180, 'cost-estimate-required-at-most-180-plus-20-reserve')
    require(args.cost_basis and Path(args.cost_basis).is_file(), 'cost-basis-file-required')
    directory = ROOT/'.artifacts/aws/plans'/str(uuid.uuid4())
    directory.mkdir(parents=True, mode=0o700)
    write_private(directory/'release.json', manifest)
    if not bootstrap:
        shutil.copytree(args.web_dir, directory/'web', symlinks=False)
        check_artifacts(manifest, directory/'web')
        print('covers=' + ','.join(stage_covers(directory/'covers')))
    shutil.copyfile(args.cost_basis, directory/'cost-basis.txt')
    run(['npm','run','build'],cwd=ROOT/'infra')
    # Synth has no lookups and no diff/change-set/asset publishing in plan mode.
    cdk(['synth','--no-lookups','--quiet','--output',directory/'assembly',
         '--context','account='+account,'--context','releaseManifest='+str(directory/'release.json'),
         '--context','webDirectory='+str(directory/'web'),'--context','coversDirectory='+str(directory/'covers'),
         '--context','phase='+('foundation' if bootstrap else 'runtime')])
    data = {'version':1,'account':account,'region':REGION,'action':args.action,
            'createdAt':now.isoformat(),'expiresAt':ends.isoformat(),
            'estimateUsd':args.estimated_total,'reserveUsd':20,
            'releaseSha256':digest(directory/'release.json'),
            'assemblySha256':tree_digest(directory/'assembly'),
            'costBasisSha256':digest(directory/'cost-basis.txt'),
            'toolchainSha256':digest(ROOT/'infra/package-lock.json'),
            'verifierTokenSha256':'' if bootstrap else verifier_hash()}
    write_private(directory/'plan.json', data)
    print('deployment_action=plan aws_writes=0 traffic_enabled=false')
    print('plan_path='+str(directory/'plan.json'))
    print('approved_plan_sha256='+digest(directory/'plan.json'))

def verify_plan(path, approved):
    path = Path(path).resolve()
    require(approved and digest(path)==approved, 'reviewed-plan-does-not-match')
    data = json.loads(path.read_text())
    require(data.get('version')==1 and data.get('region')==REGION, 'invalid-plan')
    require(data['account']==os.environ.get('NULLNULL_AWS_ACCOUNT_ID'), 'plan-account-mismatch')
    require(digest(path.parent/'release.json')==data['releaseSha256'], 'release-changed')
    require(tree_digest(path.parent/'assembly')==data['assemblySha256'], 'assembly-changed')
    require(digest(path.parent/'cost-basis.txt')==data['costBasisSha256'], 'cost-basis-changed')
    require(digest(ROOT/'infra/package-lock.json')==data['toolchainSha256'], 'toolchain-changed')
    require(0<data['estimateUsd']<=180 and data['reserveUsd']==20, 'invalid-cost-plan')
    # Only the Foundation bootstrap deploys no gate; every release/rollback carries the verifier hash.
    pattern = r'([a-f0-9]{64})?' if data.get('action') == 'bootstrap' else r'[a-f0-9]{64}'
    require(re.fullmatch(pattern, data.get('verifierTokenSha256', '')) is not None, 'invalid-verifier-hash')
    now = dt.datetime.now(dt.timezone.utc)
    require(now<dt.datetime.fromisoformat(data['expiresAt'])<=EXPIRY, 'staging-expired')
    require(dt.timedelta(0)<=now-dt.datetime.fromisoformat(data['createdAt'])<=dt.timedelta(hours=24), 'plan-older-than-24-hours')
    return data

class DeploymentLock:
    def __init__(self):
        self.owner = str(uuid.uuid4())
        self.acquired = False
        self.mutated = False
    def mutating(self):
        # Called before the first AWS write this lock guards; from then on a failure keeps the lock.
        self.mutated = True
    def __enter__(self):
        aws('dynamodb','put-item',TableName=LOCK_TABLE,
            Item={'LockId':{'S':'staging'},'Owner':{'S':self.owner},
                  'AcquiredAt':{'S':dt.datetime.now(dt.timezone.utc).isoformat()},
                  'Principal':{'S':ROLES[auth_mode()]}},
            ConditionExpression='attribute_not_exists(LockId)')
        self.acquired=True
        return self
    def check(self):
        item=aws('dynamodb','get-item',TableName=LOCK_TABLE,Key={'LockId':{'S':'staging'}},ConsistentRead=True).get('Item',{})
        require(item.get('Owner',{}).get('S')==self.owner,'deployment-lock-lost')
    def __exit__(self, typ, value, tb):
        # Never steal/TTL-expire a lock whose CloudFormation or ECS operation may still be running. A check
        # that failed before any write guarded nothing, so that lock is released instead of stalling releases.
        if typ is None or not self.mutated:
            aws('dynamodb','delete-item',TableName=LOCK_TABLE,Key={'LockId':{'S':'staging'}},
                ConditionExpression='#owner = :owner',ExpressionAttributeNames={'#owner':'Owner'},
                ExpressionAttributeValues={':owner':{'S':self.owner}})
        else:
            print('deployment_lock=retained owner='+self.owner+' manual_recovery_required=true',file=sys.stderr)

def wait_task(cluster, arn, lock, definition, container_name, expected_digest=None):
    for _ in range(360):
        lock.check()
        state=aws('ecs','describe-tasks',cluster=cluster,tasks=[arn])
        require(not state.get('failures') and len(state.get('tasks',[]))==1,'task-missing')
        task_state=state['tasks'][0]
        if task_state.get('lastStatus')=='STOPPED':
            containers={c['name']:c for c in task_state.get('containers',[])}
            essential=[c['name'] for c in definition['containerDefinitions'] if c.get('essential',True)]
            require(all(containers.get(n,{}).get('exitCode')==0 for n in essential),'task-failed')
            if expected_digest:
                require(containers[container_name].get('imageDigest')==expected_digest,'executed-image-mismatch')
            require(task_state.get('stopCode')=='EssentialContainerExited','unexpected-task-stop')
            return task_state
        time.sleep(5)
    raise OpsError('task-timeout-outcome-unknown')

def migration(manifest, lock):
    lock.check()
    cluster=output('Platform','ClusterName')
    definition=output('Migration','MigrationTaskDefinitionArn')
    task=aws('ecs','describe-task-definition',taskDefinition=definition)['taskDefinition']
    container=next((c for c in task['containerDefinitions'] if c['name']=='migration'),None)
    require(container and container['image'].endswith('@'+manifest['apiImageDigest']), 'migration-image-mismatch')
    require(container.get('command')==['--nullnull.migration-only=true'], 'migration-command-mismatch')
    env={e['name']:e['value'] for e in container.get('environment',[])}
    require(env.get('NULLNULL_JOBS_ENABLED')=='false' and env.get('SPRING_FLYWAY_ENABLED')=='true','migration-environment-mismatch')
    lock.mutating()
    result=aws('ecs','run-task',cluster=cluster,taskDefinition=definition,launchType='FARGATE',count=1,
        clientToken=lock.owner,startedBy='nullnull-stg-migration',networkConfiguration={'awsvpcConfiguration':{
            'subnets':output('Platform','AppSubnetIds').split(','),
            'securityGroups':[output('Platform','MigrationSecurityGroupId')],'assignPublicIp':'ENABLED'}})
    require(not result.get('failures') and len(result.get('tasks',[]))==1,'migration-task-did-not-start')
    try:
        wait_task(cluster, result['tasks'][0]['taskArn'], lock, task, 'migration', manifest['apiImageDigest'])
    except OpsError as error:
        raise OpsError('migration-' + str(error)) from error

def guard_stateful(stack, assembly):
    try:
        old = aws('cloudformation', 'get-template',
                  region='us-east-1' if stack == 'GlobalWaf' else REGION,
                  StackName=PREFIX+stack)['TemplateBody']
    except MissingStack:
        return
    if isinstance(old, str): old = json.loads(old)
    new = json.loads((assembly/(PREFIX+stack+'.template.json')).read_text())
    protected = ('AWS::RDS::', 'AWS::SecretsManager::', 'AWS::S3::Bucket',
                 'AWS::EC2::VPC', 'AWS::EC2::Subnet', 'AWS::DynamoDB::Table')
    resources = new.get('Resources', {})
    for logical_id, resource in old.get('Resources', {}).items():
        if resource['Type'].startswith(protected):
            candidate = resources.get(logical_id)
            require(candidate is not None, 'stateful-resource-removal-forbidden')
            for key in ['Type', 'Properties', 'DeletionPolicy', 'UpdateReplacePolicy']:
                require(resource.get(key) == candidate.get(key), 'stateful-change-requires-separate-review')

def verify_images(manifest):
    tag = image_tag(manifest)
    for name in ['api', 'ai']:
        response = aws('ecr','describe-images',repositoryName='nullnull-stg-'+name,
                       imageIds=[{'imageDigest':manifest[name+'ImageDigest']}])
        require(len(response.get('imageDetails',[])) == 1, 'release-image-not-published')
        detail = response['imageDetails'][0]
        require(detail['imageDigest'] == manifest[name+'ImageDigest'], 'release-image-mismatch')
        # The immutable tag binds this digest to the manifest's source; a digest alone proves only existence.
        require(tag in detail.get('imageTags', []), 'release-image-not-bound-to-source')

def require_kto_secret_provisioned():
    versions = aws('secretsmanager', 'describe-secret', SecretId=KTO_SECRET).get('VersionIdsToStages', {})
    # CDK creates the secret with a generated placeholder; only an operator put adds a second version.
    require(len(versions) >= 2 and any('AWSCURRENT' in stages for stages in versions.values()), 'kto-secret-not-provisioned')

def protected_templates():
    return {s:aws('cloudformation','get-template',region='us-east-1' if s=='GlobalWaf' else REGION,StackName=PREFIX+s)['TemplateBody'] for s in PROTECTED}

def aws_cli(args, timeout=600, input_text=None):
    try:
        p = subprocess.run(aws_base() + list(map(str, args)), env=child_env(), text=True, capture_output=True,
                           timeout=timeout, input=input_text)
    except subprocess.TimeoutExpired as exc:
        raise OpsError('aws-outcome-unknown') from exc
    return p

def release_bucket():
    return output('Foundation', 'ReleaseBucketName')

def read_current_release(bucket):
    p = aws_cli(['s3', 'cp', f's3://{bucket}/deployed/current.json', '-'])
    if p.returncode:
        require('404' in p.stderr or 'Not Found' in p.stderr or 'NoSuchKey' in p.stderr, 'release-record-unreadable')
        return None
    return json.loads(p.stdout)

def normalize_template(template):
    """The template minus what an app release is allowed to change. Structural: only the image digest tail of a
    task definition's image and its APP_RELEASE_VERSION are masked, never a string that merely looks alike."""
    t = json.loads(template) if isinstance(template, str) else json.loads(json.dumps(template))
    t.get('Resources', {}).pop('CDKMetadata', None)
    t.get('Parameters', {}).pop('BootstrapVersion', None)
    t.get('Rules', {}).pop('CheckBootstrapVersion', None)
    for key in ['Rules', 'Parameters', 'Conditions']:
        if key in t and not t[key]:
            t.pop(key)
    for resource in t.get('Resources', {}).values():
        resource.pop('Metadata', None)
        properties = resource.get('Properties', {})
        if resource.get('Type') == 'Custom::CDKBucketDeployment':
            # What a bucket deployment carries - the web bundle, and the #183 covers - is the app path by definition;
            # adding or reshaping a deployment is still a template change, hence infra.
            properties.pop('SourceObjectKeys', None)
        if resource.get('Type') == 'AWS::ECS::TaskDefinition':
            for container in properties.get('ContainerDefinitions', []):
                image = container.get('Image')
                if isinstance(image, dict) and isinstance(image.get('Fn::Join'), list) and len(image['Fn::Join']) == 2:
                    image['Fn::Join'][1] = ['@<release-digest>' if isinstance(p, str) and IMAGE_DIGEST_PART.fullmatch(p) else p
                                           for p in image['Fn::Join'][1]]
                for variable in container.get('Environment', []):
                    if variable.get('Name') == 'APP_RELEASE_VERSION' and RELEASE_VERSION.fullmatch(str(variable.get('Value'))):
                        variable['Value'] = '<release-version>'
    return json.dumps(t, sort_keys=True)

def live_bodies():
    """Raw live template per stack (None when the stack does not exist)."""
    live = {}
    for stack in STACKS:
        try:
            body = aws('cloudformation', 'get-template', region='us-east-1' if stack == 'GlobalWaf' else REGION,
                       StackName=PREFIX+stack)['TemplateBody']
            live[stack] = json.loads(body) if isinstance(body, str) else body
        except MissingStack:
            live[stack] = None
    return live

def baseline_sha256(bodies):
    # Raw, not normalized: an app release deployed between classification and an approved infra execute
    # changes only digests, and must still void that approval.
    return hashlib.sha256(canonical(bodies)).hexdigest()

def planned_template(directory, stack):
    return (Path(directory)/'assembly'/(PREFIX+stack+'.template.json')).read_text()

def template_findings(directory, bodies, stacks):
    findings = []
    for stack in stacks:
        if bodies.get(stack) is None:
            findings.append('stack-missing-' + stack)
        elif normalize_template(bodies[stack]) != normalize_template(planned_template(directory, stack)):
            findings.append('template-changed-' + stack)
    return findings

def classify_findings(directory, manifest, bodies=None):
    """Fail-closed: any difference that is not the release's own digests/version/web bundle is infra."""
    current = read_current_release(release_bucket())
    findings = ['no-previous-release-record'] if current is None else []
    if (current or {}).get('releaseManifest', {}).get('flywayChecksums') != manifest.get('flywayChecksums'):
        findings.append('migration-set-changed')
    return findings + template_findings(directory, bodies if bodies is not None else live_bodies(), STACKS)

def rollback_findings(directory, manifest, data, bodies=None):
    """A rollback deploys only the app stacks. It stays on the app path only when those templates differ from
    live by the release's own markers: anything else undoes a reviewed change and is reviewed again."""
    findings = []
    if manifest.get('sourceState') != 'clean':
        findings.append('rollback-target-is-not-a-clean-build')
    if data.get('acceptNewerSchema'):
        findings.append('rollback-accepts-newer-schema')
    return findings + template_findings(directory, bodies if bodies is not None else live_bodies(), APP_STACKS)

def template_diff(directory, bodies, findings, previous, manifest, account):
    """What the infra reviewer approves: a unified diff of the normalized templates and the migration set,
    with every 12-digit number (account ids) redacted. The step summary of a public repository is public."""
    lines = []
    old_migrations = {entry.split(':')[0]: entry for entry in (previous or {}).get('flywayChecksums') or []}
    new_migrations = {entry.split(':')[0]: entry for entry in manifest.get('flywayChecksums') or []}
    for name in sorted(set(old_migrations) | set(new_migrations)):
        if old_migrations.get(name) != new_migrations.get(name):
            state = 'added' if name not in old_migrations else 'removed' if name not in new_migrations else 'changed'
            lines.append(f'migration {state}: {name}')
    for finding in findings:
        if not finding.startswith(('template-changed-', 'stack-missing-')):
            continue
        stack = finding.rsplit('-', 1)[1]
        pretty = lambda text: json.dumps(json.loads(normalize_template(text)), indent=1, sort_keys=True).splitlines()
        old = [] if bodies.get(stack) is None else pretty(bodies[stack])
        lines += difflib.unified_diff(old, pretty(planned_template(directory, stack)),
                                      f'live/{PREFIX}{stack}', f'plan/{PREFIX}{stack}', lineterm='', n=3)
    text = '\n'.join(lines) + ('\n' if lines else '')
    if account:
        text = text.replace(account, '************')
    return ACCOUNT_DIGITS.sub('************', text)

def classify(args):
    data = verify_plan(args.plan, args.approved_plan_sha256)
    require(data['action'] in ['deploy', 'rollback'], 'classify-requires-deploy-or-rollback-plan')
    identity(data['account'])
    directory = Path(args.plan).resolve().parent
    rollback = data['action'] == 'rollback'
    manifest = validate_manifest(directory/'release.json', recorded=rollback)
    current = read_current_release(release_bucket())
    previous = (current or {}).get('releaseManifest', {})
    bodies = live_bodies()
    findings = (rollback_findings(directory, manifest, data, bodies) if rollback
                else classify_findings(directory, manifest, bodies))
    # What the reviewer is shown is pinned: an infra execute refuses if the live stacks moved since.
    write_private(directory/'classification.json', {'kind': 'infra' if findings else 'app', 'findings': findings,
                                                    'baselineSha256': baseline_sha256(bodies)})
    diff = template_diff(directory, bodies, findings, previous, manifest, data['account'])
    fd = os.open(directory/'template-diff.txt', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(diff)
    same = bool(current) and all(previous.get(k) == manifest.get(k) for k in
                                 ['gitSha', 'apiImageDigest', 'aiImageDigest', 'webArtifactSha256', 'flywayChecksums'])
    print('release_state=' + ('already-deployed' if same and not findings else 'new'))
    print('release_kind=' + ('infra' if findings else 'app'))
    for finding in findings:
        print('infra_reason=' + finding)
    print('template_diff=' + str(directory/'template-diff.txt') + ' lines=' + str(diff.count('\n')))

def record_release(directory, data, manifest, plan_sha):
    """Keep what a later rollback or classification needs, outside this machine: the exact plan directory."""
    bucket = release_bucket()
    with tempfile.TemporaryDirectory() as temp:
        archive = Path(temp)/'plan.tgz'
        with tarfile.open(archive, 'w:gz') as tar:
            for name in ['plan.json', 'release.json', 'cost-basis.txt', 'assembly']:
                tar.add(Path(directory)/name, arcname=name)
        key = f'releases/{plan_sha}/plan.tgz'
        require(aws_cli(['s3', 'cp', archive, f's3://{bucket}/{key}', '--only-show-errors']).returncode == 0, 'release-archive-upload-failed')
        current = aws_cli(['s3', 'cp', f's3://{bucket}/deployed/current.json', f's3://{bucket}/deployed/previous.json', '--only-show-errors'])
        record = {'planSha256': plan_sha, 'planKey': key, 'action': data['action'], 'gitSha': manifest['gitSha'],
                  'releaseVersion': manifest['releaseVersion'], 'deployedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                  'previousRecordCopied': current.returncode == 0, 'releaseManifest': manifest}
        path = Path(temp)/'current.json'
        path.write_text(json.dumps(record, sort_keys=True, indent=2))
        require(aws_cli(['s3', 'cp', path, f's3://{bucket}/deployed/current.json', '--only-show-errors']).returncode == 0, 'release-record-upload-failed')
    print('release_record=' + key)

def execute(args):
    require(args.plan, 'execute-requires-saved-plan')
    data=verify_plan(args.plan,args.approved_plan_sha256)
    require(data['action']==args.action,'plan-action-mismatch')
    identity(data['account'])
    directory=Path(args.plan).resolve().parent
    plan_sha=args.approved_plan_sha256
    manifest={} if args.action=='bootstrap' else validate_manifest(directory/'release.json', recorded=args.action=='rollback')
    def deploy_stack(name,lock=None,parameters=None):
        deploy_approved_stack(directory,data,name,lock,parameters)
    # Foundation is a separate explicit bootstrap entrypoint. Runtime execution never mutates it.
    if args.action=='bootstrap':
        deploy_stack('Foundation')
        print('bootstrap=complete runtime_not_deployed=true')
        return
    kind = args.kind
    require(kind in ['app', 'infra'], 'execute-requires-kind-app-or-infra')
    verify_images(manifest)
    require_kto_secret_provisioned()
    with DeploymentLock() as lock:
        write_private(directory/'execution.json',{'status':'running','lockOwner':lock.owner,'kind':kind})
        # A rollback never touches the protected stacks, whichever path approved it.
        before=protected_templates() if kind=='app' or args.action=='rollback' else None
        if kind=='infra':
            # Approved against what the reviewer saw (classify); anything deployed since voids that approval.
            classified = directory/'classification.json'
            require(classified.exists(), 'infra-execute-requires-classification')
            recorded = json.loads(classified.read_text())
            require(recorded.get('baselineSha256') == baseline_sha256(live_bodies()), 'live-stacks-changed-since-classification')
        elif args.action=='deploy':
            # The approval path is chosen from this classification; re-check it live, fail-closed.
            require(not classify_findings(directory, manifest), 'infra-change-requires-infra-approval')
        else:
            require(not rollback_findings(directory, manifest, data), 'rollback-requires-infra-approval')
        if args.action=='deploy':
            if kind=='infra':
                for name in INFRA_ORDER:deploy_stack(name,lock)
            deploy_stack('Migration',lock)
            migration(manifest,lock)
        else:
            # Previous task definitions only; a rollback never runs (down) migrations. The older binary will
            # meet the newer schema (JPA ddl-auto=validate, Flyway off in services), so that must be a
            # decision recorded in the approved plan, not an accident.
            current = read_current_release(release_bucket()) or {}
            deployed = (current.get('releaseManifest') or {}).get('flywayChecksums') or []
            target = manifest.get('flywayChecksums') or []
            require(deployed[:len(target)] == target, 'rollback-target-schema-diverges')
            require(len(deployed) == len(target) or data.get('acceptNewerSchema') is True,
                    'rollback-to-older-schema-requires-accept-newer-schema')
            deploy_stack('Migration',lock)
        waf_arn=output('GlobalWaf','WebAclArn','us-east-1')
        # Preserve the closed edge on every new release/rollback. Opening needs independent live verification.
        deploy_stack('WebEdge',lock,['NullnullStgWebEdge:GlobalWebAclArn='+waf_arn,'NullnullStgWebEdge:TrafficEnabled=false',
                                     'NullnullStgWebEdge:VerifierTokenSha256='+data.get('verifierTokenSha256','')])
        deploy_stack('Services',lock)
        if before is not None:require(before==protected_templates(),'protected-stack-changed')
        record_release(directory, data, manifest, plan_sha)
        write_private(directory/'execution.json',{'status':'deployed-edge-closed','lockOwner':lock.owner,'kind':kind})
    print('deployment_action=executed state=DEPLOYED_EDGE_CLOSED release_ready=false kind='+kind)

def deploy_approved_stack(directory, data, name, lock=None, parameters=None):
    """Deploy one stack from the approved assembly beside the plan (execute, and edge for WebEdge alone)."""
    assembly=Path(directory)/'assembly'
    if lock:lock.check()
    require(tree_digest(assembly)==data['assemblySha256'],'assembly-changed')
    guard_stateful(name, assembly)
    # The CDK CLI writes into the assembly it deploys (asset zips under .cache/, measured 2026-09-19), so it
    # gets a verified copy: the approved assembly stays byte-identical for the next stack and the release record.
    with tempfile.TemporaryDirectory(prefix='nullnull-assembly-') as scratch:
        copy=Path(scratch)/'assembly'
        shutil.copytree(assembly,copy,symlinks=False)
        require(tree_digest(copy)==data['assemblySha256'],'assembly-changed')
        command=['deploy',PREFIX+name,'--app',copy,'--exclusively','--concurrency','1','--require-approval','never',
                 '--toolkit-stack-name',TOOLKIT_STACK]
        for p in parameters or []:command+=['--parameters',p]
        if lock:lock.mutating()
        cdk(command, log=Path(directory)/f'cdk-{name}.log')

EDGE_STATES = {'open': 'true', 'closed': 'false'}
# What an anonymous request to /api/v1/health/live answers through the edge: the API's own JSON when the gate
# passes it, the gate's problem+json when it does not. An ALB 503 (no healthy targets) is text/html, not closed.
EDGE_PUBLIC_ANSWER = {'open': (200, 'application/json'), 'closed': (503, 'application/problem+json')}

def verify_deployed_plan(path, approved):
    """The deployed release's own approved plan, unchanged, for edge.

    It makes the same hash checks as verify_plan: the plan file, release.json, cost basis, assembly, and this
    checkout's infra/package-lock.json (the CDK toolchain that will deploy it, so run edge from a checkout whose
    lock matches the release's, with infra dependencies installed). It does not make verify_plan's time checks - the
    24-hour freshness and the plan's own expiresAt window - because edge redeploys exactly what is deployed, with one
    parameter changed, and has to work during judging, days after the release; only the staging end (EXPIRY) bounds
    it. It does not re-evaluate the cost plan either: that plan was approved when the release was deployed, and edge
    changes no resource that costs anything.
    """
    path = Path(path).resolve()
    require(approved and digest(path)==approved, 'reviewed-plan-does-not-match')
    data = json.loads(path.read_text())
    require(data.get('version')==1 and data.get('region')==REGION, 'invalid-plan')
    require(data.get('action') in ('deploy', 'rollback'), 'edge-requires-a-release-plan')
    require(data['account']==os.environ.get('NULLNULL_AWS_ACCOUNT_ID'), 'plan-account-mismatch')
    require(digest(path.parent/'release.json')==data['releaseSha256'], 'release-changed')
    require(tree_digest(path.parent/'assembly')==data['assemblySha256'], 'assembly-changed')
    require(digest(path.parent/'cost-basis.txt')==data['costBasisSha256'], 'cost-basis-changed')
    require(digest(ROOT/'infra/package-lock.json')==data['toolchainSha256'], 'toolchain-changed')
    require(re.fullmatch(r'[a-f0-9]{64}', data.get('verifierTokenSha256', '')) is not None, 'invalid-verifier-hash')
    require(dt.datetime.now(dt.timezone.utc) < EXPIRY, 'staging-expired')
    return data

def edge_traffic_enabled():
    """The WebEdge stack's live TrafficEnabled parameter, refusing while the stack is mid-operation."""
    stack = aws('cloudformation', 'describe-stacks', StackName=PREFIX+'WebEdge')['Stacks'][0]
    require(not stack.get('StackStatus', '').endswith('_IN_PROGRESS'), 'webedge-stack-busy')
    parameters = {p.get('ParameterKey'): p.get('ParameterValue') for p in stack.get('Parameters', [])}
    require(parameters.get('TrafficEnabled') in ('true', 'false'), 'webedge-traffic-parameter-unreadable')
    return parameters['TrafficEnabled']

def public_health_answers(url, attempts=40, pause=15):
    """GET /api/v1/health/live through the edge without the verifier, until the caller has what it waits for.
    CloudFront takes minutes to carry a function change to every edge location, so this retries. Yields
    (status, content type, error class) - the class of a local failure, never its message."""
    import urllib.error, urllib.request
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url.rstrip('/') + '/api/v1/health/live', timeout=10) as response:
                answer = (response.status, response.headers.get_content_type(), None)
        except urllib.error.HTTPError as error:
            answer = (error.code, error.headers.get_content_type() if error.headers else None, None)
        except OSError as error:
            answer = (None, None, type(error).__name__)
        yield answer
        if attempt + 1 < attempts:
            time.sleep(pause)

def edge(args):
    """Open or close the public API edge of the deployed release, and nothing else (owner decision A-039).

    A-039: open only once the release carries the FE login-imitation screen; open without the deletion ledger, so no
    DB snapshot is restored while it is open - close it first. Every deploy and rollback sets TrafficEnabled=false
    again (execute), so this runs after each release that should be public.

    It redeploys WebEdge alone, from the deployed release's own approved plan and assembly, with TrafficEnabled
    changed; the owner approves that plan's sha256, which current.json records. Everything that decides whether to
    deploy is read under the deployment lock, so no release can land between the check and the deploy (as ops_task).
    Opening first runs the checks CD runs after a deploy (staging-smoke.sh, then staging-flows.mjs through the
    verifier path). An edge already in the asked state is only verified. Without --execute it plans.
    """
    require(auth_mode() == 'profile', 'edge-is-local-only')
    require(args.state in EDGE_STATES, 'edge-state-must-be-open-or-closed')
    require(bool(args.plan), 'edge-requires-the-deployed-release-plan')
    data = verify_deployed_plan(args.plan, args.approved_plan_sha256)
    identity(data['account'])
    url = output('WebEdge', 'PublicUrl')
    enabled = EDGE_STATES[args.state]
    with DeploymentLock() as lock:
        current = read_current_release(release_bucket())
        require(current is not None, 'no-deployed-release-record')
        require(current.get('planSha256') == args.approved_plan_sha256, 'plan-is-not-the-deployed-release')
        release = current['releaseVersion']
        live = edge_traffic_enabled()
        print(f'edge_action={"execute" if args.execute else "plan"} state={args.state} release={release} '
              f'stack=WebEdge TrafficEnabled={live}->{enabled}')
        print('edge_note=every deploy and rollback sets TrafficEnabled=false again; open the edge again after each release')
        if live == enabled:
            print(f'edge_action=none reason=already-{args.state}')
        else:
            if args.state == 'open':
                require(len(os.environ.get('NULLNULL_VERIFIER_TOKEN', '')) >= 43, 'edge-open-requires-verifier-token')
                log = ROOT/'.artifacts/aws/edge-precheck.log'
                log.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                run(['bash', ROOT/'scripts/aws/staging-smoke.sh', '--url', url], log=log)
                run(['node', ROOT/'scripts/aws/staging-flows.mjs', '--url', url], log=log)
                print('edge_precheck=staging-smoke-and-flows-pass')
            if not args.execute:
                print('edge_action=plan aws_writes=0')
                return
            waf_arn = output('GlobalWaf', 'WebAclArn', 'us-east-1')
            deploy_approved_stack(Path(args.plan).resolve().parent, data, 'WebEdge', lock,
                                  ['NullnullStgWebEdge:GlobalWebAclArn='+waf_arn, 'NullnullStgWebEdge:TrafficEnabled='+enabled,
                                   'NullnullStgWebEdge:VerifierTokenSha256='+data['verifierTokenSha256']])
    expected = EDGE_PUBLIC_ANSWER[args.state]
    answer = (None, None, 'not-asked')
    for answer in public_health_answers(url):
        if answer[:2] == expected:
            break
    require(answer[:2] == expected, f'edge-not-{args.state}-after-deploy-last-{answer[0]}-{answer[2] or answer[1]}')
    print(f'edge={args.state} release={release} public_health_status={answer[0]} content_type={answer[1]}')

def read_dotenv(path):
    values = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        name, sep, value = line.partition('=')
        if sep and not name.strip().startswith('#'):
            values[name.strip()] = value.strip().strip('"').strip("'")
    return values

def provision_secrets(args):
    """Put the KTO key from the owner's ignored apps/api/.env.local. The value never reaches argv or stdout."""
    require(auth_mode() == 'profile', 'secret-provisioning-is-local-only')
    identity(os.environ.get('NULLNULL_AWS_ACCOUNT_ID', ''))
    key = read_dotenv(ROOT/'apps/api/.env.local').get('KTO_SERVICE_KEY', '')
    require(8 <= len(key) <= 512 and not re.search(r'\s', key), 'local-kto-key-missing-or-malformed')
    current = aws('secretsmanager', 'get-secret-value', SecretId=KTO_SECRET).get('SecretString')
    changed = current != key
    if changed:
        aws('secretsmanager', 'put-secret-value', SecretId=KTO_SECRET, SecretString=key, ClientRequestToken=str(uuid.uuid4()))
    require_kto_secret_provisioned()
    print(f'kto_secret=provisioned changed={str(changed).lower()} value_printed=false')

def curation_plan(args):
    """The local plan file a curate task imports, checked and approved before any AWS call.

    The owner approves the sha256 printed here (--approved-plan-sha256) and names who approved (--owner-approval);
    the file is read once and the sha, the checks and the encoded value all come from those bytes.
    """
    path = Path(getattr(args, 'plan_file', None) or '')
    require(bool(getattr(args, 'plan_file', None)) and path.is_file(), 'plan-file-required')
    data = path.read_bytes()
    require(len(data) <= PLAN_MAX_BYTES, 'plan-file-too-large')
    try:
        text = data.decode('utf-8')
        plan = json.loads(text)
    except (UnicodeDecodeError, ValueError):
        raise OpsError('plan-file-not-json') from None
    # The committed templates carry '<BE: ...>' placeholders; a plan still holding one is not a plan yet.
    require('<BE:' not in text, 'plan-file-has-placeholders')
    kind = CURATION_PLANS[args.task]['items']
    items = plan.get(kind) if isinstance(plan, dict) else None
    require(isinstance(items, list) and len(items) > 0, 'plan-file-has-no-' + kind)
    require(all(isinstance(i, dict) for i in items), 'plan-file-has-no-' + kind)
    if kind == 'posts':
        # A post names places and a cover. The importer and V021 refuse a cover that is not an absolute https URL;
        # caught here so the refusal costs no approval and no task.
        require(all(isinstance(i.get('places'), list) and i['places'] for i in items), 'plan-file-post-has-no-place')
        places = [p for i in items for p in i['places']]
        require(all(isinstance(i.get('cover'), dict) and str(i['cover'].get('url', '')).startswith('https://')
                    for i in items), 'plan-file-cover-url-not-https')
    else:
        places = items
    require(all(isinstance(p, dict) and PLACE_ID.fullmatch(str(p.get('placeId', ''))) for p in places),
            'plan-file-place-id-not-a-uuid')
    sha = hashlib.sha256(data).hexdigest()
    print(f'plan_sha256={sha} bytes={len(data)} {kind}={len(items)} place_ids=' + ','.join(p['placeId'] for p in places))
    require(getattr(args, 'approved_plan_sha256', None) == sha, 'plan-sha256-not-approved')
    require(bool(args.owner_approval) and len(args.owner_approval) >= 10, 'owner-approval-record-required')
    encoded = base64.b64encode(gzip.compress(data, mtime=0)).decode('ascii')
    require(len(encoded) <= PLAN_INLINE_MAX_CHARS, 'plan-too-large-for-task-overrides')
    return {'data': data, 'sha256': sha, 'encoded': encoded, 'items': len(items)}

def ops_task(args):
    """Run one allowlisted operator command in the VPC with the deployed API image."""
    require(auth_mode() == 'profile', 'ops-tasks-are-local-only')
    # Local inputs are settled before any AWS call, as the task inputs below are.
    plan = curation_plan(args) if args.task in CURATION_PLANS else None
    require(plan is not None or not getattr(args, 'plan_file', None), 'plan-file-not-accepted')
    identity(os.environ.get('NULLNULL_AWS_ACCOUNT_ID', ''))
    main_class, approval, inputs = OPS_TASKS[args.task]
    environment = [{'name': 'LOADER_MAIN', 'value': main_class}]
    for name, attribute in inputs.items():
        value = getattr(args, attribute) or ''
        require(re.fullmatch(OPS_INPUT[attribute], value) is not None, 'invalid-' + attribute.replace('_', '-'))
        if attribute == 'places':
            require(len(set(value.split(','))) == len(value.split(',')), 'duplicate-places')
        environment.append({'name': name, 'value': value})
    if args.task.startswith('kto-'):
        environment.append({'name': 'APP_CONTEST_PROFILE', 'value': '2026_KTO_WEBAPP'})
    if approval:
        # The approval is the caller's own environment (set on the command the owner approved), exactly as
        # for the local Gradle task; --owner-approval only records who approved and where. Neither alone runs.
        require(os.environ.get(approval) == 'true', approval.lower().replace('_', '-') + '-not-set-by-caller')
        require(bool(args.owner_approval) and len(args.owner_approval) >= 10, 'owner-approval-record-required')
        environment.append({'name': approval, 'value': 'true'})
    # The database is the caller's to name, like the approval. Checked here against the instance RDS reports, so a
    # wrong name stops before a task starts; the task's own check compares it with its datasource URL.
    stated = os.environ.get(OPERATIONS_TARGET, '').strip()
    instance = aws('rds', 'describe-db-instances',
                   DBInstanceIdentifier=output('Data', 'DatabaseIdentifier')).get('DBInstances') or [{}]
    endpoint = instance[0].get('Endpoint') or {}
    require(all([endpoint.get('Address'), endpoint.get('Port'), instance[0].get('DBName')]), 'staging-database-unreadable')
    database = f"postgresql://{endpoint['Address']}:{endpoint['Port']}/{instance[0]['DBName']}"
    if stated != database:
        print(f'operations_target_required={OPERATIONS_TARGET}={database}')
        require(False, 'operations-target-not-set-by-caller' if not stated else 'operations-target-not-the-staging-database')
    environment.append({'name': OPERATIONS_TARGET, 'value': stated})
    if plan:
        spec = CURATION_PLANS[args.task]
        environment += [{'name': spec['inline'], 'value': plan['encoded']}, {'name': spec['sha256'], 'value': plan['sha256']}]
    overrides = {'containerOverrides': [{'name': 'ops', 'environment': environment}]}
    require(len(json.dumps(overrides)) <= OVERRIDES_MAX_CHARS, 'task-overrides-too-large')
    cluster = output('Platform', 'ClusterName')
    with DeploymentLock() as lock:
        # Read under the lock, so no release can land between this read and the task.
        definition_arn = output('Migration', 'OpsTaskDefinitionArn')
        definition = aws('ecs', 'describe-task-definition', taskDefinition=definition_arn)['taskDefinition']
        # The smoke's report names a release, and a curate task needs an image that reads an inline plan: both run
        # only on the recorded release's own definition and image.
        bound = args.task == 'kto-smoke' or plan is not None
        current, expected_digest = release_binding(definition) if bound else (None, None)
        lock.mutating()
        result = aws('ecs', 'run-task', cluster=cluster, taskDefinition=definition_arn, launchType='FARGATE', count=1,
                     clientToken=lock.owner, startedBy='nullnull-stg-ops',
                     overrides=overrides,
                     networkConfiguration={'awsvpcConfiguration': {
                         'subnets': output('Platform', 'AppSubnetIds').split(','),
                         'securityGroups': [output('Platform', 'MigrationSecurityGroupId')], 'assignPublicIp': 'ENABLED'}})
        require(not result.get('failures') and len(result.get('tasks', [])) == 1, 'ops-task-did-not-start')
        arn = result['tasks'][0]['taskArn']
        print('ops_task=' + args.task + ' task_id=' + arn.rsplit('/', 1)[1]
              + (' owner_approval=' + re.sub(r'[^A-Za-z0-9 _:.-]', '', args.owner_approval) if approval or plan else '')
              + (' approved_plan_sha256=' + plan['sha256'] if plan else ''))
        failure = None
        try:
            wait_task(cluster, arn, lock, definition, 'ops', expected_digest)
        except OpsError as error:
            failure = error
        stream = 'ops/ops/' + arn.rsplit('/', 1)[1]
        log = aws('logs', 'get-log-events', logGroupName=output('Platform', 'MigrationLogGroupName'),
                  logStreamName=stream, startFromHead=True, limit=500)
        evidence, echoed = [], []
        for event in log.get('events', []):
            line = event.get('message', '').strip()
            if OPS_LOG_LINE.match(line):
                print('ops_log ' + line)
                if line.startswith('KTO_SMOKE_OK '):
                    evidence.append(line)
                if plan and line.startswith(CURATION_PLANS[args.task]['lines']):
                    echoed.append(line)
        if failure:
            raise failure
        if args.task == 'kto-smoke':
            write_actual_call_report(evidence, current)
        if plan:
            record_curation(args.task, plan, echoed, current)
    print('ops_task=' + args.task + ' result=succeeded')

def record_curation(task, plan, echoed, current):
    """The task imported the approved bytes, all of them, and those bytes are kept where the release's evidence is.

    Three of the task's own lines prove it, not its exit code alone: the sha it read (printed before the import),
    the count it recorded (printed after), and no failure line. A plan file on the operator's machine is not a
    record; this copy and its sha are.
    """
    spec = CURATION_PLANS[task]
    expected = f"{spec['echo']} sha256={plan['sha256']} bytes={len(plan['data'])}"
    require([line for line in echoed if line.startswith(spec['echo'] + ' ')] == [expected],
            'curation-plan-echo-mismatch')
    require(not any(line.startswith(spec['failed']) for line in echoed), 'curation-import-failed')
    done = re.compile(spec['done'].format(n=plan['items']))
    require(any(done.fullmatch(line) for line in echoed), spec['incomplete'])
    path = ROOT/'.artifacts/aws/evidence'/f"curation-{plan['sha256']}.json"
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'wb') as f:
        f.write(plan['data'])
    key = f"evidence/curation/{current['releaseVersion']}/{plan['sha256']}.json"
    require(aws_cli(['s3', 'cp', path, f's3://{release_bucket()}/{key}', '--only-show-errors']).returncode == 0,
            'curation-evidence-upload-failed')
    print(f"curation_plan=recorded task={task} sha256={plan['sha256']} key={key}")

def release_binding(definition):
    """The recorded release a bound task runs as, and the image digest it must run.

    The smoke's report credits current.json's release, and a curate task needs the image that reads an inline plan,
    so either task has to be that release's. This cannot tell whether that release's image reads an inline plan: a
    release older than the curate-hours change (rc.1001) passes here and then fails in the task, with only
    task-failed to show for it, because its main knows only a plan file. After a failed deploy the ops
    definition (Migration stack) can already be the next release's while current.json still names the previous one,
    and two releases can share one API digest (rc.1000 and rc.1001 did), so the digest alone does not name a
    release: the definition's APP_RELEASE_VERSION has to be current.json's as well. Checked before the task starts,
    so a mismatch costs no KTO call and writes nothing; the digest is checked again on the image that actually ran.
    """
    current = read_current_release(release_bucket())
    require(current is not None, 'no-deployed-release-record')
    expected = (current.get('releaseManifest') or {}).get('apiImageDigest')
    require(bool(expected), 'deployed-release-has-no-api-digest')
    container = next((c for c in definition.get('containerDefinitions', []) if c.get('name') == 'ops'), {})
    require(container.get('image', '').endswith('@' + expected), 'ops-image-not-the-deployed-release')
    environment = {e.get('name'): e.get('value') for e in container.get('environment', [])}
    require(environment.get('APP_RELEASE_VERSION') == current.get('releaseVersion'), 'ops-definition-not-the-deployed-release')
    return current, expected

def write_actual_call_report(lines, current):
    """CMP-KTO-003 evidence: the staging service's own call, as its smoke main reported it (redacted fields only).

    `called=true` is the smoke's own statement that this run's request produced the snapshot (KtoSmokeMain derives
    it from the gateway's fetchedAt on one clock). A stored snapshot handed back without a call never gets here: the
    smoke prints KTO_SMOKE_CACHED and fails, so the task ends task-failed before any report. What stops here is an
    image older than that rule, which prints an OK line with no `called`, and any OK line whose `called` is not
    true - a report without a call is exactly the evidence CMP-KTO-003 must not accept. `current` is the release record
    the caller already checked the image against.
    """
    require(len(lines) == 1, 'kto-smoke-evidence-line-missing')
    fields = dict(part.split('=', 1) for part in lines[0].split()[1:] if '=' in part)
    for name in ['source', 'contentId', 'snapshotId', 'collectorRunId', 'payloadHash', 'fetchedAt', 'called']:
        require(bool(fields.get(name)), 'kto-smoke-evidence-field-missing-' + name)
    require(fields['called'] == 'true', 'kto-smoke-did-not-call')
    require(current is not None, 'no-deployed-release-record')
    release = current['releaseVersion']
    report = {'verdict': 'verified', 'environment': 'staging', 'source': fields['source'], 'releaseId': release,
              'operation': 'KorService2/detailCommon2', 'observedAt': fields['fetchedAt'],
              # The smoke prints the collector run, which owns the api_ingest_logs row of this call.
              'ingestLogId': 'collector-run:' + fields['collectorRunId'],
              'calls': [{'operation': 'detailCommon2', 'outcome': 'OK', 'contentId': fields['contentId'],
                         'snapshotId': fields['snapshotId'], 'payloadHash': fields['payloadHash'],
                         'gitSha': current['gitSha']}]}
    path = ROOT/'.artifacts/aws/evidence'/('actual-call-' + release + '.json')
    write_private(path, report)
    run(['python3', ROOT/'scripts/check_actual_call_evidence.py', path, '--release', release, '--require-verified'])
    bucket = release_bucket()
    require(aws_cli(['s3', 'cp', path, f's3://{bucket}/evidence/{path.name}', '--only-show-errors']).returncode == 0,
            'evidence-upload-failed')
    print('actual_call=verified release=' + release + ' report=' + str(path))

def unlock(args):
    """Break-glass for a retained lock: only after proving nothing that lock guarded is still running."""
    require(auth_mode() == 'profile', 'unlock-is-local-only')
    identity(os.environ.get('NULLNULL_AWS_ACCOUNT_ID', ''))
    item = aws('dynamodb', 'get-item', TableName=LOCK_TABLE, Key={'LockId': {'S': 'staging'}}, ConsistentRead=True).get('Item')
    require(item is not None, 'no-lock-held')
    require(item.get('Owner', {}).get('S') == args.owner, 'lock-owner-mismatch')
    for region, names in [(REGION, [s for s in PROTECTED + ['Migration', 'WebEdge', 'Services'] if s != 'GlobalWaf']), ('us-east-1', ['GlobalWaf'])]:
        for name in names:
            try:
                status = aws('cloudformation', 'describe-stacks', region=region, StackName=PREFIX+name)['Stacks'][0]['StackStatus']
            except MissingStack:
                continue
            require(not status.endswith('_IN_PROGRESS'), 'stack-still-in-progress-' + name)
    cluster = output('Platform', 'ClusterName')
    for started_by in ['nullnull-stg-migration', 'nullnull-stg-ops']:
        running = aws('ecs', 'list-tasks', cluster=cluster, startedBy=started_by, desiredStatus='RUNNING').get('taskArns', [])
        require(not running, 'task-still-running-' + started_by)
    aws('dynamodb', 'delete-item', TableName=LOCK_TABLE, Key={'LockId': {'S': 'staging'}},
        ConditionExpression='#owner = :owner', ExpressionAttributeNames={'#owner': 'Owner'},
        ExpressionAttributeValues={':owner': {'S': args.owner}})
    print('deployment_lock=released owner=' + args.owner)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['deploy','rollback','bootstrap','classify','secrets','task','unlock','edge'])
    parser.add_argument('--state',choices=sorted(EDGE_STATES))
    parser.add_argument('--manifest');parser.add_argument('--web-dir');parser.add_argument('--plan')
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--approved-plan-sha256','--approved-diff-sha256',dest='approved_plan_sha256')
    parser.add_argument('--kind',choices=['app','infra'])
    parser.add_argument('--days',type=int,default=14);parser.add_argument('--estimated-total',type=float)
    parser.add_argument('--cost-basis')
    parser.add_argument('--previous-plan');parser.add_argument('--previous-plan-sha256')
    parser.add_argument('--task',choices=sorted(OPS_TASKS));parser.add_argument('--content-id')
    parser.add_argument('--content-type-id');parser.add_argument('--place-id');parser.add_argument('--owner-approval')
    parser.add_argument('--places');parser.add_argument('--plan-file')
    parser.add_argument('--owner');parser.add_argument('--accept-newer-schema',action='store_true')
    args=parser.parse_args()
    os.umask(0o077)
    try:
        if args.action=='classify': classify(args)
        elif args.action=='secrets': provision_secrets(args)
        elif args.action=='task': ops_task(args)
        elif args.action=='unlock': unlock(args)
        elif args.action=='edge': edge(args)
        elif args.execute: execute(args)
        else: plan(args)
    except (OpsError, OSError, KeyError, ValueError) as error:
        reason=str(error) if isinstance(error,OpsError) else 'invalid-input-or-local-operation'
        print('staging_ops=failed reason='+reason,file=sys.stderr)
        return 1
    return 0
if __name__=='__main__':
    sys.exit(main())
