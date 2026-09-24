// The screens both E2E suites walk.
//
// One list, imported by responsive.spec.ts and location-off.spec.ts. Playwright
// refuses to let one spec import another, and two copies would drift — the
// screen that drifted out would be the one nobody checked.

/**
 * Whether the path is expected to resolve to its screen or to a not-found one.
 *
 * Omitted means `'rendered'`, which is 16 of the 17 entries; only the exception
 * is written, the way NOT_WALKED in route-parity.test.ts names only exemptions.
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

export const SCREENS: ReadonlyArray<{
  path: string;
  name: string;
  expect?: Reach;
}> = [
  { path: '/', name: 'splash' },
  // The first screen a user sees, and the one with the most content per card.
  // It was absent from this list, so the feed card's controls were never
  // measured at 360px, at 200% zoom, or against the 44px touch floor.
  { path: '/feed', name: 'feed' },
  { path: '/posts/new', name: 'post authoring' },
  { path: '/language', name: 'language' },
  { path: '/intro', name: 'intro' },
  // A-4 sign-in (#265). Added in the same commit as the route, because this
  // list is the only thing that puts a screen in front of either spec and
  // nothing checks the two agree — measured on this very screen: with the
  // route added and this line missing, `tsc` exited 0 and all 1,059 unit tests
  // passed. route-parity.test.ts now closes that, and it was written here.
  { path: '/sign-in', name: 'sign in' },
  { path: '/profile', name: 'profile' },
  // `activeTripId` is nullable even when trips exist. The My Trip tab routes
  // that state here so the owner can choose the representative trip instead
  // of silently landing on the account screen.
  { path: '/trips/select', name: 'trip selection' },
  // A real trip id shape, though the built app has no API behind it yet: what
  // this measures is the reflow of whichever state the screen reaches, and the
  // error state has to survive 360px and 200% zoom too.
  { path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01', name: 'trip' },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/edit',
    name: 'trip schedule edit',
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/settings',
    name: 'trip details settings',
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/candidates',
    name: 'saved places',
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
  },
  // Submission screenshot #5.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimize',
    name: 'optimize setup',
  },
  // MOCK_RUN_ID (handlers.ts:360). The id here was invented, so this entry
  // measured "No such optimization" while its name claimed the run screen.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f6a00-0000-7000-8000-000000000001',
    name: 'optimization run',
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
  },
  { path: '/live', name: 'live' },
  {
    path: '/live/places/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01',
    name: 'live place detail',
  },
];
