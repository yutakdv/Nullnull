// FE-301 derivations (FR-TRP-01). No render, no server.
//
// FE-301-T1 lives mostly here: the acceptance is that the screen never shows a
// wrong total, and these are the functions that could produce one.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  daysUntil,
  isEmptySchedule,
  itemCount,
  orderedItems,
  todayIn,
  tripLength,
  visibleDays,
} from '../trip-view.js';

type TripDay = components['schemas']['TripDetail']['days'][number];

function item(position: number, name: string): TripDay['items'][number] {
  return {
    id: `018f4a10-2c31-7d42-9a55-6b1f0c3e${String(position).padStart(4, '0')}`,
    place: {
      id: '018f4a10-2c31-7d42-9a55-6b1f0c3e9001',
      name,
      categoryCode: 'ATTRACTION',
      regionCode: 'SEOUL_JONGNO',
    },
    date: '2026-10-04',
    position,
    constraints: [],
  };
}

describe('tripLength says nights and days the way the header does', () => {
  it('counts 2026-10-04 to 2026-10-07 as 3박 4일', () => {
    expect(tripLength('2026-10-04', '2026-10-07')).toEqual({ nights: 3, days: 4 });
  });

  it('counts a same-day trip as 0박 1일, not 0 days', () => {
    expect(tripLength('2026-10-04', '2026-10-04')).toEqual({ nights: 0, days: 1 });
  });

  it('spans a month boundary without losing a day', () => {
    expect(tripLength('2026-10-30', '2026-11-02')).toEqual({ nights: 3, days: 4 });
  });

  it('spans a DST transition without losing a day', () => {
    // Parsed as UTC on both ends, so a local-time DST shift cannot round the
    // subtraction down to 30 nights.
    expect(tripLength('2026-03-01', '2026-03-31').nights).toBe(30);
  });

  it('clamps reversed dates rather than showing a negative trip', () => {
    expect(tripLength('2026-10-07', '2026-10-04')).toEqual({ nights: 0, days: 1 });
  });
});

describe('daysUntil counts down and then stops', () => {
  it('is the gap while the trip is ahead', () => {
    expect(daysUntil('2026-10-04', '2026-09-02')).toBe(32);
  });

  it('is null on the start date, not zero', () => {
    // D-0 would read as "starts in 0 days" for a trip already under way.
    expect(daysUntil('2026-10-04', '2026-10-04')).toBeNull();
  });

  it('is null once the trip has started', () => {
    expect(daysUntil('2026-10-04', '2026-10-06')).toBeNull();
  });
});

describe('todayIn uses the trip timezone, not the device', () => {
  it('reads the date in the given zone', () => {
    // 2026-10-04T20:00Z is already the 5th in Seoul (UTC+9).
    const instant = new Date('2026-10-04T20:00:00Z');
    expect(todayIn('Asia/Seoul', instant)).toBe('2026-10-05');
    expect(todayIn('UTC', instant)).toBe('2026-10-04');
  });
});

describe('itemCount sums the days it is given', () => {
  const days: TripDay[] = [
    { date: '2026-10-04', items: [item(0, '경복궁'), item(1, '인사동')] },
    { date: '2026-10-05', items: [item(0, '명동')] },
    { date: '2026-10-06', items: [] },
  ];

  it('adds every day, including the empty one', () => {
    expect(itemCount(days)).toBe(3);
  });

  it('is zero for a trip with days but nothing in them', () => {
    const blank: TripDay[] = [
      { date: '2026-10-04', items: [] },
      { date: '2026-10-05', items: [] },
    ];
    expect(itemCount(blank)).toBe(0);
    expect(isEmptySchedule(blank)).toBe(true);
    // Days exist, so this is an empty schedule and not an empty trip.
    expect(blank).toHaveLength(2);
  });

  it('does not call a partly filled trip empty', () => {
    expect(isEmptySchedule(days)).toBe(false);
  });
});

describe('orderedItems follows position, not array order', () => {
  it('sorts by the contract field', () => {
    const day: TripDay = {
      date: '2026-10-04',
      items: [item(2, '명동'), item(0, '경복궁'), item(1, '인사동')],
    };
    expect(orderedItems(day).map((i) => i.place.name)).toEqual([
      '경복궁',
      '인사동',
      '명동',
    ]);
  });

  it('does not mutate the day it was given', () => {
    const day: TripDay = { date: '2026-10-04', items: [item(1, 'B'), item(0, 'A')] };
    orderedItems(day);
    expect(day.items.map((i) => i.place.name)).toEqual(['B', 'A']);
  });
});

describe('visibleDays filters to the chosen chip', () => {
  const days: TripDay[] = [
    { date: '2026-10-04', items: [item(0, '경복궁')] },
    { date: '2026-10-05', items: [] },
  ];

  it('returns every day for 전체', () => {
    expect(visibleDays(days, null)).toHaveLength(2);
  });

  it('returns just the selected day', () => {
    expect(visibleDays(days, '2026-10-05')).toEqual([days[1]]);
  });
});
