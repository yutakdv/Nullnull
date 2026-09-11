// FE-506 optimization history rules (FR-OPT-*).
//
// The distinction under test: status is where the run got to, decision is what
// the user chose. Collapsing them loses the one row that needs attention — a
// READY run nobody has answered yet.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { awaitsDecision, hasResult, isInProgress, rowState } from '../history.js';

type HistoryItem = components['schemas']['OptimizationHistoryItem'];

function run(
  status: HistoryItem['status'],
  decision: HistoryItem['decision'] = null,
): HistoryItem {
  return {
    runId: '018f4e60-0000-7000-8000-000000000001',
    tripId: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    tripTitle: '서울 가을 여행',
    scope: 'ITEM',
    status,
    decision,
    runLink:
      '/trips/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4e60-0000-7000-8000-000000000001',
    queuedAt: '2026-10-02T01:00:00Z',
  };
}

describe('in-progress runs have no result to open', () => {
  it('recognises the two working states', () => {
    expect(isInProgress(run('QUEUED'))).toBe(true);
    expect(isInProgress(run('RUNNING'))).toBe(true);
    expect(isInProgress(run('READY'))).toBe(false);
  });

  it('offers no link while a run is still working', () => {
    // A link that lands on nothing is worse than a stated status.
    expect(hasResult(run('QUEUED'))).toBe(false);
    expect(hasResult(run('RUNNING'))).toBe(false);
  });

  it('offers a link for every finished state, including the failures', () => {
    for (const status of [
      'READY',
      'APPLIED',
      'KEPT',
      'REVERTED',
      'FAILED',
      'EXPIRED',
    ] as const) {
      expect(hasResult(run(status))).toBe(true);
    }
  });
});

describe('a READY run with no decision is the one waiting on the user', () => {
  it('is flagged as awaiting a decision', () => {
    expect(awaitsDecision(run('READY'))).toBe(true);
  });

  it('is not flagged once a decision exists', () => {
    expect(awaitsDecision(run('READY', 'APPLY'))).toBe(false);
  });

  it('is not flagged for a run that ended on its own', () => {
    // FAILED and EXPIRED never had a decision to make.
    expect(awaitsDecision(run('FAILED'))).toBe(false);
    expect(awaitsDecision(run('EXPIRED'))).toBe(false);
  });
});

describe('rowState keeps decision and status apart', () => {
  it('prefers the decision when the user made one', () => {
    // "적용함" says the user chose it; "적용됨" only says it happened.
    expect(rowState(run('APPLIED', 'APPLY'))).toEqual({
      kind: 'decision',
      value: 'APPLY',
    });
    expect(rowState(run('REVERTED', 'REVERT'))).toEqual({
      kind: 'decision',
      value: 'REVERT',
    });
  });

  it('says pending for a result nobody has answered', () => {
    expect(rowState(run('READY'))).toEqual({ kind: 'pending', value: 'READY' });
  });

  it('falls back to the status and never invents a decision', () => {
    expect(rowState(run('FAILED'))).toEqual({ kind: 'status', value: 'FAILED' });
    expect(rowState(run('RUNNING'))).toEqual({ kind: 'status', value: 'RUNNING' });
    expect(rowState(run('EXPIRED'))).toEqual({ kind: 'status', value: 'EXPIRED' });
  });

  it('trusts the decision even if the status disagrees', () => {
    // The server owns both fields; the client does not arbitrate between them.
    expect(rowState(run('EXPIRED', 'KEEP'))).toEqual({
      kind: 'decision',
      value: 'KEEP',
    });
  });
});
