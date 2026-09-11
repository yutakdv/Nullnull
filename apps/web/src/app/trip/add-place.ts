import type { components } from '@nullnull/api-client';

// Place-add rules for S07-3 `476:3409` (FR-ITM-01, FE-305).
//
// The screen offers a day chip per trip day plus 미정 ("no date yet"), and that
// choice is not cosmetic: it picks a different endpoint on a different
// resource.
//
//   a day  → addTripItem      → a TripItem, scheduled, bumps the trip version
//   미정    → addTripCandidate → a TripCandidate, no date, does NOT bump it
//
// Invariants 1 and 2 are exactly this distinction: the three resources are
// separate, and `+` creates candidates without dates and without touching the
// schedule. One "add" button that guesses the endpoint from whether a date
// happens to be selected is how the two collapse into each other, so the
// choice is modelled here and named in the type.

type TripDetail = components['schemas']['TripDetail'];
type TripDay = TripDetail['days'][number];

/** `null` is the 미정 chip: no date chosen. */
export type AddTarget = string | null;

export type AddPlan =
  | { kind: 'item'; date: string; position: number }
  | { kind: 'candidate' };

/**
 * What adding this place should actually do.
 *
 * Returns the resource to create rather than a boolean, so the caller cannot
 * reach the wrong endpoint by accident: an `AddPlan` of kind 'candidate' has no
 * date to pass to addTripItem in the first place.
 */
export function planAdd(days: readonly TripDay[], target: AddTarget): AddPlan {
  if (target === null) return { kind: 'candidate' };
  const day = days.find((d) => d.date === target);
  // A date the trip does not have is not a schedulable date. Saving it as a
  // candidate keeps the place rather than dropping the user's action.
  if (!day) return { kind: 'candidate' };
  // Appended to the end of that day; `position` is 0-based, so the next free
  // index is the current count.
  return { kind: 'item', date: target, position: day.items.length };
}

/** True when the place is already scheduled on the chosen day. */
export function alreadyOnDay(
  days: readonly TripDay[],
  placeId: string,
  target: AddTarget,
): boolean {
  if (target === null) return false;
  const day = days.find((d) => d.date === target);
  return day?.items.some((item) => item.place.id === placeId) ?? false;
}

/**
 * The day chips the screen offers, in trip order, plus 미정.
 *
 * 미정 is last and always present: a user who has found a place but not decided
 * when to go needs somewhere to put it that is not a guess at a date.
 */
export function addTargets(days: readonly TripDay[]): AddTarget[] {
  return [...days.map((day) => day.date), null];
}
