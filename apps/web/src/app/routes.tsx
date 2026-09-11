import { type RouteObject } from 'react-router';
import { AppShell } from './AppShell.js';
import { NotFoundScreen } from './NotFoundScreen.js';
import { FeedScreen } from './feed/FeedScreen.js';
import { PostScreen } from './post/PostScreen.js';
import { OptimizeSetupScreen } from './optimize/OptimizeSetupScreen.js';
import { OptimizationRunScreen } from './optimize/OptimizationRunScreen.js';
import { PlaceholderScreen } from './PlaceholderScreen.js';
import { RouteErrorBoundary } from './RouteErrorBoundary.js';
import { IntroScreen } from './onboarding/IntroScreen.js';
import { LanguageScreen } from './onboarding/LanguageScreen.js';
import { SplashScreen } from './onboarding/SplashScreen.js';
import { DataGuideScreen } from './data-guide/DataGuideScreen.js';
import { AddPlaceScreen } from './trip/AddPlaceScreen.js';
import { CandidatesScreen } from './trip/CandidatesScreen.js';
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
    element: <AppShell />,
    errorElement: (
      <main id="main">
        <RouteErrorBoundary />
      </main>
    ),
    children: [
      // Onboarding: no tab bar. The user is inside a flow, and a tab press here
      // would abandon it before a session even exists.
      { index: true, element: <SplashScreen /> },
      { path: 'language', element: <LanguageScreen /> },
      { path: 'intro', element: <IntroScreen /> },
      // S02 wizard. Steps 1-3 are the local draft (FE-102); step 4 collects
      // must-visit places (FE-103) and is a nested step of the same flow, not a
      // separate entry point. Wiring them into one flow is FE-102's follow-up
      // once step 4 knows the draft it belongs to.
      //
      // No tab bar either: the draft is unsaved, so a stray tap discards it.
      { path: 'start', element: <TripWizardScreen /> },
      { path: 'start/must-visit', element: <MustVisitScreen /> },
      // Sub-pages reached by a back control, so they carry a NavBar instead.
      { path: 'posts/:postId', element: <PostScreen /> },
      { path: 'trip/:tripId/candidates', element: <CandidatesScreen /> },
      { path: 'trip/:tripId/add-place', element: <AddPlaceScreen /> },
      {
        // S09-0 setup, before a run exists (FE-501).
        path: 'trip/:tripId/optimize',
        element: <OptimizeSetupScreen />,
      },
      {
        // S09-1 run state, after submit and before a decision (FE-502).
        path: 'trip/:tripId/optimizations/:runId',
        element: <OptimizationRunScreen />,
      },
      { path: 'about-data', element: <DataGuideScreen /> },
      { path: '*', element: <NotFoundScreen /> },
    ],
  },
  {
    // Tab destinations. Same shell, with the bar (S03, S07-1, S11, S14).
    path: '/',
    element: <AppShell tabs />,
    errorElement: (
      <main id="main">
        <RouteErrorBoundary />
      </main>
    ),
    children: [
      { path: 'feed', element: <FeedScreen /> },
      { path: 'trip/:tripId', element: <TripScreen /> },
      { path: 'live', element: <PlaceholderScreen routeId="live" /> },
      { path: 'profile', element: <ProfileScreen /> },
    ],
  },
];
