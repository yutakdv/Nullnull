#!/usr/bin/env python3
"""BA-071: immutable local plans and guarded AWS execution. Never prints AWS error bodies or secret values."""
from __future__ import annotations
import argparse
import base64
import datetime as dt
import difflib
import gzip
import hashlib
import importlib.util
import io
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
import zipfile

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
    # BA-086 (#60), English place text. The link import stores the owner's reviewed "this EngService record is that
    # place" decisions and calls no provider, so the owner approves the plan bytes instead (CURATION_PLANS). The
    # refresh calls EngService2 detailCommon2 once per link, so it takes its own approval variable: the Korean
    # calls' approval is not this one. Both mains arrive with #360; an older image has neither.
    'kto-eng-link-import': ('io.nullnull.catalog.infrastructure.kto.KtoEngLinkImportMain', None, {}),
    'kto-eng-text-refresh': ('io.nullnull.catalog.infrastructure.kto.KtoEngTextRefreshMain',
                             'NULLNULL_KTO_ENG_REFRESH_APPROVED', {}),
    # Curated opening hours (A-031/A-032). No KTO call, so no KTO approval variable: the owner approves the exact
    # plan bytes instead (CURATION_PLANS below), and records who did with --owner-approval.
    'curate-hours': ('io.nullnull.catalog.infrastructure.curation.CuratedHoursImportMain', None, {}),
    # Curated feed posts (A-031, #183), the same way: no provider call, the owner approves the plan's bytes.
    'curate-posts': ('io.nullnull.social.infrastructure.curation.CuratedPostImportMain', None, {}),
    # The KTO operations the deployed release actually called, from the call-audit (CMP-KTO-006, BA-073-T3). It reads
    # the audit and calls no provider (KtoCallInventoryMain starts OperationsContext with READ access and touches no
    # gateway), so it takes no KTO approval. Its release has no input: it is the deployed one, set under the deployment
    # lock, because an inventory of another release would pass check_submission_inventory against a ledger naming it.
    'kto-call-inventory': ('io.nullnull.crowd.infrastructure.audit.KtoCallInventoryMain', None, {}),
    'seoul-live-collect': ('io.nullnull.live.infrastructure.SeoulLiveCollectMain', None,
                           {'NULLNULL_SEOUL_AREA_NAME': 'area_name'}),
    'curate-live-maps': ('io.nullnull.live.infrastructure.curation.LiveMappingImportMain', None, {}),
    'capture-live-replay': ('io.nullnull.crowd.infrastructure.persistence.ReplayManifestImportMain', None, {}),
    'list-live-replay-candidates': ('io.nullnull.crowd.infrastructure.persistence.ReplayCandidateListMain', None, {}),
    # Reopens a source whose latest collector run is QUARANTINED, by recording a reviewed RESOLVED incident
    # (SourceQuarantineReleaseMain). Without it a quarantine was a deadlock: every gateway checks the latest run
    # before starting one, so no newer run could ever displace it and the database is not reachable from outside
    # the VPC. SEOUL_CITYDATA locked itself this way on its own schedule. It reopens a source the owner judged safe,
    # so it takes an approval variable and --owner-approval like the provider calls.
    'release-source-quarantine': ('io.nullnull.crowd.infrastructure.SourceQuarantineReleaseMain',
                                  'NULLNULL_SOURCE_RELEASE_APPROVED', {'NULLNULL_RELEASE_SOURCE_CODE': 'source_code'}),
    # Takes one published post back (PostWithdrawMain, BA-082-T16): HIDDEN with published_at cleared, which every
    # reader refuses from the same commit. A-058 publishes an upload with no person in between, so this is the
    # per-post answer to a post that must come down; before it the only one was switching authoring off. It takes
    # a post down on the owner's word, so it takes an approval variable and --owner-approval like the provider calls.
    'withdraw-post': ('io.nullnull.social.infrastructure.moderation.PostWithdrawMain',
                      'NULLNULL_POST_WITHDRAW_APPROVED', {'NULLNULL_WITHDRAW_POST_ID': 'post_id'}),
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
                  # A post line per plan post, by id: the total alone would accept "9999 of 5" or a post the plan does
                  # not name. The published count must then be exactly the PUBLISHED lines.
                  'curate-posts': {'inline': 'NULLNULL_POSTS_PLAN_GZIP_BASE64', 'sha256': 'NULLNULL_POSTS_PLAN_SHA256',
                                   'echo': 'curated_posts_plan', 'lines': 'curated_post', 'items': 'posts',
                                   'entry': r'curated_post ([0-9a-f-]{36}) (PUBLISHED|ALREADY_PRESENT) \(',
                                   'done': r'curated_posts_published={published} of {n}', 'failed': 'curated_posts_failed ',
                                   'incomplete': 'curation-not-all-posts-published'},
                  'curate-live-maps': {'inline': 'NULLNULL_LIVE_MAPPING_PLAN_GZIP_BASE64',
                                       'sha256': 'NULLNULL_LIVE_MAPPING_PLAN_SHA256',
                                       'echo': 'curated_live_maps_plan', 'lines': 'curated_live_map',
                                       'items': 'mappings',
                                       'entry': r'curated_live_map ([0-9a-f-]{36}) (PROCESSED)$',
                                       'done': r'curated_live_maps_processed={n}',
                                       'failed': 'curated_live_maps_failed ',
                                       'incomplete': 'curation-not-all-live-maps-processed'},
                  # One line per reviewed place, by id, as the live maps: the English text is written by the
                  # refresh, not by this import, so "processed" is the only outcome it reports.
                  'kto-eng-link-import': {'inline': 'NULLNULL_ENG_LINK_PLAN_GZIP_BASE64',
                                          'sha256': 'NULLNULL_ENG_LINK_PLAN_SHA256',
                                          'echo': 'eng_link_plan', 'lines': 'eng_link', 'items': 'links',
                                          'entry': r'eng_link ([0-9a-f-]{36}) (PROCESSED)$',
                                          'done': r'eng_links_processed={n}',
                                          'failed': 'eng_links_failed ',
                                          'incomplete': 'eng-links-not-all-processed'},
                  'capture-live-replay': {'inline': 'NULLNULL_REPLAY_PLAN_GZIP_BASE64',
                                          'sha256': 'NULLNULL_REPLAY_PLAN_SHA256',
                                          'echo': 'replay_capture_plan', 'lines': 'replay_',
                                          'items': 'snapshotIds',
                                          'entry': r'replay_snapshot ([0-9a-f-]{36}) (CAPTURED)$',
                                          'done': r'replay_snapshots_captured={n}',
                                          'failed': 'replay_capture_failed ',
                                          'incomplete': 'replay-capture-incomplete'}}
PLAN_MAX_BYTES = 1 << 20  # io.nullnull.OperationsPlan.MAX_BYTES
# RunTask refuses overrides past a size AWS documents as 8192 characters for the whole overrides object; that figure is
# not recorded in this repository and was not measured, so these bounds keep well under it rather than at it.
PLAN_INLINE_MAX_CHARS = 6000
OVERRIDES_MAX_CHARS = 7500
PLACE_ID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
# Input shapes per ops argument. A demo place list is `contentId:contentTypeId`, comma separated.
OPS_INPUT = {'content_id': r'[0-9a-f-]{1,40}', 'content_type_id': r'[0-9a-f-]{1,40}', 'place_id': r'[0-9a-f-]{1,40}',
             'places': r'[1-9][0-9]{0,29}:[1-9][0-9]{0,29}(,[1-9][0-9]{0,29}:[1-9][0-9]{0,29})*',
             'area_name': r'[^/\\\x00-\x1f\x7f]{1,100}', 'source_code': r'[A-Z][A-Z0-9_]{1,63}'}
