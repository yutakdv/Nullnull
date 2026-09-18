// @vitest-environment happy-dom
//
// The app chrome, per route.
//
// This exists because its absence shipped: TabBar was built in FE-002, wired to
// nothing, and every screen went out with no way to reach another tab. No test
// failed, because no test asked. The screens' own tests check their content and
// would pass just as happily inside a bare <div>.
//
// The split is the Figma frames' own. A tab destination carries the bar; a
// screen the user is *inside* — onboarding, the unsaved wizard draft, a
// sub-page reached by a back control — does not, because a stray tab press
// there abandons work in progress.
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { sessionFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { createQueryClient, sessionQueryKey } from '../../shared/api/index.js';
import { routes } from '../routes.js';

const trip = tripFixtures.detailScheduled;
// The suite's default locale. Labels come from the table rather than being
// typed here, so a copy change cannot leave this test asserting old wording.
const copy = messages['en-US'];

/**
 * Renders a route, optionally with the session cache already populated.
 *
 * The client is injectable because the 내 여행 tab resolves through the owner
 * profile that `useSessionBootstrap` caches, and a test that cannot seed that
 * entry cannot tell the tab's two destinations apart.
 */
function renderAt(path: string, seed?: (client: QueryClient) => void) {
  const client = createQueryClient();
  seed?.(client);
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={client}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Seeds the bootstrap entry AppShell observes, with the given active trip. */
function withActiveTrip(activeTripId: string | null) {
  return (client: QueryClient) => {
    client.setQueryData(sessionQueryKey, {
      ...sessionFixtures.bootstrap,
      owner: { ...sessionFixtures.owner, activeTripId },
    });
  };
}

const TAB_BAR = { name: copy['nav.tabs'] };

/** Routes the Figma frames draw with the four-tab bar. */
const WITH_TABS = [
  ['/feed', 'feed'],
  [`/trip/${trip.id}`, 'trip view'],
  ['/live', 'live'],
  ['/profile', 'profile'],
] as const;

/** Routes drawn without it, and why a tab press there would be wrong. */
const WITHOUT_TABS = [
  ['/', 'splash: no session exists yet'],
  ['/language', 'onboarding step'],
  ['/intro', 'onboarding step'],
  ['/start', 'wizard draft is unsaved'],
  [`/trip/${trip.id}/candidates`, 'sub-page with a back control'],
  ['/about-data', 'sub-page with a back control'],
] as const;

describe('tab destinations carry the tab bar', () => {
  it.each(WITH_TABS)('%s (%s) shows it', async (path) => {
    renderAt(path);
    expect(await screen.findByRole('navigation', TAB_BAR)).toBeInTheDocument();
  });

  it('offers all four P0 tabs, and not the P1 search tab', async () => {
    renderAt('/profile');
    const bar = await screen.findByRole('navigation', TAB_BAR);
    const labels = [...bar.querySelectorAll('button')].map((b) => b.textContent);
    expect(labels).toEqual([
      copy['nav.tab.home'],
      copy['nav.tab.trip'],
      copy['nav.tab.live'],
      copy['nav.tab.profile'],
    ]);
  });

  it('marks the current tab for a screen reader, not by colour alone', async () => {
    renderAt('/profile');
    const bar = await screen.findByRole('navigation', TAB_BAR);
    const current = [...bar.querySelectorAll('button')].filter(
      (b) => b.getAttribute('aria-current') === 'page',
    );
    expect(current).toHaveLength(1);
    expect(current[0]?.textContent).toBe(copy['nav.tab.profile']);
  });

  it('navigates when a tab is pressed', async () => {
    const user = userEvent.setup();
    renderAt(`/trip/${trip.id}`);
    await screen.findByRole('navigation', TAB_BAR);
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));
    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: copy['profile.title'] }),
      ).toBeInTheDocument();
    });
  });
});

describe('flows the user is inside carry no tab bar', () => {
  it.each(WITHOUT_TABS)('%s (%s) hides it', async (path) => {
    renderAt(path);
    // Waits for the screen itself, so this is not passing simply because
    // nothing has rendered yet.
    await waitFor(() => {
      expect(document.querySelector('main')?.textContent).not.toBe('');
    });
    expect(screen.queryByRole('navigation', TAB_BAR)).not.toBeInTheDocument();
  });
});

describe('sub-pages offer a way back', () => {
  it.each([
    [`/trip/${trip.id}/candidates`, copy['candidates.back']],
    ['/about-data', copy['nav.back']],
  ])('%s has a named back control', async (path, label) => {
    renderAt(path);
    // A button, not a link: it goes to a named destination rather than
    // history.go(-1), which on a deep link leaves this app (C49).
    expect(await screen.findByRole('button', { name: label })).toBeInTheDocument();
  });
});

describe('the 내 여행 tab resolves to the owner active trip (BA-011)', () => {
  // The tab has no URL of its own: `activeTripId` on the owner profile decides,
  // and the create wizard PATCHes it. Before this was wired the tab always went
  // to /profile — which reads as "my trip shows nothing", because the heading
  // says 내 정보, the trip list sits below the profile block, and the tab that
  // lights up is the one the traveller did NOT press.

  it('opens the active trip when the owner has one', async () => {
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(trip.id));
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    // The destination is asserted by what RENDERS, not by window.location:
    // createMemoryRouter keeps its own history and never touches the real URL,
    // so a pathname check here passes '/' forever and proves nothing.
    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: trip.title ?? '' }),
      ).toBeInTheDocument();
    });
  });

  it('falls back to the trip list when there is no active trip', async () => {
    // A real state, not a stopgap: a traveller who has created nothing has no
    // trip to open, and a deleted active trip clears the pointer through
    // owners.active_trip_id's ON DELETE SET NULL. Both need somewhere to land.
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(null));
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: copy['profile.title'] }),
      ).toBeInTheDocument();
    });
  });

  it('falls back when the session has not bootstrapped in this tab', async () => {
    // Deep link with a cold cache: nothing has answered yet, so the tab cannot
    // know the active trip. Guessing an id here would open someone else's trip
    // or a 404; the list is the honest answer.
    const user = userEvent.setup();
    renderAt('/feed');
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: copy['profile.title'] }),
      ).toBeInTheDocument();
    });
  });

  it('marks 내 여행 as current once it has opened the trip', async () => {
    // The symptom that made this read as broken: pressing 내 여행 lit up
    // 내 정보. With the tab resolving to /trip the highlight follows the press.
    renderAt(`/trip/${trip.id}`, withActiveTrip(trip.id));
    const bar = await screen.findByRole('navigation', TAB_BAR);
    const current = [...bar.querySelectorAll('button')].filter(
      (b) => b.getAttribute('aria-current') === 'page',
    );
    expect(current[0]?.textContent).toBe(copy['nav.tab.trip']);
  });
});
