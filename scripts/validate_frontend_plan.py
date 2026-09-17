#!/usr/bin/env python3
"""Check the frontend plan manifest against the canonical sources.

This mirrors validate_backend_plan.py but encodes the differences that make the
frontend plan a different kind of document:

- Frontend *consumes* operations, so one operation legitimately appears in several
  screens. Exclusive operation ownership is a backend rule and is not checked here.
- Frontend covers the screen-bearing features only; server-side and operational
  requirements have no FE task. Total feature coverage is likewise a backend rule.

What is checked is what can silently rot: unknown phases, unknown or non-P0
features, Figma nodes that no longer exist in the handoff, operations that are not
in the OpenAPI document, dependencies that point at missing tasks or later phases,
and dependency cycles.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from xml.etree import ElementTree

# Imported rather than copied: the two plans must judge an evidence path the same
# way, and a second implementation would drift the moment one side is tightened.
from validate_backend_plan import resolve_link

PLAN = 'docs/engineering/frontend-plan.json'
BACKEND_PLAN = 'docs/engineering/backend-plan.json'
INVENTORY = 'docs/product/FUNCTIONAL_INVENTORY.md'
HANDOFF = 'docs/design/FIGMA_HANDOFF.md'
OPENAPI = 'docs/api/openapi.yaml'
STATUSES = {'planned', 'contract-ready', 'in-progress', 'integration-ready',
            'verified', 'blocked', 'deferred'}


def testcase_names(path: Path) -> set[str] | None:
    """Every testcase name in a JUnit report, or None when it is not one.

    None rather than an empty set, for the reason check_test_reports.py gives about
    `implementedTestIds`: an empty set would satisfy every "is the ID in here" check
    below, which is the failure this function exists to prevent.
    """
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError):
        return None
    cases = list(root.iter('testcase'))
    if not cases:
        return None
    names: set[str] = set()
    for case in cases:
        # Playwright writes the spec title in `name` and the describe block in
        # `classname`; an ID can be in either, so both are searched.
        for key in ('name', 'classname'):
            value = case.get(key)
            if value:
                names.add(value)
    return names


def validate(root: Path, problems: list[str]) -> None:
    try:
        data = json.loads((root / PLAN).read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as error:
        problems.append(f'frontend plan unavailable: {error}')
        return
    if not isinstance(data, dict):
        problems.append('frontend plan top level must be an object')
        return
    if data.get('schemaVersion') != 1 or data.get('branch') != 'frontend':
        problems.append('frontend plan must declare schemaVersion=1 and branch=frontend')
    if data.get('requiredChecks') != ['docs-contract', 'docker-integration']:
        problems.append('frontend plan must preserve the two stable required checks')

    inventory = (root / INVENTORY).read_text(encoding='utf-8')
    priorities = dict(re.findall(r'^\| ((?:FR|NFR)-[A-Z0-9]+-\d+) \| (P[012]) \|', inventory, re.M))
    operations = set(re.findall(r'^\s+operationId:\s*(\w+)', (root / OPENAPI).read_text(encoding='utf-8'), re.M))
    known_nodes = set(re.findall(r'`(\d+:\d+)`', (root / HANDOFF).read_text(encoding='utf-8')))
    try:
        backend_ids = {t.get('id') for t in json.loads(
            (root / BACKEND_PLAN).read_text(encoding='utf-8')).get('tasks', [])}
    except (OSError, json.JSONDecodeError):
        backend_ids = set()
        problems.append('frontend plan cannot resolve backend task IDs')

    phases = data.get('phases', [])
    tasks = data.get('tasks', [])
    if not isinstance(phases, list) or not phases or not all(isinstance(p, dict) for p in phases):
        problems.append('frontend plan phases must be a non-empty object list')
        return
    phase_ids = [p.get('id') for p in phases]
    if not all(isinstance(x, str) for x in phase_ids):
        problems.append('frontend plan phase IDs must be strings')
        return
    if phase_ids[-1] != 'B10' or data.get('lastFeaturePhase') != 'B10':
        problems.append('Live B10 must remain the last feature phase')
    phase_index = {p: n for n, p in enumerate(phase_ids)}

    if not isinstance(tasks, list) or not tasks or not all(isinstance(t, dict) for t in tasks):
        problems.append('frontend plan tasks must be a non-empty object list')
        return
    ids = [t.get('id') for t in tasks]
    if not all(isinstance(i, str) and re.fullmatch(r'FE-\d{3}', i) for i in ids):
        problems.append('frontend plan has invalid task IDs')
        return
    if len(set(ids)) != len(ids):
        problems.append('frontend plan has duplicate task IDs')
    by_id = dict(zip(ids, tasks))
    test_ids: list[str] = []

    for task in tasks:
        tid = task['id']
        phase = task.get('phase')
        if phase not in phase_index:
            problems.append(f'{tid}: unknown phase {phase}')
        for key in ('title', 'safety', 'handoff', 'owner', 'reviewer'):
            if not isinstance(task.get(key), str) or not task[key].strip():
                problems.append(f'{tid}: missing {key}')
        if task.get('priority') not in {'P0', 'P1', 'P2'} or task.get('status') not in STATUSES:
            problems.append(f'{tid}: invalid priority or status')
        if task.get('owner') != 'FE_DRI' or task.get('reviewer') != 'BE_AI_DRI':
            problems.append(f'{tid}: Frontend ownership and BE/AI review must be explicit')
        malformed = False
        for key in ('dependsOn', 'operations', 'featureIds', 'figmaNodes', 'designRequests', 'steps'):
            value = task.get(key)
            if not isinstance(value, list) or not all(isinstance(x, str) for x in value):
                problems.append(f'{tid}: {key} must be a string list')
                malformed = True
            elif key != 'steps' and len(value) != len(set(value)):
                problems.append(f'{tid}: duplicate {key}')
        if malformed:
            continue
        if not task['steps']:
            problems.append(f'{tid}: implementation steps are empty')
        for feature in task['featureIds']:
            if feature not in priorities:
                problems.append(f'{tid}: unknown feature ID: {feature}')
            elif task.get('priority') == 'P0' and priorities[feature] != 'P0':
                problems.append(f'{tid}: non-P0 feature in a P0 task: {feature}')
        for operation in task['operations']:
            if operation not in operations:
                problems.append(f'{tid}: operation not in OpenAPI: {operation}')
        for node in task['figmaNodes']:
            if node not in known_nodes:
                problems.append(f'{tid}: unverified Figma node: {node}')
        if any(f.startswith('FR-LIV-') for f in task['featureIds']) and phase != 'B10':
            problems.append(f'{tid}: Live work must be in final B10 phase')
        for dependency in task['dependsOn']:
            if dependency.startswith('BA-'):
                if dependency not in backend_ids:
                    problems.append(f'{tid}: missing backend dependency {dependency}')
                continue
            previous = by_id.get(dependency)
            if previous is None:
                problems.append(f'{tid}: missing dependency {dependency}')
            elif previous.get('phase') in phase_index and phase in phase_index:
                if phase_index[previous['phase']] > phase_index[phase]:
                    problems.append(f'{tid}: dependency points to a later phase')
                if task.get('priority') == 'P0' and previous.get('priority') != 'P0':
                    problems.append(f'{tid}: optional expansion must not block P0')
        required = task.get('tests')
        if not isinstance(required, list) or not required or not all(isinstance(t, dict) for t in required):
            problems.append(f'{tid}: non-empty acceptance tests required')
            continue
        for test in required:
            ident = test.get('id', '')
            if not isinstance(ident, str) or not re.fullmatch(re.escape(tid) + r'-T[1-9]\d*', ident):
                problems.append(f'{tid}: invalid test ID')
                continue
            test_ids.append(ident)
            if not isinstance(test.get('assertion'), str) or not test['assertion'].strip():
                problems.append(f'{tid}: missing assertion for {ident}')
        if task.get('status') == 'verified':
            evidence = task.get('evidence', {})
            if not isinstance(evidence, dict) or not all(
                    evidence.get(x) for x in ('report', 'contractSha', 'reviewer')):
                problems.append(f'{tid}: verified requires report, contract SHA and reviewer')
            elif set(evidence.get('testIds', [])) != {t['id'] for t in required}:
                problems.append(f'{tid}: evidence does not cover all required tests')
            else:
                # The report has to name something that ran. Until now these three
                # fields were only checked for being non-empty strings, so a card
                # could claim `verified` with a report path that does not exist -
                # demonstrated on #208 with the literal '완전히 지어낸 경로.xml',
                # which passed the whole gate. The testIds check above cannot catch
                # it either: it compares evidence.testIds against the card's own
                # required list, and both live in this same file, so copying the
                # ids across always matches.
                #
                # Same rule as validate_backend_plan (f08ffa7): a URL must be a run
                # of this repository's gate, and anything else must resolve to a
                # file that is actually committed.
                report = evidence['report']
                if isinstance(report, str) and report.startswith(('http://', 'https://')):
                    if not re.fullmatch(r'https://github\.com/[^/]+/[^/]+/actions/runs/\d+',
                                        report.rstrip('/')):
                        problems.append(f'{tid}: evidence.report must be a GitHub Actions run URL '
                                        f'for this repository, not {report!r}')
                else:
                    resolve_link(root, root, report, problems, tid)
                    # And the report has to CONTAIN the tests the card claims.
                    #
                    # Everything above this point can be satisfied without a test
                    # ever running: resolve_link only proves the path exists, and
                    # the testIds comparison holds evidence.testIds against the
                    # card's own tests[] - both in this same file, so copying the
                    # ids across always matches. #208 named the asymmetry: the
                    # backend plan resolves its IDs against real JUnit testcase
                    # names (check_test_reports.py), and the frontend plan resolved
                    # them against itself. Demonstrated before writing this, by
                    # pointing a card's report at frontend-plan.json itself and
                    # watching the whole gate pass.
                    #
                    # A URL cannot be opened here, so the check applies to committed
                    # paths only; that is the same boundary validate_backend_plan
                    # draws, and a run URL is judged by the gate that produced it.
                    path = (root / report).resolve()
                    names = testcase_names(path)
                    if names is None:
                        problems.append(f'{tid}: evidence.report is not a JUnit report with '
                                        f'testcases: {report}')
                    else:
                        for ident in sorted(evidence.get('testIds', [])):
                            if not any(ident in name for name in names):
                                problems.append(f'{tid}: {ident} is not named by any testcase in '
                                                f'{report}')
        if task.get('status') in {'blocked', 'deferred'} and not task.get('reason'):
            problems.append(f'{tid}: blocked/deferred requires reason and safe default')

    if len(test_ids) != len(set(test_ids)):
        problems.append('duplicate frontend acceptance test IDs')

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(tid: str) -> None:
        if tid in visiting:
            problems.append(f'frontend plan dependency cycle at {tid}')
            return
        if tid in visited:
            return
        visiting.add(tid)
        dependencies = by_id[tid].get('dependsOn', [])
        if isinstance(dependencies, list):
            for dependency in dependencies:
                if isinstance(dependency, str) and dependency in by_id:
                    visit(dependency)
        visiting.remove(tid)
        visited.add(tid)

    for tid in ids:
        visit(tid)