# A post id is exactly one canonical UUID, not the loose place_id shape: the post-condition below matches the id the
# task printed against this string, and PostWithdrawMain refuses anything else before it opens a database.
OPS_INPUT['post_id'] = PLACE_ID.pattern
# Every task above writes. From the release carrying OperationsContext (#183) a writing tool in staging runs only
# when this names the database its datasource URL points to; an older image ignores it.
OPERATIONS_TARGET = 'NULLNULL_OPERATIONS_TARGET'
# The Seoul refusal line's two words (SeoulLiveCollectMain, BA-091-T27): ProviderResponseValidator.Outcome without
# OK, and SeoulCityDataValidator.Rule in lower case with hyphens. Listed by name, not matched by shape: a line of that
# form carrying any other word is not echoed (test_seoul_refusal_log_parity holds both lists to the Java enums).
SEOUL_VALIDATION_OUTCOMES = ('SCHEMA_DRIFT', 'ENUM_DRIFT', 'RANGE', 'TIME_SKEW', 'PROVIDER_ERROR', 'MAPPING_UNCERTAIN')
SEOUL_VALIDATION_RULES = ('json-unreadable', 'result-missing', 'result-code', 'area-missing', 'area-mismatch',
                          'live-empty', 'replace-unknown', 'level-unknown', 'time-format', 'fcst-yn-unknown',
                          'forecast-empty', 'fcst-level-unknown', 'fcst-time-format', 'replace-substituted')
