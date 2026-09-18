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
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
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

  it('offers sign-in as a link to the sign-in screen', async () => {
    // This used to assert the opposite — that the row was inert text with a
    // `준비 중` badge, and neither a button nor a link. The owner moved sign-in
    // into P0 (#264, #265), so the clause is inverted rather than deleted: the
    // row now has to BE a control, and something has to say so.
    renderProfile();
    const link = await screen.findByRole('link', { name: copy['profile.login'] });
    expect(link).toHaveAttribute('href', '/sign-in');
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

// FE-506-T1 is "이력이 상태·시각·대상 링크만 보여주고 일정 본문을 복제하지
// 않는다", and this block is what proves it: the rows carry decision, status,
// scope and a link to the target trip, and nothing here renders an item. The
// ID is in the name because that is the string the aggregator reads - the
// clause was proven and invisible, the same shape as FE-504 and FE-104.
//
// history.ts has its own unit tests, but those cover rowState's logic, not the
// rendered row, and T1 is a claim about what the SCREEN shows.
describe('FE-506-T1 optimization history shows status without itinerary content', () => {
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

  it('carries no itinerary content, only status, time and a link', async () => {
    // The second half of FE-506-T1, which had no assertion: the clause is
    // "상태·시각·대상 링크만 보여주고 일정 본문을 복제하지 않는다", and the
    // cases around it only separate decision from status.
    //
    // CLAUDE.md's P0 decision is that history must not keep a copy of the
    // itinerary, so the row may name the TARGET TRIP but never its stops.
    // Scoped to the history section because the trip list on the same screen
    // legitimately names trips.
    renderProfile();
    // Waits for a row before measuring: the section renders its loading state
    // first, and an empty section would satisfy "no itinerary content" without
    // proving anything.
    await screen.findByText(new RegExp(copy['profile.history.decision.APPLY']));
    const history = screen.getByRole('region', {
      name: new RegExp(copy['profile.history.title']),
    });
    // Every stop the trip fixtures hold. If a row ever rendered the itinerary
    // these are the strings that would appear.
    for (const stop of ['경복궁', '인사동']) {
      expect(within(history).queryByText(new RegExp(stop))).toBeNull();
    }
    // And the row is not empty of everything - it still links its target trip,
    // so the assertion above is about itinerary content, not a blank section.
    expect(within(history).getAllByRole('link').length).toBeGreaterThan(0);
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

// FE-506-T2 is "기본/loading/empty/error/offline/stale 상태를 각각 렌더한다".
// ProfileScreen implements all three branches for the history section
// (isPending, isError, and an empty items list), but nothing exercised them:
// the block above covers the default state, and "the trip list renders each of
// its states" is a different section on the same screen. A branch that renders
// and is never asserted is the shape this repo keeps finding.
//
// Offline is not a separate branch here by design - a network failure returns
// null from toProblem and falls into the same error state (shared/api/
// problem.ts:67), so the error case below is what covers it.
describe('FE-506-T2 the history section renders each of its states', () => {
  it('shows a loading state before the answer arrives', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations`, async () => {
        await delay('infinite');
        return HttpResponse.json({ items: [], page: { hasMore: false } });
      }),
    );
    renderProfile();
    expect(await screen.findByText(copy['profile.history.loading'])).toBeInTheDocument();
  });

  it('says so when there is no history yet', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations`, () =>
        HttpResponse.json({ items: [], page: { hasMore: false } }),
      ),
    );
    renderProfile();
    expect(await screen.findByText(copy['profile.history.empty'])).toBeInTheDocument();
  });

  it('reports a failure instead of an empty history', async () => {
    // The distinction invariant 6 asks for: "nothing here" and "we could not
    // find out" are different answers, and showing the empty copy for a failed
    // request tells the user something the server never said.
    // HttpResponse.error() rather than a Problem body: this is the branch a
    // dropped connection takes, and it is the same branch invariant-6's
    // "데이터 부재" distinction has to survive. The trip-list error test above
    // uses the same shape.
    server.use(http.get(`${API_BASE}/optimizations`, () => HttpResponse.error()));
    renderProfile();
    expect(await screen.findByText(copy['profile.history.error'])).toBeInTheDocument();
    expect(screen.queryByText(copy['profile.history.empty'])).toBeNull();
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

// FE-506-T3 is "keyboard 이동·focus 복귀·접근성 이름과 360px·200% zoom·reduced
// motion을 검증한다". The block below covers the screen's links in general; the
// history rows need their own case because they are the part of S14 this card
// owns, and a row that is a <li> with an onClick rather than a link is
// reachable by mouse only - which is exactly what these assertions rule out.
//
// 360px and 200% zoom are not asserted here: jsdom computes no geometry, so
// that half lives in e2e/responsive.spec.ts, whose SCREENS list carries
// /profile. reduced motion has nothing to assert on this screen - it animates
// nothing (no transition or animation in ProfileScreen.module.css).
describe('FE-506-T3 the history rows are reachable and named', () => {
  it('gives every openable row a name that says which run it opens', async () => {
    renderProfile();
    // Waits for a row: the section renders its loading state first, and
    // getAllByRole on an empty section throws rather than proving anything.
    await screen.findByText(new RegExp(copy['profile.history.decision.APPLY']));
    const history = screen.getByRole('region', {
      name: new RegExp(copy['profile.history.title']),
    });
    const rows = within(history).getAllByRole('link');
    expect(rows.length).toBeGreaterThan(0);
    for (const row of rows) {
      // "Open the {date} {trip} optimization result" - the date and the trip
      // are what distinguish one row from the next, and a screen reader user
      // hearing "link" five times learns nothing.
      const name = row.getAttribute('aria-label') ?? row.textContent ?? '';
      expect(name).toMatch(/optimization result/i);
    }
  });

  it('reaches a history row by keyboard alone', async () => {
    const user = userEvent.setup();
    renderProfile();
    await screen.findByText(new RegExp(copy['profile.history.decision.APPLY']));
    const history = screen.getByRole('region', {
      name: new RegExp(copy['profile.history.title']),
    });
    const target = within(history).getAllByRole('link')[0];
    expect(target).toBeDefined();

    // Tabs until the row takes focus rather than assuming its position: the
    // trip list above it varies with the fixture, so a fixed count would be a
    // test of the fixture and not of the row.
    for (let i = 0; i < 40 && document.activeElement !== target; i += 1) {
      await user.tab();
    }
    expect(target).toHaveFocus();
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

    // Sign-in now sits above the trip list and is the first link on the screen
    // (#265). Asserting the order rather than just "a trip link gets focus"
    // keeps this honest: a second tab has to reach the list, so a control
    // inserted between them would still be caught.
    await user.tab();
    expect(screen.getByRole('link', { name: copy['profile.login'] })).toHaveFocus();

    await user.tab();
    const firstTrip = screen
      .getAllByRole('link')
      .find((a) => a.getAttribute('href')?.startsWith('/trip/'));
    expect(firstTrip).toHaveFocus();
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

/**
 * FR-TRP-04: deleting a trip and everything it owns.
 *
 * The contract requires If-Match and Idempotency-Key on this operation, and
 * the ETag it wants is the quoted trip version. That is why these assert the
 * HEADERS and not just that a DELETE went out: a request without a validator
 * is how one device's delete lands on another device's newer trip.
 */
describe('a trip can be deleted from the profile', () => {
  const target = tripFixtures.page.items[0] ?? null;

  /** Records what the delete actually carried; the screen-level recorder keeps no headers. */
  function recordDelete() {
    const seen: { ifMatch: string | null; key: string | null; url: string }[] = [];
    server.use(
      http.delete(`${API_BASE}/trips/:tripId`, ({ request }) => {
        seen.push({
          ifMatch: request.headers.get('If-Match'),
          key: request.headers.get('Idempotency-Key'),
          url: request.url,
        });
        return new HttpResponse(null, { status: 204 });
      }),
    );
    return seen;
  }

  async function openConfirm(user: ReturnType<typeof userEvent.setup>) {
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    await user.click(
      await within(card).findByRole('button', {
        name: copy['trip.delete.open'].replace('{name}', target?.title ?? ''),
      }),
    );
    return card;
  }

  it('asks before deleting, and sends nothing if the answer is no', async () => {
    const user = userEvent.setup();
    renderProfile();
    await openConfirm(user);

    expect(
      await screen.findByRole('heading', { name: copy['trip.delete.title'] }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: copy['trip.delete.cancel'] }));

    expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(0);
  });

  it('sends the row version as If-Match and a key with the delete', async () => {
    const user = userEvent.setup();
    const seen = recordDelete();
    renderProfile();
    await openConfirm(user);
    await user.click(screen.getByRole('button', { name: copy['trip.delete.confirm'] }));

    await waitFor(() => {
      expect(seen).toHaveLength(1);
    });
    // The quoted trip version, which is what the contract defines an ETag to
    // be. Asserted as the exact string: `"3"` and `3` are not the same header.
    expect(seen[0]?.ifMatch).toBe(`"${String(target?.version ?? 0)}"`);
    expect(seen[0]?.key).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(seen[0]?.url).toContain(target?.id ?? '');
  });

  it('takes the trip out of the list and says so', async () => {
    const user = userEvent.setup();
    renderProfile();
    const card = await openConfirm(user);
    await user.click(screen.getByRole('button', { name: copy['trip.delete.confirm'] }));

    // The row goes. This is only meaningful because the msw handler actually
    // drops it from the list it serves.
    await waitFor(() => {
      expect(
        within(card).queryByRole('link', { name: new RegExp(target?.title ?? '') }),
      ).not.toBeInTheDocument();
    });
    // And the outcome is announced somewhere that OUTLIVES the deleted row.
    expect(
      await screen.findByText(
        copy['trip.delete.deleted'].replace('{name}', target?.title ?? ''),
      ),
    ).toBeInTheDocument();
  });

  it('reports a conflict instead of retrying when the trip moved on', async () => {
    const user = userEvent.setup();
    server.use(
      http.delete(`${API_BASE}/trips/:tripId`, () => problemResponse('TRIP_CHANGED')),
    );
    renderProfile();
    await openConfirm(user);
    await user.click(screen.getByRole('button', { name: copy['trip.delete.confirm'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['trip.delete.conflict'],
    );
    // Nothing was deleted, so the row is still there to try again on.
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    expect(
      within(card).getByRole('link', { name: new RegExp(target?.title ?? '') }),
    ).toBeInTheDocument();
  });
});

describe('the trip count does not present one page as the total', () => {
  // `TripPage` has no total — `items.length` counts the page in hand. Past the
  // first page that number is smaller than the list it labels, and unlike the
  // candidates case there is no second screen to contradict it, so nothing on
  // screen reveals the gap. `hasMore` is the contract's own way of saying the
  // page is partial; where it is true the figure is withheld rather than
  // guessed, which is the same rule the rest of this app follows for a number
  // the contract cannot source.
  it('shows the count while this page is the whole set', async () => {
    renderProfile();
    const section = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });

    // The default fixture is a complete page.
    expect(tripFixtures.page.page.hasMore).toBe(false);

    // Counted from what actually rendered, not from the fixture: the mock trip
    // list is stateful (a delete removes a row), so an earlier test in this
    // file can leave fewer trips than the fixture declares and a literal would
    // make this pass or fail on test ORDER rather than on the behaviour.
    const rows = await within(section).findAllByRole('link');
    expect(
      within(section).getByText(
        copy['profile.trips.count'].replace('{count}', String(rows.length)),
      ),
    ).toBeInTheDocument();
  });

  it('withholds it when the page is only part of the list', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () =>
        HttpResponse.json({
          ...tripFixtures.page,
          page: { ...tripFixtures.page.page, hasMore: true, nextCursor: 'more' },
        }),
      ),
    );
    renderProfile();
    const section = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    // The list still renders — only the claim about the total is dropped.
    const rows = await within(section).findAllByRole('link');
    expect(rows.length).toBeGreaterThan(0);

    expect(
      within(section).queryByText(
        copy['profile.trips.count'].replace('{count}', String(rows.length)),
      ),
    ).toBeNull();
  });
});
