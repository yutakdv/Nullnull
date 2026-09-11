// FE-305 place-add rules (FR-ITM-01). Invariants 1 and 2.
//
// The whole point of these tests: choosing a day and choosing 미정 must reach
// different resources. A single "add" that infers the endpoint from whether a
// date is set is how a candidate silently becomes a scheduled item.
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { addTargets, alreadyOnDay, planAdd } from '../add-place.js';

const days = tripFixtures.detailScheduled.days;

describe('planAdd picks the resource, not just a flag', () => {
  it('schedules an item when a trip day is chosen', () => {
    // 10-04 already holds two items, so the next position is 2.
    expect(planAdd(days, '2026-10-04')).toEqual({
      kind: 'item',
      date: '2026-10-04',
      position: 2,
    });
  });

  it('appends at zero on an empty day', () => {
    expect(planAdd(days, '2026-10-06')).toEqual({
      kind: 'item',
      date: '2026-10-06',
      position: 0,
    });
  });

  it('creates a candidate for 미정, with no date at all', () => {
    // Invariant 2: `+` creates a candidate without a date and does not bump the
    // trip schedule version. The plan carries no date to pass on.
    const plan = planAdd(days, null);
    expect(plan).toEqual({ kind: 'candidate' });
    expect(plan).not.toHaveProperty('date');
  });

  it('keeps the place as a candidate when the date is not in the trip', () => {
    // Dropping the action would lose what the user found; inventing a date
    // would schedule something they never picked.
    expect(planAdd(days, '2026-12-25')).toEqual({ kind: 'candidate' });
  });
});

describe('alreadyOnDay guards the duplicate case', () => {
  const scheduledPlaceId = days[0]?.items[0]?.place.id ?? '';

  it('sees a place already scheduled on that day', () => {
    expect(alreadyOnDay(days, scheduledPlaceId, '2026-10-04')).toBe(true);
  });

  it('does not object to the same place on a different day', () => {
    // Visiting one place twice is a real itinerary, not a mistake.
    expect(alreadyOnDay(days, scheduledPlaceId, '2026-10-05')).toBe(false);
  });

  it('never objects for 미정, which schedules nothing', () => {
    expect(alreadyOnDay(days, scheduledPlaceId, null)).toBe(false);
  });
});

describe('addTargets offers every day and then 미정', () => {
  it('lists the trip days in order with 미정 last', () => {
    expect(addTargets(days)).toEqual([
      '2026-10-04',
      '2026-10-05',
      '2026-10-06',
      '2026-10-07',
      null,
    ]);
  });

  it('still offers 미정 for a trip with no days', () => {
    // Somewhere to put a place that is not a guess at a date.
    expect(addTargets([])).toEqual([null]);
  });
});
