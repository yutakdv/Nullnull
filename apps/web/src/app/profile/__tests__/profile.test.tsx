// @vitest-environment happy-dom
//
// happy-dom because the router builds a Request to navigate (#67).
//
// FE-105 acceptance (FR-PRO-01, FR-PRO-02, FR-PRO-03).
//
// FE-105-T1: the profile labels the test account without exposing a sign-in
//             control or making an authentication request.
// FE-105-T2: the trip list renders its default, loading, empty, server-error
//             and connection-failure states. The connection failure is a
//             request that fails with no response while the browser believes
//             it is online; a browser that has announced it is offline is not
//             covered (see the case below and the card's handoff).
// FE-105-T3: keyboard reach and accessible names. 360px and 200% zoom are the
//             other half, measured on /profile by e2e/responsive.spec.ts.
// FE-105-T4: closing the trip delete confirm keeps focus in the page.
// FE-105-T5 (reduced motion) is e2e/responsive.spec.ts's alone: jsdom
//             computes no style, so nothing here could measure it.
// FE-105-T6: the profile reopens the language screen.
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
  return {
    ...render(
      <QueryClientProvider client={createQueryClient()}>
        <I18nProvider>
          <RouterProvider router={router} />
        </I18nProvider>
      </QueryClientProvider>,
    ),
    router,
  };
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

describe('FE-105-T1 S14 test account state', () => {
  it('labels the test account', async () => {
    renderProfile();
    expect(await screen.findByText(copy['profile.guest.name'])).toBeInTheDocument();
    expect(await screen.findByText(copy['profile.guest.note'])).toBeInTheDocument();
  });

  it('shows no sign-in control in the profile summary', async () => {
    renderProfile();
    await screen.findByText(copy['profile.guest.note']);
    expect(screen.queryByRole('link', { name: copy['profile.login'] })).toBeNull();
    expect(screen.queryByRole('button', { name: copy['profile.login'] })).toBeNull();
    expect(screen.queryByText(copy['profile.comingSoon'])).toBeNull();
  });

  it('sends no auth request while rendering the profile', async () => {
    renderProfile();
    await screen.findByText(copy['profile.guest.note']);
    const authCalls = requests.filter((r) => /login|auth|session\/account/i.test(r.url));
    expect(authCalls).toEqual([]);
  });
});

// FE-105-T2 was "기본/loading/empty/error/offline/stale 상태를 각각 렌더한다"
// with no testcase carrying it. It is now the trip list's five, and `stale` is
// out of it on purpose: nothing on this screen can be stale in a way the user
// sees. The list is a plain query read, a background refetch keeps the rows it
// has, and the profile renders no freshness claim that could go out of date —
// a case asserting a stale state here would assert a state nothing produces
// (AGENTS.md rule 7②). The history, interests and deletion sections carry
// their own cards' state clauses (FE-506-T2, FE-106-T2, deletion.test.tsx).
//
// A dropped connection and a server error reach the same branch — a dropped
// connection returns null from toProblem and falls into `isError`
// (shared/api/problem.ts) — but they are different inputs, so each is sent once
// below rather than one standing in for the other.
describe('FE-105-T2 the trip list renders each of its states', () => {
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

  it('offers a retry when the connection fails', async () => {
    // HttpResponse.error() is a network failure with no response at all, sent
    // while the browser believes it is online. That is all this proves. Once
    // the browser has announced it is offline, TanStack's default networkMode
    // ('online', not overridden in query-client.ts) PAUSES the query instead
    // of failing it, and the list stays on its loading line with no retry
    // until the connection returns — measured, and left as a known gap in
    // FE-105's handoff rather than claimed here.
    server.use(http.get(`${API_BASE}/trips`, () => HttpResponse.error()));
    renderProfile();
    expect(await screen.findByText(copy['profile.trips.error'])).toBeInTheDocument();
    expect(
      screen.getAllByRole('button', { name: copy['profile.retry'] })[0],
    ).toBeInTheDocument();
  });

  it('offers a retry when the server answers with an error', async () => {
    // A Problem body rather than a dropped connection. INTERNAL_ERROR is
    // `safe-get-once` in problem-policy.ts, so the query repeats the GET once
    // after its 1s backoff before the error shows — hence the longer wait.
    server.use(http.get(`${API_BASE}/trips`, () => problemResponse('INTERNAL_ERROR')));
    renderProfile();
    expect(
      await screen.findByText(copy['profile.trips.error'], {}, { timeout: 3000 }),
    ).toBeInTheDocument();
    // "Could not find out" is not "you have none".
    expect(screen.queryByText(copy['profile.trips.empty'])).toBeNull();
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

// FE-105-T3 was "keyboard 이동·focus 복귀·접근성 이름과 360px·200% zoom·reduced
// motion을 검증한다" — six clauses behind one id, which the aggregator counts as
// proven by a test of any one of them (AGENTS.md registration rule 3). It is
// split the way FE-104 and FE-203 were: T3 keeps keyboard reach and accessible
// names here, plus 360px and 200% zoom in e2e/responsive.spec.ts; focus return
// is T4 below; reduced motion is T5, measured on /profile by the same spec.
describe('FE-105-T3 the profile is reachable by keyboard', () => {
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
    const firstTrip = screen
      .getAllByRole('link')
      .find((a) => a.getAttribute('href')?.startsWith('/trip/'));
    expect(firstTrip).toHaveFocus();
  });

  it('reaches the data guide link and follows it', async () => {
    // By keyboard, as the describe says. This used to click, which proved the
    // link worked for a mouse and nothing about reaching it.
    const user = userEvent.setup();
    renderProfile();
    const guide = await screen.findByRole('link', {
      name: new RegExp(copy['profile.dataGuide.title']),
    });
    for (let i = 0; i < 40 && document.activeElement !== guide; i += 1) {
      await user.tab();
    }
    expect(guide).toHaveFocus();
    await user.keyboard('{Enter}');
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'data-guide-heading',
      );
    });
  });

  it('names each trip’s delete control after its trip', async () => {
    // The control draws only a ✕, so without its label a screen reader hears
    // "✕" once per trip and cannot tell which trip it would delete.
    //
    // Every fixture trip, not only the ones found on screen: resetMockState()
    // puts the list back after each test (vitest.setup.ts), so all of them are
    // listed, and filtering by what rendered would hide a missing row. The
    // length guard keeps the loop from passing over an empty fixture.
    expect(tripFixtures.page.items.length).toBeGreaterThan(0);
    renderProfile();
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    for (const trip of tripFixtures.page.items) {
      expect(
        await within(card).findByRole('button', {
          name: copy['trip.delete.open'].replace('{name}', trip.title),
        }),
      ).toBeInTheDocument();
    }
  });
});

