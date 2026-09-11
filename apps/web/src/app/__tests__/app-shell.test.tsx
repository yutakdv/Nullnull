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
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { createQueryClient } from '../../shared/api/index.js';
import { routes } from '../routes.js';

const trip = tripFixtures.detailScheduled;
// The suite's default locale. Labels come from the table rather than being
// typed here, so a copy change cannot leave this test asserting old wording.
const copy = messages['en-US'];

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
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
  ['/start/must-visit', 'wizard draft is unsaved'],
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
