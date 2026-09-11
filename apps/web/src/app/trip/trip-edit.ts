import type { components } from '@nullnull/api-client';
import { tripLength } from './trip-view.js';

// Edit buffer rules for S07-2 `411:1837` (FR-TRP-02, FR-TRP-03, FR-TRP-05).
//
// Pure so the dirty test and the patch construction can be checked without a
// render: FE-302's acceptance is that no user edit is ever lost, and both the
// dirty-exit prompt and the conflict recovery hang off these.
//
// updateTrip patches *metadata only* — title, dates, timezone, planningLevel,
// status. Item add/move/reorder/delete are FR-ITM-* and belong to FE-305, so
// nothing here touches days or items.

type TripDetail = components['schemas']['TripDetail'];
type UpdateTripRequest = components['schemas']['UpdateTripRequest'];
type PlanningLevel = components['schemas']['PlanningLevel'];

/** The fields this screen edits, as strings the form holds. */
export interface TripDraft {
  title: string;
  startDate: string;
  endDate: string;
  planningLevel: PlanningLevel;
}

/** Contract limits, not invented: UpdateTripRequest.title is 1..100. */
export const MAX_TITLE_LENGTH = 100;

/** The draft a trip starts from when edit mode opens. */
export function draftFrom(trip: TripDetail): TripDraft {
  return {
    title: trip.title,
    startDate: trip.startDate,
    endDate: trip.endDate,
    planningLevel: trip.planningLevel,
  };
}

/**
 * True when the draft differs from the trip it was taken from.
 *
 * This is what the dirty-exit prompt asks about, so it has to be exact in both
 * directions: a false positive nags on every cancel, and a false negative
 * discards real edits without asking.
 */
export function isDirty(draft: TripDraft, trip: TripDetail): boolean {
  const base = draftFrom(trip);
  return (
    draft.title !== base.title ||
    draft.startDate !== base.startDate ||
    draft.endDate !== base.endDate ||
    draft.planningLevel !== base.planningLevel
  );
}

export type DraftError =
  | 'title-empty'
  | 'title-too-long'
  | 'range-reversed'
  | 'range-too-long';

/**
 * Client-side validation, limited to what the contract states.
 *
 * Deliberately does NOT try to predict the server's date-shrink rejection.
 * UpdateTripRequest says a shrink is refused "while any item or DATE/
 * RESERVATION lock lies outside the new range" — that depends on server state,
 * and a client that guesses at it either blocks a legal edit or promises one
 * the server will refuse. The 422 is surfaced instead (FR-TRP-05: no implicit
 * deletion).
 */
export function draftError(draft: TripDraft): DraftError | null {
  const title = draft.title.trim();
  if (title.length === 0) return 'title-empty';
  if (title.length > MAX_TITLE_LENGTH) return 'title-too-long';
  if (
    Date.parse(`${draft.endDate}T00:00:00Z`) < Date.parse(`${draft.startDate}T00:00:00Z`)
  ) {
    return 'range-reversed';
  }
  // CreateTripRequest caps a range at 30 days; the same ceiling applies here.
  if (tripLength(draft.startDate, draft.endDate).days > 30) return 'range-too-long';
  return null;
}

/**
 * The merge-patch body, carrying only what actually changed.
 *
 * A patch that resends every field would overwrite a concurrent edit to a field
 * this user never touched — the whole point of PATCH over PUT. Returns null
 * when nothing changed, because UpdateTripRequest is minProperties:1 and an
 * empty patch is a 400.
 */
export function toPatch(draft: TripDraft, trip: TripDetail): UpdateTripRequest | null {
  const patch: UpdateTripRequest = {};
  const title = draft.title.trim();
  if (title !== trip.title) patch.title = title;
  if (draft.startDate !== trip.startDate) patch.startDate = draft.startDate;
  if (draft.endDate !== trip.endDate) patch.endDate = draft.endDate;
  if (draft.planningLevel !== trip.planningLevel) {
    patch.planningLevel = draft.planningLevel;
  }
  return Object.keys(patch).length === 0 ? null : patch;
}

/**
 * Which form field a server field error belongs to.
 *
 * The contract's `field` is a server-side path, so this maps only the names
 * this form actually renders and returns null for anything else — an unmapped
 * error still has to be shown, just not attached to the wrong input.
 */
