// The screens both E2E suites walk.
//
// One list, imported by responsive.spec.ts and location-off.spec.ts. Playwright
// refuses to let one spec import another, and two copies would drift — the
// screen that drifted out would be the one nobody checked.
//
// Data only. open-screen.ts reads it and does the visiting; this file stays
// free of Playwright and Node because route-parity.test.ts imports it into the
// app's own tsconfig program, which has neither.
import type { MessageKey } from '../src/i18n/messages.js';

/**
 * Whether the path is expected to resolve to its screen or to a not-found one.
 *
 * Omitted means `'rendered'`, which is 21 of the 22 entries (counted by the
 * `name:` lines); only the exception is written, the way NOT_WALKED in
 * route-parity.test.ts names only exemptions.
 *
 * This exists because a path cannot say it on its own. Two entries here carried
 * ids that resolved to nothing — `/posts/018f4c30-…` rendered "That post does
 * not exist" and an optimizations id rendered "No such optimization" — while
 * their names claimed the post and the run. Both were mistakes. The remaining
 * `'missing'` entry is not, and nothing in the path distinguishes the two
 * cases: `018f4d40-…` and `018f5b00-…` are the same shape. The author knows
 * which was meant; the check cannot infer it, so the author declares it.
 */
type Reach = 'rendered' | 'missing';

/**
 * How the composed gate reaches a trip-scoped screen. Every `/trip/…` entry says.
 *
 * The paths below carry the MSW fixture's trip id, and against the real API that id
 * is nobody's trip (seeded-trip.ts has why): the gate answers 404, so until this
 * field existed every trip-scoped entry measured "We can't find that trip" there —
 * at 360px, at 200% zoom, for the 44px floor and for a geolocation call — while
 * its name claimed the trip, the editor, the optimizer. Every one of those checks
 * passes on a not-found screen, so nothing went red. The mock run is unaffected
 * either way: MSW answers any trip id with its own trip.
 *
 *   - `own-trip`: the gate swaps the fixture id for a trip the test creates
 *     through the real API, the way sheet-responsive.spec.ts already does.
 *   - `approved-run`: the gate keeps optimization off (FEATURE_OPTIMIZATION_ITEM),
 *     so no real run can reach READY there. The approved READY example is served
 *     with page.route instead — the same example, with the same id, trip id and
 *     trip version overrides, that the MSW handler serves in the mock run — as
 *     optimization-keyboard.spec.ts and core-locale.spec.ts already do.
 */
export type GateReach = 'own-trip' | 'approved-run';

/**
 * Something only the reached screen draws: not its loading, error or not-found
 * state. A message key rather than a string, so it matches in both locales, and
 * `{…}` placeholders in the message match any value (open-screen.ts).
 *
 * Declared per entry rather than inferred for the reason `expect` is: the
 * not-found screen reflows, meets the 44px floor and asks for no location, so
 * nothing else in a walk can tell it apart from the screen the name claims.
 */
export interface Shows {
  role: 'heading' | 'button' | 'link' | 'textbox' | 'group' | 'text';
  key: MessageKey;
}

export interface Screen {
  path: string;
  name: string;
  expect?: Reach;
  gate?: GateReach;
  shows?: Shows;
}

