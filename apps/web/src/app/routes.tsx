import { type RouteObject } from 'react-router';
import { AppShell } from './AppShell.js';
import { NotFoundScreen } from './NotFoundScreen.js';
import { FeedScreen } from './feed/FeedScreen.js';
import { PostScreen } from './post/PostScreen.js';
import { OptimizeSetupScreen } from './optimize/OptimizeSetupScreen.js';
import { OptimizationRunScreen } from './optimize/OptimizationRunScreen.js';
import { RouteErrorBoundary } from './RouteErrorBoundary.js';
import { IntroScreen } from './onboarding/IntroScreen.js';
import { LanguageScreen } from './onboarding/LanguageScreen.js';
import { SplashScreen } from './onboarding/SplashScreen.js';
import { DataGuideScreen } from './data-guide/DataGuideScreen.js';
import { LiveScreen } from './live/LiveScreen.js';
import { AddPlaceScreen } from './trip/AddPlaceScreen.js';
import { CandidatesScreen } from './trip/CandidatesScreen.js';
import { TripScreen } from './trip/TripScreen.js';
import { ProfileScreen } from './profile/ProfileScreen.js';
import { ImportPasteScreen } from './trip-create/ImportPasteScreen.js';
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
      // S02 wizard, one route for all four steps. Steps 1-3 are the local
      // draft (FE-102) and step 4 collects must-visit places (FE-103); they are
      // held as component state in TripWizardScreen, so the draft survives
      // moving between them.
      //
      // Step 4 had its own `start/must-visit` route until #185. Nothing linked
      // to it, so it was reachable only by typing the URL, and the picks it
      // collected could not reach the draft the other steps built. Removing it
      // is what let step 3's answer branch to it (FIGMA_HANDOFF §2 lists one
      // path for trip creation, and never gave step 4 one).
      //
      // No tab bar either: the draft is unsaved, so a stray tap discards it.
      { path: 'start', element: <TripWizardScreen /> },
      // S02-4C-A paste `401:1221` (FE-104), reached from step 3 rather than by
      // URL — MOSTLY_PLANNED routes here, and the CTA offers it directly.
      { path: 'start/import', element: <ImportPasteScreen /> },
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
      // S11 live, a 준비 중 screen until BA-091 opens the data (FE-401).
      { path: 'live', element: <LiveScreen /> },
      { path: 'profile', element: <ProfileScreen /> },
    ],
  },
];
