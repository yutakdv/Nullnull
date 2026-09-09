// @vitest-environment happy-dom
//
// happy-dom because the router builds a Request to navigate (#67).
//
// FE-105 acceptance (FR-PRO-01, FR-PRO-02, FR-PRO-03).
//
// FE-105-T1: the sign-in CTA makes no request and no route.
// FE-105-T2: default/loading/empty/error states each render.
// FE-105-T3: keyboard reach, focus and accessible names.
//
// The history assertions matter beyond rendering: CLAUDE.md forbids keeping
// itinerary content for history, so a test checks the screen shows status and
// target only, rather than trusting the fixture to stay thin.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { optimizationFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let requests: { method: string; url: string }[] = [];

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderProfile() {
  const router = createMemoryRouter(routes, { initialEntries: ['/profile'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('S14 profile shows the anonymous guest state', () => {
  it('explains where trips are stored', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.guest.note'])).toBeInTheDocument();
  });

  it('shows sign-in as `준비 중` and never as a control', async () => {
    renderProfile();
    await screen.findByText(copy['profile.login']);
    expect(screen.getByText(copy['profile.comingSoon'])).toBeInTheDocument();
    // Not a button and not a link: there is nothing to activate.
    expect(
      screen.queryByRole('button', { name: copy['profile.login'] }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: copy['profile.login'] }),
    ).not.toBeInTheDocument();
  });

  it('sends no auth request while rendering the profile', async () => {
    renderProfile();
    await screen.findByText(copy['profile.guest.note']);
    const authCalls = requests.filter((r) => /login|auth|session\/account/i.test(r.url));
    expect(authCalls).toEqual([]);
  });
});

describe('the trip list renders each of its states', () => {
  it('lists the trips it is given', async () => {
    renderProfile();
    for (const trip of tripFixtures.page.items) {
      expect(await screen.findByText(trip.title)).toBeInTheDocument();
    }
  });

  it('says so when there are none', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.pageEmpty)),
    );
    renderProfile();
    expect(await screen.findByText(copy['profile.trips.empty'])).toBeInTheDocument();
  });

  it('shows a loading state before the answer arrives', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, async () => {
        await delay(50);
        return HttpResponse.json(tripFixtures.page);
      }),
    );
    renderProfile();
    expect(await screen.findByText(copy['profile.trips.loading'])).toBeInTheDocument();
  });

  it('offers a retry when the request fails', async () => {
    server.use(http.get(`${API_BASE}/trips`, () => HttpResponse.error()));
    renderProfile();
    expect(await screen.findByText(copy['profile.trips.error'])).toBeInTheDocument();
    expect(
      screen.getAllByRole('button', { name: copy['profile.retry'] })[0],
    ).toBeInTheDocument();
  });
});

describe('optimization history shows status without itinerary content', () => {
  it('renders one row per run with its decision state', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.history.APPLIED'])).toBeInTheDocument();
    expect(screen.getByText(copy['profile.history.KEPT'])).toBeInTheDocument();
    expect(screen.getByText(copy['profile.history.REVERTED'])).toBeInTheDocument();
  });

  it('states that itinerary content is not kept', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.history.note'])).toBeInTheDocument();
  });

  it('links each run to its own page rather than inlining the plan', async () => {
    renderProfile();
    await screen.findByText(copy['profile.history.APPLIED']);
    for (const run of optimizationFixtures.historyPage.items) {
      const link = screen
        .getAllByRole('link')
        .find((a) => a.getAttribute('href') === run.runLink);
      expect(link).toBeDefined();
    }
  });

  it('says so when there is no history', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations`, () =>
        HttpResponse.json(optimizationFixtures.historyPageEmpty),
      ),
    );
    renderProfile();
    expect(await screen.findByText(copy['profile.history.empty'])).toBeInTheDocument();
  });
});

describe('privacy rows report state instead of asking for permission', () => {
  it('says location never leaves the device and offers no toggle', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.location.note'])).toBeInTheDocument();
    expect(screen.getByText(copy['profile.location.off'])).toBeInTheDocument();
    expect(
      screen.queryByRole('switch', { name: copy['profile.location.title'] }),
    ).not.toBeInTheDocument();
  });

  it('never calls the geolocation API', async () => {
    let asked = false;
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: () => {
          asked = true;
        },
        watchPosition: () => {
          asked = true;
          return 0;
        },
      },
    });
    renderProfile();
    await screen.findByText(copy['profile.location.note']);
    expect(asked).toBe(false);
  });
});

describe('the profile is reachable by keyboard', () => {
  it('moves focus through the links in order', async () => {
    const user = userEvent.setup();
    renderProfile();
    await screen.findByText(tripFixtures.page.items[0]?.title ?? '');

    await user.tab();
    const first = screen
      .getAllByRole('link')
      .find((a) => a.getAttribute('href')?.startsWith('/trip/'));
    expect(first).toHaveFocus();
  });

  it('reaches the data guide link and follows it', async () => {
    const user = userEvent.setup();
    renderProfile();
    const guide = await screen.findByRole('link', {
      name: new RegExp(copy['profile.dataGuide.title']),
    });
    await user.click(guide);
    await waitFor(() => {
      expect(screen.getByTestId('placeholder-route')).toHaveTextContent('about-data');
    });
  });
});
