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
  // The remaining routes. routes.tsx defines 17 paths and this list held 8, so
  // half the app had never been measured at 360px, at 200% zoom, or against the
  // 44px touch floor — and, through location-off.spec.ts, had never been
  // checked for a geolocation call either. Every entry below is an implemented
  // screen, not a placeholder, and two of them are submission screenshots.
  { path: '/start', name: 'trip start' },
  { path: '/posts/018f4c30-3d52-7f63-8b66-7c2e1d4f9b02', name: 'post detail' },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/add-place',
    name: 'add place',
  },
  // Submission screenshot #5.
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimize',
    name: 'optimize setup',
  },
  {
    path: '/trip/018f4a10-2c31-7d42-9a55-6b1f0c3e8a01/optimizations/018f4d40-4e63-7a74-9c77-8d3f2e5a0c03',
    name: 'optimization run',
  },
  { path: '/live', name: 'live' },
];
