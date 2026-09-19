import { readFileSync } from 'node:fs';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { ProposalCard } from '../ProposalCard.js';

type OptimizationProposal = components['schemas']['OptimizationProposal'];
type TripItemState = components['schemas']['TripItemState'];

const RUN = JSON.parse(
  readFileSync('../../packages/contracts/fixtures/optimizations/run-ready.json', 'utf8'),
) as { proposals: OptimizationProposal[] };

const FIXTURE = RUN.proposals[0];
if (!FIXTURE) throw new Error('run-ready.json has no proposal to render');

function proposal(): OptimizationProposal {
  return JSON.parse(JSON.stringify(FIXTURE)) as OptimizationProposal;
}

// The screen passes localized copy; these stand in for it. Deliberately
// distinctive so an assertion cannot pass on some other element's text.
const LABELS = {
  crowdLabel: 'CROWD_LABEL',
  // The direction as a word, which is all a screen reader gets now that the
  // figure is unsigned and the arrow is aria-hidden (#279 하2).
  crowdDown: 'CROWD_DOWN',
  crowdUp: 'CROWD_UP',
  comparisonUnavailable: 'COMPARISON_UNAVAILABLE',
  changesTitle: 'CHANGES_TITLE',
  changeCount: 'CHANGES_{count}',
  // Three, not one: the chip names the field that moved (#279 하3). Distinct
  // strings so a test cannot pass by finding the wrong one.
  moveDate: 'MOVE_DATE_LABEL',
  moveTime: 'MOVE_TIME_LABEL',
  moveOrder: 'MOVE_ORDER_LABEL',
  add: 'ADD_LABEL',
  remove: 'REMOVE_LABEL',
  constraintsOk: 'CONSTRAINTS_OK',
  constraintsBroken: 'CONSTRAINTS_BROKEN',
  licenseTerms: 'LICENSE_TERMS',
};

/**
 * The attribution element, or null when the card rendered none.
 *
 * Found by DataAttribution's own class rather than by its text, because the
 * credit string also appears inside the server's `summary` sentence. Asserting
 * on text alone cannot tell "the card credited the source" from "the optimizer
 * mentioned it in prose", and invariant 8 is about the former.
 */
function attributionIn(container: HTMLElement): Element | null {
  return container.querySelector('[class*="attribution"]');
}