# Log lines an ops task may echo: the mains' own redacted evidence and settings-origin lines, OperationsContext's
# target line (no user, password or query), and the failure code they throw. Anything else stays in CloudWatch.
OPS_LOG_LINE = re.compile(r'^(KTO_(?!ENG_TEXT_REFRESH)[A-Z_]+ [A-Za-z0-9_ =:.,()<>/+-]{0,400}|.*Exception: KTO [a-z ]+ failed: [A-Za-z_ ()]{1,80}'
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
                          # The call inventory (KtoCallInventoryMain.render), four shapes: its header, one line per operation,
                          # what it excluded and the total with the evidence verdict. Only audit ids, counts and instants.
                          r'|kto_inventory target=(postgresql://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9_]+|unknown)'
                          r' environment=[a-z]+ release=v0\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?'
                          r'|kto_operation source=[A-Z0-9_]{2,100} endpoint=[A-Z0-9_:-]{2,100} calls=[0-9]{1,9}'
                          r' first=[0-9T:.-]{10,40}Z last=[0-9T:.-]{10,40}Z'
                          r'|kto_inventory_excluded rejected=[0-9]{1,9} replay=[0-9]{1,9}'
                          r'|kto_inventory operations=[0-9]{1,4} counts_as_evidence=(true|false reason=[a-z-]{1,60})'
                          r'|seoul_live_collect live=true'
                          r'|seoul_live_collect_failed reason=[A-Za-z_]{1,80}'
                          # Why a Seoul collection was refused (SeoulLiveCollectMain): the validator's outcome and the
                          # check that fired, two fixed vocabularies named above. Never the provider's code, area or
                          # message.
                          r'|seoul_live_validation outcome=(' + '|'.join(SEOUL_VALIDATION_OUTCOMES) + ') rule=('
                          + '|'.join(SEOUL_VALIDATION_RULES) + ')'
                          # The quarantine release (SourceQuarantineReleaseMain): the source, the run it released and
                          # when that run started, or why it released nothing. Source codes and ids only.
                          r'|source_quarantine_released source=[A-Z][A-Z0-9_]{1,63} run=[0-9a-f-]{36} run_started=[0-9T:.-]{10,40}Z'
                          r'|source_quarantine_release_refused source=[A-Z][A-Z0-9_]{1,63} reason=[a-z-]{1,40}'
                          r'|source_quarantine_release_failed reason=[A-Za-z_]{1,80}'
                          r'|curated_live_maps_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}'
                          r'|curated_live_map [0-9a-f-]{36} PROCESSED'
                          r'|curated_live_maps_processed=[0-9]{1,4}|curated_live_maps_failed reason=[A-Za-z_]{1,80}'
                          # The English link import (KtoEngLinkImportMain, BA-086): the sha it imported, place ids and
                          # counts, a failure's code. Never the evidence URL, which stays in the plan file.
                          r'|eng_link_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}'
                          r'|eng_link [0-9a-f-]{36} PROCESSED'
                          r'|eng_links_processed=[0-9]{1,4}|eng_links_failed reason=[A-Za-z_]{1,80}'
                          # The English text refresh (KtoEngTextRefreshMain, BA-086): where each setting came from, one
                          # line per link with its place id and an enum word, and the totals. Never a value, a title, an
                          # address or a URL. The generic KTO_ shape above leaves this prefix out, so nothing looser
                          # passes for it (EnglishTextTaskRegressions feeds these back).
                          r'|KTO_ENG_TEXT_REFRESH_SETTINGS [A-Z][A-Z0-9_]{0,63} <- '
                          r'(process env \(overrides \.env\.local\)|process env|\.env\.local|absent)'
                          r'|KTO_ENG_TEXT_REFRESH placeId=[0-9a-f-]{36} (outcome|failure)=[A-Z][A-Z_]{0,63}'
                          r'|KTO_ENG_TEXT_REFRESH_DONE links=[0-9]{1,4} attempted=[0-9]{1,4} failed=[0-9]{1,4}'
                          r'|replay_capture_plan sha256=[0-9a-f]{64} bytes=[0-9]{1,7}'
                          r'|replay_snapshot [0-9a-f-]{36} CAPTURED'
                          r'|replay_snapshots_captured=[0-9]{1,3}|replay_capture_failed reason=[A-Za-z_]{1,80}'
                          r'|replay_manifest_id=[0-9a-f-]{36}'
                          r'|replay_candidate snapshot=[0-9a-f-]{36} area=[0-9a-f-]{36} observed=[0-9T:.-]{10,40}Z'
                          r'|replay_candidates_failed reason=[A-Za-z_]{1,80}'
                          # The withdrawal (PostWithdrawMain): the post id and a fixed outcome, or a failure code.
                          # Never the post's title, body or cover URL.
                          r'|post_withdrawn post=[0-9a-f-]{36} outcome=(WITHDRAWN|ALREADY_HIDDEN) cover=(DELETED|ALREADY_ABSENT|NOT_USER_UPLOAD) versions=[0-9]{1,9}'
                          r'|post_withdraw_failed reason=COVER_CLEANUP_FAILED cover_cleanup=pending'
                          r'|post_withdraw_failed reason=[A-Za-z_]{1,80}'
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
    # A rollback runs no migration and closes the edge: the migrations the returned-to release appended are not its own.
    previous.pop('acceptAdditiveSchema',None)
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

def named_migrations(value):
    """--accept-additive-schema: comma-separated migration file names."""
    return [name.strip() for name in (value or '').split(',') if name.strip()]

def plan(args):
    # A-067: the migrations a preserve-open deploy may append are named here, so the reviewer approves them by hash.
    additive = named_migrations(getattr(args, 'accept_additive_schema', None))
    require(not additive or args.action == 'deploy', 'accept-additive-schema-deploy-only')
    if args.action=='rollback':
        return rollback_plan(args)
    bootstrap = args.action == 'bootstrap'
    require(bootstrap or (args.manifest and args.web_dir), 'manifest-and-web-dir-required')
    account = os.environ.get('NULLNULL_AWS_ACCOUNT_ID', '')
    require(len(account)==12 and account.isdigit(), 'invalid-account-id')
    manifest = {'kind':'foundation-bootstrap'} if bootstrap else validate_manifest(args.manifest)
    if not bootstrap: check_artifacts(manifest, args.web_dir)
    require(set(additive) <= {entry.split(':')[0] for entry in manifest.get('flywayChecksums') or []},
            'accept-additive-schema-not-in-release')
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
    if additive:
        data['acceptAdditiveSchema'] = additive
    write_private(directory/'plan.json', data)
    print('deployment_action=plan aws_writes=0 traffic_enabled=false')
    if additive:
        print('accept_additive_schema=' + ','.join(additive))
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
    if stack == 'WebEdge':
        new = without_approved_web_bucket_cors(old, new)
    protected = ('AWS::RDS::', 'AWS::SecretsManager::', 'AWS::S3::Bucket',
                 'AWS::EC2::VPC', 'AWS::EC2::Subnet', 'AWS::DynamoDB::Table')
    resources = new.get('Resources', {})
    for logical_id, resource in old.get('Resources', {}).items():
        if resource['Type'].startswith(protected):
            candidate = resources.get(logical_id)
            require(candidate is not None, 'stateful-resource-removal-forbidden')
            for key in ['Type', 'Properties', 'DeletionPolicy', 'UpdateReplacePolicy']:
                require(without_cdk_ownership_tags(resource.get(key)) == without_cdk_ownership_tags(candidate.get(key)),
                        'stateful-change-requires-separate-review')

# The one change to a stateful resource that is not a change to what it holds: each CDK BucketDeployment marks the
# bucket it writes into with an `aws-cdk:cr-owned:<prefix>:<hash>` tag, so adding one (the curated covers, #300) adds a
# tag to the web bucket. Run 35461072422 stopped there. Only that key family is ignored, and only under Tags; every
# other tag and property still stops the deploy.
CDK_OWNERSHIP_TAG = 'aws-cdk:cr-owned:'
POST_UPLOAD_CORS = {'CorsRules': [{'AllowedOrigins': ['https://d54awmnmi4c3z.cloudfront.net'],
                                   'AllowedMethods': ['PUT'], 'AllowedHeaders': ['content-type'], 'MaxAge': 300}]}

def without_approved_web_bucket_cors(old, planned):
    """Ignore only the approved first-time WebBucket CORS addition, never another bucket property."""
    def buckets(template):
        return [(key, resource) for key, resource in template.get('Resources', {}).items()
                if resource.get('Type') == 'AWS::S3::Bucket']
    existing, proposed = buckets(old), buckets(planned)
    # Pin the synthesized WebBucket ID so a replacement requires a new review.
    if (len(existing) != 1 or len(proposed) != 1 or
            existing[0][0] != 'WebBucket12880F5B' or proposed[0][0] != existing[0][0]):
        return planned
    key, resource = proposed[0]
    old_props = existing[0][1].get('Properties', {})
    props = resource.get('Properties', {})
    if 'CorsConfiguration' in old_props or props.get('CorsConfiguration') != POST_UPLOAD_CORS:
        return planned
    return {**planned, 'Resources': {**planned['Resources'], key: {**resource,
            'Properties': {name: value for name, value in props.items() if name != 'CorsConfiguration'}}}}

def without_cdk_ownership_tags(properties):
    if not isinstance(properties, dict) or not isinstance(properties.get('Tags'), list):
        return properties
    tags = [t for t in properties['Tags'] if not (isinstance(t, dict) and str(t.get('Key', '')).startswith(CDK_OWNERSHIP_TAG))]
    return {**properties, 'Tags': tags}

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
        if resource.get('Type') == 'Custom::CDKBucketDeployment' and properties.get('DestinationBucketKeyPrefix') != 'covers/':
            # The web bundle is the app path by definition. The #183 covers are not masked: a published post holds its
            # cover's URL and checksum, so a photo changed under the same name would break posts already live, and
            # that change must reach the infra reviewer's diff rather than ride an app release unseen.
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

def preserve_open_template_findings(directory, bodies):
    findings = template_findings(directory, bodies, PROTECTED+['Services'])
    if bodies.get('WebEdge') is None:
        return findings + ['stack-missing-WebEdge']
    live = json.loads(normalize_template(bodies['WebEdge']))
    planned = json.loads(normalize_template(planned_template(directory, 'WebEdge')))
    if live == planned:
        return findings
    if live == without_approved_web_bucket_cors(live, planned):
        return findings
    return findings + ['template-changed-WebEdge']

def preserve_open_schema_allowed(deployed, target, additive):
    """Whether a --preserve-open-edge deploy may move the schema from `deployed` to `target` (flywayChecksums lists).

    The edge stays open for the whole deploy, and the old API keeps serving between the migration task and the
    Services update, so it reads the new schema. Without names in the plan (--accept-additive-schema when the plan
    was made) the schema must not move at all. With them (A-067), the deployed list must be an exact prefix of the
    release's - every entry the same name and checksum, in the same order - and what the release appends must be
    exactly the named files, each named once. Whether the old code tolerates those files is the reviewer's question,
    not this function's: the name is the operator saying it was asked and answered."""
    if not additive:
        return deployed == target
    appended = [entry.split(':')[0] for entry in target[len(deployed):]]
    # The name comparison is a multiset one: it also refuses a name given twice, and a named file on a schema that
    # did not grow, since `appended` is then empty.
    return target[:len(deployed)] == deployed and sorted(appended) == sorted(additive)

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
    if data.get('acceptAdditiveSchema'):
        print('accept_additive_schema=' + ','.join(data['acceptAdditiveSchema']))
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
    preserve_open = getattr(args, 'preserve_open_edge', False)
    require(not preserve_open or args.action == 'deploy', 'preserve-open-deploy-only')
    # A-067: the approved plan names what the schema may grow by. An execute-time name may repeat it, never widen it.
    additive = data.get('acceptAdditiveSchema') or []
    given = named_migrations(getattr(args, 'accept_additive_schema', None))
    require(not given or sorted(given) == sorted(additive), 'accept-additive-schema-not-in-approved-plan')
    require(not additive or preserve_open, 'accept-additive-schema-requires-preserve-open-edge')
    verify_images(manifest)
    require_kto_secret_provisioned()
    with DeploymentLock() as lock:
        write_private(directory/'execution.json',{'status':'running','lockOwner':lock.owner,'kind':kind})
        # A rollback never touches the protected stacks, whichever path approved it.
        before=protected_templates() if kind=='app' or args.action=='rollback' or preserve_open else None
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
        if preserve_open:
            current = read_current_release(release_bucket())
            deployed = ((current or {}).get('releaseManifest') or {}).get('flywayChecksums')
            # Named migrations are checked against what is deployed. Without that record every migration would read as
            # appended, and a plan naming them all would pass.
            require(deployed or not additive, 'accept-additive-schema-requires-deployed-release')
            require(preserve_open_schema_allowed(deployed or [], manifest.get('flywayChecksums') or [], additive),
                    'preserve-open-schema-change')
            require(edge_traffic_enabled() == 'true', 'preserve-open-requires-open-edge')
            # The web bundle asset may change, but its edge behavior, the online services shape and
            # every protected stack must be structurally unchanged while public traffic is open.
            require(not preserve_open_template_findings(directory, live_bodies()),
                    'preserve-open-template-change')
            url = output('WebEdge', 'PublicUrl')
            require(any(answer[:2] == (200,'application/json') for answer in
                        public_health_answers(url, attempts=3, pause=2)),
                    'public-edge-unhealthy-before-deploy')
            waf_arn = output('GlobalWaf','WebAclArn','us-east-1')
            open_parameters = ['NullnullStgWebEdge:GlobalWebAclArn='+waf_arn,
                               'NullnullStgWebEdge:TrafficEnabled=true',
                               'NullnullStgWebEdge:VerifierTokenSha256='+data.get('verifierTokenSha256','')]
        # A new Migration task definition imports the WebEdge bucket/domain. On a fresh stack,
        # create those exports before Migration; on an existing release they already exist.
        webedge_first = preserve_open or (args.action == 'deploy' and live_bodies().get('WebEdge') is None)
        if args.action=='deploy':
            if kind=='infra':
                for name in INFRA_ORDER:deploy_stack(name,lock)
            if webedge_first:
                if preserve_open:
                    edge_parameters = open_parameters
                else:
                    waf_arn = output('GlobalWaf','WebAclArn','us-east-1')
                    edge_parameters = ['NullnullStgWebEdge:GlobalWebAclArn='+waf_arn,
                                       'NullnullStgWebEdge:TrafficEnabled=false',
                                       'NullnullStgWebEdge:VerifierTokenSha256='+data.get('verifierTokenSha256','')]
                deploy_stack('WebEdge',lock,edge_parameters)
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
        if not webedge_first:
            waf_arn=output('GlobalWaf','WebAclArn','us-east-1')
            # Default deploy and rollback close the edge; preserving open traffic is explicit.
            deploy_stack('WebEdge',lock,['NullnullStgWebEdge:GlobalWebAclArn='+waf_arn,'NullnullStgWebEdge:TrafficEnabled=false',
                                         'NullnullStgWebEdge:VerifierTokenSha256='+data.get('verifierTokenSha256','')])
        deploy_stack('Services',lock)
        if before is not None:require(before==protected_templates(),'protected-stack-changed')
        if preserve_open:
            require(any(answer[:2] == (200,'application/json') for answer in
                        public_health_answers(url, attempts=12, pause=5)),
                    'public-edge-unhealthy-after-deploy')
        record_release(directory, data, manifest, plan_sha)
        state = 'deployed-edge-open' if preserve_open else 'deployed-edge-closed'
        write_private(directory/'execution.json',{'status':state,'lockOwner':lock.owner,'kind':kind})
    state = 'DEPLOYED_EDGE_OPEN' if preserve_open else 'DEPLOYED_EDGE_CLOSED'
    print('deployment_action=executed state='+state+' release_ready=false kind='+kind)

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
    DB snapshot is restored while it is open - close it first. Default deploy and rollback set
    TrafficEnabled=false again; an explicitly reviewed preserve-open deploy retains the open edge.

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
        print('edge_note=default deploy and rollback set TrafficEnabled=false; preserve-open deploy retains it')
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
    """Put the selected key from ignored apps/api/.env.local without printing it or changing proxy tokens."""
    require(auth_mode() == 'profile', 'secret-provisioning-is-local-only')
    identity(os.environ.get('NULLNULL_AWS_ACCOUNT_ID', ''))
    if getattr(args, 'seoul', False):
        key = read_dotenv(ROOT/'apps/api/.env.local').get('SEOUL_API_KEY', '')
        require(8 <= len(key) <= 512 and not re.search(r'\s', key), 'local-seoul-key-missing-or-malformed')
        secret_id = 'nullnull-stg/seoul-proxy'
        raw = aws('secretsmanager', 'get-secret-value', SecretId=secret_id).get('SecretString')
        require(isinstance(raw, str), 'seoul-secret-invalid')
        try:
            current = json.loads(raw)
        except ValueError:
            raise OpsError('seoul-secret-invalid') from None
        require(isinstance(current, dict) and isinstance(current.get('apiKey'), str)
                and isinstance(current.get('proxyToken'), str) and bool(current['proxyToken'])
                and not re.search(r'\s', current['proxyToken']), 'seoul-secret-invalid')
        changed = current['apiKey'] != key
        if changed:
            # ECS reads proxyToken only at task startup. Replacing it here would break the running API.
            current['apiKey'] = key
            aws('secretsmanager', 'put-secret-value', SecretId=secret_id, SecretString=json.dumps(current),
                ClientRequestToken=str(uuid.uuid4()))
        print(f'seoul_secret=provisioned changed={str(changed).lower()} value_printed=false')
        return
    key = read_dotenv(ROOT/'apps/api/.env.local').get('KTO_SERVICE_KEY', '')
    require(8 <= len(key) <= 512 and not re.search(r'\s', key), 'local-kto-key-missing-or-malformed')
    current = aws('secretsmanager', 'get-secret-value', SecretId=KTO_SECRET).get('SecretString')
    changed = current != key
    if changed:
        aws('secretsmanager', 'put-secret-value', SecretId=KTO_SECRET, SecretString=key, ClientRequestToken=str(uuid.uuid4()))
    require_kto_secret_provisioned()
    print(f'kto_secret=provisioned changed={str(changed).lower()} value_printed=false')

UTC_INSTANT = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,9})?Z')

