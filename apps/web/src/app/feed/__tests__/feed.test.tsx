// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-201 acceptance (FR-FED-01, FR-FED-02), S03-F0 `391:310` and
// S03-F1 `396:2926`.
//
// FE-201-T1: pagination continues without duplicates or gaps, and an expired
//            cursor is distinguished from a load failure.
// FE-201-T2: default/loading/empty/error states each render.
// FE-201-T3: keyboard reach, accessible names, announced results.
//
// The pagination assertions read what actually reached the wire. A "load
// more" that refetched page one would still append cards and look correct on
// screen, so the test checks the cursor that was sent.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { candidateFixtures, feedFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

/** Every feed request's cursor, in order, as the server saw it. */
let cursors: (string | null)[] = [];

beforeEach(() => {
  cursors = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (!url.pathname.endsWith('/feed')) return;
    cursors.push(url.searchParams.get('cursor'));
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderFeed() {
  const router = createMemoryRouter(routes, { initialEntries: ['/feed'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

const firstTitle = feedFixtures.page.items[0]?.post.title ?? '';
const secondPageTitle = feedFixtures.pageTwo.items[0]?.post.title ?? '';

describe('FE-201-T2 the feed renders each of its states', () => {
  it('lists the cards the first page returned', async () => {
    renderFeed();
    expect(await screen.findByText(firstTitle)).toBeInTheDocument();
    for (const card of feedFixtures.page.items) {
      expect(screen.getByText(card.post.title)).toBeInTheDocument();
    }
  });

  it('shows a loading state before the answer arrives', async () => {
    server.use(
      http.get(`${API_BASE}/feed`, async () => {
        await delay(50);
        return HttpResponse.json(feedFixtures.page);
      }),
    );
    renderFeed();
    expect(await screen.findByText(copy['feed.loading'])).toBeInTheDocument();
  });

  it('says so when the feed is empty', async () => {
    server.use(
      http.get(`${API_BASE}/feed`, () => HttpResponse.json(feedFixtures.pageEmpty)),
    );
    renderFeed();
    expect(await screen.findByText(copy['feed.empty'])).toBeInTheDocument();
  });

  it('offers a retry when the request fails', async () => {
    server.use(http.get(`${API_BASE}/feed`, () => HttpResponse.error()));
    renderFeed();
    expect(await screen.findByText(copy['feed.error'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['feed.retry'] })).toBeInTheDocument();
  });

  it('does not hang on loading when the trip list fails', async () => {
    // The feed query is gated on trips.isSuccess, because the cursor the
    // server mints is bound to the trip selection. Nothing read trips.isError,
    // so when /trips failed the feed query stayed disabled, feed.isPending
    // stayed true, and the screen showed 불러오는 중 for ever — no error, no
    // retry. Confirmed by rendering it: the body read "Browse Loading".
    server.use(http.get(`${API_BASE}/trips`, () => HttpResponse.error()));
    renderFeed();
    expect(await screen.findByText(copy['feed.error'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['feed.retry'] })).toBeInTheDocument();
    expect(screen.queryByText(copy['feed.loading'])).toBeNull();
  });

  it('recovers the whole screen when the trip list retry succeeds', async () => {
    let attempt = 0;
    server.use(
      http.get(`${API_BASE}/trips`, () => {
        attempt += 1;
        if (attempt === 1) return HttpResponse.error();
        return HttpResponse.json(tripFixtures.page);
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await user.click(await screen.findByRole('button', { name: copy['feed.retry'] }));
    expect(await screen.findByText(firstTitle)).toBeInTheDocument();
    // And the error goes with it. Retrying only the feed leaves the trip query
    // in error, so the cards come back UNDER a banner still saying the feed
    // could not be loaded — verified by removing the trips.refetch and
    // watching this assertion fail while the one above still passed.
    await waitFor(() => {
      expect(screen.queryByText(copy['feed.error'])).toBeNull();
    });
  });

  it('shows the trip prompt only when there is no trip (S03-F0)', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.pageEmpty)),
    );
    renderFeed();
    expect(await screen.findByText(copy['feed.emptyNoTrip'])).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: copy['feed.createTrip'] }),
    ).toBeInTheDocument();
  });

  it('does not show the trip prompt when a trip exists (S03-F1)', async () => {
    renderFeed();
    await screen.findByText(firstTitle);
    expect(screen.queryByText(copy['feed.emptyNoTrip'])).not.toBeInTheDocument();
  });
});

describe('FE-201-T1 pagination continues without duplicates or gaps', () => {
  it('asks for the next page with the cursor the server sent', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);

    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await screen.findByText(secondPageTitle);

    // The first request carries no cursor; the second carries exactly the one
    // page one returned. Refetching page one would repeat null here.
    expect(cursors).toEqual([null, feedFixtures.page.page.nextCursor]);
  });

  it('appends the next page without dropping or repeating a card', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await screen.findByText(secondPageTitle);

    const titles = [...feedFixtures.page.items, ...feedFixtures.pageTwo.items].map(
      (card) => card.post.title,
    );
    for (const title of titles) {
      expect(screen.getAllByText(title)).toHaveLength(1);
    }
  });

  it('stops offering more at the end and says so', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));

    expect(await screen.findByText(copy['feed.end'])).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: copy['feed.more'] }),
    ).not.toBeInTheDocument();
  });

  it('stops when hasMore is false even if a cursor came with it', async () => {
    // The contract makes hasMore the required field and nextCursor optional,
    // so hasMore is the authority. A server that sends a trailing cursor with
    // hasMore false must not produce another "show more" — reading only the
    // cursor would page forever.
    server.use(
      http.get(`${API_BASE}/feed`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) return HttpResponse.json(feedFixtures.page);
        return HttpResponse.json({
          ...feedFixtures.pageTwo,
          page: { nextCursor: 'dHJhaWxpbmctY3Vyc29yLXdpdGgtbm8tbW9yZQ', hasMore: false },
        });
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await screen.findByText(secondPageTitle);

    expect(await screen.findByText(copy['feed.end'])).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: copy['feed.more'] }),
    ).not.toBeInTheDocument();
  });

  it('recovers from an expired cursor instead of reporting a failure', async () => {
    // The contract gives the cursor 15 minutes and answers 410 afterwards.
    // Its policy is reset-cursor with retry: none, so the screen reloads from
    // the first page and says what happened — it must NOT show the generic
    // load error, which would leave the user with a dead end.
    let served = 0;
    server.use(
      http.get(`${API_BASE}/feed`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor !== null) {
          served += 1;
          return problemResponse('CURSOR_EXPIRED');
        }
        return HttpResponse.json(feedFixtures.page);
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));

    expect(await screen.findByText(copy['feed.cursorExpired'])).toBeInTheDocument();
    // The generic load error belongs to real failures only. Showing it here
    // would tell the user something broke when the screen already recovered.
    await waitFor(() => {
      expect(screen.queryByText(copy['feed.error'])).not.toBeInTheDocument();
    });
    expect(
      screen.queryByRole('button', { name: copy['feed.retry'] }),
    ).not.toBeInTheDocument();
    // The first page is still on screen rather than an empty list.
    expect(screen.getByText(firstTitle)).toBeInTheDocument();
    expect(served).toBeGreaterThan(0);
  });

  it('gives up rather than looping when the reset also expires', async () => {
    // The guard against re-entering the reset is a latch released in a
    // .finally(), and the effect's deps include the query object, which is new
    // on every render. If the reset refetch ALSO answers CURSOR_EXPIRED the
    // latch is already open when the effect re-runs, so it fires again — a
    // request per render, against a code the contract marks retry: 'none'
    // (problem-policy.ts:103-108).
    let feedCalls = 0;
    server.use(
      http.get(`${API_BASE}/feed`, () => {
        feedCalls += 1;
        return problemResponse('CURSOR_EXPIRED');
      }),
    );
    renderFeed();
    await screen.findByText(copy['feed.cursorExpired']);
    const settled = feedCalls;
    await new Promise((resolve) => setTimeout(resolve, 600));
    // A couple of attempts is recovery; a climbing count is a loop.
    expect(feedCalls).toBe(settled);
    expect(feedCalls).toBeLessThanOrEqual(3);
  });

  it('recovers again when a later cursor expires in the same session', async () => {
    // The mark that stops the loop has to clear once a page loads, or the
    // first expiry in a session is the only one ever recovered from and every
    // later one leaves the user on a dead 더 보기.
    let expireNext = true;
    const served: string[] = [];
    server.use(
      http.get(`${API_BASE}/feed`, ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor !== null && expireNext) {
          expireNext = false;
          return problemResponse('CURSOR_EXPIRED');
        }
        served.push(cursor ?? 'first');
        return HttpResponse.json(
          cursor === null ? feedFixtures.page : feedFixtures.pageTwo,
        );
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);

    // First expiry: recovered, page one served again.
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await screen.findByText(copy['feed.cursorExpired']);
    await waitFor(() => {
      expect(served.filter((c) => c === 'first').length).toBeGreaterThan(1);
    });

    // A second expiry later in the same session must recover too.
    expireNext = true;
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await waitFor(() => {
      expect(served.filter((c) => c === 'first').length).toBeGreaterThan(2);
    });
  });

  it('does not show the load error while a cursor reset is still in flight', async () => {
    // The window this guards: the query is in its error state with a cursor
    // code, and the refetch has not resolved yet. Without the `expired` check
    // the generic failure and its retry button flash on screen for a fault
    // the screen is already recovering from — and the contract forbids
    // retrying a cursor error at all (problem-policy.ts:97-107).
    server.use(
      http.get(`${API_BASE}/feed`, async ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor !== null) return problemResponse('CURSOR_EXPIRED');
        // Slow first page so the reset stays in flight while we assert.
        await delay(80);
        return HttpResponse.json(feedFixtures.page);
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));

    expect(await screen.findByText(copy['feed.cursorExpired'])).toBeInTheDocument();
    expect(screen.queryByText(copy['feed.error'])).not.toBeInTheDocument();
  });
});

