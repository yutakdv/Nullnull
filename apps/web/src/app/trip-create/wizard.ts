import type { components } from '@nullnull/api-client';

// Wizard draft state for S02-1/2/3 (FR-TRC-01/02/03).
//
// Steps 1-3 are a local draft: FIGMA_HANDOFF marks them "local draft". The
// NOTHING branch requests a read-only preview before createTrip; the other
// branches remain local until createTrip. Keeping the rules here rather than in
// the screen means they can be tested without rendering, and the screen cannot
// quietly disagree with them.
//
// The limits are the contract's, not invented: CreateTripRequest caps the range
// at 30 calendar days and interests at 20 unique entries.

type PlanningLevel = components['schemas']['PlanningLevel'];
type TripInterest = components['schemas']['TripInterest'];
type CreateTripRequest = components['schemas']['CreateTripRequest'];
type PlaceSummary = components['schemas']['PlaceSummary'];
type SeedTripItem = components['schemas']['SeedTripItem'];

export const MAX_TRIP_DAYS = 30;
export const MAX_INTERESTS = 20;

/**
 * Upper bound on must-visit picks (step 4, S02-4B).
 *
 * PROVISIONAL, and the only number here the contract does not own. #180
 * settled the picks onto the candidate: each one is its own `addTripCandidate`
 * after createTrip (#185), and a request that carries one place has no count to
 * cap. This borrows `seedItems`' own `maxItems: 100` because that is the cap
 * the contract already puts on places carried into a new trip.
 */
export const MAX_MUST_VISIT = 100;

/**
 * Upper bound on manually entered stops (step 5, S02-4C-C).
 *
 * The contract's own number this time: `CreateTripRequest.seedItems` is
 * `maxItems: 100`, and every stop on this screen becomes one seed item.
 */
export const MAX_SEED_ITEMS = 100;

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

/**
 * Which half of the day a stop sits in — `오전 ▾` / `오후 ▾` on the card.
 *
 * DISPLAY AND ORDER ONLY. It never becomes a `startTime`, and the next person
 * to read this will be tempted to map it to one: see `seedItemsOf`.
 */
export type Daypart = 'MORNING' | 'AFTERNOON';

/** One manually entered stop, before it has an id or a server row. */
export interface DraftStop {
  /** Identity within the draft, so reordering and removal need no index math. */
  key: string;
  place: PlaceSummary;
  /** ISO date, always set: this screen only adds a stop UNDER a day header. */
  date: string;
  daypart: Daypart;
  /**
   * Picked as a must-visit on the confirm step (S02-5C `438:3259`).
   *
   * Becomes a `MUST_VISIT` constraint on this stop's seed item. Unlike
   * `draft.mustVisit`, which holds dateless places #180 settled onto the
   * candidate, this one CAN be a lock: the stop already has a date, so it is a
   * `seedItem`, and `trip_constraints.trip_item_id` has a row to point at.
   */
  mustVisit: boolean;
}

export interface WizardDraft {
  startDate: string | null;
  endDate: string | null;
  interests: string[];
  planningLevel: PlanningLevel | null;
  /**
   * Manually entered stops from step 5 (S02-4C-C `438:3199`), in the order the
   * screen shows them. Flat rather than grouped by date because the day groups
   * are derived from the trip's own range (`tripDays`) — a stop cannot sit on a
   * day the trip does not have, and a second copy of the day list could
   * disagree with the range chosen in step 1.
   */
  stops: DraftStop[];
  /**
   * Must-visit places from step 4, kept whole rather than as ids.
   *
   * The step renders each pick's name, thumbnail and attribution, so holding
   * ids would mean re-fetching what the search already returned. Only the ids
   * go to the server, one `addTripCandidate` each once the trip exists (#185).
   */
  mustVisit: PlaceSummary[];
}

