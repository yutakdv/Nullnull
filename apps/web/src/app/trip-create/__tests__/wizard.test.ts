// FE-102 wizard rules (FR-TRC-01, FR-TRC-02, FR-TRC-03).
//
// These are the contract's limits, tested without rendering: CreateTripRequest
// caps the range at 30 calendar days and interests at 20 unique entries, and
// the server rejects an inverted range. A screen that lets the user build a
// request the server will refuse is a screen that wastes a round trip and shows
// an error it could have prevented.
import { describe, expect, it } from 'vitest';
import {
  EMPTY_DRAFT,
  MAX_INTERESTS,
  MAX_TRIP_DAYS,
  canAddInterest,
  dateError,
  rangeLength,
  selectDay,
  toCreateRequest,
  toggleInterest,
} from '../wizard.js';

describe('picking a date range', () => {
  it('counts an inclusive range', () => {
    expect(rangeLength('2026-10-04', '2026-10-07')).toBe(4);
    expect(rangeLength('2026-10-04', '2026-10-04')).toBe(1);
  });

  it('starts the range on the first tap', () => {
    const draft = selectDay(EMPTY_DRAFT, '2026-10-04');
    expect(draft).toMatchObject({ startDate: '2026-10-04', endDate: null });
  });

  it('closes the range on a later tap', () => {
    const draft = selectDay(selectDay(EMPTY_DRAFT, '2026-10-04'), '2026-10-07');
    expect(draft).toMatchObject({ startDate: '2026-10-04', endDate: '2026-10-07' });
  });

  it('restarts rather than inverting when the second tap is earlier', () => {
    const draft = selectDay(selectDay(EMPTY_DRAFT, '2026-10-07'), '2026-10-04');
    // The server rejects end < start; the user cannot reach that state here.
    expect(draft).toMatchObject({ startDate: '2026-10-04', endDate: null });
  });

  it('starts a new range once one is complete', () => {
    const complete = selectDay(selectDay(EMPTY_DRAFT, '2026-10-04'), '2026-10-07');
    expect(selectDay(complete, '2026-11-01')).toMatchObject({
      startDate: '2026-11-01',
      endDate: null,
    });
  });
});

describe('the 30-day limit the contract sets', () => {
  it('accepts exactly 30 days', () => {
    const draft = { ...EMPTY_DRAFT, startDate: '2026-10-01', endDate: '2026-10-30' };
    expect(rangeLength(draft.startDate, draft.endDate)).toBe(MAX_TRIP_DAYS);
    expect(dateError(draft)).toBeNull();
  });

  it('refuses 31', () => {
    const draft = { ...EMPTY_DRAFT, startDate: '2026-10-01', endDate: '2026-10-31' };
    expect(dateError(draft)).toBe('tooLong');
  });

  it('refuses an incomplete range', () => {
    expect(dateError(EMPTY_DRAFT)).toBe('incomplete');
    expect(dateError({ ...EMPTY_DRAFT, startDate: '2026-10-01' })).toBe('incomplete');
  });
});

describe('interests', () => {
  it('adds and removes without duplicating', () => {
    let draft = toggleInterest(EMPTY_DRAFT, 'FRIENDS');
    draft = toggleInterest(draft, 'FRIENDS');
    expect(draft.interests).toEqual([]);
  });

  it('keeps entries unique, as uniqueItems requires', () => {
    const draft = toggleInterest(toggleInterest(EMPTY_DRAFT, 'FOOD'), 'NATURE');
    expect(new Set(draft.interests).size).toBe(draft.interests.length);
  });

  it('stops at the contract maximum', () => {
    let draft = EMPTY_DRAFT;
    for (let n = 0; n < MAX_INTERESTS + 5; n += 1) {
      draft = toggleInterest(draft, `CODE_${String(n)}`);
    }
    expect(draft.interests).toHaveLength(MAX_INTERESTS);
    expect(canAddInterest(draft)).toBe(false);
  });

  it('allows none, which the contract permits', () => {
    const request = toCreateRequest(
      {
        startDate: '2026-10-04',
        endDate: '2026-10-07',
        interests: [],
        planningLevel: 'NOTHING',
      },
      'Asia/Seoul',
    );
    expect(request?.interests).toEqual([]);
  });
});

describe('building the create request', () => {
  const ready = {
    startDate: '2026-10-04',
    endDate: '2026-10-07',
    interests: ['FRIENDS', 'FOOD'],
    planningLevel: 'MUST_VISIT_ONLY' as const,
  };

  it('carries only the fields the contract declares', () => {
    const request = toCreateRequest(ready, 'Asia/Seoul');
    expect(Object.keys(request ?? {}).sort()).toEqual([
      'endDate',
      'interests',
      'planningLevel',
      'startDate',
      'timezone',
    ]);
  });

  it('gives every interest the neutral midpoint weight', () => {
    // The screen collects membership, not strength. Inventing a per-chip weight
    // would put a preference in the request the user never expressed.
    const request = toCreateRequest(ready, 'Asia/Seoul');
    expect(request?.interests.every((i) => i.weight === 3)).toBe(true);
  });

  it('refuses to build from an incomplete draft', () => {
    expect(toCreateRequest(EMPTY_DRAFT, 'Asia/Seoul')).toBeNull();
    expect(toCreateRequest({ ...ready, planningLevel: null }, 'Asia/Seoul')).toBeNull();
    expect(toCreateRequest({ ...ready, endDate: '2026-12-31' }, 'Asia/Seoul')).toBeNull();
  });
});
