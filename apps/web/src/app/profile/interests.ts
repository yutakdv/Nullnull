import type { components } from '@nullnull/api-client';
import { INTEREST_GROUPS, MAX_INTERESTS } from '../trip-create/wizard.js';

// Interest editing rules for the S14 profile card (FR-PRO-05, FE-106).
//
// Pure on purpose: these are the rules the ETag conflict recovery depends on,
// and they are worth testing without a server or a render.
//
// The catalogue and the weight convention are the wizard's (trip-create/
// wizard.ts). A second list here would be a second source of truth, and the
// screens would drift apart the first time one of them gained a code.

type TripInterest = components['schemas']['TripInterest'];

export { MAX_INTERESTS, INTEREST_GROUPS };

/**
 * Every interest the catalogue offers, flattened.
 *
 * A trip may carry a code this build does not know — the server owns the
 * vocabulary — so the screen renders unknown codes too rather than dropping
 * them. Silently discarding one would turn a read-modify-write into data loss.
 */
export const CATALOGUE_CODES: readonly string[] = INTEREST_GROUPS.flatMap(
  (group) => group.codes as readonly string[],
);

/** Codes on the trip that this build's catalogue does not list. */
export function unknownCodes(interests: readonly TripInterest[]): string[] {
  return interests
    .map((interest) => interest.code)
    .filter((code) => !CATALOGUE_CODES.includes(code));
}

/** The selection a trip's interests represent, order-independent. */
export function toSelection(interests: readonly TripInterest[]): string[] {
  return interests.map((interest) => interest.code);
}

/** Toggles one code, respecting the contract's 20-entry cap. */
export function toggle(selection: readonly string[], code: string): string[] {
  if (selection.includes(code)) return selection.filter((c) => c !== code);
  if (selection.length >= MAX_INTERESTS) return [...selection];
  return [...selection, code];
}

export function canAdd(selection: readonly string[]): boolean {
  return selection.length < MAX_INTERESTS;
}

/**
 * True when the draft differs from what the server holds.
 *
 * Set comparison, not array comparison: the contract's array is uniqueItems
 * and carries no ordering guarantee, so a reordered array is the same set and
 * must not present itself as an unsaved change.
 */
export function isDirty(
  selection: readonly string[],
  saved: readonly TripInterest[],
): boolean {
  const a = new Set(selection);
  const b = new Set(saved.map((interest) => interest.code));
  if (a.size !== b.size) return true;
  return [...a].some((code) => !b.has(code));
}

/**
 * Builds the replacement body.
 *
 * Weight is fixed at 3, the midpoint of the contract's 1-5 range, because this
 * card collects membership and not strength — the same choice the create wizard
 * makes. Preserves the weight a trip already carries so that editing the set
 * here does not silently flatten weights some other screen set.
 */
export function toReplaceBody(
  selection: readonly string[],
  saved: readonly TripInterest[],
): { interests: TripInterest[] } {
  const existing = new Map(saved.map((interest) => [interest.code, interest.weight]));
  return {
    interests: selection.map((code) => ({ code, weight: existing.get(code) ?? 3 })),
  };
}
