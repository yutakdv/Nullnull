import type { components } from '@nullnull/api-client';

// Wizard draft state for S02-1/2/3 (FR-TRC-01/02/03).
//
// Steps 1-3 are a local draft: FIGMA_HANDOFF marks them "local draft" and the
// only server call in this flow is createTrip at the end. Keeping the rules here
// rather than in the screen means they can be tested without rendering, and the
// screen cannot quietly disagree with them.
//
// The limits are the contract's, not invented: CreateTripRequest caps the range
// at 30 calendar days and interests at 20 unique entries.

type PlanningLevel = components['schemas']['PlanningLevel'];
type TripInterest = components['schemas']['TripInterest'];
type CreateTripRequest = components['schemas']['CreateTripRequest'];

export const MAX_TRIP_DAYS = 30;
export const MAX_INTERESTS = 20;

/** Interest codes, grouped as the Figma screen groups them (438:3108). */
export const INTEREST_GROUPS = [
  {
    id: 'who',
    codes: ['ALONE', 'FRIENDS', 'PARTNER', 'SPOUSE', 'KIDS', 'PARENTS'],
  },
  {
    id: 'style',
    codes: [
      'LANDMARKS',
      'RELAXED',
      'CULTURE',
      'NATURE',
      'FOOD',
      'LOCAL_VIBE',
      'ACTIVITY',
    ],
  },
] as const;

export interface WizardDraft {
  startDate: string | null;
  endDate: string | null;
  interests: string[];
  planningLevel: PlanningLevel | null;
}

export const EMPTY_DRAFT: WizardDraft = {
  startDate: null,
  endDate: null,
  interests: [],
  planningLevel: null,
};

/** Inclusive day count, or null when the range is incomplete. */
export function rangeLength(start: string | null, end: string | null): number | null {
  if (!start || !end) return null;
  const from = Date.parse(`${start}T00:00:00Z`);
  const to = Date.parse(`${end}T00:00:00Z`);
  if (Number.isNaN(from) || Number.isNaN(to)) return null;
  return Math.round((to - from) / 86_400_000) + 1;
}

/**
 * Applies a tapped day to the range.
 *
 * A second tap earlier than the first restarts the selection rather than
 * producing an inverted range, so the user cannot reach a state the server
 * would reject.
 */
export function selectDay(draft: WizardDraft, day: string): WizardDraft {
  const { startDate, endDate } = draft;
  if (!startDate || endDate || day < startDate) {
    return { ...draft, startDate: day, endDate: null };
  }
  return { ...draft, endDate: day };
}

export function toggleInterest(draft: WizardDraft, code: string): WizardDraft {
  const has = draft.interests.includes(code);
  if (has) {
    return { ...draft, interests: draft.interests.filter((c) => c !== code) };
  }
  // uniqueItems and maxItems: 20 in the contract. Silently ignoring the 21st
  // is wrong; the screen disables further chips instead (see canAddInterest).
  if (draft.interests.length >= MAX_INTERESTS) return draft;
  return { ...draft, interests: [...draft.interests, code] };
}

export function canAddInterest(draft: WizardDraft): boolean {
  return draft.interests.length < MAX_INTERESTS;
}

/** Why step 1 cannot continue, or null when it can. */
export function dateError(draft: WizardDraft): 'incomplete' | 'tooLong' | null {
  const length = rangeLength(draft.startDate, draft.endDate);
  if (length === null) return 'incomplete';
  if (length > MAX_TRIP_DAYS) return 'tooLong';
  return null;
}

/**
 * Builds the create request.
 *
 * Interests carry weight 3 — the contract's 1..5 midpoint — because this screen
 * collects membership, not strength. A weight the user never expressed must not
 * be dressed up as a preference, so every chip gets the same neutral value.
 *
 * Returns null when the draft is incomplete, so a caller cannot submit a
 * half-filled trip.
 */
export function toCreateRequest(
  draft: WizardDraft,
  timezone: string,
): CreateTripRequest | null {
  if (dateError(draft) !== null) return null;
  if (!draft.startDate || !draft.endDate || !draft.planningLevel) return null;

  const interests: TripInterest[] = draft.interests.map((code) => ({
    code,
    weight: 3,
  }));

  return {
    startDate: draft.startDate,
    endDate: draft.endDate,
    timezone,
    planningLevel: draft.planningLevel,
    interests,
  };
}
