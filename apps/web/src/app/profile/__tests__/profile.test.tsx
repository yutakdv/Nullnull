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
import { render, screen, waitFor, within } from '@testing-library/react';
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

/**
 * Same screen in Korean.
 *
 * Seeds the stored locale rather than taking a test-only prop, so the provider
 * resolves it through the path the app actually uses.
 */
function renderKo() {
  localStorage.setItem('nullnull.locale', 'ko-KR');
  return renderProfile();
}

afterEach(() => {
  localStorage.clear();
});

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
    // Scoped to the trips section: the history card links its target trip by
    // the same title, and FE-106's interest selector names every trip too, so
    // a document-wide query matches several places at once.
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    for (const trip of tripFixtures.page.items) {
      expect(
        await within(card).findByRole('link', { name: new RegExp(trip.title) }),
      ).toBeInTheDocument();
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
  it('shows what the user chose, not just where the run ended', async () => {
    renderProfile();
    // The decision is the half of the row a status cannot supply. "APPLIED" is
    // where the run got to; "적용함" is that the user chose it. A row that
    // printed only the status would read the same for a run the server ended
    // and a run the user ended.
    await screen.findByText(new RegExp(copy['profile.history.decision.APPLY']));
    expect(
      screen.getByText(new RegExp(copy['profile.history.decision.KEEP'])),
    ).toBeInTheDocument();
    expect(
      screen.getByText(new RegExp(copy['profile.history.decision.REVERT'])),
    ).toBeInTheDocument();
  });

  it('shows the decision for a decided run and the status for an undecided one', async () => {
    renderProfile();
    await screen.findByText(new RegExp(copy['profile.history.scope.TRIP']));
    const notes = screen.getAllByText(/·/).map((n) => n.textContent ?? '');
    const running = notes.find((n) => n.includes(copy['profile.history.scope.TRIP']));
    expect(running).toContain(copy['profile.history.RUNNING']);
    expect(running).not.toContain(copy['profile.history.pending']);
  });

  it('renders the decision rather than the status for a decided run', async () => {
    // Korean is the only locale where this is observable: 적용됨 (the run
    // reached APPLIED) and 적용함 (the user chose APPLY) are different words,
    // while en-US spells both "Applied" and KEPT/REVERTED collide in both
    // locales. Without this assertion the screen could ignore rowState's
    // decision branch entirely and every other test would still pass.
    renderKo();
    const ko = messages['ko-KR'];
    await screen.findByText(new RegExp(ko['profile.history.scope.TRIP']));
    const notes = screen.getAllByText(/·/).map((n) => n.textContent ?? '');
    const applied = notes.filter((n) => n.includes(ko['profile.history.decision.APPLY']));
    expect(applied.length).toBeGreaterThan(0);
    for (const note of notes) {
      expect(note).not.toContain(ko['profile.history.APPLIED']);
    }
  });

  it('separates a READY run awaiting a decision from a decided one', async () => {
    renderProfile();
    // The one actionable row: the result exists and nobody has answered it.
    // It must not borrow the copy of a run that was actually decided.
    const pending = await screen.findByText(new RegExp(copy['profile.history.pending']));
    expect(pending).toBeInTheDocument();
    expect(pending.textContent).not.toContain(copy['profile.history.decision.APPLY']);
  });

  it('does not offer a link for a run that has no result yet', async () => {
    renderProfile();
    await screen.findByText(new RegExp(copy['profile.history.RUNNING']));
    const running = optimizationFixtures.historyPage.items.find(
      (run) => run.status === 'RUNNING',
    );
    // A queued or running run has nothing to open; linking to it would land
    // the user on an empty page.
    // Matched on runId, not runLink: the href is built from tripId+runId
    // rather than echoing the server's value, so comparing runLink would
    // pass even if the row did render a link.
    expect(
      screen
        .queryAllByRole('link')
        .find((a) =>
          a.getAttribute('href')?.endsWith(`/optimizations/${running?.runId}`),
        ),
    ).toBeUndefined();
  });

  it('names the scope, because APPLIED means different things per scope', async () => {
    renderProfile();
    await screen.findByText(new RegExp(copy['profile.history.scope.TRIP']));
    expect(
      screen.getAllByText(new RegExp(copy['profile.history.scope.ITEM'])).length,
    ).toBeGreaterThan(0);
  });

  it('states that itinerary content is not kept', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.history.note'])).toBeInTheDocument();
  });

  it('links each run to its own page rather than inlining the plan', async () => {
    renderProfile();
    await screen.findByText(new RegExp(copy['profile.history.decision.APPLY']));
    const withResult = optimizationFixtures.historyPage.items.filter(
      (run) => run.status !== 'QUEUED' && run.status !== 'RUNNING',
    );
    expect(withResult.length).toBeGreaterThan(0);
    for (const run of withResult) {
      const link = screen
        .getAllByRole('link')
        .find(
          (a) =>
            a.getAttribute('href') === `/trip/${run.tripId}/optimizations/${run.runId}`,
        );
      expect(link).toBeDefined();
    }
  });

  it('opens a run instead of the not-found screen', async () => {
    // The regression this guards: the row used to render `run.runLink`
    // verbatim, and the contract spells it `/trips/{id}/optimizations/{id}`
    // (plural) while the only route registered here is `/trip/:tripId/...`
    // (singular). Every link rendered fine and 404'd on click, which an
    // href-only assertion cannot catch — so this one clicks.
    const router = createMemoryRouter(routes, { initialEntries: ['/profile'] });
    render(
      <QueryClientProvider client={createQueryClient()}>
        <I18nProvider>
          <RouterProvider router={router} />
        </I18nProvider>
      </QueryClientProvider>,
    );
    await screen.findByText(new RegExp(copy['profile.history.scope.TRIP']));
    const decided = optimizationFixtures.historyPage.items.find(
      (r) => r.status === 'APPLIED',
    );
    const link = screen
      .getAllByRole('link')
      .find((a) => a.getAttribute('href')?.endsWith(`/optimizations/${decided?.runId}`));
    expect(link).toBeDefined();
    await userEvent.click(link as HTMLElement);
    expect(router.state.location.pathname).toBe(
      `/trip/${decided?.tripId}/optimizations/${decided?.runId}`,
    );
    expect(document.body.textContent ?? '').not.toMatch(/not found/i);
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
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    await within(card).findByRole('link', {
      name: new RegExp(tripFixtures.page.items[0]?.title ?? ''),
    });

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
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'data-guide-heading',
      );
    });
  });
});