def utc_instant(value):
    """An ISO instant in UTC, the way plans write one (2026-09-21T06:00:00Z), with a real calendar date. A date alone,
    a local time or words are not an instant, and Jackson would refuse them only after the task had started."""
    if not isinstance(value, str) or not UTC_INSTANT.fullmatch(value):
        return False
    try:
        dt.datetime.fromisoformat(value[:19])
    except ValueError:
        return False
    return True

def https_evidence_url(value):
    """What EngTextLinkImporter.Link accepts: https, a host and no userinfo, and nothing URI.create cannot read. A
    prefix check let an empty host, a user:password@ and a space through."""
    import urllib.parse
    # Only the characters RFC 3986 allows - unreserved, reserved and percent-encoding - since those are what
    # URI.create reads. quote() changes anything else (|, a backslash, ^, braces, a space, a control character,
    # non-ASCII), so a string it would change is refused here rather than by the task after launch.
    if not isinstance(value, str) or not value or urllib.parse.quote(value, safe=":/?#[]@!$&'()*+,;=%-._~") != value:
        return False
    try:
        parts = urllib.parse.urlsplit(value)
        parts.port
    except ValueError:
        return False
    return parts.scheme == 'https' and bool(parts.hostname) and parts.username is None and parts.password is None

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
    if kind == 'snapshotIds':
        require(len(items) <= 100 and all(isinstance(i, str) and PLACE_ID.fullmatch(i) for i in items)
                and len(items) == len(set(items)), 'plan-file-snapshot-ids-invalid')
        require(isinstance(plan.get('name'), str) and 0 < len(plan['name'].strip()) <= 200
                and isinstance(plan.get('capturedFrom'), str) and isinstance(plan.get('capturedTo'), str),
                'plan-file-replay-window-invalid')
        places = [{'placeId': i} for i in items]
    else:
        require(all(isinstance(i, dict) for i in items), 'plan-file-has-no-' + kind)
    if kind == 'posts':
        # A post names places and a cover. The importer and V021 refuse a cover that is not an absolute https URL;
        # caught here so the refusal costs no approval and no task.
        require(all(isinstance(i.get('places'), list) and i['places'] for i in items), 'plan-file-post-has-no-place')
        places = [p for i in items for p in i['places']]
        require(all(isinstance(i.get('cover'), dict) and str(i['cover'].get('url', '')).startswith('https://')
                    for i in items), 'plan-file-cover-url-not-https')
    elif kind == 'mappings':
        require(all(isinstance(i.get('areaName'), str) and 0 < len(i['areaName'].strip()) <= 200
                    and isinstance(i.get('evidenceUrl'), str) and i['evidenceUrl'].startswith('https://')
                    and i.get('mappingType') in ('AREA', 'AREA_FALLBACK')
                    and isinstance(i.get('fallbackUsed'), bool)
                    and (i['mappingType'] == 'AREA_FALLBACK') == i['fallbackUsed']
                    and type(i.get('confidence')) in (int, float) and 0 <= i['confidence'] <= 1
                    and isinstance(i.get('verifiedAt'), str) and i['verifiedAt']
                    for i in items), 'plan-file-live-map-invalid')
        places = items
    elif kind == 'links':
        # KtoPlaceRequest and EngTextLinkImporter refuse the same things; caught here so a typo costs no approval and
        # no task. Identifiers are KTO's stable numeric ids, one decision per place.
        identifier = re.compile(r'[1-9][0-9]{0,29}')
        require(all(isinstance(i.get('contentId'), str) and identifier.fullmatch(i['contentId'])
                    and isinstance(i.get('contentTypeId'), str) and identifier.fullmatch(i['contentTypeId'])
                    and utc_instant(i.get('reviewedAt')) and https_evidence_url(i.get('evidenceUrl'))
                    for i in items) and len({str(i.get('placeId')) for i in items}) == len(items),
                'plan-file-eng-link-invalid')
        places = items
    elif kind != 'snapshotIds':
        places = items
    require(all(isinstance(p, dict) and PLACE_ID.fullmatch(str(p.get('placeId', ''))) for p in places),
            'plan-file-place-id-not-a-uuid')
    sha = hashlib.sha256(data).hexdigest()
    id_label = 'snapshot_ids' if kind == 'snapshotIds' else 'place_ids'
    print(f'plan_sha256={sha} bytes={len(data)} {kind}={len(items)} {id_label}=' + ','.join(p['placeId'] for p in places))
    require(getattr(args, 'approved_plan_sha256', None) == sha, 'plan-sha256-not-approved')
    require(bool(args.owner_approval) and len(args.owner_approval) >= 10, 'owner-approval-record-required')
    encoded = base64.b64encode(gzip.compress(data, mtime=0)).decode('ascii')
    require(len(encoded) <= PLAN_INLINE_MAX_CHARS, 'plan-too-large-for-task-overrides')
    return {'data': data, 'sha256': sha, 'encoded': encoded, 'items': len(items),
            'ids': items if kind == 'snapshotIds' else
                   [str(i.get('id', i.get('placeId', ''))) for i in items]}