describe('FE-503 the proposal card previews a change set', () => {
  it('shows the optimizer’s own summary rather than one of ours', () => {
    render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    expect(screen.getByText(FIXTURE.summary)).toBeInTheDocument();
  });

  it('counts the changes it drew', () => {
    render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    expect(
      screen.getByText(`CHANGES_${String(FIXTURE.changes.length)}`),
    ).toBeInTheDocument();
  });

  it('names the operation in words, not colour alone', () => {
    render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    // The fixture's single change is a MOVE, and what it moves is the DAY
    // (10-04 → 10-07 at the same 13:00), so the chip is the date one.
    expect(screen.getByText('MOVE_DATE_LABEL')).toBeInTheDocument();
  });

  // #279 하2: "↓ -55" said the direction twice — once as a glyph, once as a
  // sign — and a rehearsal read the two as colliding.
  //
  // The sign is gone, which is only safe because a WORD replaced it. The arrow
  // is `aria-hidden`, so before this the minus was the whole of the direction
  // for a screen reader; dropping it alone would have taken direction away from
  // exactly the people who cannot see the arrow. Both halves are asserted here,
  // because a card that did one and not the other passes either test alone.
  describe('#279 하2 the figure is a magnitude and the direction is a word', () => {
    function withDelta(delta: number) {
      const next = proposal();
      next.metrics.crowdDelta = delta;
      return next;
    }

    it('prints no sign on a fall, and says it fell', () => {
      render(<ProposalCard labels={LABELS} proposal={withDelta(-55)} />);

      expect(screen.getByText('55')).toBeInTheDocument();
      expect(screen.queryByText('-55')).toBeNull();
      // The half a sign-only fix would miss.
      expect(screen.getByText('CROWD_DOWN')).toBeInTheDocument();
      expect(screen.queryByText('CROWD_UP')).toBeNull();
    });

    it('prints no sign on a rise either, and says it rose', () => {
      // The `+` was the other half of the old spelling. A fix that only
      // stripped the minus would leave "↑ +12" saying it twice.
      render(<ProposalCard labels={LABELS} proposal={withDelta(12)} />);

      expect(screen.getByText('12')).toBeInTheDocument();
      expect(screen.queryByText('+12')).toBeNull();
      expect(screen.getByText('CROWD_UP')).toBeInTheDocument();
      expect(screen.queryByText('CROWD_DOWN')).toBeNull();
    });

    it('names no direction when nothing moved', () => {
      // Zero has no direction to announce, and the arrow is empty for it too.
      // Announcing "0 감소" would state a fall that did not happen.
      render(<ProposalCard labels={LABELS} proposal={withDelta(0)} />);

      expect(screen.getByText('0')).toBeInTheDocument();
      expect(screen.queryByText('CROWD_DOWN')).toBeNull();
      expect(screen.queryByText('CROWD_UP')).toBeNull();
    });

    it('keeps the direction word out of sight but in the accessibility tree', () => {
      // Visually hidden, not `aria-hidden` and not display:none — the point is
      // that it IS announced. `toBeVisible` would pass for a word that was
      // simply printed next to the figure, which is not what was asked for, so
      // this asserts the class the stylesheet hides rather than visibility
      // (jsdom does not apply CSS modules' rules).
      render(<ProposalCard labels={LABELS} proposal={withDelta(-55)} />);

      const word = screen.getByText('CROWD_DOWN');
      expect(word.className).toMatch(/srOnly/);
      expect(word.getAttribute('aria-hidden')).toBeNull();
    });
  });

  // #279 하2: the chip named the wrong field.
  //
  // A rehearsal moved a stop to another day and the card said "시간 변경". All
  // three of MOVE, REORDER and REPLACE become one `move` ROW because they share
  // a shape, and the chip was reading that shape as a meaning.
  //
  // Each case asserts the other two chips are ABSENT as well as the right one
  // present: asserting only presence passes for a card that draws all three.
  describe('#279 하3 the move chip names the field that actually moved', () => {
    function withMove(before: Partial<TripItemState>, after: Partial<TripItemState>) {
      const next = proposal();
      const change = next.changes[0];
      if (!change?.before || !change.after) throw new Error('fixture lost its move');
      Object.assign(change.before, before);
      Object.assign(change.after, after);
      return next;
    }

    it('says date when the day changes', () => {
      render(
        <ProposalCard
          labels={LABELS}
          proposal={withMove(
            { date: '2026-10-04', startTime: '13:00:00' },
            { date: '2026-10-07', startTime: '13:00:00' },
          )}
        />,
      );

      expect(screen.getByText('MOVE_DATE_LABEL')).toBeInTheDocument();
      expect(screen.queryByText('MOVE_TIME_LABEL')).not.toBeInTheDocument();
      expect(screen.queryByText('MOVE_ORDER_LABEL')).not.toBeInTheDocument();
    });

    it('says time when the day is the same and the clock moves', () => {
      render(
        <ProposalCard
          labels={LABELS}
          proposal={withMove(
            { date: '2026-10-04', startTime: '13:00:00' },
            { date: '2026-10-04', startTime: '09:30:00' },
          )}
        />,
      );

      expect(screen.getByText('MOVE_TIME_LABEL')).toBeInTheDocument();
      expect(screen.queryByText('MOVE_DATE_LABEL')).not.toBeInTheDocument();
    });

    it('says date when both the day and the clock move', () => {
      // The day is the bigger fact and the row prints both sides underneath,
      // so the chip summarises rather than trying to say everything.
      render(
        <ProposalCard
          labels={LABELS}
          proposal={withMove(
            { date: '2026-10-04', startTime: '13:00:00' },
            { date: '2026-10-07', startTime: '09:30:00' },
          )}
        />,
      );

      expect(screen.getByText('MOVE_DATE_LABEL')).toBeInTheDocument();
      expect(screen.queryByText('MOVE_TIME_LABEL')).not.toBeInTheDocument();
    });

    it('says order when neither the day nor the clock moves', () => {
      // A REORDER within one day: same date, no time on either side.
      render(
        <ProposalCard
          labels={LABELS}
          proposal={withMove(
            { date: '2026-10-04', startTime: null },
            { date: '2026-10-04', startTime: null },
          )}
        />,
      );

      expect(screen.getByText('MOVE_ORDER_LABEL')).toBeInTheDocument();
      expect(screen.queryByText('MOVE_TIME_LABEL')).not.toBeInTheDocument();
    });

    it('treats an absent time and a null time as the same untimed stop', () => {
      // `startTime` is BOTH optional and nullable in the contract (not in
      // `required`, type `["string","null"]`), so one side can arrive absent
      // and the other explicitly null for a stop that never had a time. A raw
      // `!==` calls that a time change and the chip says the clock moved when
      // nothing did.
      //
      // Written after measuring: the previous case set null on both sides, so
      // dropping the `?? null` normalisation in `moveKind` left every test
      // green. This is the case that fails without it.
      const next = proposal();
      const change = next.changes[0];
      if (!change?.before || !change.after) throw new Error('fixture lost its move');
      Object.assign(change.before, { date: '2026-10-04', startTime: null });
      Object.assign(change.after, { date: '2026-10-04' });
      delete (change.after as { startTime?: unknown }).startTime;

      render(<ProposalCard labels={LABELS} proposal={next} />);

      expect(screen.getByText('MOVE_ORDER_LABEL')).toBeInTheDocument();
      expect(screen.queryByText('MOVE_TIME_LABEL')).not.toBeInTheDocument();
    });
  });
});

