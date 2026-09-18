// Derivations behind the applied/undo panel (S09-3, FE-504).
//
// Two rules live here and they fail in opposite directions:
//
//  - `panelVersions` prints numbers. Getting it wrong shows the traveller a
//    version their trip is not on.
//  - `shouldReadRun` spends a request. Getting it wrong costs a round trip, or
//    a panel that does not appear — but it can NEVER turn an undo on, because
//    the button reads `revertAvailability` and nothing else. The tests say so
//    explicitly, so that a later reader who widens this clock knows what they
//    are and are not touching.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { panelVersions, shouldReadRun, formatInstant } from '../applied-revert.js';

type OptimizationRun = components['schemas']['OptimizationRun'];
type OptimizationHistoryItem =
  components['schemas']['OptimizationHistoryPage']['items'][number];

// The applied fixture, which carries a real APPLY decision.
const APPLIED = JSON.parse(
  readFileSync(
    '../../packages/contracts/fixtures/optimizations/run-applied.json',
    'utf8',
  ),
) as OptimizationRun;

function run(overrides: Partial<OptimizationRun> = {}): OptimizationRun {
  return { ...(JSON.parse(JSON.stringify(APPLIED)) as OptimizationRun), ...overrides };
}

function historyItem(
  overrides: Partial<OptimizationHistoryItem> = {},
): OptimizationHistoryItem {
  return {
    runId: '018f4a20-9f11-7c08-b3d7-2e5a41c9b101',
    tripId: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    tripTitle: '서울 가을 여행',
    scope: 'ITEM',
    status: 'APPLIED',
    decision: 'APPLY',
    runLink:
      '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4a20-9f11-7c08-b3d7-2e5a41c9b101',
    queuedAt: '2026-10-02T01:00:00Z',
    decidedAt: '2026-10-02T01:13:40Z',
    ...overrides,
  };
}

describe('panelVersions prints the pair the state actually moved between', () => {
  it('reads the apply as input → resulting while it stands', () => {
    // run-applied.json: inputTripVersion 2, the APPLY's resultingTripVersion 3.
    const versions = panelVersions(run().decisions, run().inputTripVersion);

    expect(versions).toEqual({ from: 2, to: 3, restored: 2 });
  });

  it('reads a reverted run as apply-result → revert-result, not as a rewind', () => {
    // The contract writes the changes back as a NEW revision — "history is
    // never overwritten" — so the trip is on 4, not back on 2. A panel that
    // printed `2 → 3` here would contradict its own third clause, which tells
    // the traveller the itinerary now matches v2.
    const applied = run();
    const apply = applied.decisions[0];
    if (!apply) throw new Error('run-applied.json has no decision');
    const reverted = run({
      decisions: [
        apply,
        {
          id: '018f4a60-4d31-7e22-8c03-5f6a7b8c9d02',
          runId: applied.id,
          proposalId: apply.proposalId,
          decision: 'REVERT',
          resultingTripVersion: 4,
          beforeRevisionId: '018f4a40-6c21-7b18-9e44-1d2c3b4a5f03',
          afterRevisionId: '018f4a40-6c21-7b18-9e44-1d2c3b4a5f04',
          revertedDecisionId: apply.id,
          decidedAt: '2026-10-02T02:00:00Z',
        },
      ],
    });

    expect(panelVersions(reverted.decisions, reverted.inputTripVersion)).toEqual({
      from: 3,
      to: 4,
      restored: 2,
    });
  });

  it('has no panel for a run that was kept', () => {
    // KEEP records intent and moves nothing, so there is no revision pair to
    // describe and nothing to undo.
    const kept = run({
      decisions: [
        {
          id: '018f4a60-4d31-7e22-8c03-5f6a7b8c9d03',
          runId: APPLIED.id,
          proposalId: APPLIED.decisions[0]?.proposalId ?? '',
          decision: 'KEEP',
          decidedAt: '2026-10-02T01:13:40Z',
        },
      ],
    });

    expect(panelVersions(kept.decisions, kept.inputTripVersion)).toBeNull();
  });

  it('has no panel for a run nobody has decided', () => {
    expect(panelVersions([], run().inputTripVersion)).toBeNull();
  });
});

describe('shouldReadRun spends a request, and only that', () => {
  // The window's own boundary. `decidedAt` is 01:13:40 on 10-02.
  const decidedAt = Date.parse('2026-10-02T01:13:40Z');
  const DAY = 24 * 60 * 60 * 1000;

  it('reads the run just inside the 24-hour window', () => {
    expect(shouldReadRun(historyItem(), decidedAt + DAY - 1000)).toBe(true);
  });

  it('stops reading once the window has closed', () => {
    // A request past this point comes back EXPIRED or NOT_APPLICABLE, so it
    // buys nothing. The panel is not suppressed by this — the server's own
    // value is what the panel renders, and it would say EXPIRED too.
    expect(shouldReadRun(historyItem(), decidedAt + DAY)).toBe(false);
  });

  it('does not read a run that was kept', () => {
    expect(shouldReadRun(historyItem({ decision: 'KEEP' }), decidedAt + 1000)).toBe(
      false,
    );
  });

  it('does not read a run whose undo was already spent', () => {
    expect(shouldReadRun(historyItem({ decision: 'REVERT' }), decidedAt + 1000)).toBe(
      false,
    );
  });

  it('does not read an undecided run', () => {
    expect(
      shouldReadRun(
        historyItem({ decision: null, decidedAt: null, status: 'RUNNING' }),
        decidedAt + 1000,
      ),
    ).toBe(false);
  });

  it('does not read when there is no history at all', () => {
    expect(shouldReadRun(undefined, decidedAt)).toBe(false);
  });

  it('refuses an unparseable timestamp rather than treating it as now', () => {
    // A NaN comparison is false either way, but asserting it keeps a later
    // rewrite from turning "cannot tell" into "inside the window".
    expect(shouldReadRun(historyItem({ decidedAt: 'not-a-date' }), decidedAt)).toBe(
      false,
    );
  });
});

describe('formatInstant', () => {
  it('carries the time, which the panel needs for a deadline', () => {
    // `ProfileScreen`'s runDate gives the day alone; a deadline of "10/8" does
    // not tell a traveller whether they have an hour or a day.
    const formatted = formatInstant('2026-10-08T14:35:00Z', 'ko-KR');

    expect(formatted).not.toBeNull();
    expect(formatted).toMatch(/\d/);
    expect(formatted).toContain('35');
  });

  it('returns null rather than a guess for an unparseable value', () => {
    expect(formatInstant('not-a-date', 'ko-KR')).toBeNull();
    expect(formatInstant(null, 'ko-KR')).toBeNull();
  });
});