COVER_MAX_BYTES = 20 << 20  # far above the five photos (2.4-2.9 MB each); bounds what a wrong URL could make us read

def fetch_public(url):
    """GET a public URL: the bytes, or a refusal. A redirect or a non-200 is a cover the post would not show."""
    import urllib.request
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            require(response.status == 200 and response.geturl() == url, 'cover-not-served-as-approved')
            body = response.read(COVER_MAX_BYTES + 1)
    except OSError:
        raise OpsError('cover-not-served-as-approved') from None
    require(len(body) <= COVER_MAX_BYTES, 'cover-not-served-as-approved')
    return body

def verify_served_covers(plan_bytes, public_url):
    """Every cover the plan will publish is served, now, by the deployed edge, as exactly the approved bytes.

    The repository's test compares the committed plan with the committed photos; this compares whatever plan is being
    run with what the deployed release actually serves. Without it a plan naming another domain, or a release that does
    not carry the photos, publishes posts whose covers are broken - and a published post is ALREADY_PRESENT to every
    rerun, so the only repair is deleting it.
    """
    origin = public_url.rstrip('/') + '/covers/'
    posts = json.loads(plan_bytes)['posts']
    for post in posts:
        cover = post['cover']
        require(str(cover.get('url', '')).startswith(origin), 'cover-not-on-the-deployed-edge')
        require(hashlib.sha256(fetch_public(cover['url'])).hexdigest() == cover.get('checksum'),
                'cover-not-served-as-approved')
    print(f'covers_verified={len(posts)} origin={origin}')

def ops_task(args):
    """Run one allowlisted operator command in the VPC with the deployed API image."""
    require(auth_mode() == 'profile', 'ops-tasks-are-local-only')
    # Local inputs are settled before any AWS call, as the task inputs below are.
    plan = curation_plan(args) if args.task in CURATION_PLANS else None
    require(plan is not None or not getattr(args, 'plan_file', None), 'plan-file-not-accepted')
    identity(os.environ.get('NULLNULL_AWS_ACCOUNT_ID', ''))
    if args.task == 'curate-posts':
        verify_served_covers(plan['data'], output('WebEdge', 'PublicUrl'))
    main_class, approval, inputs = OPS_TASKS[args.task]
    environment = [{'name': 'LOADER_MAIN', 'value': main_class}]
    for name, attribute in inputs.items():
        value = getattr(args, attribute) or ''
        require(re.fullmatch(OPS_INPUT[attribute], value) is not None, 'invalid-' + attribute.replace('_', '-'))
        if attribute == 'area_name':
            require(value.strip() == value and '..' not in value, 'invalid-area-name')
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
    if args.task == 'seoul-live-collect':
        environment += [{'name': 'SEOUL_BASE_URL', 'value': output('Services', 'SeoulProxyUrl')},
                        {'name': 'SEOUL_ALLOWED_HOST', 'value': output('Services', 'SeoulProxyHost')}]
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
        bound = args.task in ('kto-smoke', 'kto-call-inventory', 'seoul-live-collect',
                              'list-live-replay-candidates') or plan is not None
        # A withdrawal needs the release that carries PostWithdrawMain; an older ops image would fail inside the task
        # after the lock was taken, so it is refused here instead.
        bound = bound or args.task == 'withdraw-post'
        # The English refresh likewise needs the release that carries its main (#360), and its calls are credited to
        # the release that made them.
        bound = bound or args.task == 'kto-eng-text-refresh'
        current, expected_digest = release_binding(definition) if bound else (None, None)
        if args.task == 'kto-call-inventory':
            # Read under the lock with the binding, so the release inventoried is the one this task definition is.
            require_release_version_unique(current)
            overrides['containerOverrides'][0]['environment'].append(
                {'name': 'NULLNULL_INVENTORY_RELEASE', 'value': current['releaseVersion']})
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
        # Every page: a task that logs more than one page of startup before its own lines (the inventory prints last)
        # would otherwise lose exactly the lines that are its result. The last page repeats the token it was given.
        events, token = [], None
        for _ in range(200):
            page = aws('logs', 'get-log-events', logGroupName=output('Platform', 'MigrationLogGroupName'),
                       logStreamName=stream, startFromHead=True, limit=500, **({'nextToken': token} if token else {}))
            events += page.get('events', [])
            following = page.get('nextForwardToken')
            if not following or following == token:
                break
            token = following
        else:
            # The bound is a safety stop, not an end: CloudWatch says the stream is read when the token stops moving.
            raise OpsError('task-log-not-fully-read')
        evidence, echoed, inventory, seoul, released = [], [], [], [], []
        withdrawn, refreshed = [], []
        for event in events:
            line = event.get('message', '').strip()
            # Count terminal-looking lines even when they fail the safe echo allowlist: a malformed
            # second line must invalidate a success, without printing its possibly sensitive text.
            if args.task == 'withdraw-post' and line.startswith(('post_withdrawn ', 'post_withdraw_failed ')):
                withdrawn.append(line)
            if args.task == 'kto-eng-text-refresh' and line.startswith('KTO_ENG_TEXT_REFRESH_DONE'):
                refreshed.append(line)
            if OPS_LOG_LINE.match(line):
                print('ops_log ' + line)
                if line.startswith('KTO_SMOKE_OK '):
                    evidence.append(line)
                if plan and line.startswith(CURATION_PLANS[args.task]['lines']):
                    echoed.append(line)
                if args.task == 'kto-call-inventory' and line.startswith(('kto_inventory', 'kto_operation ')):
                    inventory.append(line)
                if args.task == 'seoul-live-collect' and line == 'seoul_live_collect live=true':
                    seoul.append(line)
                if args.task == 'release-source-quarantine' and line.startswith('source_quarantine_released '):
                    released.append(line)
        if failure:
            raise failure
        if args.task == 'seoul-live-collect':
            require(seoul == ['seoul_live_collect live=true'], 'seoul-collect-not-live')
        # A release that released nothing is not a success: the main prints a refused line and exits zero so a
        # benign no-op leaves no stack trace, and this is what stops that line from reading as a source reopened.
        if args.task == 'release-source-quarantine':
            require(len(released) == 1, 'source-not-released')
        # Exactly one terminal line, a success, naming the post the owner approved: a count alone would accept a
        # withdrawal of some other post. ALREADY_HIDDEN is a success - a rerun finds the post where the first run left it.
        if args.task == 'withdraw-post':
            success = (r'post_withdrawn post=' + re.escape(args.post_id)
                       + r' outcome=(WITHDRAWN|ALREADY_HIDDEN)'
                       + r' cover=(DELETED versions=[1-9][0-9]{0,8}|ALREADY_ABSENT versions=0|NOT_USER_UPLOAD versions=0)')
            require(len(withdrawn) == 1 and re.fullmatch(success, withdrawn[0]), 'post-not-withdrawn')
        # Exactly one DONE line that tried every link and failed none. The main exits zero with no links at all, and a
        # task can end before it prints DONE; neither is a refresh, for the reason withdraw-post counts its line.
        if args.task == 'kto-eng-text-refresh':
            done = re.fullmatch(r'KTO_ENG_TEXT_REFRESH_DONE links=([1-9][0-9]{0,3}) attempted=([1-9][0-9]{0,3}) failed=0',
                                refreshed[0]) if len(refreshed) == 1 else None
            require(done is not None and done.group(1) == done.group(2), 'eng-text-not-refreshed')
        if args.task == 'kto-smoke':
            write_actual_call_report(evidence, current)
        if plan:
            record_curation(args.task, plan, echoed, current)
        if args.task == 'kto-call-inventory':
            record_inventory(inventory, current)
    print('ops_task=' + args.task + ' result=succeeded')

