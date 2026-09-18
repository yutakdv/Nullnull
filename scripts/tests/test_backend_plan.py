"""Negative tests for documentation coverage and Obsidian validators."""
from __future__ import annotations

import copy
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from validate_backend_plan import validate, validate_plan, validate_canvas, resolve_link, headings, has_calendar_estimate


class BackendPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = json.loads((ROOT/'docs/engineering/backend-plan.json').read_text())
        cls.cards = (ROOT/'docs/roles/BACKEND_AI_PLAYBOOK.md').read_text()
        cls.ops = set(re.findall(r'^\s+operationId: (\w+)', (ROOT/'docs/api/openapi.yaml').read_text(), re.M))
        cls.features = set(re.findall(r'^\| ((?:FR|NFR)-[A-Z0-9]+-\d+) \|', (ROOT/'docs/product/FUNCTIONAL_INVENTORY.md').read_text(), re.M))
        cls.canvas = json.loads((ROOT/'docs/BACKEND_ROADMAP.canvas').read_text())
        # The specimen card's header status, read rather than assumed. These tests mutate a status
        # and expect the mismatch to be reported; hard-coding the card's current value made every
        # one of them a silent no-op the day BA-030 was raised to integration-ready - the mutation
        # set the status to what the card already declared, so nothing was compared.
        cls.specimen = 'BA-030'
        card = cls.cards[cls.cards.index('### BA-030'):]
        cls.specimen_card = card[:card.index('### BA-031')]
        header = next(line for line in cls.specimen_card.splitlines()
                      if line.startswith('**') and ' — ' in line)
        cls.specimen_header = header
        cls.specimen_status = re.findall(r'`([a-z][a-z-]*)`', header)[0]
        # Any status the card does not declare. Never equal to the above, whatever it becomes.
        cls.other_status = next(s for s in ('planned', 'in-progress', 'integration-ready', 'verified')
                                if s != cls.specimen_status)

    def check_mutation(self, mutate, expected, cards=None):
        plan = copy.deepcopy(self.plan)
        mutate(plan)
        errors = []
        validate_plan(plan, self.ops, self.features, self.cards if cards is None else cards, ROOT, errors)
        self.assertTrue(any(expected in e for e in errors), errors)

    def test_current_repository(self):
        errors = []
        validate(ROOT, errors)
        self.assertEqual([], errors)

    def test_an_external_owner_marker_needs_a_reason_and_a_tracking_issue(self):
        """The one place a card can be promoted while a clause is unproven here, so it is shaped
        like the oasdiff exceptions: a role, a reason and a tracking issue, or it is not an
        exception - it is a hole. A bare {"role": "FE"} would be a way to promote anything."""
        def marked(value):
            def mutate(plan):
                clause = next(c for task in plan['tasks'] for c in task['tests']
                              if c.get('externalOwner'))
                clause['externalOwner'] = value
            return mutate
        for broken, expected in (
                ({'role': 'FE', 'reason': 'x'}, 'needs a issue'),
                ({'role': 'FE', 'issue': '#233'}, 'needs a reason'),
                ({'role': 'BE', 'reason': 'x', 'issue': '#233'}, 'needs a known role'),
                ({'role': 'FE', 'reason': 'x', 'issue': 'later'}, 'must be #<number>'),
                ('FE', 'must be an object')):
            with self.subTest(broken=broken):
                self.check_mutation(marked(broken), expected)

    def test_rec_coverage_must_cite_ids_apps_ai_actually_implements(self):
        """The marker exempts a clause from needing a JUnit name, so a citation nobody can follow
        would be a way to promote an unproven clause. requiredTestIds is the wish list; only
        implementedTestIds has a test behind it."""
        def mutate(plan):
            clause = next(c for task in plan['tasks'] for c in task['tests'] if c.get('recCoverage'))
            clause['recCoverage']['ids'] = ['REC-DATA-03']
        self.check_mutation(mutate, 'which apps/ai does not implement')

    def test_rec_coverage_rejects_an_id_that_is_not_shaped_like_one(self):
        def mutate(plan):
            clause = next(c for task in plan['tasks'] for c in task['tests'] if c.get('recCoverage'))
            clause['recCoverage']['ids'] = ['BA-051-T1']
        self.check_mutation(mutate, 'has an invalid REC ID')

    def test_rec_coverage_needs_ids_and_a_reason(self):
        for value, expected in (({'ids': [], 'reason': 'x'}, 'needs a non-empty ids list'),
                                ({'ids': ['REC-LLM-01'], 'reason': '  '}, 'needs a reason'),
                                ('REC-LLM-01', 'must be an object')):
            def mutate(plan, value=value):
                clause = next(c for task in plan['tasks'] for c in task['tests']
                              if c.get('recCoverage'))
                clause['recCoverage'] = value
            self.check_mutation(mutate, expected)

    def test_the_card_row_must_name_the_cited_rec_ids(self):
        """Without this the citation lives only in JSON and the human card still reads as if a
        JUnit test covered the clause - the distance between what a card says and what runs."""
        clause = next(c for task in self.plan['tasks'] for c in task['tests']
                      if c.get('recCoverage'))
        rec = clause['recCoverage']['ids'][0]
        row = next(line for line in self.cards.splitlines()
                   if line.startswith(f"- `{clause['id']}`"))
        self.assertIn(rec, row, 'the specimen row must name it, or this test proves nothing')
        cards = self.cards.replace(row, row.replace(rec, 'REC-GONE-99'))
        errors = []
        validate_plan(copy.deepcopy(self.plan), self.ops, self.features, cards, ROOT, errors)
        self.assertTrue(any(f'the card must name {rec}' in e for e in errors), errors)

    def test_dated_evidence_link_is_not_a_calendar_estimate(self):
        self.assertFalse(has_calendar_estimate('[PM 검토](../project/PM_REVIEW_2026-09-06.md)'))

    def test_visible_calendar_estimates_are_still_rejected(self):
        for text in ('마감 2026-09-06', '[2026-09-06 완료](review.md)', '착수 09/06', '작업 (2.5d)'):
            with self.subTest(text=text):
                self.assertTrue(has_calendar_estimate(text))

    def test_every_manifest_field_is_compared_against_the_line_that_declares_it(self):
        """No field may be satisfied by prose elsewhere in the card.

        The card/manifest loop used to ask only whether each value appeared SOMEWHERE in the card.
        Two fields were already living on that hole when it was found: BA-030's prose named
        `integration-ready` and `FCR-020`, so the manifest could have claimed either and passed.
        Fixing one field and leaving the rest is how the same defect comes back under a different
        name, so every field the loop covered is pinned here.
        """
        cases = (
            ('designRequests', lambda t: t.update({'designRequests': ['FCR-020']}),
             'differs for FCR'),
            ('priority', lambda t: t.update({'priority': 'P1'}), 'card header declares'),
            ('figmaNodes', lambda t: t.update({'figmaNodes': ['999:999']}), 'differs for - Figma:'),
            ('title', lambda t: t.update({'title': '다른 제목'}), 'does not declare the manifest title'),
            ('status', lambda t: t.update({'status': self.other_status}), 'card header declares'),
            ('testIds', lambda t: t['tests'].append({'id': 'BA-030-T4', 'assertion': 'x'}),
             'test IDs'),
        )
        for field, mutate, expected in cases:
            with self.subTest(field=field):
                self.check_mutation(
                    lambda p, mutate=mutate: mutate(
                        next(t for t in p['tasks'] if t['id'] == 'BA-030')),
                    expected)

    def test_the_card_really_does_mention_those_values_in_prose(self):
        """Guards the test above: if BA-030's prose stopped naming them it would pass vacuously."""
        card = self.cards[self.cards.index('### BA-030'):]
        card = card[:card.index('### BA-031')]
        self.assertIn('FCR-020', card)
        # Not the status: the card declares its own status in the header now, so asserting the
        # card 'mentions' it would pass on the header and guard nothing.
        # ...while the rows that actually declare them say otherwise.
        figma_row = next(line for line in card.splitlines() if line.startswith('- Figma:'))
        self.assertIn('FCR: 해당 없음', figma_row)

    def test_status_must_match_the_card_header_not_prose(self):
        """A card that merely MENTIONS a status must not satisfy the status check.

        This is how the check failed silently: BA-030's card gained the sentence
        "integration-ready로 올리지 않는다", and the old presence-anywhere comparison then
        accepted integration-ready in the manifest for that card indefinitely. Prose about a
        status is the most natural thing to write in a card, so the hole opens by accident.

        The prose is written here rather than borrowed from whatever the card happens to say
        today. Borrowing it is what made this test stop testing: the sentence it relied on was
        rewritten when the card was raised, and the mutation quietly became a no-op.
        """
        prose_card = self.cards.replace(
            self.specimen_header,
            self.specimen_header + f'\n\n아직 `{self.other_status}`로 올리지 않는다.', 1)
        self.assertIn(self.other_status, prose_card,
                      'the card really does mention the status in prose')
        self.check_mutation(
            lambda p: next(t for t in p['tasks'] if t['id'] == self.specimen).update(
                {'status': self.other_status}),
            'card header declares', cards=prose_card)

    def test_status_drift_in_either_direction_is_caught(self):
        # The manifest moving without the card...
        self.check_mutation(
            lambda p: next(t for t in p['tasks'] if t['id'] == self.specimen).update(
                {'status': self.other_status}),
            'card header declares')
        # ...and the card moving without the manifest.
        moved = self.cards.replace(
            self.specimen_header,
            self.specimen_header.replace(f'`{self.specimen_status}`', f'`{self.other_status}`'), 1)
        self.assertNotEqual(moved, self.cards, 'the specimen header was actually rewritten')
        errors = []
        validate_plan(copy.deepcopy(self.plan), self.ops, self.features, moved, ROOT, errors)
        self.assertTrue(any('card header declares' in e for e in errors), errors)

    def test_the_fcr_registry_status_column_is_actually_checked(self):
        """PM-021's precondition: the column had no guard at all.

        What was checked was a PROSE bullet near the top of the document ("- 상태: Open — ..."),
        which survives every row's status being emptied. The registry's own column - the thing the
        FCR process runs on - was unguarded.
        """
        from validate_backend_plan import check_fcr_registry
        header = '| ID | Pri | 현재 Figma 증거 | 목표 상태 | 소유/검토 | 상태 |'
        rule = '| --- | --- | --- | --- | --- | --- |'
        row = '| FCR-001 | P0 | evidence | goal | FE / BE | Open |'

        problems: list[str] = []
        check_fcr_registry('\n'.join([header, rule, row]), problems)
        self.assertEqual([], problems)

        # A row that lost its status.
        problems = []
        check_fcr_registry('\n'.join([header, rule, '| FCR-001 | P0 | e | g | FE |  |']), problems)
        self.assertTrue(any('empty status cell' in p for p in problems), problems)

        # A renamed or removed header: the column can no longer be located, so silence would mean
        # the check quietly stopped checking.
        problems = []
        check_fcr_registry('\n'.join(['| ID | Pri | x | y | z | 진행 |', rule, row]), problems)
        self.assertTrue(any('header is missing or changed' in p for p in problems), problems)

        # A table with no rows is not a passing registry either.
        problems = []
        check_fcr_registry('\n'.join([header, rule]), problems)
        self.assertTrue(any('no rows' in p for p in problems), problems)

    def test_the_real_registry_has_rows_so_the_check_is_not_vacuous(self):
        # Guards the case above: against an empty registry every assertion there would pass while
        # the repository's own table went unchecked.
        text = (ROOT / 'docs/design/FIGMA_CHANGE_REQUESTS.md').read_text(encoding='utf-8')
        self.assertIn('| ID | Pri | 현재 Figma 증거 | 목표 상태 | 소유/검토 | 상태 |', text)
        self.assertGreaterEqual(len(re.findall(r'^\| (FCR-\d{3}) \|', text, re.M)), 30)

    def test_missing_operation(self):
        """BA-000-T1 담당 task가 없는 operation은 계획 검증에서 거부된다"""
        self.check_mutation(lambda p: next(t for t in p['tasks'] if 'getPlace' in t['operations'])['operations'].clear(), 'operation coverage mismatch')

    def test_duplicate_operation_owner(self):
        self.check_mutation(lambda p: p['tasks'][0]['operations'].append('getPlace'), 'duplicate operation owners')

    def test_unknown_operation(self):
        self.check_mutation(lambda p: p['tasks'][0]['operations'].append('inventedEndpoint'), 'unknown=')

    def test_missing_feature(self):
        """BA-000-T1 담당 task가 없는 기능 ID는 계획 검증에서 거부된다"""
        def mutate(p):
            for t in p['tasks']:
                t['featureIds'] = [f for f in t['featureIds'] if f != 'FR-LIV-07']
        self.check_mutation(mutate, 'feature coverage mismatch')

    def test_unknown_feature(self):
        self.check_mutation(lambda p: p['tasks'][0]['featureIds'].append('FR-FAKE-01'), 'unknown=')

    def test_duplicate_task(self):
        self.check_mutation(lambda p: p['tasks'].append(copy.deepcopy(p['tasks'][0])), 'duplicate task IDs')

    def test_dependency_cycle(self):
        """BA-000-T2 순서 DAG의 cycle은 계획 검증에서 거부된다"""
        self.check_mutation(lambda p: p['tasks'][0]['dependsOn'].append('BA-001'), 'dependency cycle')

    def test_same_phase_cycle(self):
        """BA-000-T2 같은 phase 안의 cycle도 거부된다 — phase 경계가 순환을 가리지 않는다"""
        self.check_mutation(lambda p: p['tasks'][1]['dependsOn'].append('BA-002'), 'dependency cycle')

    def test_missing_dependency(self):
        self.check_mutation(lambda p: p['tasks'][0]['dependsOn'].append('BA-999'), 'missing dependency')

    def test_later_phase_dependency(self):
        self.check_mutation(lambda p: p['tasks'][0]['dependsOn'].append('BA-092'), 'later phase')

    def test_optional_ml_must_not_block_live(self):
        def mutate(p):
            next(t for t in p['tasks'] if t['id']=='BA-090')['dependsOn'].append('BA-087')
        self.check_mutation(mutate, 'optional expansion must not block P0')

    def test_live_phase_moved(self):
        """BA-000-T2 Live 작업을 앞 phase로 옮기면 거부된다 — B10이 마지막 기능 단계다"""
        def mutate(p):
            next(t for t in p['tasks'] if t['id']=='BA-091')['phase']='B03'
        self.check_mutation(mutate, 'Live work must be in final')

    def test_phase_after_live(self):
        """BA-000-T2 B10 뒤에 기능 phase를 더하면 거부된다 — 반대 방향의 같은 규칙"""
        self.check_mutation(lambda p: p['phases'].append({'id':'B11'}), 'last feature phase')

    def test_wrong_branch(self):
        self.check_mutation(lambda p: p.update(branch='main'), 'branch=backend')

    def test_extra_required_check(self):
        self.check_mutation(lambda p: p['requiredChecks'].append('fake-green'), 'two stable')

    def test_test_list_cannot_be_empty(self):
        self.check_mutation(lambda p: p['tasks'][0].update(tests=[]), 'non-empty acceptance')

    def test_duplicate_test_id(self):
        def mutate(p):
            p['tasks'][0]['tests'].append(copy.deepcopy(p['tasks'][0]['tests'][0]))
        self.check_mutation(mutate, 'duplicate backend acceptance')

    # --- The clause text in both files (#GOV-001). Only the ID used to be compared, and BA-020-T1~T3
    # had drifted apart. Each case asserts its specimen is shaped the way it claims, or it would pass
    # on a card that no longer has the row.

    def clause_errors(self, plan=None, cards=None):
        errors = []
        validate_plan(copy.deepcopy(self.plan) if plan is None else plan, self.ops, self.features,
                      self.cards if cards is None else cards, ROOT, errors)
        return [e for e in errors if 'card clause for' in e or "필수 검증 list" in e]

    def required_row(self, ident):
        rows = [line for line in self.cards.splitlines() if line.startswith(f'- `{ident}`: ')]
        self.assertTrue(rows, f'{ident} has no row, so this case proves nothing')
        return rows[0]

    def test_a_one_character_change_on_either_side_is_caught(self):
        ident = 'BA-030-T1'
        row = self.required_row(ident)
        plan = copy.deepcopy(self.plan)
        clause = next(c for t in plan['tasks'] for c in t['tests'] if c['id'] == ident)
        self.assertEqual(row, f"- `{ident}`: {clause['assertion']}", 'the specimen must be an exact row')
        clause['assertion'] = clause['assertion'][:-1] + '.'
        self.assertTrue(self.clause_errors(plan=plan), 'a changed manifest clause must be reported')
        self.assertTrue(self.clause_errors(cards=self.cards.replace(row, row[:-1] + '.')),
                        'a changed card row must be reported')
        # Only " — " opens the evidence part. Text run on without it is a different clause.
        self.assertTrue(self.clause_errors(cards=self.cards.replace(row, row + '고 기록한다')),
                        'a clause extended without the dash must be reported')

    def test_markup_is_part_of_the_clause(self):
        # What BA-020-T1~T3 had: the card wrapped class names in backticks and the manifest did not.
        # The rule compares the text as written rather than normalising markup away - simpler, and
        # the two files then have one spelling instead of two that a reader must reconcile.
        ident = 'BA-020-T2'
        row = self.required_row(ident)
        self.assertIn('SourceRegistryIT', row, 'the specimen must name the class, or this proves nothing')
        marked = row.replace('SourceRegistryIT', '`SourceRegistryIT`', 1)
        self.assertTrue(self.clause_errors(cards=self.cards.replace(row, marked)))

    def test_evidence_after_the_dash_is_not_the_clause(self):
        ident = 'BA-000-T1'
        row = self.required_row(ident)
        clause = next(c['assertion'] for t in self.plan['tasks'] for c in t['tests'] if c['id'] == ident)
        self.assertTrue(row.startswith(f"- `{ident}`: {clause} — "), 'the specimen must carry evidence')
        self.assertEqual([], self.clause_errors(cards=self.cards.replace(row, row + ' 덧붙인 증거')))
        broken = row.replace(clause, clause[:-1] + '!', 1)
        self.assertTrue(self.clause_errors(cards=self.cards.replace(row, broken)),
                        'the clause before the dash is still compared')

    def test_an_evidence_row_outside_the_required_list_is_ignored(self):
        # BA-004-T2 has a second "- `ID`:" row under the card's evidence heading that lists testcase
        # names. Reading it as the clause would fail a card that is correct.
        ident = 'BA-004-T2'
        rows = [line for line in self.cards.splitlines() if line.startswith(f'- `{ident}`: ')]
        self.assertEqual(2, len(rows), 'the specimen needs its evidence row, or this proves nothing')
        evidence = rows[1]
        self.assertEqual([], self.clause_errors(cards=self.cards.replace(evidence, evidence + ' 아무 말')))

    def test_a_row_moved_out_of_the_required_list_is_caught(self):
        ident = 'BA-030-T1'
        row = self.required_row(ident)
        cards = self.cards.replace(row + '\n', '', 1).replace('### BA-031', row + '\n\n### BA-031', 1)
        self.assertTrue(any(f'{ident} has no row' in e for e in self.clause_errors(cards=cards)))

    def test_stale_human_card(self):
        # The card losing a test ID is now reported by the exact per-card comparison rather than
        # by "this value appears nowhere", which could be satisfied by prose.
        self.check_mutation(lambda p: None, 'task card test IDs', self.cards.replace('BA-051-T2','REMOVED'))

    def test_missing_card(self):
        self.check_mutation(lambda p: None, 'task card IDs differ', self.cards.replace('### BA-051','### Removed'))

    def test_verified_without_evidence(self):
        self.check_mutation(lambda p: p['tasks'][0].update(status='verified'), 'verified requires report')

    def test_verified_missing_report_file(self):
        def mutate(p):
            t=p['tasks'][0]
            t.update(status='verified', evidence={'report':'docs/missing-test-report.json',
                'contractSha':'example', 'secondPass':'대조함',
                'provenBy':{x['id']: [x['id']+' case'] for x in t['tests']}})
        self.check_mutation(mutate, 'missing vault target')

    def test_verified_report_must_be_a_run_of_this_repository(self):
        """resolve_link waves http(s) through, so without this any URL would read as evidence."""
        def mutate(p):
            t=p['tasks'][0]
            t.update(status='verified', evidence={'report':'https://example.com/green',
                'contractSha':'example', 'secondPass':'대조함',
                'provenBy':{x['id']: [x['id']+' case'] for x in t['tests']}})
        self.check_mutation(mutate, 'must be a GitHub Actions run URL')

    def test_verified_accepts_a_real_actions_run_url(self):
        """The negative above proves nothing if every URL is refused."""
        plan = copy.deepcopy(self.plan)
        task = plan['tasks'][0]
        task.update(status='verified',
                    evidence={'report': 'https://github.com/yutakdv/Nullnull/actions/runs/123',
                              'contractSha': 'example', 'secondPass': '대조함',
                              'provenBy': {x['id']: [x['id'] + ' case'] for x in task['tests']}})
        errors = []
        validate_plan(plan, self.ops, self.features, self.cards, ROOT, errors)
        self.assertEqual([], [e for e in errors if 'evidence.report' in e], errors)

    def test_verified_provenby_must_cover_every_acceptance_id(self):
        """An ID with nothing to point at is what stops the promotion - see BA-002-T3's second clause."""
        def mutate(p):
            t=p['tasks'][0]
            proven={x['id']: [x['id']+' case'] for x in t['tests']}
            proven.pop(sorted(proven)[-1])
            t.update(status='verified', evidence={'report':'docs/engineering/backend-plan.json',
                'contractSha':'example', 'secondPass':'대조함', 'provenBy':proven})
        self.check_mutation(mutate, 'provenBy must name a testcase for exactly')

    def test_verified_provenby_rejects_a_testcase_that_is_not_the_claimed_id(self):
        """Without this a card could point at any green testcase in the repository."""
        def mutate(p):
            t=p['tasks'][0]
            proven={x['id']: [x['id']+' case'] for x in t['tests']}
            proven[sorted(proven)[0]]=['a testcase naming no acceptance id']
            t.update(status='verified', evidence={'report':'docs/engineering/backend-plan.json',
                'contractSha':'example', 'secondPass':'대조함', 'provenBy':proven})
        self.check_mutation(mutate, 'must be a non-empty list of testcase names containing')

    def test_blocked_without_reason(self):
        self.check_mutation(lambda p: p['tasks'][0].update(status='blocked'), 'blocked/deferred requires reason')

    def test_malformed_dependency_type(self):
        self.check_mutation(lambda p: p['tasks'][0].update(dependsOn='BA-001'), 'must be a string list')

    def test_canvas_missing_file(self):
        canvas=copy.deepcopy(self.canvas)
        canvas['nodes'][1]['file']='docs/not-found.md'
        errors=[]; validate_canvas(ROOT,canvas,errors)
        self.assertTrue(any('missing vault target' in e for e in errors),errors)

    def test_canvas_missing_heading(self):
        canvas=copy.deepcopy(self.canvas)
        canvas['nodes'][1]['subpath']='#not-found'
        errors=[]; validate_canvas(ROOT,canvas,errors)
        self.assertTrue(any('missing heading' in e for e in errors),errors)

    def test_canvas_dangling_edge(self):
        canvas=copy.deepcopy(self.canvas)
        canvas['edges'][0]['toNode']='not-a-node'
        errors=[]; validate_canvas(ROOT,canvas,errors)
        self.assertTrue(any('missing edge endpoint' in e for e in errors),errors)

    def test_canvas_duplicate_node(self):
        canvas=copy.deepcopy(self.canvas)
        canvas['nodes'].append(copy.deepcopy(canvas['nodes'][0]))
        errors=[]; validate_canvas(ROOT,canvas,errors)
        self.assertTrue(any('duplicate or invalid node' in e for e in errors),errors)

    def test_link_cannot_escape_vault(self):
        errors=[];resolve_link(ROOT,ROOT,'../outside.md',errors,'test')
        self.assertTrue(any('escapes vault' in e for e in errors))

    def test_unicode_heading_and_code_fence(self):
        text='# 한글 · 제목\n\n```md\n## 가짜\n```\n\n## 한글 · 제목\n'
        self.assertIn('한글--제목',headings(text))
        self.assertIn('한글--제목-1',headings(text))
        self.assertNotIn('가짜',headings(text))

    def test_markdown_anchor_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'note.md').write_text('# 실제\n')
            errors=[];resolve_link(root,root,'note.md#없는절',errors,'test')
            self.assertTrue(any('missing heading' in e for e in errors))


if __name__ == '__main__':
    unittest.main()