// FE-503-T1 — "before/after 비교가 provenance 없는 수치를 만들지 않는다".
// All five tests below measure that one clause: the number and its credit
// appear together, or neither appears. Nothing else lives in this block, which
// is what lets the ID sit on the describe (a JUnit name is "<describe> <test>",
// so an ID here lands on every test inside).
describe('FE-503-T1 invariant 8: a crowd figure never appears without its credit', () => {
  it('draws the delta and the attribution together', () => {
    const { container } = render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    // 47 in the fixture, which holds -47: the figure is a magnitude now and
    // the arrow plus `crowdDown` carry the direction (#279 하2).
    expect(screen.getByText('47')).toBeInTheDocument();
    // Scoped to the comparison block, not the whole card: the server's own
    // `summary` sentence ALSO ends with '출처: ⓒ한국관광공사', so a card-wide
    // query matches even when the card renders no attribution element at all.
    // Measured — the first version of this test passed for the wrong reason
    // and its `queryByText(...).toBeNull()` twin failed on the summary.
    expect(attributionIn(container)).not.toBeNull();
  });

  // The mutation guard. Deleting the `blocked` branch in ProposalCard makes the
  // card render a figure for a proposal that may not be compared, and this is
  // what turns red: the number appears where the card promised a sentence.
  it('shows no number at all when the comparison is blocked', () => {
    const input = proposal();
    input.metrics.comparisonEligible = false;
    input.metrics.comparisonReasonCode = 'DIFFERENT_METRIC';

    render(<ProposalCard labels={LABELS} proposal={input} />);

    expect(screen.getByText('COMPARISON_UNAVAILABLE')).toBeInTheDocument();
    // Not "no -47" — NO delta at all. A card that switched to a different
    // number would still be wrong, so the assertion is on the shape of the
    // text rather than on one value.
    expect(screen.queryByText(/^[+-]?\d+$/)).toBeNull();
  });

  it('shows no credit when there is no figure to credit', () => {
    const input = proposal();
    input.metrics.comparisonEligible = false;

    const { container } = render(<ProposalCard labels={LABELS} proposal={input} />);

    // An attribution with no number beside it would credit a source for
    // something the card is not showing.
    expect(attributionIn(container)).toBeNull();
  });

  it('blocks when a provenance record refuses, even though the metrics allowed it', () => {
    const input = proposal();
    const second = input.dataProvenance[1];
    if (!second) throw new Error('fixture no longer has a second provenance record');
    second.comparisonEligible = false;

    render(<ProposalCard labels={LABELS} proposal={input} />);

    expect(screen.getByText('COMPARISON_UNAVAILABLE')).toBeInTheDocument();
    expect(screen.queryByText('47')).toBeNull();
  });

  it('never prints the server’s reason code to the user', () => {
    const input = proposal();
    input.metrics.comparisonEligible = false;
    input.metrics.comparisonReasonCode = 'SAME_SOURCE_SCOPE_SET';

    render(<ProposalCard labels={LABELS} proposal={input} />);

    // The contract leaves this field free-form (no enum, maxLength 100), so a
    // card that echoed it would show a token for any value added later.
    expect(screen.queryByText(/SAME_SOURCE_SCOPE_SET/)).toBeNull();
    expect(screen.getByText('COMPARISON_UNAVAILABLE')).toBeInTheDocument();
  });
});