def require_release_version_unique(current):
    """The call-audit is keyed by the release version string, so an inventory is one artifact's only if no other
    artifact ever deployed under that string. Nothing upstream guarantees it: CI names a release by its workflow run
    number, which a re-run keeps while rebuilding the images, and a hand-made manifest names whatever it is given. Every
    recorded release is read; one with this version but another git sha or image is a union the checker would accept as
    one release's list. A rollback records the same manifest again under a new plan, which is the same artifact."""
    bucket = release_bucket()
    listing = aws_cli(['s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', 'releases/', '--output', 'json'])
    require(listing.returncode == 0, 'release-records-unlistable')
    keys = sorted(o['Key'] for o in json.loads(listing.stdout or '{}').get('Contents', [])
                  if re.fullmatch(r'releases/[a-f0-9]{64}/plan\.tgz', o['Key']))
    require(current['planKey'] in keys, 'deployed-release-not-recorded')
    mine = current['releaseManifest']
    with tempfile.TemporaryDirectory(prefix='nullnull-releases-') as temp:
        for key in keys:
            archive = Path(temp)/'plan.tgz'
            require(aws_cli(['s3', 'cp', f's3://{bucket}/{key}', archive, '--only-show-errors']).returncode == 0,
                    'release-archive-unreadable')
            with tarfile.open(archive) as tar:
                member = tar.extractfile('release.json')
                require(member is not None, 'release-archive-unreadable')
                other = json.loads(member.read())
            if other.get('releaseVersion') == current['releaseVersion']:
                require(all(other.get(f) == mine.get(f) for f in ('gitSha', 'apiImageDigest', 'aiImageDigest')),
                        'release-version-reused-by-another-artifact')

def record_inventory(lines, current):
    """The task's inventory lines, verbatim and in their order, as the file check_submission_inventory.py --inventory
    reads, kept with the release's evidence.

    Verbatim because that checker matches the lines themselves (^kto_inventory ... release=, ^kto_operation source=
    endpoint=, counts_as_evidence=true): the ops_log prefix this operator prints would make every one of them miss. And
    complete, or nothing: the total the main prints last must count exactly the operation lines read back, because an
    inventory that lost a line to the log would list fewer APIs than were called and still read as a list.
    """
    release = current['releaseVersion']
    headers = [line for line in lines if line.startswith('kto_inventory target=')]
    require(len(headers) == 1, 'inventory-header-missing')
    require(headers[0].endswith(' release=' + release), 'inventory-not-for-the-deployed-release')
    totals = [line for line in lines if line.startswith('kto_inventory operations=')]
    operations = [line for line in lines if line.startswith('kto_operation ')]
    require(len(totals) == 1 and totals[0].split()[1] == f'operations={len(operations)}', 'inventory-incomplete')
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    path = ROOT/'.artifacts/aws/evidence'/f'kto-inventory-{release}-{stamp}.txt'
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    key = f'evidence/kto-inventory/{release}/{path.name}'
    require(aws_cli(['s3', 'cp', path, f's3://{release_bucket()}/{key}', '--only-show-errors']).returncode == 0,
            'inventory-evidence-upload-failed')
    verdict = totals[0].split(' ', 2)[2]
    print(f'kto_inventory_file={path} release={release} operations={len(operations)} {verdict} evidence={key}')

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
    published = None
    if 'entry' in spec:
        entries = [m.groups() for m in (re.match(spec['entry'], line) for line in echoed) if m]
        ids = [entry_id for entry_id, _ in entries]
        require(len(ids) == len(set(ids)) and sorted(ids) == sorted(plan['ids']), spec['incomplete'])
        published = sum(1 for _, outcome in entries if outcome == 'PUBLISHED')
    done = re.compile(spec['done'].format(n=plan['items'], published=published))
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

# BA-006-T2: the bundles, images and logs of this staging hold none of the secrets the runtime is given. Only the two
# secrets the operator may read (OperatorSecrets) are looked for; the others the tasks are given are named in the
# evidence as not scanned and the verdict says partial - reading the database or signing secrets onto this machine to
# prove they did not leak would itself be the leak, and infra/iam/operator.json has no room to grant it.
SCAN_SECRETS = {'KTO_SERVICE_KEY': KTO_SECRET, 'VERIFIER_TOKEN': 'nullnull-stg/verifier-token'}
# The task secret each looked-for value is injected as (the verifier token is never given to a task: the edge strips it).
SCAN_TASK_SECRET_NAMES = {'KTO_SERVICE_KEY'}
# Where each task family's definition is read from. ops and migration are bound to the deployed revision through the
# Migration stack outputs; api and ai have no such output and the operator cannot describe services, so their latest
# ACTIVE revision is read - their log groups are Platform's and do not change with a revision.
SCAN_TASK_DEFINITIONS = {'nullnull-stg-api': None, 'nullnull-stg-ai': None,
                         'nullnull-stg-ops': 'OpsTaskDefinitionArn', 'nullnull-stg-migration': 'MigrationTaskDefinitionArn'}
SCAN_IMAGES = {'api': ('nullnull-stg-api', 'apiImageDigest'), 'ai': ('nullnull-stg-ai', 'aiImageDigest')}
# Recognised by their bytes, not their names: a zip needs no .jar suffix and a gzip no .gz.
MAGICS = [(b'PK\x03\x04', 'zip'), (b'\x1f\x8b', 'gzip'), (b'BZh', 'bzip2'), (b'\xfd7zXZ\x00', 'xz'),
          (b'\x28\xb5\x2f\xfd', 'zstd')]
EXPAND_DEPTH = 6        # a layer, the boot jar, a library jar, and room for what they hold
EXPAND_MAX_BYTES = 512 << 20  # per inflated blob; beyond it the blob is counted as not expanded, never as clean

def value_forms(value):
    """Every way a value is likely to be written down. KtoKorServiceProperties sends serviceKey=URLEncoder(key), so a
    request line holds java.net.URLEncoder's form (unreserved '.-*_', space as '+', uppercase %XX of the UTF-8 bytes);
    another client or formatter may write lowercase hex; a JSON encoder may escape '/' with a backslash; and a header or config
    may carry it base64-encoded."""
    upper = ''.join(c if (c.isascii() and c.isalnum()) or c in '.-*_' else '+' if c == ' '
                    else ''.join(f'%{b:02X}' for b in c.encode('utf-8')) for c in value)
    forms = {'URLENCODED': upper, 'URLENCODED_LOWER': re.sub(r'%[0-9A-F]{2}', lambda m: m.group(0).lower(), upper),
             'JSON_ESCAPED': value.replace('/', '\\/'), 'BASE64': base64.b64encode(value.encode('utf-8')).decode('ascii')}
    return {suffix: form for suffix, form in forms.items() if form != value}

def scan_values():
    """name -> bytes to find, read from Secrets Manager. The values never leave this process or reach a line."""
    values = {}
    for name, secret_id in SCAN_SECRETS.items():
        value = aws('secretsmanager', 'get-secret-value', SecretId=secret_id).get('SecretString') or ''
        # The scanner's own floor: shorter would match everywhere and prove nothing.
        require(len(value) >= 8, 'secret-unreadable-or-too-short-' + name.lower().replace('_', '-'))
        values[name] = value.encode('utf-8')
        for suffix, form in value_forms(value).items():
            values[f'{name}_{suffix}'] = form.encode('utf-8')
    return values