describe('FE-201 the card shows only what the contract supplies', () => {
  it('credits the source the server named on a card that has one', async () => {
    renderFeed();
    await screen.findByText(firstTitle);
    // CMP-ATT-001: the crowd reading carries its own attribution and it is
    // displayed verbatim, never composed here (CMP-ATT-003).
    const credit = feedFixtures.page.items[0]?.crowd?.provenance.attribution ?? '';
    expect(credit).not.toBe('');
    expect(screen.getAllByText(credit).length).toBeGreaterThan(0);
  });

  it('renders a card whose crowd reading is unavailable without inventing one', async () => {
    renderFeed();
    const unavailable = feedFixtures.page.items.find(
      (card) => card.crowd?.state === 'UNAVAILABLE',
    );
    await screen.findByText(unavailable?.post.title ?? '');
    // A missing reading is not a zero and not a "보통" (invariant 8, AGENTS.md
    // rule 6). The contract sends no value, unit or ordinalLevel for an
    // UNAVAILABLE reading, so none of them may appear.
    expect(unavailable?.crowd?.value).toBeNull();
    expect(unavailable?.crowd?.ordinalLevel).toBeNull();
    const card = screen
      .getByText(unavailable?.post.title ?? '')
      .closest('article') as HTMLElement;
    expect(within(card).queryByRole('img')).toBeNull();
  });

  it('does not treat a saved post as a scheduled item', async () => {
    // Invariant 1: SavedPost, TripCandidate and TripItem are different things.
    // The fixture has a card that is saved to the trip and one that is
    // scheduled in it; both render, and neither claims to be the other.
    renderFeed();
    await screen.findByText(firstTitle);
    const saved = feedFixtures.page.items.find(
      (card) => card.candidateState === 'SAVED_TO_SELECTED_TRIP',
    );
    const scheduled = feedFixtures.page.items.find(
      (card) => card.candidateState === 'SCHEDULED_IN_SELECTED_TRIP',
    );
    expect(screen.getByText(saved?.post.title ?? '')).toBeInTheDocument();
    expect(screen.getByText(scheduled?.post.title ?? '')).toBeInTheDocument();
  });
});

