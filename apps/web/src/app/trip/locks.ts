import type { components } from '@nullnull/api-client';

// Lock rules for S07-7 `413:2081` and S07-10b `527:3876`
// (FR-CON-02, FR-CON-05, FE-304).
//
// Invariant 7: the four locks are independent and none is released
// automatically. The contract enforces the first half structurally — the
// endpoint is DELETE /constraints/{constraintType}, one lock per request — and
// this module enforces the second: every release is an explicit user action,
// and the confirm says exactly which lock goes and which stay.
//
// That last part is the whole reason this file exists rather than a bare
// confirm(). "정말 해제할까요?" tells the user nothing about what they are about
// to lose; the frames instead state the consequence ("Must Visit 표시가 풀려요.
// 날짜·시간은 그대로 이어받아요"), and a consequence has to be derived from the
// item rather than written once and hoped to stay true.

type TripItem = components['schemas']['TripDetail']['days'][number]['items'][number];
type ConstraintType = components['schemas']['ConstraintType'];

export const LOCK_TYPES: ConstraintType[] = ['MUST_VISIT', 'DATE', 'TIME', 'RESERVATION'];

/** The locks an item currently carries. */
export function activeLocks(item: TripItem): ConstraintType[] {
  const present = new Set(item.constraints.map((c) => c.type));
  return LOCK_TYPES.filter((type) => present.has(type));
}

export function hasLock(item: TripItem, type: ConstraintType): boolean {
  return item.constraints.some((c) => c.type === type);
}

/**
 * Locks that remain after releasing one.
 *
 * The confirm names these, so a user releasing DATE can see that TIME and
 * MUST_VISIT are untouched. Computed rather than assumed: an "everything else
 * stays" sentence written once becomes wrong the moment a lock is added.
 */
export function remainingLocks(
  item: TripItem,
  releasing: ConstraintType,
): ConstraintType[] {
  return activeLocks(item).filter((type) => type !== releasing);
}

/**
 * Whether a lock came from an import rather than the user.
 *
 * RESERVATION carries its own source, and the catalog marks a
 * reservation-locked control as disabled with a reason: it is managed where the
 * reservation lives, not here. Releasing it from this screen would leave the
 * booking and the itinerary disagreeing.
 */
export function isReservationManaged(item: TripItem): boolean {
  return hasLock(item, 'RESERVATION');
}

/**
 * True when releasing this lock needs a confirm.
 *
 * DATE and MUST_VISIT have their own frames (527:3876, 413:2081) because
 * releasing them changes what the optimizer may do with the item. TIME does
 * not: the frames give it no dialog, and adding one would be a confirm the
 * design never asked for.
 */
export function needsConfirm(type: ConstraintType): boolean {
  return type === 'MUST_VISIT' || type === 'DATE';
}

/**
 * Locks this screen can offer to SET on an item (FE-307, FR-CON-01/03).
 *
 * Not all four, and the omissions are the design:
 *
 *   - RESERVATION is excluded by the component rule — COMPONENT_CATALOG says
 *     `reservation-locked`는 toggle로 제공하지 않는다 — and the contract agrees,
 *     because its body needs a date and a startTime rather than a boolean. Its
 *     input has no design yet (FCR-033), so offering it here would mean
 *     inventing one.
 *   - TIME is excluded while an item has no startTime: the contract requires
 *     startTime and toleranceMinutes, and there is nothing to pin an item to
 *     when the schedule has not given it a time.
 *
 * Already-set locks never appear — setting one that exists would send a
 * request that changes nothing.
 */
export function settableLocks(item: TripItem): ConstraintType[] {
  const settable: ConstraintType[] = ['MUST_VISIT', 'DATE'];
  if (item.startTime != null) settable.push('TIME');
  return settable.filter((type) => !hasLock(item, type));
}