export const SCREENS: ReadonlyArray<Screen> = [
  { path: '/', name: 'splash' },
  // The first screen a user sees, and the one with the most content per card.
  // It was absent from this list, so the feed card's controls were never
  // measured at 360px, at 200% zoom, or against the 44px touch floor.
  { path: '/feed', name: 'feed' },
  { path: '/posts/new', name: 'post authoring' },
  // Onboarding screens and the profile declare what only they draw, so the
  // IDs responsive.spec.ts puts on them (FE-101-T3/T5, FE-105-T3/T5) measure
  // the screen they name and not a redirect or a not-found page.
  {
    path: '/language',
    name: 'language',
    shows: { role: 'button', key: 'language.next' },
  },
  { path: '/intro', name: 'intro', shows: { role: 'button', key: 'intro.start' } },
  // A-4 sign-in (#265). Added in the same commit as the route, because this
  // list is the only thing that puts a screen in front of either spec and
  // nothing checks the two agree — measured on this very screen: with the
  // route added and this line missing, `tsc` exited 0 and all 1,059 unit tests
  // passed. route-parity.test.ts now closes that, and it was written here.
  { path: '/sign-in', name: 'sign in' },
  // Only the route: the profile's data sections share no drawn state between
  // the mock (a trip list) and the gate (a fresh session, no trips), and the
  // trip count's message is a bare `{count}` that would match any text. So a
  // loading or error frame of the trip list is not excluded here.
  { path: '/profile', name: 'profile', shows: { role: 'heading', key: 'profile.title' } },
  // `activeTripId` is nullable even when trips exist. The My Trip tab routes
  // that state here so the owner can choose the representative trip instead
  // of silently landing on the account screen.
  { path: '/trips/select', name: 'trip selection' },
  // The fixture trip id. The mock run answers it with its own trip, and the
  // gate swaps it for a trip the test creates (GateReach above). The comment
  // that stood here said the error state was what this measured and that it
  // "has to survive 360px and 200% zoom too" — true of the error state, and it
  // was the ONLY state the gate ever measured on these routes. The error state
  // keeps its own entry below: `optimization run (missing)`.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    name: 'trip',
    gate: 'own-trip',
    shows: { role: 'button', key: 'trip.editStart' },
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/edit',
    name: 'trip schedule edit',
    gate: 'own-trip',
    // Rendered only in edit mode; the read-only trip offers "Edit itinerary".
    shows: { role: 'link', key: 'trip.addPlace' },
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/settings',
    name: 'trip details settings',
    gate: 'own-trip',
    shows: { role: 'textbox', key: 'trip.field.title' },
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/candidates',
    name: 'saved places',
    gate: 'own-trip',
    // The heading carries the trip's count only once the trip has loaded; the
    // not-found state keeps the bare title.
    shows: { role: 'heading', key: 'candidates.open' },
  },
  { path: '/about-data', name: 'data guide' },
  // The remaining routes. routes.tsx defines 17 paths and this list held 8, so
  // half the app had never been measured at 360px, at 200% zoom, or against the
  // 44px touch floor — and, through location-off.spec.ts, had never been
  // checked for a geolocation call either. Every entry below is an implemented
  // screen, with one deliberate exception named "(missing)" below.
  //
  // One of them is a submission screenshot: `/trip/{id}/optimize` is #5. The
  // earlier "two of them" was wrong — SUBMISSION_RUNBOOK.md fixes the list at
  // five routes (/feed, /trip/{id}, /trip/{id}/candidates, /about-data,
  // /trip/{id}/optimize) and only that last one is below. `optimization run`
  // is the conditional #6 and the runbook excludes it until BA-051/BA-052
  // land, so changing its id here cannot change a submission image.
  { path: '/start', name: 'trip start' },
  // The paste screen (S02-4C-A, FE-104). It was the one route in routes.tsx
  // that this list still did not name — 16 of 17 were here — so the screen
  // with the app's largest text input, a review list and two blocking CTAs had
  // never been measured at 360px, at 200% zoom, or against the 44px floor.
  //
  // FE-104-T3 asks for exactly that, and the clause had no way to be proven
  // while the route was absent from the only spec that measures reflow.
  { path: '/start/import', name: 'paste import' },
  // The id is postFixtures.detail.id, which handlers.ts:328 answers with the
  // real post. It was an invented id until now, so this entry measured the
  // "That post does not exist" error screen while claiming to measure the post
  // — the reflow of a title, a body and a save control had never been seen.
  { path: '/posts/018f5b00-0000-7000-8000-000000000001', name: 'post detail' },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/add-place',
    name: 'add place',
    gate: 'own-trip',
    // Not the heading or the search box: this screen draws both whether or not
    // the trip resolved (AddPlaceScreen reads the trip only for its day chips).
    // A day chip exists only when the trip's days did.
    shows: { role: 'button', key: 'trip.day' },
  },
  // Submission screenshot #5.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimize',
    name: 'optimize setup',
    gate: 'own-trip',
    // The sheet's title is drawn in its loading and error states too; the
    // stop picker is drawn only for a trip that loaded.
    shows: { role: 'group', key: 'optimize.target' },
  },
  // MOCK_RUN_ID (handlers.ts:453). The id here was invented, so this entry
  // measured "No such optimization" while its name claimed the run screen.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f6a00-0000-7000-8000-000000000001',
    name: 'optimization run',
    gate: 'approved-run',
    // READY, not the QUEUED/RUNNING the mock passes through on its first two
    // reads: FE-503's IDs ride on this entry and name the READY preview.
    shows: { role: 'heading', key: 'run.title.ready' },
  },
  // The error state, kept deliberately rather than lost in the fix above.
  //
  // Replacing the invented id bought the run screen's reflow and would have
  // spent the error screen's, which was the only thing this list had ever
  // measured here. Both are worth measuring: a 404 still has to survive 360px,
  // 200% zoom and the 44px floor. The id is invented ON PURPOSE and the name
  // says so, because the next reader's first question about a 404 in a list of
  // "implemented screens" is whether it is a bug (#195).
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4d40-4e63-7a74-9c77-8d3f2e5a0c03',
    name: 'optimization run (missing)',
    expect: 'missing',
    // The trip resolves in both modes (the gate uses the test's own), so what
    // is missing is the run and nothing else.
    gate: 'own-trip',
    shows: { role: 'text', key: 'run.notFound' },
  },
  { path: '/live', name: 'live' },
  {
    path: '/live/places/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01',
    name: 'live place detail',
  },
];
