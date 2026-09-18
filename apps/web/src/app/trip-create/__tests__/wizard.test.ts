// FE-102 wizard rules (FR-TRC-01, FR-TRC-02, FR-TRC-03).
//
// These are the contract's limits, tested without rendering: CreateTripRequest
// caps the range at 30 calendar days and interests at 20 unique entries, and
// the server rejects an inverted range. A screen that lets the user build a
// request the server will refuse is a screen that wastes a round trip and shows
// an error it could have prevented.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  EMPTY_DRAFT,
  MAX_INTERESTS,
  MAX_SEED_ITEMS,
  MAX_TRIP_DAYS,
  addStop,
  canAddInterest,
  canAddStop,
  dateError,
  rangeLength,
  removeStop,
  seedItemsOf,
  selectDay,
  toCreateRequest,
  toggleInterest,
  tripDays,
  type WizardDraft,
} from '../wizard.js';

type PlaceSummary = components['schemas']['PlaceSummary'];

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
        mustVisit: [],
        stops: [],
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
    mustVisit: [],
    stops: [],
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

// S02-4C-C manual entry (`438:3199`, FR-TRC-05, FE-103).
//
// The rules that decide what reaches the server, tested without rendering for
// the same reason the rest of this file is: the screen cannot quietly disagree
// with them.

function placeNamed(id: string, name: string): PlaceSummary {
  // Only the fields these rules read. The screen renders more, and
  // must-visit.test.tsx covers that against the msw fixture.
  return { id, name } as PlaceSummary;
}

const planned = {
  startDate: '2026-10-04',
  endDate: '2026-10-07',
  interests: [],
  planningLevel: 'MOSTLY_PLANNED' as const,
  mustVisit: [],
  stops: [],
};

describe('the days a manual entry screen offers', () => {
  it('lists every day of the range, inclusive', () => {
    expect(tripDays(planned)).toEqual([
      '2026-10-04',
      '2026-10-05',
      '2026-10-06',
      '2026-10-07',
    ]);
  });

  it('crosses a month boundary without repeating or skipping a day', () => {
    const days = tripDays({ ...planned, startDate: '2026-10-30', endDate: '2026-11-02' });
    expect(days).toEqual(['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02']);
  });

  it('offers nothing while the range is incomplete', () => {
    expect(tripDays(EMPTY_DRAFT)).toEqual([]);
    expect(tripDays({ ...planned, endDate: null })).toEqual([]);
  });
});

describe('adding stops to a day', () => {
  it('keeps a place added to two days on both', () => {
    // A traveller can pass the same station twice; seedItems has no uniqueness
    // rule, so this is not the duplicate that addMustVisit drops.
    const place = placeNamed('p1', '서울역');
    let draft = addStop(planned, '2026-10-04', place, 'k1');
    draft = addStop(draft, '2026-10-05', place, 'k2');
    expect(draft.stops).toHaveLength(2);
  });

  it('refuses a day outside the trip', () => {
    // The screen only renders 장소 추가 under a real day header, so this is a
    // guard against a caller, not something the user can reach.
    const draft = addStop(planned, '2026-10-20', placeNamed('p1', '경복궁'), 'k1');
    expect(draft.stops).toEqual([]);
  });

  it('stops at the contract maximum rather than dropping the last silently', () => {
    let draft: WizardDraft = planned;
    for (let n = 0; n < MAX_SEED_ITEMS + 3; n += 1) {
      draft = addStop(
        draft,
        '2026-10-04',
        placeNamed(`p${String(n)}`, '경복궁'),
        `k${String(n)}`,
      );
    }
    expect(draft.stops).toHaveLength(MAX_SEED_ITEMS);
    expect(canAddStop(draft)).toBe(false);
  });

  it('removes one stop by key, leaving a repeat of the same place', () => {
    const place = placeNamed('p1', '경복궁');
    let draft = addStop(planned, '2026-10-04', place, 'k1');
    draft = addStop(draft, '2026-10-04', place, 'k2');
    draft = removeStop(draft, 'k1');
    expect(draft.stops.map((s) => s.key)).toEqual(['k2']);
  });
});

describe('sending manually entered stops', () => {
  const twoDays = (() => {
    let draft: WizardDraft = planned;
    draft = addStop(draft, '2026-10-04', placeNamed('p1', '경복궁'), 'k1');
    draft = addStop(draft, '2026-10-04', placeNamed('p2', '인사동'), 'k2', 'AFTERNOON');
    draft = addStop(draft, '2026-10-05', placeNamed('p3', '남산'), 'k3');
    return draft;
  })();

  it('never sends a time the traveller did not name', () => {
    // THE decision of this screen. The card offers 오전 and 오후 and nothing
    // finer, so no clock time was ever chosen; mapping 오전 to 09:00:00 would
    // put a number in the itinerary that nobody picked and that the trip screen
    // would then show back as fact. ItineraryParser already refuses the easier
    // version of this inference (a bare `3시` is AMBIGUOUS_TIME), and a
    // meridiem with no hour says even less.
    const items = seedItemsOf(twoDays);
    expect(items).not.toHaveLength(0);
    expect(items.every((item) => item.startTime === null)).toBe(true);
  });

  it('numbers positions per day, restarting at zero', () => {
    // requireDistinctPositions buckets by date, so day 2 starts over. The spine
    // numbers the stops 1, 2, 3 within each day and this is that order.
    const items = seedItemsOf(twoDays);
    expect(items.map((i) => [i.date, i.position])).toEqual([
      ['2026-10-04', 0],
      ['2026-10-04', 1],
      ['2026-10-05', 0],
    ]);
  });

  it('carries the stops into the create request', () => {
    const request = toCreateRequest(twoDays, 'Asia/Seoul');
    expect(request?.seedItems).toHaveLength(3);
    expect(request?.seedItems?.[0]).toMatchObject({ placeId: 'p1', date: '2026-10-04' });
  });

  it('omits seedItems entirely when no stop was entered', () => {
    // Not `[]`: the server branches on whether seedItems is empty to decide
    // whether the catalog publication gate applies, and an empty array would
    // claim this trip carries seeded places.
    const request = toCreateRequest(planned, 'Asia/Seoul');
    expect(request).not.toBeNull();
    expect('seedItems' in (request ?? {})).toBe(false);
  });

  it('drops the daypart, which is a sorting aid and not data', () => {
    // If this ever needs to survive, the field for it is a real time the
    // traveller entered — which the frame does not draw, so it needs a Figma
    // change request first rather than an invented mapping here.
    const items = seedItemsOf(twoDays);
    expect(items.some((item) => 'daypart' in item)).toBe(false);
  });
});