describe('FE-503 each change row draws only the sides it has', () => {
  it('shows both ends of a move', () => {
    const input = proposal();
    const list = render(<ProposalCard labels={LABELS} proposal={input} />);

    const item = list.container.querySelector('li');
    expect(item).not.toBeNull();
    if (!item) return;
    // 2026-10-04 13:00 → 2026-10-07 13:00 in the fixture.
    expect(within(item).getByText('10/4 13:00')).toBeInTheDocument();
    expect(within(item).getByText('10/7 13:00')).toBeInTheDocument();
  });

  it('gives an added stop one side and no arrow partner', () => {
    const input = proposal();
    input.changes = [
      {
        operation: 'ADD',
        itemId: 'added-1',
        before: null,
        after: { placeId: 'p1', date: '2026-10-05', position: 2, startTime: '09:30:00' },
      },
    ] as OptimizationProposal['changes'];

    render(<ProposalCard labels={LABELS} proposal={input} />);

    expect(screen.getByText('ADD_LABEL')).toBeInTheDocument();
    expect(screen.getByText('10/5 09:30')).toBeInTheDocument();
  });

  it('renders an untimed stop without inventing a time', () => {
    const input = proposal();
    input.changes = [
      {
        operation: 'ADD',
        itemId: 'added-2',
        before: null,
        after: { placeId: 'p2', date: '2026-10-06', position: 1, startTime: null },
      },
    ] as OptimizationProposal['changes'];

    render(<ProposalCard labels={LABELS} proposal={input} />);

    expect(screen.getByText('10/6')).toBeInTheDocument();
  });

  it('skips an operation it does not know rather than dropping the card', () => {
    const input = proposal();
    input.changes = [
      ...input.changes,
      { operation: 'SPLIT', itemId: 'future', before: null, after: null },
    ] as unknown as OptimizationProposal['changes'];

    render(<ProposalCard labels={LABELS} proposal={input} />);

    // The known change still renders, and the count reflects what was drawn —
    // not what arrived. See the unresolved trade-off in preview.ts.
    expect(screen.getByText(FIXTURE.summary)).toBeInTheDocument();
    expect(screen.getByText('CHANGES_1')).toBeInTheDocument();
  });
});

describe('FE-503 validation is stated, not implied', () => {
  it('says the constraints held when every check passed', () => {
    render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    expect(screen.getByText('CONSTRAINTS_OK')).toBeInTheDocument();
  });

  it('says so when a check failed', () => {
    const input = proposal();
    input.validation.allConstraintsPreserved = false;
    const check = input.validation.checks[0];
    if (!check) throw new Error('fixture no longer has a validation check');
    check.passed = false;

    render(<ProposalCard labels={LABELS} proposal={input} />);

    expect(screen.getByText('CONSTRAINTS_BROKEN')).toBeInTheDocument();
    expect(screen.queryByText('CONSTRAINTS_OK')).toBeNull();
  });
});
