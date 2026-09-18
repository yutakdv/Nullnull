import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  changeRows,
  crowdComparison,
  decisionPhase,
  isStale,
  type DecisionState,
} from '../preview.js';

type OptimizationProposal = components['schemas']['OptimizationProposal'];
type OptimizationChange = components['schemas']['OptimizationChange'];
type OptimizationStatus = components['schemas']['OptimizationStatus'];
type TripItemState = components['schemas']['TripItemState'];

// The approved example, not a hand-written object: a fixture that drifts from
// the contract is caught by the ajv check in verify:ci, and a literal here
// would be a second source of truth nobody validates.
//
// Measured shape at the time of writing: metrics.comparisonEligible true,
// crowdDelta -47, and TWO dataProvenance records, both TEMPORAL and both
// eligible. The second one matters — the rules below say "every record", and a
// fixture with one record cannot tell "every" from "the first".
const RUN = JSON.parse(
  readFileSync('../../packages/contracts/fixtures/optimizations/run-ready.json', 'utf8'),
) as { proposals: OptimizationProposal[] };

const FIXTURE = RUN.proposals[0];
if (!FIXTURE) throw new Error('run-ready.json has no proposal to test against');

/** A deep copy, so a mutation in one case cannot leak into the next. */
function proposal(): OptimizationProposal {
  return JSON.parse(JSON.stringify(FIXTURE)) as OptimizationProposal;
}

describe('crowdComparison holds invariant 8 in the return shape', () => {
  it('shows the delta with the credit that must be printed beside it', () => {
    const result = crowdComparison(proposal());

    expect(result.kind).toBe('shown');
    if (result.kind !== 'shown') return;
    expect(result.delta).toBe(-47);
    // The point of the shape: the number cannot be taken without this.
    expect(result.provenance.attribution).toBe('출처: ⓒ한국관광공사');
  });

  // Each case removes exactly one thing. Any case that would ALSO fail an
  // earlier rule proves nothing about its own rule, because the earlier rule
  // would reject it first.
  it('blocks when the server says the pair is not comparable', () => {
    const input = proposal();
    input.metrics.comparisonEligible = false;
    input.metrics.comparisonReasonCode = 'DIFFERENT_METRIC';

    const result = crowdComparison(input);

    expect(result.kind).toBe('blocked');
    if (result.kind !== 'blocked') return;
    expect(result.reasonCode).toBe('DIFFERENT_METRIC');
  });

  it('blocks a missing delta rather than drawing it as no change', () => {
    const input = proposal();
    input.metrics.crowdDelta = null;
    input.metrics.comparisonReasonCode = 'NO_SNAPSHOT';

    const result = crowdComparison(input);

    expect(result.kind).toBe('blocked');
    if (result.kind !== 'blocked') return;
    expect(result.reasonCode).toBe('NO_SNAPSHOT');
  });

  it('blocks when nothing carries an attribution to print', () => {
    const input = proposal();
    input.dataProvenance = [];

    const result = crowdComparison(input);

    expect(result.kind).toBe('blocked');
    if (result.kind !== 'blocked') return;
    // No server verdict to quote: the records are simply absent.
    expect(result.reasonCode).toBeNull();
  });

  it('blocks when a provenance record refuses the comparison the metrics allowed', () => {
    const input = proposal();
    // The SECOND record, deliberately. The first still says yes, so a check
    // that only reads `dataProvenance[0]` stays green here — which is the bug
    // this case exists to catch.
    const second = input.dataProvenance[1];
    if (!second) throw new Error('fixture no longer has a second provenance record');
    second.comparisonEligible = false;
    second.comparisonReasonCode = 'PROVIDER_INCIDENT';

    const result = crowdComparison(input);

    expect(result.kind).toBe('blocked');
    if (result.kind !== 'blocked') return;
    // The reason comes from the record that refused, not from the metrics.
    expect(result.reasonCode).toBe('PROVIDER_INCIDENT');
  });

  it('blocks a comparison across two different axes', () => {
    const input = proposal();
    const second = input.dataProvenance[1];
    if (!second) throw new Error('fixture no longer has a second provenance record');
    // Still eligible — only the axis differs, so rule 4 cannot reject it and
    // this case measures rule 5 alone.
    second.comparisonAxis = 'SPATIAL';

    const result = crowdComparison(input);

    expect(result.kind).toBe('blocked');
    if (result.kind !== 'blocked') return;
    expect(result.reasonCode).toBeNull();
  });

  it('gives a blocked result no delta to reach for, at runtime and not just in types', () => {
    const input = proposal();
    input.metrics.comparisonEligible = false;

    const result = crowdComparison(input);

    // `in`, not `=== undefined`: a property present and undefined would still
    // let `result.delta` be read and rendered as empty. The field must not
    // exist.
    expect('delta' in result).toBe(false);
    expect('provenance' in result).toBe(false);
  });
});

describe('isStale separates "moved" from "not read yet"', () => {
  it('is not stale while the trip version is still unknown', () => {
    expect(isStale(7, undefined)).toBe(false);
  });

  it('is not stale when the versions match', () => {
    expect(isStale(7, 7)).toBe(false);
  });

  it('is stale when the trip has moved on', () => {
    expect(isStale(7, 8)).toBe(true);
  });

  it('is stale when the trip version is somehow behind the run', () => {
    // `>` would call this fresh. Nobody predicts this state, and an
    // unexplained difference is still a difference.
    expect(isStale(7, 6)).toBe(true);
  });
});

