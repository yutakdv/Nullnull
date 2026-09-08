#!/usr/bin/env python3
"""Check plan coverage and native Obsidian references, without external packages.

This validates documentation, not application functionality or provider evidence.
The runtime test gates are implemented with the corresponding application slices.
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from urllib.parse import unquote

PLAN = 'docs/engineering/backend-plan.json'
CARD = 'docs/roles/BACKEND_AI_PLAYBOOK.md'
CANVAS = 'docs/BACKEND_ROADMAP.canvas'
STATUSES = {'planned', 'contract-ready', 'in-progress', 'integration-ready',
            'verified', 'blocked', 'deferred'}
LIVE_OPERATIONS = {'queryLiveAreas', 'listLiveAreaPlaces', 'getLivePlace'}


def headings(text: str) -> set[str]:
    """Accept native Obsidian headings and GitHub Markdown heading slugs."""
    result: set[str] = set()
    seen: Counter[str] = Counter()
    fenced = False
    for line in text.splitlines():
        if line.startswith(('```', '~~~')):
            fenced = not fenced
        if fenced:
            continue
        match = re.match(r'^#{1,6}\s+(.+?)(?:\s+#+)?$', line)
        if not match:
            continue
        title = match[1].strip()
        slug = re.sub(r'[^\w\-\s]', '', title.lower()).replace(' ', '-')
        number = seen[slug]
        seen[slug] += 1
        result.update((title, slug + (f'-{number}' if number else '')))
    return result


def resolve_link(root: Path, parent: Path, target: str, problems: list[str], label: str) -> None:
    if target.startswith(('https://', 'http://', 'mailto:')):
        return
    target = target.strip('<>')
    file_part, _, anchor = unquote(target).partition('#')
    dest = (parent / file_part).resolve() if file_part else parent
    try:
        dest.relative_to(root.resolve())
    except ValueError:
        problems.append(f'{label}: reference escapes vault: {target}')
        return
    if not dest.exists():
        problems.append(f'{label}: missing vault target: {target}')
    elif anchor and dest.is_file() and dest.suffix == '.md':
        if anchor not in headings(dest.read_text(encoding='utf-8')):
            problems.append(f'{label}: missing heading: {target}')


def validate_plan(data: dict, operations: set[str], features: set[str],
                  cards: str, root: Path, problems: list[str]) -> None:
    if data.get('schemaVersion') != 1 or data.get('branch') != 'backend':
        problems.append('backend plan must declare schemaVersion=1 and branch=backend')
    if data.get('requiredChecks') != ['docs-contract', 'docker-integration']:
        problems.append('backend plan must preserve the two stable required checks')
    phases = data.get('phases', [])
    tasks = data.get('tasks', [])
    if not isinstance(phases, list) or not phases or not all(isinstance(p, dict) for p in phases):
        problems.append('backend plan phases must be a non-empty object list')
        return
    phase_ids = [p.get('id') for p in phases]
    if not all(isinstance(x, str) for x in phase_ids):
        problems.append('backend plan phase IDs must be strings')
        return
    if len(set(phase_ids)) != len(phase_ids):
        problems.append('backend plan has duplicate phase IDs')
    if phase_ids[-1] != 'B10' or data.get('lastFeaturePhase') != 'B10':
        problems.append('Live B10 must remain the last feature phase')
    phase_index = {p: n for n, p in enumerate(phase_ids)}
    if not isinstance(tasks, list) or not tasks or not all(isinstance(t, dict) for t in tasks):
        problems.append('backend plan tasks must be a non-empty object list')
        return
    ids = [t.get('id') for t in tasks]
    if not all(isinstance(i, str) and re.fullmatch(r'BA-\d{3}', i) for i in ids):
        problems.append('backend plan has invalid task IDs')
        return
    if len(set(ids)) != len(ids):
        problems.append('backend plan has duplicate task IDs')
    by_id = dict(zip(ids, tasks))
    owners: Counter[str] = Counter()
    assigned: set[str] = set()
    test_ids: list[str] = []
    card_sections = dict(re.findall(r'^### (BA-\d{3})\s*\n(.*?)(?=^### BA-|^## |\Z)', cards, re.M | re.S))
    if set(card_sections) != set(ids):
        problems.append('backend task card IDs differ from plan manifest')
    for task in tasks:
        tid = task['id']
        phase = task.get('phase')
        if phase not in phase_index:
            problems.append(f'{tid}: unknown phase {phase}')
        for key in ('title', 'entities', 'safety', 'handoff', 'owner', 'reviewer', 'note'):
            if not isinstance(task.get(key), str) or not task[key].strip():
                problems.append(f'{tid}: missing {key}')
        if task.get('priority') not in {'P0', 'P1', 'P2'} or task.get('status') not in STATUSES:
            problems.append(f'{tid}: invalid priority or status')
        if task.get('owner') != 'BE_AI_DRI' or task.get('reviewer') != 'FE_DRI':
            problems.append(f'{tid}: Backend/AI ownership and FE review must be explicit')
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
        owners.update(task['operations'])
        assigned.update(task['featureIds'])
        if not task['steps']:
            problems.append(f'{tid}: implementation steps are empty')
        if (LIVE_OPERATIONS.intersection(task['operations']) or
                any(f.startswith('FR-LIV-') for f in task['featureIds'])) and phase != 'B10':
            problems.append(f'{tid}: Live work must be in final B10 phase')
        for dependency in task['dependsOn']:
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
        card = card_sections.get(tid, '')
        # Keep machine metadata and human task cards synchronized.
        values = ([task.get('title', ''), task.get('priority', ''), task.get('status', '')] +
                  task['operations'] + task['featureIds'] + task['figmaNodes'] +
                  task['designRequests'] + task['dependsOn'] + [t.get('id', '') for t in required])
        for value in values:
            if isinstance(value, str) and value and value not in card:
                problems.append(f'{tid}: task card missing manifest value {value}')
        for prefix, expected, pattern in (
            ('- 기능 ID:', task['featureIds'], r'(?:FR|NFR)-[A-Z0-9]+-\d+'),
            ('- API:', task['operations'], r'`([a-z][A-Za-z0-9]+)`'),
            ('- 선행:', task['dependsOn'], r'BA-\d{3}'),
        ):
            row = next((line for line in card.splitlines() if line.startswith(prefix)), '')
            if set(re.findall(pattern, row)) != set(expected):
                problems.append(f'{tid}: task card metadata differs for {prefix}')
        if task.get('note') != f'{CARD}#{tid.lower()}':
            problems.append(f'{tid}: note must link to its exact task card')
        if task.get('status') == 'verified':
            evidence = task.get('evidence', {})
            if not isinstance(evidence, dict) or not all(evidence.get(x) for x in ('report', 'contractSha', 'reviewer', 'testIds')):
                problems.append(f'{tid}: verified requires report, contract SHA, reviewer and test IDs')
            else:
                if set(evidence['testIds']) != {t['id'] for t in required}:
                    problems.append(f'{tid}: evidence does not cover all required tests')
                resolve_link(root, root, evidence['report'], problems, tid)
        if task.get('status') in {'blocked', 'deferred'} and not task.get('reason'):
            problems.append(f'{tid}: blocked/deferred requires reason and safe default')
    if set(owners) != operations:
        problems.append(f'operation coverage mismatch: missing={sorted(operations-set(owners))}, unknown={sorted(set(owners)-operations)}')
    duplicates = [o for o, count in owners.items() if count > 1]
    if duplicates:
        problems.append('duplicate operation owners: ' + ', '.join(sorted(duplicates)))
    if assigned != features:
        problems.append(f'feature coverage mismatch: missing={sorted(features-assigned)}, unknown={sorted(assigned-features)}')
    if len(test_ids) != len(set(test_ids)):
        problems.append('duplicate backend acceptance test IDs')
    # Detect cycles even within one phase.
    visiting: set[str] = set()
    visited: set[str] = set()
    def visit(tid: str) -> None:
        if tid in visiting:
            problems.append(f'backend plan dependency cycle at {tid}')
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


def validate_canvas(root: Path, data: dict, problems: list[str]) -> None:
    nodes, edges = data.get('nodes'), data.get('edges')
    if not isinstance(nodes, list) or not isinstance(edges, list):
        problems.append('Canvas must contain nodes and edges lists')
        return
    seen: set[str] = set()
    node_ids: set[str] = set()
    for node in nodes:
        if not isinstance(node, dict):
            problems.append('Canvas node must be an object')
            continue
        ident = node.get('id')
        if not isinstance(ident, str) or not ident or ident in seen:
            problems.append('Canvas duplicate or invalid node ID')
            continue
        seen.add(ident); node_ids.add(ident)
        if not all(isinstance(node.get(k), (int, float)) for k in ('x','y','width','height')):
            problems.append(f'Canvas {ident}: invalid dimensions')
        elif node['width'] <= 0 or node['height'] <= 0:
            problems.append(f'Canvas {ident}: dimensions must be positive')
        if node.get('type') == 'file':
            target = node.get('file', '') + node.get('subpath', '')
            resolve_link(root, root, target, problems, 'Canvas ' + ident)
        elif node.get('type') == 'text':
            if not isinstance(node.get('text'), str):
                problems.append(f'Canvas {ident}: text required')
            else:
                for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', node['text']):
                    resolve_link(root, root, target, problems, 'Canvas ' + ident)
        else:
            problems.append(f'Canvas {ident}: unsupported node type')
    for edge in edges:
        if not isinstance(edge, dict):
            problems.append('Canvas edge must be an object')
            continue
        ident = edge.get('id')
        if not isinstance(ident, str) or not ident or ident in seen:
            problems.append('Canvas duplicate or invalid edge ID')
        seen.add(ident)
        if edge.get('fromNode') not in node_ids or edge.get('toNode') not in node_ids:
            problems.append(f'Canvas {ident}: missing edge endpoint')
        if any(edge.get(k) not in {'top','right','bottom','left'} for k in ('fromSide','toSide')):
            problems.append(f'Canvas {ident}: invalid side')


def has_calendar_estimate(text: str) -> bool:
    # Citation paths may contain dates; visible labels still must avoid calendar estimates.
    visible = re.sub(r'(\[[^\]\n]*\])\([^\)\n]*\)', r'\1', text)
    return bool(re.search(r'20\d{2}[-/]\d{2}[-/]\d{2}|(?<![A-Z0-9-])(?:0[1-9]|1[0-2])/(?:0[1-9]|[12]\d|3[01])(?![A-Z0-9])|\(\d+(?:\.\d+)?d\)', visible))


def validate(root: Path, problems: list[str]) -> None:
    try:
        data = json.loads((root / PLAN).read_text(encoding='utf-8'))
        canvas = json.loads((root / CANVAS).read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as error:
        problems.append(f'plan/Canvas unavailable: {error}')
        return
    if not isinstance(data, dict) or not isinstance(canvas, dict):
        problems.append('plan/Canvas top level must be an object')
        return
    inventory = (root / 'docs/product/FUNCTIONAL_INVENTORY.md').read_text(encoding='utf-8')
    operations = set(re.findall(r'^\s+operationId:\s*(\w+)', (root/'docs/api/openapi.yaml').read_text(), re.M))
    features = set(re.findall(r'^\| ((?:FR|NFR)-[A-Z0-9]+-\d+) \|', inventory, re.M))
    validate_plan(data, operations, features, (root/CARD).read_text(), root, problems)
    validate_canvas(root, canvas, problems)
    priorities = dict(re.findall(r'^\| (FR-[A-Z0-9]+-\d+) \| (P[012]) \|', inventory, re.M))
    known_nodes = set(re.findall(r'`(\d+:\d+)`', (root/'docs/design/FIGMA_HANDOFF.md').read_text()))
    for task in data.get('tasks', []):
        if not isinstance(task, dict):
            continue
        if isinstance(task.get('featureIds'), list):
            for feature in task['featureIds']:
                if isinstance(feature, str) and feature in priorities and task.get('priority') != priorities[feature]:
                    problems.append(f"{task.get('id')}: feature priority differs from inventory: {feature}")
        if isinstance(task.get('figmaNodes'), list):
            for node in task['figmaNodes']:
                if not isinstance(node, str) or node not in known_nodes:
                    problems.append(f"{task.get('id')}: unverified Figma node: {node}")
    for path in (root/'docs/engineering/IMPLEMENTATION_PLAN.md', root/CARD):
        text = path.read_text()
        if has_calendar_estimate(text):
            problems.append(f'{path.name}: development plan must use priorities and dependencies, not calendar estimates')
    # Foreign tool output (npm/uv) inside docs is not a note; Git ignores it too.
    paths = [p for p in (root/'docs').rglob('*.md') if not {'node_modules', '.venv'} & set(p.parts)]
    paths += list((root/'.claude').rglob('*.md'))
    paths += list(root.glob('*.md'))
    # App guides are canon for their path; their links and anchors need the same gate.
    paths += sorted(root.glob('apps/*/CLAUDE.md'))
    fcr_text = (root/'docs/design/FIGMA_CHANGE_REQUESTS.md').read_text()
    fcr_ids = set(re.findall(r'^\| (FCR-\d+) \|', fcr_text, re.M))
    for path in paths:
        text = path.read_text(encoding='utf-8')
        label = str(path.relative_to(root))
        # Skill YAML is a separate existing manifest; do not require note properties there.
        if 'docs' == path.relative_to(root).parts[0]:
            if not text.startswith('---\n') or '\n---\n' not in text[4:]:
                problems.append(f'{label}: missing native Obsidian properties')
            else:
                front = text[4:].split('\n---\n', 1)[0]
                for key in ('aliases','doc_type','status','area','tags'):
                    if not re.search(r'^'+key+r':', front, re.M):
                        problems.append(f'{label}: missing property {key}')
        # Ignore fenced code examples, inspect real Markdown links and anchors.
        prose = re.sub(r'^```[^\n]*\n.*?^```\s*$', '', text, flags=re.M|re.S)
        for target in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)', prose):
            if target.startswith('#'):
                target = path.name + target
            resolve_link(root, path.parent, target, problems, label)
        for fcr in set(re.findall(r'FCR-\d{3}', prose)) - fcr_ids:
            problems.append(f'{label}: unregistered design request {fcr}')
    env = (root/'docs/operations/ENVIRONMENT.md').read_text()
    row = next((line for line in env.splitlines() if line.startswith('|') and 'APP_REVERT_WINDOW' in line), '')
    if 'PT24H' not in row:
        problems.append('APP_REVERT_WINDOW must match the API 24-hour contract')
