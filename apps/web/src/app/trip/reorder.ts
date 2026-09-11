import type { components } from '@nullnull/api-client';

// Move and reorder rules for S07-10 `521:3976` and S07-2 `527:4085`
// (FR-ITM-03, FR-ITM-05, FE-305).
//
// Every one of these returns the COMPLETE ordering for the days it touches,
// because that is the only shape the contract accepts: reorderTripItems is
// "reorder or move trip items atomically", and `trip_items` is unique on
// (trip, date, position). A partial sequence collides with itself halfway
// through and leaves the trip in a state nobody asked for.
//
// That is also why there is no `moveItem` that returns one entry. The unit of
// work is the day, not the item.

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];
type TripItem = TripDay['items'][number];
type ReorderEntry = components['schemas']['ReorderTripItemsRequest']['items'][number];

/** The contract caps one reorder at 100 entries. */
export const MAX_REORDER_ENTRIES = 100;

/** Items of one day in contract order. */
function ordered(day: TripDay): TripItem[] {
  return [...day.items].sort((a, b) => a.position - b.position);
}

/** Renumbers a day from 0, which is what `position` means. */
function renumber(date: string, items: readonly TripItem[]): ReorderEntry[] {
  return items.map((item, index) => ({ itemId: item.id, date, position: index }));
}

/**
 * Moves one item up or down inside its own day.
 *
 * Returns null when the move is impossible (item not found, already at the
 * edge) rather than returning an unchanged ordering — a no-op request would
 * still bump the trip version and invalidate everyone else's ETag.
 */
export function reorderWithinDay(
  days: readonly TripDay[],
  itemId: string,
  direction: -1 | 1,
): ReorderEntry[] | null {
  const day = days.find((d) => d.items.some((item) => item.id === itemId));
  if (!day) return null;
  const items = ordered(day);
  const from = items.findIndex((item) => item.id === itemId);
  const to = from + direction;
  if (to < 0 || to >= items.length) return null;
  const next = [...items];
  const [moved] = next.splice(from, 1);
  if (!moved) return null;
  next.splice(to, 0, moved);
  return renumber(day.date, next);
}

/**
 * Moves one item to another day, appending it to the end.
 *
 * Both days are renumbered in the same payload: the source day closes the gap
 * the item left, and the target day gets the item at its end. Sending only the
 * moved item would leave the source day with a hole in its positions.
 */
export function moveToDay(
  days: readonly TripDay[],
  itemId: string,
  targetDate: string,
): ReorderEntry[] | null {
  const source = days.find((d) => d.items.some((item) => item.id === itemId));
  const target = days.find((d) => d.date === targetDate);
  if (!source || !target) return null;
  if (source.date === targetDate) return null;
  const moved = source.items.find((item) => item.id === itemId);
  if (!moved) return null;

  const remaining = ordered(source).filter((item) => item.id !== itemId);
  const appended = [...ordered(target), moved];
  return [...renumber(source.date, remaining), ...renumber(target.date, appended)];
}

/** True when the item is already first in its day. */
export function isFirstInDay(days: readonly TripDay[], itemId: string): boolean {
  const day = days.find((d) => d.items.some((item) => item.id === itemId));
  if (!day) return true;
  return ordered(day)[0]?.id === itemId;
}

/** True when the item is already last in its day. */
export function isLastInDay(days: readonly TripDay[], itemId: string): boolean {
  const day = days.find((d) => d.items.some((item) => item.id === itemId));
  if (!day) return true;
  const items = ordered(day);
  return items[items.length - 1]?.id === itemId;
}

/**
 * The day an item currently sits on.
 *
 * The move sheet disables this row rather than hiding it, so the user can see
 * where the item is now (`지금 이 날짜예요`).
 */
export function currentDate(days: readonly TripDay[], itemId: string): string | null {
  return days.find((d) => d.items.some((item) => item.id === itemId))?.date ?? null;
}

/**
 * Whether a date move needs the user to confirm first.
 *
 * A DATE lock pins the item to its day, so moving it means releasing that lock
 * — which invariant 7 says never happens on its own. RESERVATION is pinned by
 * a booking that lives elsewhere, so it blocks the move outright rather than
 * offering a confirm this screen cannot honour.
 *
 * MUST_VISIT and TIME do not block a date move: the first pins the place and
 * the second pins the clock time, and the frame promises the start time carries
 * over ("옮기면 시작 시간은 그대로 이어받아요").
 */
export type MoveBlock = 'reservation' | 'date-lock' | null;

export function moveBlock(item: TripItem): MoveBlock {
  const types = new Set(item.constraints.map((c) => c.type));
  if (types.has('RESERVATION')) return 'reservation';
  if (types.has('DATE')) return 'date-lock';
  return null;
}
