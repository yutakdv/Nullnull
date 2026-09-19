#!/usr/bin/env python3
"""GitHub-side gates of the staging CD (no AWS access, stdlib only).

verify     Run inside `staging-release`: the commit being deployed is this run's commit on main, and
           BOTH required workflows (docs-contract, docker-integration) succeeded on exactly that SHA.
reconcile  Run on a schedule: main merges are made by GITHUB_TOKEN auto-merge, which starts no push
           workflow, so nothing tests a merge commit unless asked. For main HEAD this dispatches the two
           required workflows when they never ran (or were cancelled), waits while they run, and when
           both succeeded dispatches `staging-release` once for that SHA.

Only decisions are pure (tested); the GitHub calls are thin.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

REQUIRED = {'docs-contract.yml': 'Docs and contracts', 'integration.yml': 'Docker integration'}
RELEASE_WORKFLOW = 'staging-release.yml'
TESTED_EVENTS = {'push', 'workflow_dispatch'}
IN_FLIGHT = {'queued', 'in_progress', 'waiting', 'requested', 'pending'}
SHA = re.compile(r'[0-9a-f]{40}')

class GateError(Exception):
    pass

def workflow_state(runs, sha):
    """'success' | 'running' | 'failed' | 'absent' for one required workflow on one SHA.

    Only runs of exactly this SHA on main from push/workflow_dispatch count. A cancelled run (both
    required workflows cancel-in-progress per ref) is not a verdict, so it reads as absent, not failed.
    """
    relevant = [r for r in runs if r.get('head_sha') == sha and r.get('head_branch') == 'main'
                and r.get('event') in TESTED_EVENTS]
    if any(r.get('status') in IN_FLIGHT for r in relevant):
        return 'running'
    concluded = [r.get('conclusion') for r in relevant if r.get('status') == 'completed']
    if 'success' in concluded:
        return 'success'
    if any(c not in (None, 'cancelled', 'skipped', 'stale') for c in concluded):
        return 'failed'
    return 'absent'

def reconcile_decision(states, release_runs, sha):
    """What the reconciler does for main HEAD, given each required workflow's state.

    One release run per SHA: a finished release (success or failure) is never re-dispatched here;
    a human re-runs a failed one after reading why it failed.
    """
    if any(s == 'failed' for s in states.values()):
        return ('stop', 'required-ci-failed')
    if any(s == 'running' for s in states.values()):
        return ('wait', 'required-ci-running')
    missing = sorted(w for w, s in states.items() if s == 'absent')
    if missing:
        return ('dispatch-ci', ','.join(missing))
    mine = [r for r in release_runs if r.get('head_sha') == sha]
    if any(r.get('status') in IN_FLIGHT for r in mine):
        return ('wait', 'release-running')
    if any(r.get('status') == 'completed' and r.get('conclusion') not in ('cancelled',) for r in mine):
        return ('done', 'release-already-ran')
    return ('dispatch-release', sha)

def api(method, path, body=None):
    request = urllib.request.Request('https://api.github.com' + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                                              'Accept': 'application/vnd.github+json',
                                              'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        raise GateError(f'github-api-{method.lower()}-{error.code}') from error

def runs_for(repo, workflow, sha):
    return api('GET', f'/repos/{repo}/actions/workflows/{workflow}/runs?head_sha={sha}&per_page=100')['workflow_runs']

def verify(args):
    repo, sha = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    if os.environ.get('GITHUB_REF') != 'refs/heads/main':
        raise GateError('release-runs-only-on-main')
    if args.action == 'deploy':
        # The environment deployment record carries github.sha, and checkout uses it: deploying any other
        # commit would make that record lie. A moved main fails here and the reconciler retries.
        if not SHA.fullmatch(args.sha or '') or args.sha != sha:
            raise GateError('expected-sha-is-not-this-runs-commit')
        states = {w: workflow_state(runs_for(repo, w, sha), sha) for w in REQUIRED}
        for workflow, state in states.items():
            print(f'required_ci workflow={workflow} state={state} sha={sha}')
        if any(state != 'success' for state in states.values()):
            raise GateError('required-ci-not-successful-for-this-exact-sha')
    print(f'release_gate=pass action={args.action} sha={sha}')

def reconcile(_args):
    repo = os.environ['GITHUB_REPOSITORY']
    if os.environ.get('STAGING_AUTO_DEPLOY') != 'true':
        print('reconcile=disabled reason=vars.STAGING_AUTO_DEPLOY-is-not-true')
        return
    sha = api('GET', f'/repos/{repo}/commits/main')['sha']
    states = {w: workflow_state(runs_for(repo, w, sha), sha) for w in REQUIRED}
    releases = runs_for(repo, RELEASE_WORKFLOW, sha)
    action, detail = reconcile_decision(states, releases, sha)
    print(f'reconcile sha={sha} states={json.dumps(states, sort_keys=True)} action={action} detail={detail}')
    if action == 'dispatch-ci':
        for workflow in detail.split(','):
            api('POST', f'/repos/{repo}/actions/workflows/{workflow}/dispatches', {'ref': 'main'})
    elif action == 'dispatch-release':
        api('POST', f'/repos/{repo}/actions/workflows/{RELEASE_WORKFLOW}/dispatches',
            {'ref': 'main', 'inputs': {'action': 'deploy', 'expected_sha': sha}})

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('command', choices=['verify', 'reconcile'])
    parser.add_argument('--sha'); parser.add_argument('--action', choices=['deploy', 'rollback'], default='deploy')
    args = parser.parse_args()
    try:
        verify(args) if args.command == 'verify' else reconcile(args)
    except (GateError, KeyError) as error:
        print('ci_gate=failed reason=' + (str(error) if isinstance(error, GateError) else 'missing-environment'),
              file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