def recorded_assemblies(bucket, current, into):
    """Every release this operator ever deployed, from its record in the release bucket: the assembly and, proven by
    tree digest, its web bundle. All of them, not only the current one: the web deployment never prunes, so the hashed
    files of an old release are still served to anyone holding their URL."""
    listing = aws_cli(['s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', 'releases/', '--output', 'json'])
    require(listing.returncode == 0, 'release-records-unlistable')
    keys = sorted(o['Key'] for o in json.loads(listing.stdout or '{}').get('Contents', [])
                  if re.fullmatch(r'releases/[a-f0-9]{64}/plan\.tgz', o['Key']))
    require(current['planKey'] in keys, 'deployed-release-not-recorded')
    found = []
    for n, key in enumerate(keys):
        archive = into/f'release-{n}.tgz'
        require(aws_cli(['s3', 'cp', f's3://{bucket}/{key}', archive, '--only-show-errors']).returncode == 0,
                'release-archive-unreadable')
        plan = into/f'release-{n}'
        with tarfile.open(archive) as tar:
            tar.extractall(plan, filter='data')
        archive.unlink()
        require(digest(plan/'plan.json') == key.split('/')[1], 'release-archive-not-its-plan')
        data = json.loads((plan/'plan.json').read_text())
        require(tree_digest(plan/'assembly') == data['assemblySha256'], 'assembly-changed')
        web = json.loads((plan/'release.json').read_text())['webArtifactSha256']
        bundles = [d for d in sorted((plan/'assembly').iterdir())
                   if d.is_dir() and d.name.startswith('asset.') and 'sha256:' + tree_digest(d) == web]
        require(len(bundles) == 1, 'deployed-web-bundle-not-in-assembly')
        found.append({'planSha256': key.split('/')[1], 'assembly': plan/'assembly', 'bundle': bundles[0],
                      'current': key == current['planKey']})
    return found

def task_definitions():
    """The definitions the scan reads log groups and injected secret names from."""
    definitions = []
    for family, output_key in SCAN_TASK_DEFINITIONS.items():
        reference = output('Migration', output_key) if output_key else family
        definitions.append(aws('ecs', 'describe-task-definition', taskDefinition=reference)['taskDefinition'])
    return definitions

def export_logs(definitions, since_ms, into):
    """Every retained log event (or those after --since) of every group the task definitions log to, one file each."""
    groups = sorted({((c.get('logConfiguration') or {}).get('options') or {}).get('awslogs-group')
                     for d in definitions for c in d.get('containerDefinitions', [])} - {None})
    require(groups, 'no-log-groups-found')
    counts = {}
    for n, group in enumerate(groups):
        command = ['logs', 'filter-log-events', '--log-group-name', group, '--output', 'json']
        if since_ms is not None:
            command += ['--start-time', str(since_ms)]
        p = aws_cli(command)
        require(p.returncode == 0, 'log-events-unreadable')
        events = json.loads(p.stdout or '{}').get('events', [])
        with (into/f'group-{n}.log').open('w', encoding='utf-8') as out:
            for event in events:
                out.write(str(event.get('message', '')) + '\n')
        counts[group] = len(events)
    # An empty read is not a clean one: the API logs every start, so nothing at all means nothing was read.
    require(sum(counts.values()) > 0, 'no-log-events-to-scan')
    return counts

def export_images(manifest, account, into):
    """Each release image, saved by digest from ECR, through a Docker config that exists only for this scan: the ECR
    token lives 12 hours, and a check for leaked credentials must not leave one in the operator's own Docker config.
    The config sits inside the scan's temporary directory, so it goes with it; the logout is for the token itself."""
    require(shutil.which('docker') is not None, 'docker-required-for-image-scan')
    registry = f'{account}.dkr.ecr.{REGION}.amazonaws.com'
    config = into/'docker-config'
    config.mkdir(mode=0o700)
    env = {**os.environ, 'DOCKER_CONFIG': str(config)}
    token = aws_cli(['ecr', 'get-login-password'])
    require(token.returncode == 0 and token.stdout.strip(), 'ecr-login-unavailable')
    try:
        login = subprocess.run(['docker', 'login', '--username', 'AWS', '--password-stdin', registry],
                               input=token.stdout, text=True, capture_output=True, timeout=120, env=env)
        require(login.returncode == 0, 'docker-login-failed')
        saved = {}
        for name, (repository, field) in SCAN_IMAGES.items():
            reference = f'{registry}/{repository}@{manifest[field]}'
            for command in (['docker', 'pull', '--quiet', reference],
                            ['docker', 'save', '-o', str(into/f'{name}.tar'), reference]):
                require(subprocess.run(command, capture_output=True, text=True, timeout=900, env=env).returncode == 0,
                        'image-unavailable-' + name)
            saved[name] = into/f'{name}.tar'
        return saved
    finally:
        subprocess.run(['docker', 'logout', registry], capture_output=True, text=True, timeout=60, env=env)

def magic(head):
    return next((kind for prefix, kind in MAGICS if head.startswith(prefix)), None)

def decompress(kind, data):
    """The blob's content, or None when it would inflate past EXPAND_MAX_BYTES or cannot be read."""
    import bz2, lzma
    try:
        if kind == 'zstd':
            from compression import zstd  # Python 3.14
            source = zstd.ZstdFile(io.BytesIO(data))
        else:
            buffer = io.BytesIO(data)
            source = gzip.GzipFile(fileobj=buffer) if kind == 'gzip' else bz2.BZ2File(buffer) if kind == 'bzip2' \
                else lzma.LZMAFile(buffer)
        with source:
            content = source.read(EXPAND_MAX_BYTES + 1)
    except (ImportError, OSError, EOFError, ValueError, lzma.LZMAError):
        return None
    return content if len(content) <= EXPAND_MAX_BYTES else None

def scan_blob(data, values, where, depth, hits, counts):
    """What a compressed or archived blob holds, found by its bytes and expanded until nothing is left compressed. A
    blob this cannot open, or that goes deeper or larger than the bounds, is counted - it makes the verdict partial,
    because a secret inside it would be invisible and the scan would otherwise read as clean."""
    kind = magic(data[:8])
    if kind is None:
        if tarfile.is_tarfile(io.BytesIO(data)) if len(data) >= 512 else False:
            with tarfile.open(fileobj=io.BytesIO(data)) as members:
                for member in members:
                    if member.isfile():
                        extracted = members.extractfile(member)
                        if extracted is not None:
                            scan_blob(extracted.read(), values, f'{where}:{member.name}', depth + 1, hits, counts)
        return
    if depth >= EXPAND_DEPTH:
        counts['unexpanded'] += 1
        return
    if kind == 'zip':
        try:
            archive = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            counts['unexpanded'] += 1
            return
        counts['archives'] += 1
        for info in archive.infolist():
            if info.is_dir():
                continue
            if info.file_size > EXPAND_MAX_BYTES:
                counts['unexpanded'] += 1
                continue
            content = archive.read(info)
            counts['entries'] += 1
            record_hits(content, values, f'{where}!{info.filename}', hits)
            scan_blob(content, values, f'{where}!{info.filename}', depth + 1, hits, counts)
        return
    content = decompress(kind, data)
    if content is None:
        counts['unexpanded'] += 1
        return
    counts['inflated'] += 1
    record_hits(content, values, f'{where}({kind})', hits)
    scan_blob(content, values, f'{where}({kind})', depth + 1, hits, counts)

def record_hits(content, values, where, hits):
    for name, value in values.items():
        if value in content:
            hits.setdefault(name, set()).add(where)

def scan_image(tar_path, values, hits, counts):
    """A saved image: every blob in it expanded by scan_blob. A containerd image store saves layers as pulled -
    compressed - so the saved tar's own bytes would show nothing."""
    with tarfile.open(tar_path) as outer:
        for member in outer:
            if member.isfile():
                extracted = outer.extractfile(member)
                if extracted is not None:
                    data = extracted.read()
                    record_hits(data, values, f'{tar_path.stem}:{member.name}', hits)
                    scan_blob(data, values, f'{tar_path.stem}:{member.name}', 0, hits, counts)
    tar_path.unlink()

