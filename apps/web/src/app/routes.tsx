import { Outlet, type RouteObject } from 'react-router';
import { NotFoundScreen } from './NotFoundScreen.js';
import { PlaceholderScreen } from './PlaceholderScreen.js';
import { RouteErrorBoundary } from './RouteErrorBoundary.js';
import { IntroScreen } from './onboarding/IntroScreen.js';
import { LanguageScreen } from './onboarding/LanguageScreen.js';
import { SplashScreen } from './onboarding/SplashScreen.js';
import { DataGuideScreen } from './data-guide/DataGuideScreen.js';
import { TripScreen } from './trip/TripScreen.js';
import { ProfileScreen } from './profile/ProfileScreen.js';
import { MustVisitScreen } from './trip-create/MustVisitScreen.js';
import { TripWizardScreen } from './trip-create/TripWizardScreen.js';

// P0 route table from docs/design/FIGMA_HANDOFF.md §2. Screens arrive with
// their own slices; until then each route renders a labelled placeholder and
// an unknown path renders an explicit not-found screen. Nothing paints blank.
//
// errorElement sits on the layout route so a screen that throws is replaced by
// the boundary while the shell around it stays mounted (FE-003).
export const routes: RouteObject[] = [
  {
    path: '/',
    element: (
      <main id="main">
        <Outlet />
      </main>
    ),
    errorElement: (
      <main id="main">
        <RouteErrorBoundary />
      </main>
    ),
    children: [
      { index: true, element: <SplashScreen /> },
      { path: 'language', element: <LanguageScreen /> },
      { path: 'intro', element: <IntroScreen /> },
      // S02 wizard. Steps 1-3 are the local draft (FE-102); step 4 collects
      // must-visit places (FE-103) and is a nested step of the same flow, not a
      // separate entry point. Wiring them into one flow is FE-102's follow-up
      // once step 4 knows the draft it belongs to.
      { path: 'start', element: <TripWizardScreen /> },
      { path: 'start/must-visit', element: <MustVisitScreen /> },
      { path: 'feed', element: <PlaceholderScreen routeId="feed" /> },
      { path: 'posts/:postId', element: <PlaceholderScreen routeId="post-detail" /> },
      { path: 'trip/:tripId', element: <TripScreen /> },
      {
        path: 'trip/:tripId/optimizations/:runId',
        element: <PlaceholderScreen routeId="optimization" />,
      },
      { path: 'live', element: <PlaceholderScreen routeId="live" /> },
      { path: 'profile', element: <ProfileScreen /> },
      { path: 'about-data', element: <DataGuideScreen /> },
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
];
