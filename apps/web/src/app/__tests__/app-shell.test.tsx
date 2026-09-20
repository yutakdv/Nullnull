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
import { delay, http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { sessionFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { createQueryClient, sessionQueryKey } from '../../shared/api/index.js';
import { API_BASE } from '../../shared/testing/msw/handlers.js';
import { server } from '../../shared/testing/msw/server.js';
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

/** Seeds the owner and trip list that decide the 내 여행 tab destination. */
function withActiveTrip(
  activeTripId: string | null,
  tripPage: typeof tripFixtures.page = tripFixtures.page,
) {
  return (client: QueryClient) => {
    client.setQueryData(sessionQueryKey, {
      ...sessionFixtures.bootstrap,
      owner: { ...sessionFixtures.owner, activeTripId },
    });
    // The screen must observe the state this case names rather than immediately
    // replacing it with the default MSW page before the assertion runs.
    client.setQueryDefaults(['trips'], { staleTime: Infinity });
    client.setQueryData(['trips'], tripPage);
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

describe('route families share responsive content widths', () => {
  it.each([
    ['/start', 'form'],
    ['/trips/select', 'form'],
    [`/trip/${trip.id}`, 'detail'],
    ['/feed', 'browse'],
  ] as const)('%s uses the %s content frame', (path, width) => {
    renderAt(path, withActiveTrip(trip.id));
    expect(document.getElementById('main')).toHaveAttribute('data-content-width', width);
  });
});

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

describe('the 내 여행 tab opens the trip selector (BA-011)', () => {
  // The tab has a stable index route. `activeTripId` remains Feed's
  // representative trip, but it never skips the traveller past the list.

  it('opens the trip selector even when the owner has an active trip', async () => {
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(trip.id));
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: 'My trips' }),
      ).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /서울 가을 여행/ })).toBeInTheDocument();
    expect(screen.getByText('Feed trip')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /서울 가을 여행.*Feed trip/ }),
    ).toHaveAttribute('aria-pressed', 'true');
    const startTrip = screen.getByRole('link', { name: 'Start a new trip' });
    expect(startTrip).toHaveAttribute('href', '/start');
    await user.click(startTrip);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Add your trip dates' }),
    ).toBeInTheDocument();
  });

  it('opens a trip-selection screen when trips exist but none is active', async () => {
    // Catch the old fallback to /profile: an account screen happens to contain
    // trip links, but it does not explain that one must become representative.
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(null));
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: 'My trips' }),
      ).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /서울 가을 여행/ })).toBeInTheDocument();
    expect(screen.queryByText('Feed trip')).not.toBeInTheDocument();
  });

  it('opens trip setup when the owner has no trips', async () => {
    const user = userEvent.setup();
    renderAt(
      '/feed',
      withActiveTrip(null, {
        ...tripFixtures.page,
        items: [],
      }),
    );
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: 'Add your trip dates' }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole('navigation', TAB_BAR)).not.toBeInTheDocument();
  });

  it('keeps 내 여행 current while choosing a representative trip', async () => {
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(null));
    const bar = await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await screen.findByRole('heading', { level: 1, name: 'My trips' });
    const current = [...bar.querySelectorAll('button')].filter(
      (button) => button.getAttribute('aria-current') === 'page',
    );
    expect(current[0]?.textContent).toBe(copy['nav.tab.trip']);
  });

  it('sets the chosen trip as representative and opens it', async () => {
    // Removing either the preference update or the navigation makes this fail:
    // the first leaves the shell pointing at null, the second strands the user
    // on the chooser after their deliberate selection.
    const user = userEvent.setup();
    renderAt('/feed', withActiveTrip(null));
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));
    await user.click(await screen.findByRole('button', { name: /서울 가을 여행/ }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: trip.title ?? '' }),
      ).toBeInTheDocument();
    });
  });

  it('announces a pending feed-trip update and blocks competing navigation', async () => {
    server.use(
      http.patch(`${API_BASE}/me`, async () => {
        await delay('infinite');
        return HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    renderAt('/trips/select', withActiveTrip(trip.id));

    const alternative = await screen.findByRole('button', { name: /부산 겨울 여행/ });
    await user.click(alternative);

    expect(screen.getByRole('status')).toHaveTextContent(
      'Updating the trip used for your feed…',
    );
    const create = screen.getByRole('link', { name: 'Start a new trip' });
    expect(create).toHaveAttribute('aria-disabled', 'true');
    expect(alternative).toBeDisabled();
    await user.click(create);
    expect(
      screen.getByRole('heading', { level: 1, name: 'My trips' }),
    ).toBeInTheDocument();
  });

  it('leaves a direct visit to the profile reading as 내 정보', async () => {
    // Only the tab press carries the override. A reload, a link or a typed URL
    // has no history state, and the path decides as it always did — otherwise
    // the bar would start lying in the other direction.
    renderAt('/profile', withActiveTrip(null));
    const bar = await screen.findByRole('navigation', TAB_BAR);
    const current = [...bar.querySelectorAll('button')].filter(
      (b) => b.getAttribute('aria-current') === 'page',
    );
    expect(current[0]?.textContent).toBe(copy['nav.tab.profile']);
  });

  it('loads the selector when this tab has a cold session cache', async () => {
    // A refresh recovers CSRF without bootstrapping another owner. It must read
    // GET /me as well so Feed and selection share the saved representative,
    // but the 내 여행 tab still starts from the selector.
    const user = userEvent.setup();
    renderAt('/feed');
    await screen.findByRole('navigation', TAB_BAR);

    await user.click(screen.getByRole('button', { name: copy['nav.tab.trip'] }));

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: 'My trips' }),
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