// FE-105-T4, the "focus 복귀" third of the old T3. The trip delete confirm is
// the profile's own dialog; the deletion section's confirm is DeletionSection's.
// ConfirmDialog does the restoring, but a card's clause is proven on its
// screen: these open the dialog from the profile row and look at where focus
// lands on the profile, not at the component in isolation.
describe('FE-105-T4 closing a confirm on the profile returns focus', () => {
  const target = tripFixtures.page.items[0] ?? null;

  async function focusDeleteControl(user: ReturnType<typeof userEvent.setup>) {
    const card = await screen.findByRole('region', {
      name: copy['profile.trips.title'],
    });
    const control = await within(card).findByRole('button', {
      name: copy['trip.delete.open'].replace('{name}', target?.title ?? ''),
    });
    for (let i = 0; i < 40 && document.activeElement !== control; i += 1) {
      await user.tab();
    }
    expect(control).toHaveFocus();
    return control;
  }

  it('returns focus to the delete control when the confirm is cancelled', async () => {
    const user = userEvent.setup();
    renderProfile();
    const control = await focusDeleteControl(user);
    await user.keyboard('{Enter}');
    // The safe choice takes focus when the confirm opens.
    const cancel = await screen.findByRole('button', {
      name: copy['trip.delete.cancel'],
    });
    await waitFor(() => {
      expect(cancel).toHaveFocus();
    });
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(control).toHaveFocus();
    });
  });

  it('keeps focus in the page, not the document, once the row is deleted', async () => {
    // The opener goes with its row, so there is nothing to return to — the
    // failure this guards is focus falling to <body>, or staying on a button
    // inside the closed dialog, and the next Tab starting over at the top of
    // the page.
    //
    // Measured on this screen: focus lands on <main>. The confirm lives in the
    // trips <section>, which ConfirmDialog treats as the block its change
    // rewrites and so skips, and no control precedes that section — so the
    // shared `restoreFocusTo` fallback takes it (focus-restore.ts). That is
    // the designed last resort, and the next Tab continues inside the page.
    const user = userEvent.setup();
    renderProfile();
    const control = await focusDeleteControl(user);
    await user.keyboard('{Enter}');
    await user.click(
      await screen.findByRole('button', { name: copy['trip.delete.confirm'] }),
    );

    await waitFor(() => {
      expect(control).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(document.activeElement).not.toBe(document.body);
    });
    const active = document.activeElement as HTMLElement | null;
    expect(active?.closest('dialog')).toBeNull();
    expect(document.getElementById('main')?.contains(active)).toBe(true);
  });
});

// FE-105-T6. S14 lists locale among its rows (FIGMA_HANDOFF §H/I) and the
// route table says /language is "profile에서 재진입 가능", but the splash
// redirect was the only way to /language: once past onboarding, nothing led
// back to KO/EN. The row takes the data-guide row's list style; FCR-016 keeps
// the S14 language flow's own frame open, so its layout and copy follow Figma
// when that lands.
//
// The id is on the first case only. The two below prove the row's note, not
// that the row reopens the choice, and a describe title would lend them T6.
describe('the profile reopens the language choice', () => {
  it('FE-105-T6 opens the language screen by keyboard', async () => {
    const user = userEvent.setup();
    const { router } = renderProfile();
    const row = await screen.findByRole('link', {
      name: new RegExp(copy['profile.language.title']),
    });
    for (let i = 0; i < 40 && document.activeElement !== row; i += 1) {
      await user.tab();
    }
    expect(row).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(
      await screen.findByRole('heading', { level: 1, name: /Choose your language/ }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/language');
    // The return target rides on the link. Without it the language screen
    // cannot tell it was opened from here and its Next walks on into the intro
    // (FE-101-T6); a row that dropped it still reached /language above.
    expect(router.state.location.search).toBe('?from=profile');
    // Opening a screen changes nothing: the choice is saved where it is made.
    expect(requests.filter((r) => r.method === 'PATCH')).toHaveLength(0);
  });

  it('names the language the app is showing', async () => {
    renderProfile();
    const row = await screen.findByRole('link', {
      name: new RegExp(copy['profile.language.title']),
    });
    expect(row).toHaveTextContent(copy['language.en.name']);
    expect(row).not.toHaveTextContent(copy['language.ko.name']);
  });

  it('names Korean when the app is in Korean', async () => {
    renderKo();
    const ko = messages['ko-KR'];
    const row = await screen.findByRole('link', {
      name: new RegExp(ko['profile.language.title']),
    });
    expect(row).toHaveTextContent(ko['language.ko.name']);
    expect(row).not.toHaveTextContent(ko['language.en.name']);
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