export const EMPTY_DRAFT: WizardDraft = {
  startDate: null,
  endDate: null,
  interests: [],
  planningLevel: null,
  mustVisit: [],
  stops: [],
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
 * The trip's days, as ISO dates, one per `day N` header on S02-4C-C.
 *
 * Derived from the range rather than stored, so it cannot drift from step 1.
 * Empty while the range is incomplete or invalid, which is the same answer
 * `dateError` gives — the manual step is unreachable in that state anyway.
 *
 * Built by stepping a UTC instant one day at a time: `new Date(y, m, d + 1)` in
 * local time would skip or repeat a day in a zone with DST, and these are
 * calendar dates, not instants.
 */
export function tripDays(draft: WizardDraft): string[] {
  const length = rangeLength(draft.startDate, draft.endDate);
  if (length === null || length > MAX_TRIP_DAYS || !draft.startDate) return [];
  const start = Date.parse(`${draft.startDate}T00:00:00Z`);
  return Array.from({ length }, (_, index) =>
    new Date(start + index * 86_400_000).toISOString().slice(0, 10),
  );
}

/**
 * Adds a stop to one day.
 *
 * The same place may appear on two days, and even twice on one day — a
 * traveller can pass through a station in the morning and again at night, and
 * the contract allows it (`seedItems` has no uniqueness rule). So there is no
 * duplicate check here, unlike `addMustVisit` where a repeat is meaningless.
 *
 * The cap is the contract's `maxItems: 100`; the screen disables 장소 추가 at
 * the limit rather than dropping the 101st silently.
 */
export function addStop(
  draft: WizardDraft,
  date: string,
  place: PlaceSummary,
  key: string,
  daypart: Daypart = 'MORNING',
): WizardDraft {
  if (draft.stops.length >= MAX_SEED_ITEMS) return draft;
  if (!tripDays(draft).includes(date)) return draft;
  // Not picked by default: the confirm step asks, and a pick the traveller
  // never made must not arrive pre-made.
  return {
    ...draft,
    stops: [...draft.stops, { key, place, date, daypart, mustVisit: false }],
  };
}

export function removeStop(draft: WizardDraft, key: string): WizardDraft {
  return { ...draft, stops: draft.stops.filter((stop) => stop.key !== key) };
}

/** Switches one stop between 오전 and 오후, leaving its position alone. */
export function setStopDaypart(
  draft: WizardDraft,
  key: string,
  daypart: Daypart,
): WizardDraft {
  return {
    ...draft,
    stops: draft.stops.map((stop) => (stop.key === key ? { ...stop, daypart } : stop)),
  };
}

/**
 * Turns one stop's must-visit pick on or off (S02-5C `438:3259`).
 *
 * No cap and no cross-stop rule: MUST_VISIT is one lock per ITEM, and the four
 * lock types are independent (invariant 7). Picking every stop is a valid
 * answer, and so is picking none.
 */
export function toggleStopMustVisit(draft: WizardDraft, key: string): WizardDraft {
  return {
    ...draft,
    stops: draft.stops.map((stop) =>
      stop.key === key ? { ...stop, mustVisit: !stop.mustVisit } : stop,
    ),
  };
}

/** The stops of one day, in the order they will be sent. */
export function stopsOn(draft: WizardDraft, date: string): DraftStop[] {
  return draft.stops.filter((stop) => stop.date === date);
}

export function canAddStop(draft: WizardDraft): boolean {
  return draft.stops.length < MAX_SEED_ITEMS;
}

/**
 * The stops as `seedItems`, ordered within each day.
 *
 * `startTime` IS ALWAYS NULL, AND THAT IS THE DECISION, NOT AN OMISSION.
 *
 * The card offers 오전 and 오후 and nothing finer, so the traveller never names
 * a time. Turning 오전 into `09:00:00` would put a number in the itinerary that
 * nobody chose and that the trip screen would then display back as fact — the
 * server stores what it is sent. This repository already refuses that exact
 * inference one layer down: `ItineraryParser` reads `오후 3시` as 15:00 but
 * classifies a bare `3시` as AMBIGUOUS_TIME and makes it a question for the
 * traveller, because an hour without a meridiem is two moments. A meridiem
 * without an hour is twelve, so it is strictly less to go on, and inventing one
 * is what invariant 9 forbids.
 *
 * Order survives instead, which is what the screen actually collected: the
 * stops of a day are numbered 1, 2, 3 down the spine, and `position` carries
 * exactly that. `TripItemCard` renders a null `startTime` as 시간 미정.
 *
 * The daypart is therefore lost on submit, and that is honest — it is a sorting
 * aid, and the sorted order is what goes. If it must survive as data, the field
 * for it is a real time the traveller entered, which needs a Figma change
 * request first (the frame draws no time input).
 *
 * `position` restarts at 0 for each day because the server scopes it per day:
 * `TripScheduleRules.requireDistinctPositions` buckets by date and rejects only
 * a repeat within one date. Gaps are legal there — position is an ordinal, not
 * an index — but consecutive from 0 is what the spine's 1, 2, 3 means.
 */
export function seedItemsOf(draft: WizardDraft): SeedTripItem[] {
  return tripDays(draft).flatMap((date) =>
    stopsOn(draft, date).map((stop, position) => ({
      placeId: stop.place.id,
      date,
      position,
      startTime: null,
      // Omitted when nothing was picked, for the reason toCreateRequest omits
      // an empty seedItems: an empty array is a statement that this stop
      // carries constraints, and it carries none.
      //
      // `locked: true` is the contract's `const` for this variant, not a
      // choice — SetMustVisitConstraintInput admits no other value. An unset
      // pick sends NO constraint rather than `locked: false`, because the four
      // locks are independent and never auto-released (invariant 7): absence is
      // how "not locked" is said.
      ...(stop.mustVisit
        ? { constraints: [{ type: 'MUST_VISIT' as const, locked: true as const }] }
        : {}),
    })),
  );
}

/**
 * What follows step 3, decided by the answer given there.
 *
 * The three cards of `438:3134` are a branch, not three ways of saying the same
 * thing — each one names what happens next, and the screen after it keeps that
 * promise:
 *
 *   NOTHING          아직 하나도 없어요      → read-only recommendation preview
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
): 'recommend' | 'must-visit' | 'method' | null {
  switch (draft.planningLevel) {
    case 'MUST_VISIT_ONLY':
      return 'must-visit';
    case 'MOSTLY_PLANNED':
      return 'method';
    case 'NOTHING':
      return 'recommend';
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
 * They are sent AFTER `createTrip` returns instead: TripWizardScreen POSTs each
 * pick to `/trips/{tripId}/candidates` with `mustVisit: true`, naming the new
 * trip per call (#185). Those N+1 requests are not one transaction (invariant
 * 5), so when the trip is created and only some picks land, the must-visit step
 * names the ones that did not and retries exactly those.
 *
 * `draft.stops` IS sent, and the difference from `mustVisit` is the date. The
 * paragraph above turns on one fact — a place with no date cannot be a
 * `seedItem`, because `date` is required — and every stop here was added under
 * a `day N` header, so it has one. That makes it a seed item, sent inside the
 * same `createTrip` call rather than as N follow-up requests, so #185's
 * partial-failure question does not arise: one command, one transaction
 * (invariant 5).
 *
 * Only one of the two can be non-empty in practice — `mustVisit` is collected
 * on the MUST_VISIT_ONLY branch and `stops` on MOSTLY_PLANNED — but this
 * function does not enforce that, because a draft carrying both is a bug in the
 * branching, not something to paper over here.
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

  const seedItems = seedItemsOf(draft);

  return {
    startDate: draft.startDate,
    endDate: draft.endDate,
    timezone,
    planningLevel: draft.planningLevel,
    interests,
    // Omitted rather than sent empty: the server branches on
    // `!command.seedItems().isEmpty()` to decide whether the catalog
    // publication gate applies, and an empty array and an absent field mean the
    // same thing to it. Sending `[]` would be a claim that this trip carries
    // seeded places.
    ...(seedItems.length > 0 ? { seedItems } : {}),
  };
}
