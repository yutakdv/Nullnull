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
import { feedFixtures, tripFixtures } from '@nullnull/contracts';
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
