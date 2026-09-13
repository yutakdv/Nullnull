// The screens both E2E suites walk.
//
// One list, imported by responsive.spec.ts and location-off.spec.ts. Playwright
// refuses to let one spec import another, and two copies would drift — the
// screen that drifted out would be the one nobody checked.
export const SCREENS = [
  { path: '/', name: 'splash' },
  // The first screen a user sees, and the one with the most content per card.
  // It was absent from this list, so the feed card's controls were never
  // measured at 360px, at 200% zoom, or against the 44px touch floor.
  { path: '/feed', name: 'feed' },
  { path: '/language', name: 'language' },
  { path: '/intro', name: 'intro' },
  { path: '/profile', name: 'profile' },
  // A real trip id shape, though the built app has no API behind it yet: what
  // this measures is the reflow of whichever state the screen reaches, and the
  // error state has to survive 360px and 200% zoom too.
  { path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01', name: 'trip' },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/candidates',
    name: 'saved places',
  },
  { path: '/about-data', name: 'data guide' },
];
