// FE-303 candidate rules (FR-CAN-05, FR-CAN-07, FR-ITM-02).
//
// The five match states are the point. FIGMA_HANDOFF's candidate relation table
// gives each a distinct UI, and collapsing them is how "we haven't checked yet"
// becomes "no dates work".
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  blockedSlots,
  eligibleSlots,
  hasDecision,
  isScheduled,
  nextPosition,
  visibleCandidates,
} from '../candidates.js';

type TripCandidate = components['schemas']['TripCandidate'];
type CandidateMatchResult = components['schemas']['CandidateMatchResult'];

function match(
  state: CandidateMatchResult['state'],
  slots: CandidateMatchResult['slots'] = [],
): CandidateMatchResult {
  return { candidateId: '018f4c30-9b55-7f22-8d13-6e8f4a2b3c01', state, slots };
}

function candidate(status: TripCandidate['status'], name = '서울숲'): TripCandidate {
  return {
    id: `018f4d40-1122-7a33-9b44-${status.slice(0, 8).padEnd(12, '0')}`,
    tripId: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    place: {
      id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b04',
      name,
      categoryCode: 'PARK',
      regionCode: 'KR-11-120',
    },
    status,
    sources: [{ type: 'SEARCH', createdAt: '2026-09-08T02:00:00Z' }],
    createdAt: '2026-09-08T02:00:00Z',
  };
}

describe('the five match states are not interchangeable', () => {
  it('treats EXACT and SIMILAR as decisions', () => {
    expect(hasDecision(match('EXACT'))).toBe(true);
    expect(hasDecision(match('SIMILAR'))).toBe(true);
  });

  it('does not treat CHECKING as an answer', () => {
    // The server is still looking. Showing "no dates" here would be a claim
    // it never made.
    expect(hasDecision(match('CHECKING'))).toBe(false);
    expect(eligibleSlots(match('CHECKING'))).toEqual([]);
  });

  it('does not treat UNKNOWN as an answer either', () => {
    // "Not enough evidence" is different from "nothing works".
    expect(hasDecision(match('UNKNOWN'))).toBe(false);
  });

  it('treats NONE as a decision that nothing is eligible', () => {
    expect(hasDecision(match('NONE'))).toBe(false);
    expect(eligibleSlots(match('NONE'))).toEqual([]);
  });

  it('offers no slots from a state that has not decided, even if slots arrive', () => {
    // A CHECKING result with slots attached is contradictory; the state wins,
    // because acting on half-computed slots is what schedules the wrong day.
    const contradictory = match('CHECKING', [
      { date: '2026-10-05', eligible: true, suggestedTime: null, reasonCode: null },
    ]);
    expect(eligibleSlots(contradictory)).toEqual([]);
  });
});

describe('slots are split into what can and cannot be picked', () => {
  const result = match('EXACT', [
    { date: '2026-10-06', eligible: true, suggestedTime: null, reasonCode: null },
    {
      date: '2026-10-04',
      eligible: false,
      suggestedTime: null,
      reasonCode: 'TIME_CONFLICT',
    },
    {
      date: '2026-10-05',
      eligible: true,
      suggestedTime: '14:00:00',
      reasonCode: null,
    },
  ]);

  it('returns eligible slots in date order', () => {
    expect(eligibleSlots(result).map((s) => s.date)).toEqual([
      '2026-10-05',
      '2026-10-06',
    ]);
  });

  it('keeps blocked slots with their reason rather than hiding them', () => {
    // A date that simply vanishes reads as a bug; one with a reason reads as
    // an answer.
    const blocked = blockedSlots(result);
    expect(blocked).toHaveLength(1);
    expect(blocked[0]?.date).toBe('2026-10-04');
    expect(blocked[0]?.reasonCode).toBe('TIME_CONFLICT');
  });
});

describe('candidate status decides what the card offers', () => {
  it('marks a scheduled candidate as already placed', () => {
    expect(isScheduled(candidate('SCHEDULED'))).toBe(true);
    expect(isScheduled(candidate('ACTIVE'))).toBe(false);
  });

  it('hides dismissed candidates but keeps active and scheduled ones', () => {
    const all = [candidate('ACTIVE'), candidate('SCHEDULED'), candidate('DISMISSED')];
    expect(visibleCandidates(all).map((c) => c.status)).toEqual(['ACTIVE', 'SCHEDULED']);
  });
});

describe('nextPosition appends to the chosen day', () => {
  const days = [
    { date: '2026-10-04', items: [{}, {}] },
    { date: '2026-10-05', items: [] },
  ] as unknown as components['schemas']['TripDetail']['days'];

  it('is the current count, because position is 0-based', () => {
    expect(nextPosition(days, '2026-10-04')).toBe(2);
    expect(nextPosition(days, '2026-10-05')).toBe(0);
  });

  it('is 0 for a date the trip does not have', () => {
    expect(nextPosition(days, '2026-12-25')).toBe(0);
  });
});
