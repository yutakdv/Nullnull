// FE-305 move/reorder rules (FR-ITM-03, FR-ITM-05).
//
// The assertion that matters throughout: every payload carries the COMPLETE
// ordering for the days it touches. The contract applies a reorder atomically
// and trip_items is unique on (trip, date, position), so a partial sequence
// collides with itself. These tests exist to keep that true.
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import {
  currentDate,
  isFirstInDay,
  isLastInDay,
  moveBlock,
  moveToDay,
  reorderWithinDay,
} from '../reorder.js';

const days = tripFixtures.detailScheduled.days;
// 10-04 holds 경복궁 (pos 0, MUST_VISIT + DATE) and 인사동 (pos 1, TIME).
// 10-05 holds 명동 (pos 0). 10-06 and 10-07 are empty.
function item(dayIndex: number, itemIndex: number) {
  const found = days[dayIndex]?.items[itemIndex];
  if (!found) throw new Error(`fixture missing day ${dayIndex} item ${itemIndex}`);
  return found;
}

const gyeongbok = item(0, 0);
const insadong = item(0, 1);
const myeongdong = item(1, 0);

describe('reorderWithinDay renumbers the whole day', () => {
  it('swaps two items and renumbers both from zero', () => {
    const order = reorderWithinDay(days, insadong.id, -1);
    expect(order).toEqual([
      { itemId: insadong.id, date: '2026-10-04', position: 0 },
      { itemId: gyeongbok.id, date: '2026-10-04', position: 1 },
    ]);
  });

  it('sends every item of the day, not only the one that moved', () => {
    // A payload naming just the moved item leaves a hole in the day's
    // positions, and the unique index rejects the result.
    const order = reorderWithinDay(days, gyeongbok.id, 1) ?? [];
    expect(order).toHaveLength(days[0]?.items.length ?? 0);
  });

  it('refuses a move off the top or bottom instead of sending a no-op', () => {
    // An unchanged ordering would still bump the trip version and invalidate
    // every other client's ETag for nothing.
    expect(reorderWithinDay(days, gyeongbok.id, -1)).toBeNull();
    expect(reorderWithinDay(days, insadong.id, 1)).toBeNull();
  });

  it('returns null for an item the trip does not have', () => {
    expect(reorderWithinDay(days, 'missing-id', 1)).toBeNull();
  });

  it('orders by position rather than array order', () => {
    const shuffled = days.map((day) => ({ ...day, items: [...day.items].reverse() }));
    const order = reorderWithinDay(shuffled, insadong.id, -1);
    // Same answer as the unshuffled input: position is the contract's ordering
    // field, so array order must not change the result.
    expect(order?.[0]?.itemId).toBe(insadong?.id);
  });
});

describe('moveToDay renumbers both days in one payload', () => {
  it('closes the gap in the source day and appends to the target', () => {
    const order = moveToDay(days, gyeongbok.id, '2026-10-05') ?? [];
    // Source keeps 인사동, renumbered to 0.
    expect(order).toContainEqual({
      itemId: insadong.id,
      date: '2026-10-04',
      position: 0,
    });
    // Target keeps 명동 at 0 and takes the moved item at 1.
    expect(order).toContainEqual({
      itemId: myeongdong.id,
      date: '2026-10-05',
      position: 0,
    });
    expect(order).toContainEqual({
      itemId: gyeongbok.id,
      date: '2026-10-05',
      position: 1,
    });
  });

  it('moves into an empty day at position zero', () => {
    const order = moveToDay(days, gyeongbok.id, '2026-10-06') ?? [];
    expect(order).toContainEqual({
      itemId: gyeongbok.id,
      date: '2026-10-06',
      position: 0,
    });
  });

  it('refuses a move to the day the item is already on', () => {
    expect(moveToDay(days, gyeongbok.id, '2026-10-04')).toBeNull();
  });

  it('refuses a date the trip does not have', () => {
    expect(moveToDay(days, gyeongbok.id, '2026-12-25')).toBeNull();
  });

  it('never emits two entries for the same day and position', () => {
    const order = moveToDay(days, insadong.id, '2026-10-05') ?? [];
    const slots = order.map((e) => `${e.date}#${String(e.position)}`);
    // The unique index would reject a duplicate, and the whole transaction
    // with it.
    expect(new Set(slots).size).toBe(slots.length);
  });
});

describe('edge helpers answer for the item, not the array', () => {
  it('knows the first and last item of a day', () => {
    expect(isFirstInDay(days, gyeongbok.id)).toBe(true);
    expect(isLastInDay(days, gyeongbok.id)).toBe(false);
    expect(isLastInDay(days, insadong.id)).toBe(true);
  });

  it('treats a lone item as both first and last', () => {
    expect(isFirstInDay(days, myeongdong.id)).toBe(true);
    expect(isLastInDay(days, myeongdong.id)).toBe(true);
  });

  it('reports the day an item sits on', () => {
    expect(currentDate(days, gyeongbok.id)).toBe('2026-10-04');
    expect(currentDate(days, 'missing-id')).toBeNull();
  });
});

describe('moveBlock reads the locks the move would disturb', () => {
  it('asks for confirmation when a DATE lock pins the item', () => {
    // Moving it means releasing that lock, and invariant 7 says that never
    // happens without the user saying so.
    expect(moveBlock(gyeongbok)).toBe('date-lock');
  });

  it('blocks outright when a reservation pins the item', () => {
    const reserved = {
      ...myeongdong,
      constraints: [
        {
          type: 'RESERVATION',
          locked: true,
          source: 'IMPORT',
          date: '2026-10-05',
          startTime: '19:00:00',
          endTime: null,
        },
      ],
    } as typeof myeongdong;
    // The booking lives elsewhere, so this screen cannot honour a confirm.
    expect(moveBlock(reserved)).toBe('reservation');
  });

  it('does not block on TIME, because the start time carries over', () => {
    // The frame promises "옮기면 시작 시간은 그대로 이어받아요".
    expect(moveBlock(insadong)).toBeNull();
  });

  it('does not block an unlocked item', () => {
    expect(moveBlock(myeongdong)).toBeNull();
  });
});
