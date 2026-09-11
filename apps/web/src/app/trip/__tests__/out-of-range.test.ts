// FE-306: which items a date-range change would strand (FR-TRP-05).
//
// The rule is quoted from the contract, not inferred. UpdateTripRequest:
// "Date-range shrink is rejected with VALIDATION_FAILED while any item or
// DATE/RESERVATION lock lies outside the new range."
//
// That is two conditions joined by "or", and both are tested here: an item
// whose own date falls outside, and a lock whose date falls outside. Nothing
// in the contract ties a DateConstraint's date to its item's date, so they are
// checked separately rather than assumed equal.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { tripFixtures } from '@nullnull/contracts';
import { draftFrom, isShrink, outOfRangeItems } from '../trip-edit.js';

type TripDetail = components['schemas']['TripDetail'];

const trip = tripFixtures.detailScheduled;

/**
 * A minimal place for the hand-built trips below.
 *
 * Built rather than borrowed from the fixture: indexing into days[0].items[0]
 * is possibly-undefined under the strict index rules, and these cases only
 * need a name.
 */
function placeNamed(name: string): components['schemas']['PlaceSummary'] {
  return {
    id: '018f4e60-0000-7000-8000-0000000000ff',
    name,
    categoryCode: 'ATTRACTION',
    regionCode: 'SEOUL',
    address: '서울',
  };
}

/** The fixture range, for readability in the expectations below. */
const FIRST_DAY = trip.startDate;
const LAST_DAY = trip.endDate;

function withRange(startDate: string, endDate: string) {
  return { ...draftFrom(trip), startDate, endDate };
}

describe('an unchanged or widened range strands nothing', () => {
  it('reports nothing when the range is untouched', () => {
    expect(outOfRangeItems(trip, draftFrom(trip))).toEqual([]);
  });

  it('reports nothing when the range grows', () => {
    expect(outOfRangeItems(trip, withRange('2026-10-01', '2026-10-20'))).toEqual([]);
  });

  it('does not call a widened range a shrink', () => {
    expect(isShrink(withRange('2026-10-01', '2026-10-20'), trip)).toBe(false);
    expect(isShrink(draftFrom(trip), trip)).toBe(false);
  });

  it('calls a narrowed range a shrink, on either end', () => {
    expect(isShrink(withRange('2026-10-05', LAST_DAY), trip)).toBe(true);
    expect(isShrink(withRange(FIRST_DAY, '2026-10-06'), trip)).toBe(true);
  });
});

describe('a half-typed range reports nothing', () => {
  // A `type="date"` input reads as "" mid-typing and after a clear, and ""
  // sorts before every real date — so an unguarded compare marks the entire
  // trip out of range while the user is still choosing a date.
  it('reports nothing while the end date is empty', () => {
    expect(outOfRangeItems(trip, withRange(FIRST_DAY, ''))).toEqual([]);
  });

  it('reports nothing while the start date is empty', () => {
    expect(outOfRangeItems(trip, withRange('', LAST_DAY))).toEqual([]);
  });

  it('does not call an incomplete range a shrink', () => {
    expect(isShrink(withRange(FIRST_DAY, ''), trip)).toBe(false);
    expect(isShrink(withRange('', LAST_DAY), trip)).toBe(false);
  });
});

describe('an item whose own date falls outside', () => {
  it('lists the items left behind when the start moves forward', () => {
    // Dropping the first day strands both items scheduled on it.
    const affected = outOfRangeItems(trip, withRange('2026-10-05', LAST_DAY));
    expect(affected.map((item) => item.placeName).sort()).toEqual(['경복궁', '인사동']);
  });

  it('names the date that puts each item outside', () => {
    const [first] = outOfRangeItems(trip, withRange('2026-10-05', LAST_DAY));
    expect(first?.date).toBe('2026-10-04');
  });

  it('lists items stranded by pulling the end date back', () => {
    const affected = outOfRangeItems(trip, withRange(FIRST_DAY, '2026-10-04'));
    expect(affected.map((item) => item.placeName)).toEqual(['명동']);
  });

  it('treats the range as inclusive on both ends', () => {
    // An item exactly on the first or last day is inside, not outside.
    expect(outOfRangeItems(trip, withRange('2026-10-04', '2026-10-05'))).toEqual([]);
  });
});