export function fieldToInput(field: string): keyof TripDraft | null {
  const known: Record<string, keyof TripDraft> = {
    title: 'title',
    startDate: 'startDate',
    endDate: 'endDate',
    planningLevel: 'planningLevel',
  };
  // Server paths may be dotted or prefixed ("trip.startDate", "/startDate").
  const leaf = field.split(/[./]/).filter(Boolean).pop() ?? '';
  return known[leaf] ?? null;
}

/** One item that would fall outside the draft's date range. */
export interface OutOfRangeItem {
  itemId: string;
  placeName: string;
  /** The item's own date. Null when only a lock's date is out of range. */
  date: string | null;
  /**
   * The locks on this item that the shrink rule names.
   *
   * Only DATE and RESERVATION appear: UpdateTripRequest's rule is about "any
   * item or DATE/RESERVATION lock", so MUST_VISIT and TIME do not change the
   * outcome and listing them would imply they do (invariant 7 — the four locks
   * are independent).
   */
  blockingLocks: ('DATE' | 'RESERVATION')[];
}

/**
 * True once both ends of the draft range are actually filled in.
 *
 * A `type="date"` input reads as "" while it is being typed or after it is
 * cleared, and string comparison puts "" before every real date — so an
 * unguarded range check treats a half-typed range as one that excludes
 * everything.
 */
function isCompleteRange(draft: TripDraft): boolean {
  return draft.startDate !== '' && draft.endDate !== '';
}

/** True while `date` sits outside the inclusive [start, end] range. */
function outsideRange(date: string, startDate: string, endDate: string): boolean {
  return date < startDate || date > endDate;
}

/**
 * The items a date-range change would leave outside the trip.
 *
 * Computed entirely from the trip already on screen — TripItem requires `date`
 * and `constraints`, so this needs no extra request.
 *
 * This reports; it does not decide. UpdateTripRequest states the rule exactly:
 * a shrink "is rejected with VALIDATION_FAILED while any item or DATE/
 * RESERVATION lock lies outside the new range". The client holds those inputs,
 * so the outcome is genuinely predictable — but the trip is a cached query and
 * may be stale, so the save stays enabled and the server remains the judge.
 * Blocking it here would turn a stale snapshot into a refusal to let the user
 * try (FR-TRP-05: the 422 is surfaced, never pre-empted).
 *
 * Dates compare as ISO strings, which sorts correctly for `format: date` and
 * avoids inventing a timezone the comparison does not need.
 */
export function outOfRangeItems(trip: TripDetail, draft: TripDraft): OutOfRangeItem[] {
  // A half-typed date input reports an empty value, and "" sorts before every
  // real date — so an unguarded compare marks the whole trip out of range
  // mid-keystroke. An incomplete range has nothing to say yet.
  if (!isCompleteRange(draft)) return [];
  const affected: OutOfRangeItem[] = [];
  for (const day of trip.days) {
    for (const item of day.items) {
      // The item's own date, not the day's: they agree today, but the item
      // carries the field the rule is written against.
      const itemOutside = outsideRange(item.date, draft.startDate, draft.endDate);

      // "any item OR DATE/RESERVATION lock lies outside" is two conditions, not
      // one. Both constraints carry their own `date`, and nothing in the
      // contract ties it to the item's, so a lock is checked on its own date.
      const blockingLocks = item.constraints
        .filter(
          (constraint) =>
            (constraint.type === 'DATE' || constraint.type === 'RESERVATION') &&
            outsideRange(constraint.date, draft.startDate, draft.endDate),
        )
        .map((constraint) => constraint.type as 'DATE' | 'RESERVATION');

      if (!itemOutside && blockingLocks.length === 0) continue;
      affected.push({
        itemId: item.id,
        placeName: item.place.name,
        date: itemOutside ? item.date : null,
        blockingLocks,
      });
    }
  }
  return affected;
}

/**
 * True when the range actually narrowed on either end.
 *
 * Growing a range strands nothing, so the preview stays silent for it rather
 * than listing items that are outside a range the user is widening.
 */
export function isShrink(draft: TripDraft, trip: TripDetail): boolean {
  if (!isCompleteRange(draft)) return false;
  return draft.startDate > trip.startDate || draft.endDate < trip.endDate;
}
