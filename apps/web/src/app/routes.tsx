import { Outlet, type RouteObject } from 'react-router';
import { NotFoundScreen } from './NotFoundScreen.js';
import { PlaceholderScreen } from './PlaceholderScreen.js';

// P0 route table from docs/design/FIGMA_HANDOFF.md §2. Screens arrive with
// their own slices; until then each route renders a labelled placeholder and
// an unknown path renders an explicit not-found screen. Nothing paints blank.
export const routes: RouteObject[] = [
  {
    path: '/',
    element: (
      <main id="main">
        <Outlet />
      </main>
    ),
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
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
];