describe('only the locks the rule names are reported', () => {
  it('reports the DATE lock on a stranded item', () => {
    const affected = outOfRangeItems(trip, withRange('2026-10-05', LAST_DAY));
    const gyeongbok = affected.find((item) => item.placeName === '경복궁');
    expect(gyeongbok?.blockingLocks).toEqual(['DATE']);
  });

  it('ignores dateless locks rather than comparing an absent date', () => {
    // MUST_VISIT and TIME carry no `date` in the contract at all. Selecting
    // locks by type is what keeps them out; without it the comparison runs
    // against undefined, which is false by luck rather than by rule. This
    // builds an item whose ONLY locks are the dateless two, on a date that is
    // itself out of range, so a type filter that let them through would list
    // them as blocking.
    const dateless: TripDetail = {
      ...trip,
      days: [
        {
          date: '2026-10-04',
          items: [
            {
              id: '018f4e60-0000-7000-8000-0000000000bb',
              place: placeNamed('북촌'),
              date: '2026-10-04',
              position: 0,
              constraints: [
                { type: 'MUST_VISIT', locked: true, source: 'USER' },
                {
                  type: 'TIME',
                  locked: true,
                  source: 'USER',
                  startTime: '09:00:00',
                  toleranceMinutes: 30,
                },
              ],
            },
          ],
        },
      ],
    };
    const affected = outOfRangeItems(dateless, {
      ...draftFrom(dateless),
      startDate: '2026-10-05',
      endDate: '2026-10-07',
    });
    // The item is out of range on its own date, so it is listed —
    // but with no blocking locks, because neither lock has a date to compare.
    expect(affected).toHaveLength(1);
    expect(affected[0]?.blockingLocks).toEqual([]);
  });

  it('does not report MUST_VISIT or TIME as blocking', () => {
    // 경복궁 also carries MUST_VISIT and 인사동 carries TIME. Neither appears in
    // the contract's rule, and listing them would imply they block the save —
    // the four locks are independent (invariant 7).
    const affected = outOfRangeItems(trip, withRange('2026-10-05', LAST_DAY));
    for (const item of affected) {
      expect(item.blockingLocks).not.toContain('MUST_VISIT');
      expect(item.blockingLocks).not.toContain('TIME');
    }
    const insadong = affected.find((item) => item.placeName === '인사동');
    expect(insadong?.blockingLocks).toEqual([]);
  });
});

describe('a lock whose date differs from its item', () => {
  /**
   * The contract states the two conditions separately and never ties a
   * DateConstraint's date to its item's date, so this builds the case the
   * fixture does not contain: the item is inside the new range while its lock
   * is not.
   */
  function tripWithDivergentLock(): TripDetail {
    return {
      ...trip,
      days: [
        {
          date: '2026-10-05',
          items: [
            {
              id: '018f4e60-0000-7000-8000-0000000000aa',
              place: placeNamed('한옥마을'),
              date: '2026-10-05',
              position: 0,
              constraints: [
                {
                  type: 'RESERVATION',
                  locked: true,
                  source: 'USER',
                  date: '2026-10-07',
                  startTime: '10:00:00',
                },
              ],
            },
          ],
        },
      ],
    };
  }

  it('reports an in-range item whose lock is out of range', () => {
    const divergent = tripWithDivergentLock();
    // The item sits on 10-05, inside the new range; its reservation is on
    // 10-07, outside it. The contract rejects on either.
    const affected = outOfRangeItems(divergent, {
      ...draftFrom(divergent),
      startDate: '2026-10-04',
      endDate: '2026-10-06',
    });
    expect(affected).toHaveLength(1);
    expect(affected[0]?.placeName).toBe('한옥마을');
    expect(affected[0]?.blockingLocks).toEqual(['RESERVATION']);
  });

  it('leaves the date null when only the lock is out of range', () => {
    // The item's own date is not what puts it there, so naming it would point
    // the user at a date that is perfectly inside the range.
    const divergent = tripWithDivergentLock();
    const affected = outOfRangeItems(divergent, {
      ...draftFrom(divergent),
      startDate: '2026-10-04',
      endDate: '2026-10-06',
    });
    expect(affected[0]?.date).toBeNull();
  });

  it('reports nothing when both the item and its lock are inside', () => {
    const divergent = tripWithDivergentLock();
    expect(
      outOfRangeItems(divergent, {
        ...draftFrom(divergent),
        startDate: '2026-10-04',
        endDate: '2026-10-08',
      }),
    ).toEqual([]);
  });
});
