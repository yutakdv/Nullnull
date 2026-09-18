import { readFileSync } from 'node:fs';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { ProposalCard } from '../ProposalCard.js';

type OptimizationProposal = components['schemas']['OptimizationProposal'];

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
  comparisonUnavailable: 'COMPARISON_UNAVAILABLE',
  changesTitle: 'CHANGES_TITLE',
  changeCount: 'CHANGES_{count}',
  move: 'MOVE_LABEL',
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

    // The fixture's single change is a MOVE.
    expect(screen.getByText('MOVE_LABEL')).toBeInTheDocument();
  });
});

describe('FE-503 invariant 8: a crowd figure never appears without its credit', () => {
  it('draws the delta and the attribution together', () => {
    const { container } = render(<ProposalCard labels={LABELS} proposal={proposal()} />);

    // -47 in the fixture, rendered with its sign.
    expect(screen.getByText('-47')).toBeInTheDocument();
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
    expect(screen.queryByText('-47')).toBeNull();
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
