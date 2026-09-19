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
import {
  latestDecidedRun,
  panelVersions,
  shouldReadRun,
  formatInstant,
} from '../applied-revert.js';

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

describe('FE-505-T1 panelVersions prints the actual revision pair (FCR-015 trace)', () => {
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

describe('FE-505-T1 shouldReadRun spends only the required request (FCR-015 trace)', () => {
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

  // #279 하4. This case asserted `false` until a rehearsal showed what that
  // cost: the panel vanished the instant the undo succeeded, so the traveller
  // got no confirmation that anything had happened.
  //
  // The undo IS spent — that part of the old reasoning was right — but the
  // request does not buy another undo, it buys the REVERTED panel, which
  // `AppliedPanel` already draws (Figma `724:4730`) and which nothing else can
  // reach. Skipping the call saved a round trip and spent the confirmation.
  it('reads a reverted run, because REVERTED is the confirmation', () => {
    expect(shouldReadRun(historyItem({ decision: 'REVERT' }), decidedAt + 1000)).toBe(
      true,
    );
  });

  it('stops reading a reverted run once its window has closed too', () => {
    // The window still applies: after 24h there is nothing left to say about
    // it, and this is what keeps the REVERT branch from reading every old run
    // on every trip visit.
    expect(shouldReadRun(historyItem({ decision: 'REVERT' }), decidedAt + DAY)).toBe(
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

describe('FE-505-T1 formatInstant keeps the undo deadline (FCR-015 trace)', () => {
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

// The row the panel is about, picked out of a page rather than assumed to be
// first. `limit: 1` + "newest-first" read "the last run" as "the last DECIDED
// run", and those are the same sentence only while nothing has happened since
// the apply — which is not the state a traveller with a live undo is in.
describe('FE-505-T1 latestDecidedRun finds the panel run (FCR-015 trace)', () => {
  const decidedAt = Date.parse('2026-10-02T01:13:40Z');
  const soon = decidedAt + 1000;

  /** A run in flight: no decision, no decidedAt. This is what sits at row 0. */
  function undecided(overrides: Partial<OptimizationHistoryItem> = {}) {
    return historyItem({
      runId: '018f4a20-9f11-7c08-b3d7-2e5a41c9b104',
      status: 'RUNNING',
      decision: null,
      decidedAt: null,
      ...overrides,
    });
  }

  it('skips runs started after the apply to reach the applied one', () => {
    // The defect, in one line: start another optimization while the undo is
    // still live and row 0 stops being the row that matters.
    const page = [undecided(), undecided({ status: 'READY' }), historyItem()];

    expect(latestDecidedRun(page, soon)?.decision).toBe('APPLY');
  });

  it('takes the FIRST decided row, not merely any of them', () => {
    // Newest-first is the server's guarantee and this function leans on it. A
    // scan that returned the last match would answer with the oldest run in
    // the page, which is a different trip moment wearing the same shape.
    const newer = historyItem({ runId: 'newer', decidedAt: '2026-10-02T01:13:40Z' });
    const older = historyItem({ runId: 'older', decidedAt: '2026-10-02T01:00:00Z' });

    expect(latestDecidedRun([newer, older], soon)?.runId).toBe('newer');
  });

  it('answers undefined when every row is still undecided', () => {
    expect(latestDecidedRun([undecided(), undecided()], soon)).toBeUndefined();
  });

  it('answers undefined for an empty page and for no page at all', () => {
    expect(latestDecidedRun([], soon)).toBeUndefined();
    expect(latestDecidedRun(undefined, soon)).toBeUndefined();
  });

  it('does not reach past the 24-hour window to find one', () => {
    // A stale decided row is not a rescue: the window is closed, and picking
    // it would ask for a run the server answers EXPIRED. The scan uses the
    // same test as the single-row gate, so this cannot drift from it.
    expect(
      latestDecidedRun([historyItem()], decidedAt + 24 * 60 * 60 * 1000),
    ).toBeUndefined();
  });

  it("finds the applied run in the CONTRACT's own five-row example", () => {
    // Non-vacuity, and the reason this whole change exists. The example that
    // ships with listOptimizationHistory has RUNNING and READY above APPLIED,
    // so an owner in that state could never see the panel while row 0 was the
    // answer. Read from the pinned fixture rather than retyped, so a contract
    // edit moves this test instead of leaving it asserting a shape that is
    // gone.
    const page = JSON.parse(
      readFileSync(
        '../../packages/contracts/fixtures/optimizations/history-page.json',
        'utf8',
      ),
    ) as { items: OptimizationHistoryItem[] };

    expect(page.items[0]?.decision, 'the example must still open undecided').toBeNull();
    expect(
      page.items.filter((item) => item.decision).length,
      'and must still carry a decided row to find',
    ).toBeGreaterThan(0);

    const found = latestDecidedRun(page.items, Date.parse('2026-10-02T02:00:00Z'));

    expect(found?.decision).toBe('APPLY');
    expect(found?.runId).toBe('018f4a20-9f11-7c08-b3d7-2e5a41c9b101');
  });
});
