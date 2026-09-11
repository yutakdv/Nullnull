// FE-304 lock rules (FR-CON-02, FR-CON-05). Invariant 7.
//
// The assertion that matters is independence: releasing one lock leaves the
// others exactly as they were, and nothing releases on its own.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import {
  LOCK_TYPES,
  activeLocks,
  hasLock,
  isReservationManaged,
  needsConfirm,
  remainingLocks,
} from '../locks.js';

type TripItem = components['schemas']['TripDetail']['days'][number]['items'][number];
type TripConstraint = TripItem['constraints'][number];

const MUST_VISIT: TripConstraint = { type: 'MUST_VISIT', locked: true, source: 'USER' };
const DATE: TripConstraint = {
  type: 'DATE',
  locked: true,
  source: 'USER',
  date: '2026-10-04',
};
const TIME: TripConstraint = {
  type: 'TIME',
  locked: true,
  source: 'USER',
  startTime: '13:00:00+09:00',
  toleranceMinutes: 30,
};
const RESERVATION: TripConstraint = {
  type: 'RESERVATION',
  locked: true,
  source: 'IMPORT',
  date: '2026-10-05',
  startTime: '19:00:00+09:00',
  endTime: null,
};

function item(constraints: TripConstraint[]): TripItem {
  return {
    id: '018f4c30-9b55-7f22-8d13-6e8f4a2b3c01',
    place: {
      id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01',
      name: '경복궁',
      categoryCode: 'ATTRACTION',
      regionCode: 'KR-11-110',
    },
    date: '2026-10-04',
    position: 0,
    constraints,
  };
}

describe('the four locks are reported independently', () => {
  it('lists exactly the locks an item carries, in a stable order', () => {
    expect(activeLocks(item([TIME, MUST_VISIT]))).toEqual(['MUST_VISIT', 'TIME']);
    expect(activeLocks(item([]))).toEqual([]);
  });

  it('knows the four types the contract defines', () => {
    expect(LOCK_TYPES).toEqual(['MUST_VISIT', 'DATE', 'TIME', 'RESERVATION']);
  });

  it('answers for one lock without consulting the others', () => {
    const only = item([DATE]);
    expect(hasLock(only, 'DATE')).toBe(true);
    expect(hasLock(only, 'TIME')).toBe(false);
    expect(hasLock(only, 'MUST_VISIT')).toBe(false);
  });
});

describe('releasing one lock leaves the rest untouched', () => {
  const all = item([MUST_VISIT, DATE, TIME, RESERVATION]);

  it('keeps every other lock when MUST_VISIT goes', () => {
    // The dialog says "날짜·시간은 그대로 이어받아요" — this is what makes that
    // sentence true rather than merely reassuring.
    expect(remainingLocks(all, 'MUST_VISIT')).toEqual(['DATE', 'TIME', 'RESERVATION']);
  });

  it('keeps every other lock when DATE goes', () => {
    expect(remainingLocks(all, 'DATE')).toEqual(['MUST_VISIT', 'TIME', 'RESERVATION']);
  });

  it('does not release a lock the item never had', () => {
    const one = item([TIME]);
    expect(remainingLocks(one, 'DATE')).toEqual(['TIME']);
  });

  it('returns nothing left when the only lock is released', () => {
    expect(remainingLocks(item([DATE]), 'DATE')).toEqual([]);
  });
});

describe('a reservation lock is managed where the reservation is', () => {
  it('is recognised so the control can say why it is not editable here', () => {
    expect(isReservationManaged(item([RESERVATION]))).toBe(true);
    expect(isReservationManaged(item([DATE, TIME]))).toBe(false);
  });
});

describe('only the locks with a confirm frame ask for one', () => {
  it('confirms MUST_VISIT and DATE, which have frames', () => {
    // 413:2081 and 527:3876.
    expect(needsConfirm('MUST_VISIT')).toBe(true);
    expect(needsConfirm('DATE')).toBe(true);
  });

  it('does not invent a confirm for TIME', () => {
    // No frame asks for one, and a dialog the design never specified is a
    // decision made in code rather than in the design.
    expect(needsConfirm('TIME')).toBe(false);
  });
});