describe('FE-201-T3 keyboard and accessible names', () => {
  it('names the list after the screen heading', async () => {
    renderFeed();
    await screen.findByText(firstTitle);
    const list = screen.getByRole('list', { name: copy['feed.title'] });
    expect(within(list).getAllByRole('listitem')).toHaveLength(
      feedFixtures.page.items.length,
    );
  });

  it('reaches the more button by keyboard and activates it with Enter', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);

    const more = screen.getByRole('button', { name: copy['feed.more'] });
    more.focus();
    expect(more).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText(secondPageTitle)).toBeInTheDocument();
  });

  it('announces that it is loading more rather than going silent', async () => {
    server.use(
      http.get(`${API_BASE}/feed`, async ({ request }) => {
        const cursor = new URL(request.url).searchParams.get('cursor');
        if (cursor === null) return HttpResponse.json(feedFixtures.page);
        await delay(50);
        return HttpResponse.json(feedFixtures.pageTwo);
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['feed.more'] }));
    await waitFor(() => {
      expect(screen.getByText(copy['feed.loadingMore'])).toBeInTheDocument();
    });
  });
});

describe('FE-203 the add button actually saves a candidate', () => {
  // The card has taken an onAddCandidate prop since it was built, and the
  // screen never passed one. Pressing 내 여행에 담기 sent nothing, changed
  // nothing and said nothing — confirmed in a browser by wrapping fetch:
  // zero requests, the aria-label unchanged. A screen-reader user heard a
  // button that promised to add the place and had no way to learn it had not.
  //
  // Saving a candidate is not scheduling. It writes /trips/:id/candidates and
  // creates no TripItem, so the itinerary's version cannot move (invariant 1
  // and 2) — asserted at the wire below.
  let posted: { path: string; body: unknown; key: string | null }[] = [];

  /**
   * A CandidateSaveResult built from the approved candidate fixture.
   *
   * `tripScheduleChanged` is `const: false` in the contract — the schema
   * itself encodes invariant 2 — so it is written out rather than left to a
   * guess.
   */
  const saveResult = (duplicate: boolean) => ({
    candidate: candidateFixtures.page.items[0],
    duplicate,
    tripScheduleChanged: false,
  });

  beforeEach(() => {
    posted = [];
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ request }) => {
        posted.push({
          path: new URL(request.url).pathname,
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        });
        return HttpResponse.json(saveResult(false), { status: 201 });
      }),
    );
  });

  it('sends the place the card is about', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['tripAdd.idle'] }));

    await waitFor(() => {
      expect(posted).toHaveLength(1);
    });
    const first = feedFixtures.page.items[0];
    expect((posted[0]?.body as Record<string, unknown>).placeId).toBe(
      first?.primaryPlace.id,
    );
    expect(posted[0]?.path).toBe(
      `/api/v1/trips/${tripFixtures.page.items[0]?.id ?? ''}/candidates`,
    );
  });

  it('creates no trip item and touches no itinerary', async () => {
    const writes: string[] = [];
    const record = ({ request }: { request: Request }) => {
      if (request.method === 'GET') return;
      const path = new URL(request.url).pathname;
      if (path.startsWith('/api/v1/session') || path.startsWith('/api/v1/demo')) return;
      writes.push(`${request.method} ${path}`);
    };
    server.events.on('request:start', record);
    try {
      const user = userEvent.setup();
      renderFeed();
      await screen.findByText(firstTitle);
      await user.click(screen.getByRole('button', { name: copy['tripAdd.idle'] }));
      await waitFor(() => {
        expect(posted).toHaveLength(1);
      });
      // Exactly one write, to the candidates resource. /items would be a
      // TripItem, which invariant 2 says a candidate save must never create.
      expect(writes).toHaveLength(1);
      expect(writes[0]).toMatch(/\/candidates$/);
      expect(writes.some((w) => w.includes('/items'))).toBe(false);
    } finally {
      server.events.removeListener('request:start', record);
    }
  });

  it('carries an Idempotency-Key, and the same one on a retry', async () => {
    // invariant 6. A fresh key per press would let the retry after a lost
    // response save the place twice.
    let attempt = 0;
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ request }) => {
        posted.push({
          path: new URL(request.url).pathname,
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
        });
        attempt += 1;
        if (attempt === 1) return HttpResponse.error();
        return HttpResponse.json(saveResult(false), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['tripAdd.idle'] }));
    await screen.findByRole('button', { name: copy['tripAdd.error'] });
    await user.click(screen.getByRole('button', { name: copy['tripAdd.error'] }));

    await waitFor(() => {
      expect(posted).toHaveLength(2);
    });
    expect(posted[0]?.key).toBeTruthy();
    expect(posted[0]?.key).toBe(posted[1]?.key);
  });

  it('says it worked, on the button the user pressed', async () => {
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    // Scoped to the card that was pressed: two other fixture cards are
    // already saved, so a document-wide query matches them too and would pass
    // without this card changing at all.
    const card = screen.getByText(firstTitle).closest('article') as HTMLElement;
    await user.click(within(card).getByRole('button', { name: copy['tripAdd.idle'] }));
    expect(
      await within(card).findByRole('button', { name: copy['tripAdd.saved'] }),
    ).toBeInTheDocument();
  });

  it('distinguishes an already-saved place from a new one', async () => {
    // The contract answers 200 with duplicate:true when the candidate already
    // existed and 201 when it is new. Both mean saved, and the user is told
    // which rather than being shown a plain success for a no-op.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, () =>
        HttpResponse.json(saveResult(true), { status: 200 }),
      ),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['tripAdd.idle'] }));
    expect(
      await screen.findByRole('button', { name: copy['tripAdd.duplicate'] }),
    ).toBeInTheDocument();
  });

  it('reports a failure instead of claiming the place was saved', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, () =>
        problemResponse('RATE_LIMITED'),
      ),
    );
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['tripAdd.idle'] }));
    expect(
      await screen.findByRole('button', { name: copy['tripAdd.error'] }),
    ).toBeInTheDocument();
  });

  it('does not send anything for a card with no trip selected', async () => {
    // NO_TRIP_SELECTED has nowhere to save to. The button says so and the
    // press must not reach the server with a guessed trip id.
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    await user.click(screen.getByRole('button', { name: copy['tripAdd.no-trip'] }));
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(posted).toHaveLength(0);
  });

  it('leaves a place already in the trip alone', async () => {
    // SAVED_TO_SELECTED_TRIP and SCHEDULED_IN_SELECTED_TRIP are both already
    // there; pressing again would be a duplicate request for no gain.
    const user = userEvent.setup();
    renderFeed();
    await screen.findByText(firstTitle);
    const saved = screen.getAllByRole('button', { name: copy['tripAdd.saved'] });
    expect(saved.length).toBeGreaterThan(0);
    await user.click(saved[0] as HTMLElement);
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(posted).toHaveLength(0);
  });
});