def secret_scan(args):
    """BA-006-T2: every recorded release's web bundle (and the rest of its assembly), the deployed release's two
    images, and the retained logs hold neither secret the operator can read, in any of the forms value_forms names. The
    verdict and what was read go to the release bucket as evidence; no value is ever printed or written."""
    require(auth_mode() == 'profile', 'secret-scan-is-local-only')
    account = os.environ.get('NULLNULL_AWS_ACCOUNT_ID', '')
    identity(account)
    bucket = release_bucket()
    current = read_current_release(bucket)
    require(current is not None, 'no-deployed-release-record')
    manifest = current['releaseManifest']
    since = dt.datetime.fromisoformat(args.since) if args.since else None
    require(since is None or since.tzinfo is not None, 'since-needs-a-timezone')
    # The same scanner the repository tests (scripts/tests/test_secret_exposure.py), not a second copy of its loop.
    scanner_spec = importlib.util.spec_from_file_location('check_secret_exposure', ROOT/'scripts/check_secret_exposure.py')
    scanner = importlib.util.module_from_spec(scanner_spec)
    scanner_spec.loader.exec_module(scanner)
    values = scan_values()
    hits, counts = {}, {'inflated': 0, 'archives': 0, 'entries': 0, 'unexpanded': 0}
    definitions = task_definitions()
    injected = sorted({s['name'] for d in definitions for c in d.get('containerDefinitions', [])
                       for s in c.get('secrets', []) or []})
    not_scanned = [name for name in injected if name not in SCAN_TASK_SECRET_NAMES]
    with tempfile.TemporaryDirectory(prefix='nullnull-secret-scan-') as temp:
        temp = Path(temp)
        (temp/'releases').mkdir()
        releases = recorded_assemblies(bucket, current, temp/'releases')
        (temp/'logs').mkdir()
        log_counts = export_logs(definitions, int(since.timestamp() * 1000) if since else None, temp/'logs')
        images = {}
        if not args.without_images:
            (temp/'images').mkdir()
            for name, tar_path in export_images(manifest, account, temp/'images').items():
                images[name] = manifest[SCAN_IMAGES[name][1]]
                scan_image(tar_path, values, hits, counts)
        files = [p for p in sorted(temp.rglob('*')) if p.is_file()]
        require(files, 'no-files-to-scan')
        for path in files:
            relative = str(path.relative_to(temp))
            for name in scanner.scan_file(path, values):
                hits.setdefault(name, set()).add(relative)
            # An assembly holds the covers and a bundle holds whatever it ships: expand what is compressed there too.
            if path.parent != temp/'logs':
                with path.open('rb') as f:
                    head = f.read(8)
                if magic(head):
                    scan_blob(path.read_bytes(), values, relative, 0, hits, counts)
        bundle_files = sum(1 for r in releases for p in r['bundle'].rglob('*') if p.is_file())
    leaked = {name: sorted(where) for name, where in sorted(hits.items())}
    partial = ([f'task secrets not scanned: {", ".join(not_scanned)}'] if not_scanned else []) + \
              (['images not scanned'] if not images else []) + \
              ([f'{counts["unexpanded"]} blobs not expanded'] if counts['unexpanded'] else [])
    verdict = 'leaked' if leaked else 'clean-partial' if partial else 'clean'
    scanned_at = dt.datetime.now(dt.timezone.utc)
    evidence = {'version': 2, 'check': 'BA-006-T2', 'verdict': verdict, 'partialBecause': partial,
                'release': current['releaseVersion'], 'planSha256': current['planSha256'],
                'scannedAt': scanned_at.isoformat(), 'variables': sorted(values), 'taskSecretsNotScanned': not_scanned,
                'releasesScanned': [r['planSha256'] for r in releases], 'files': len(files),
                'webBundleFiles': bundle_files,
                'logs': {'since': since.isoformat() if since else 'retention', 'eventsByGroup': log_counts},
                'images': images or 'not-scanned', 'blobsInflated': counts['inflated'],
                'archivesExpanded': counts['archives'], 'archiveEntries': counts['entries'],
                'blobsNotExpanded': counts['unexpanded'], 'leaked': leaked}
    path = ROOT/'.artifacts/aws/evidence'/f"secret-exposure-{scanned_at.strftime('%Y%m%dT%H%M%SZ')}.json"
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    write_private(path, evidence)
    key = f"evidence/secret-exposure/{current['releaseVersion']}/{path.name}"
    require(aws_cli(['s3', 'cp', path, f's3://{bucket}/{key}', '--only-show-errors']).returncode == 0,
            'secret-scan-evidence-upload-failed')
    print(f"secret_exposure={verdict} release={current['releaseVersion']} variables={len(values)} files={len(files)} "
          f"releases={len(releases)} web_bundle_files={bundle_files} log_events={sum(log_counts.values())} "
          f"images={','.join(sorted(images)) or 'none'} blobs_inflated={counts['inflated']} "
          f"archive_entries={counts['entries']} blobs_not_expanded={counts['unexpanded']} evidence={key}")
    for reason in partial:
        print(f'secret_exposure_partial reason={reason}')
    for name, where in leaked.items():
        # The variable and how many places, never the value.
        print(f'secret_exposure_leak variable={name} files={len(where)}')
    require(not leaked, 'secret-exposure-leaked')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['deploy','rollback','bootstrap','classify','secrets','task','unlock','edge',
                                          'secret-scan'])
    parser.add_argument('--state',choices=sorted(EDGE_STATES))
    parser.add_argument('--seoul',action='store_true',help='secrets only: update Seoul apiKey, preserving proxyToken')
    parser.add_argument('--manifest');parser.add_argument('--web-dir');parser.add_argument('--plan')
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--approved-plan-sha256','--approved-diff-sha256',dest='approved_plan_sha256')
    parser.add_argument('--kind',choices=['app','infra'])
    parser.add_argument('--preserve-open-edge',action='store_true',help='deploy only: retain an already-open edge with unchanged schema and edge/service templates')
    parser.add_argument('--accept-additive-schema',help='deploy plan only: the migration files, comma-separated, this release '
                        'appends to the deployed schema; recorded in the plan for a --preserve-open-edge execute, where '
                        'nothing else about the schema may change. At execute it may only repeat the plan\'s names')
    parser.add_argument('--days',type=int,default=14);parser.add_argument('--estimated-total',type=float)
    parser.add_argument('--cost-basis')
    parser.add_argument('--previous-plan');parser.add_argument('--previous-plan-sha256')
    parser.add_argument('--task',choices=sorted(OPS_TASKS));parser.add_argument('--content-id')
    parser.add_argument('--content-type-id');parser.add_argument('--place-id');parser.add_argument('--owner-approval')
    parser.add_argument('--places');parser.add_argument('--area-name');parser.add_argument('--source-code');parser.add_argument('--plan-file')
    parser.add_argument('--post-id')
    parser.add_argument('--owner');parser.add_argument('--accept-newer-schema',action='store_true')
    parser.add_argument('--since');parser.add_argument('--without-images',action='store_true')
    args=parser.parse_args()
    os.umask(0o077)
    try:
        require(not args.seoul or args.action == 'secrets', 'seoul-option-requires-secrets')
        if args.action=='classify': classify(args)
        elif args.action=='secrets': provision_secrets(args)
        elif args.action=='task': ops_task(args)
        elif args.action=='unlock': unlock(args)
        elif args.action=='edge': edge(args)
        elif args.action=='secret-scan': secret_scan(args)
        elif args.execute: execute(args)
        else: plan(args)
    except (OpsError, OSError, KeyError, ValueError) as error:
        reason=str(error) if isinstance(error,OpsError) else 'invalid-input-or-local-operation'
        print('staging_ops=failed reason='+reason,file=sys.stderr)
        return 1
    return 0
if __name__=='__main__':
    sys.exit(main())