describe('decisionPhase maps every run status', () => {
  const base = {
    hasProposals: true,
    stale: false,
    pending: false,
    errored: false,
  };

  // The table from the design, run as data so a missing row is visible rather
  // than implied by the absence of a test.
  const ROWS: {
    name: string;
    input: Parameters<typeof decisionPhase>[0];
    expected: { kind: 'none' } | { kind: 'bar'; state: DecisionState };
  }[] = [
    { name: 'QUEUED', input: { ...base, status: 'QUEUED' }, expected: { kind: 'none' } },
    {
      name: 'RUNNING',
      input: { ...base, status: 'RUNNING' },
      expected: { kind: 'none' },
    },
    {
      name: 'READY with no proposals',
      input: { ...base, status: 'READY', hasProposals: false },
      expected: { kind: 'none' },
    },
    {
      name: 'READY and stale',
      input: { ...base, status: 'READY', stale: true },
      expected: { kind: 'bar', state: 'stale' },
    },
    {
      name: 'READY and applying',
      input: { ...base, status: 'READY', pending: true },
      expected: { kind: 'bar', state: 'applying' },
    },
    {
      name: 'READY after a failed apply',
      input: { ...base, status: 'READY', errored: true },
      expected: { kind: 'bar', state: 'failed' },
    },
    {
      name: 'READY, decision open',
      input: { ...base, status: 'READY' },
      expected: { kind: 'bar', state: 'preview' },
    },
    {
      name: 'APPLIED',
      input: { ...base, status: 'APPLIED' },
      expected: { kind: 'bar', state: 'applied' },
    },
    { name: 'KEPT', input: { ...base, status: 'KEPT' }, expected: { kind: 'none' } },
    {
      name: 'REVERTED',
      input: { ...base, status: 'REVERTED' },
      expected: { kind: 'none' },
    },
    { name: 'FAILED', input: { ...base, status: 'FAILED' }, expected: { kind: 'none' } },
    {
      name: 'EXPIRED',
      input: { ...base, status: 'EXPIRED' },
      expected: { kind: 'none' },
    },
  ];

  it.each(ROWS)('$name', ({ input, expected }) => {
    expect(decisionPhase(input)).toEqual(expected);
  });

  it('covers every status the contract defines', () => {
    // A ninth status would compile (the switch is exhaustive) but go untested,
    // so the count is asserted against the values themselves rather than a
    // number someone has to remember to raise.
    const STATUSES: OptimizationStatus[] = [
      'QUEUED',
      'RUNNING',
      'READY',
      'APPLIED',
      'KEPT',
      'REVERTED',
      'FAILED',
      'EXPIRED',
    ];
    const covered = new Set(ROWS.map((row) => row.input.status));
    expect([...covered].sort()).toEqual([...STATUSES].sort());
  });

  it('reads a stale run as stale even while a request is in flight', () => {
    // Priority, not an accident of ordering: applying a stale run is the thing
    // the bar exists to prevent, so it outranks the spinner.
    expect(
      decisionPhase({ ...base, status: 'READY', stale: true, pending: true }),
    ).toEqual({ kind: 'bar', state: 'stale' });
  });

  it('does not tell the user a KEEP updated the itinerary', () => {
    // Invariant 4: KEEP records intent and changes nothing. `applied` says the
    // opposite.
    const result = decisionPhase({ ...base, status: 'KEPT' });
    expect(result).toEqual({ kind: 'none' });
    expect(JSON.stringify(result)).not.toContain('applied');
  });

  it('does not offer an apply retry for a run that itself failed', () => {
    // `status: FAILED` and `state: failed` are different axes. The first has
    // no proposal to retry.
    expect(decisionPhase({ ...base, status: 'FAILED', errored: true })).toEqual({
      kind: 'none',
    });
  });
});

describe('changeRows gives each row only the sides it has', () => {
  const state = (position: number): TripItemState => ({
    placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03',
    date: '2026-10-04',
    position,
    startTime: '13:00:00',
    crowd: null,
  });

  it('reads the fixture proposal', () => {
    const rows = changeRows(proposal().changes);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.kind).toBe('move');
  });

  it('treats MOVE, REORDER and REPLACE as one two-sided shape', () => {
    const changes = (['MOVE', 'REORDER', 'REPLACE'] as const).map((operation) => ({
      operation,
      itemId: `item-${operation}`,
      before: state(1),
      after: state(2),
    })) as OptimizationChange[];

    const rows = changeRows(changes);

    expect(rows.map((row) => row.kind)).toEqual(['move', 'move', 'move']);
  });

  it('gives an add no before and a remove no after', () => {
    const changes = [
      { operation: 'ADD', itemId: 'added', before: null, after: state(3) },
      { operation: 'REMOVE', itemId: 'removed', before: state(4), after: null },
    ] as OptimizationChange[];

    const rows = changeRows(changes);

    expect(rows[0]).toEqual({ kind: 'add', itemId: 'added', after: state(3) });
    expect(rows[1]).toEqual({ kind: 'remove', itemId: 'removed', before: state(4) });
    // The contract fixes these at null, so the row must not carry them at all.
    expect('before' in (rows[0] ?? {})).toBe(false);
    expect('after' in (rows[1] ?? {})).toBe(false);
  });

  it('skips an operation it does not know rather than dropping the whole preview', () => {
    const changes = [
      { operation: 'MOVE', itemId: 'known', before: state(1), after: state(2) },
      // A sixth value the server might add later.
      { operation: 'SPLIT', itemId: 'future', before: state(1), after: state(2) },
    ] as unknown as OptimizationChange[];

    const rows = changeRows(changes);

    expect(rows).toHaveLength(1);
    expect(rows[0]?.itemId).toBe('known');
  });

  it('returns nothing for no changes', () => {
    expect(changeRows([])).toEqual([]);
  });
});
