import type { RouteObject } from 'react-router';
import { AppLayout } from './AppLayout.js';
import { NotFoundScreen } from './NotFoundScreen.js';
import { PlaceholderScreen } from './PlaceholderScreen.js';

// Route table from docs/design/FIGMA_HANDOFF.md §2. Every P0 route exists so
// deep links and redirects can be exercised from FE-001 onward; the screens
// themselves arrive with their own slices, so each one renders an explicit
// "not built yet" placeholder instead of a blank page.
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <PlaceholderScreen routeId="splash" /> },
      { path: 'language', element: <PlaceholderScreen routeId="language" /> },
      { path: 'intro', element: <PlaceholderScreen routeId="intro" /> },
      { path: 'start', element: <PlaceholderScreen routeId="start" /> },
      { path: 'feed', element: <PlaceholderScreen routeId="feed" /> },
      { path: 'posts/:postId', element: <PlaceholderScreen routeId="post-detail" /> },
      { path: 'trip/:tripId', element: <PlaceholderScreen routeId="trip" /> },
      {
        path: 'trip/:tripId/optimizations/:runId',
        element: <PlaceholderScreen routeId="optimization" />,
      },
      { path: 'live', element: <PlaceholderScreen routeId="live" /> },
      { path: 'profile', element: <PlaceholderScreen routeId="profile" /> },
      { path: 'about-data', element: <PlaceholderScreen routeId="about-data" /> },
      // An unknown path renders an explicit not-found screen with a way back,
      // rather than redirecting. A silent redirect hides typos and breaks the
      // back button; a blank shell would violate the "no empty screen" rule.
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
];
