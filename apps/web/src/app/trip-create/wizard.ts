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
type PlaceSummary = components['schemas']['PlaceSummary'];

export const MAX_TRIP_DAYS = 30;
export const MAX_INTERESTS = 20;

/**
 * Upper bound on must-visit picks (step 4, S02-4B).
 *
 * PROVISIONAL, and the only number here the contract does not yet own.
 * CreateTripRequest has no field for a place without a date, so #180 is still
 * shaping one; this borrows `seedItems`' own `maxItems: 100` because that is
 * the cap the contract already puts on places carried into a new trip. When the
 * field lands, take its `maxItems` and delete this constant rather than keeping
 * a second answer to the same question.
 */
export const MAX_MUST_VISIT = 100;

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
  /**
   * Must-visit places from step 4, kept whole rather than as ids.
   *
   * The step renders each pick's name, thumbnail and attribution, so holding
   * ids would mean re-fetching what the search already returned. Only the ids
   * will go to the server once #180 gives them a field.
   */
  mustVisit: PlaceSummary[];
}

export const EMPTY_DRAFT: WizardDraft = {
  startDate: null,
  endDate: null,
  interests: [],
  planningLevel: null,
  mustVisit: [],
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

/**
 * Adds a must-visit pick, ignoring one already chosen.
 *
 * Duplicates are dropped rather than rejected loudly: the step disables a
 * result's 담기 button once it is picked, so a repeat can only arrive from two
 * results describing the same place.
 */
export function addMustVisit(draft: WizardDraft, place: PlaceSummary): WizardDraft {
  if (draft.mustVisit.some((p) => p.id === place.id)) return draft;
  if (draft.mustVisit.length >= MAX_MUST_VISIT) return draft;
  return { ...draft, mustVisit: [...draft.mustVisit, place] };
}

export function removeMustVisit(draft: WizardDraft, placeId: string): WizardDraft {
  return { ...draft, mustVisit: draft.mustVisit.filter((p) => p.id !== placeId) };
}

/**
 * What follows step 3, decided by the answer given there.
 *
 * The three cards of `438:3134` are a branch, not three ways of saying the same
 * thing — each one names what happens next, and the screen after it keeps that
 * promise:
 *
 *   NOTHING          아직 하나도 없어요      → create now, the server fills the days
 *   MUST_VISIT_ONLY  꼭 가고 싶은 곳만 정했어요 → step 4, S02-4B must-visit
 *   MOSTLY_PLANNED   거의 다 세우고 왔어요     → step 4, S02-4C input-method
 *
 * The first two used to be the same call, so answering "꼭 가고 싶은 곳만
 * 정했어요" created the trip without ever asking which places (#185).
 *
 * MOSTLY_PLANNED used to create the trip immediately too, for a stated reason:
 * routing to the standalone paste screen would DROP the dates and interests
 * this wizard had just collected, because ImportPasteScreen builds its own
 * draft from EMPTY_DRAFT. That reason is gone now that `400:1201` is a STEP of
 * this wizard rather than a separate route — the method choice keeps the draft
 * it is holding, and only the manual branch continues inside it.
 *
 * Answering 거의 다 세우고 왔어요 and being given a trip with empty days was
 * the screen contradicting its own question (FR-TRC-05: 수동/붙여넣기 분기).
 *
 * `null` while step 3 is unanswered, so a caller cannot act before the choice.
 */
export function nextAfterPlanning(
  draft: WizardDraft,
): 'create' | 'must-visit' | 'method' | null {
  switch (draft.planningLevel) {
    case 'MUST_VISIT_ONLY':
      return 'must-visit';
    case 'MOSTLY_PLANNED':
      return 'method';
    case 'NOTHING':
      return 'create';
    default:
      return null;
  }
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
 *
 * `draft.mustVisit` is deliberately NOT sent from HERE, and it never will be:
 * the owner settled #180 on 2026-09-13 with option B, where the intention
 * rides on the CANDIDATE rather than on the trip. `AddCandidateRequest` now
 * carries `mustVisit` (openapi.yaml, and `mustVisit?: boolean` in the
 * generated client) and `CreateTripRequest` deliberately does not — a place
 * with no date cannot be a `seedItem` (`date` is required) and cannot hold a
 * lock (`trip_constraints.trip_item_id` is NOT NULL), so the contract keeps it
 * an intention until scheduling turns it into a `MUST_VISIT` constraint.
 *
 * So the remaining work is NOT "one line here". It is: after `createTrip`
 * returns, POST each pick to `/trips/{tripId}/candidates` with
 * `mustVisit: true` — `useAddTripCandidate` already takes a per-call `tripId`
 * for exactly this caller. That wiring is still open because N+1 requests are
 * not one transaction (invariant 5): #185 asks what the screen should do when
 * the trip is created and only some of the candidates land, and that question
 * has no answer yet. Until it does, the picks stay in the draft so the step can
 * show them and so going back keeps them, and the two exits say which one the
 * traveller took (#185).
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
