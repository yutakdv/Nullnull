// @vitest-environment happy-dom
//
// FE-305 slice 3 acceptance (FR-ITM-01), S07-3 `476:3409`.
//
// The assertion carrying the weight: the day chips choose a RESOURCE, not a
// setting. A chosen day creates a TripItem; 미정 creates a TripCandidate with no
// date and no schedule change. Invariants 1 and 2 are that separation, and a
// single "add" that infers the endpoint from whether a date is set is exactly
// how the two collapse — so this is asserted from the URL that goes out.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { placeFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const firstResult = placeFixtures.searchPage.items[0];
// 경복궁 (items[0]) is already on day 1 of the fixture trip, so it is the
// duplicate case. 명동 is the one that can actually be added there.
const addable = placeFixtures.searchPage.items[1];

interface Sent {
  method: string;
  url: string;
  ifMatch: string | null;
  idempotency: string | null;
  body: Record<string, unknown> | null;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    // searchPlaces is a POST too (the query rides in the body so it never
    // reaches a URL), so the capture is scoped to the two add endpoints.
    if (request.method !== 'POST') return;
    if (!/\/items$|\/candidates$/.test(new URL(request.url).pathname)) return;
    const clone = request.clone();
    const base = {
      method: request.method,
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
      idempotency: request.headers.get('Idempotency-Key'),
    };
    void clone.json().then(
      (body) => {
        sent.push({ ...base, body: body as Record<string, unknown> });
      },
      () => {
        sent.push({ ...base, body: null });
      },
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderScreen() {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/add-place`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Types a query and waits for the first result. */
async function searchFor(text: string) {
  const user = userEvent.setup();
  renderScreen();
  // The day chips come from the trip; waiting for them keeps the add path from
  // running against an empty day list.
  await screen.findByRole('button', { name: /Day 4/ });
  await user.type(await screen.findByRole('searchbox'), text);
  await screen.findByText(firstResult?.name ?? '');
  return user;
}

const addNamed = (name: string) => copy['addPlace.addNamed'].replace('{name}', name);

describe('FE-305-T2 the screen renders each state', () => {
  it('offers a chip per trip day plus 미정', async () => {
    renderScreen();
    // The day chips come from the trip, so they appear once it has loaded.
    await screen.findByRole('button', { name: /Day 4/ });
    const chips = screen.getAllByRole('button', { name: /Day \d|No date yet/ });
    // Four days in the fixture, then the no-date option.
    expect(chips).toHaveLength(trip.days.length + 1);
    expect(
      screen.getByRole('button', { name: copy['addPlace.someday'] }),
    ).toBeInTheDocument();
  });

  it('says so when nothing matches', async () => {
    server.use(
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPageEmpty),
      ),
    );
    const user = userEvent.setup();
    renderScreen();
    await user.type(await screen.findByRole('searchbox'), '없는장소');
    expect(await screen.findByText(copy['addPlace.noResults'])).toBeInTheDocument();
  });

  it('reports a failed search instead of showing nothing', async () => {
    server.use(http.post(`${API_BASE}/places/search`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderScreen();
    await user.type(await screen.findByRole('searchbox'), '서울');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['addPlace.searchError'],
    );
  });

  it('states what each destination means', async () => {
    renderScreen();
    // The only place the two outcomes are spelled out for the user.
    expect(await screen.findByText(copy['addPlace.note'])).toBeInTheDocument();
  });
});

describe('FE-305-T1 the day choice picks the resource', () => {
  it('creates a scheduled item when a day is chosen', async () => {
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: /Day 1/ }));
    await user.click(screen.getByRole('button', { name: addNamed(addable?.name ?? '') }));

    await waitFor(() => {
      expect(sent.some((r) => r.url.endsWith('/items'))).toBe(true);
    });
    const post = sent.find((r) => r.url.endsWith('/items'));
    // A TripItem needs a date and a position, and the mutation is guarded by
    // the trip's ETag because it changes the schedule.
    expect(post?.body).toMatchObject({
      placeId: addable?.id,
      date: '2026-10-04',
      position: 2,
    });
    expect(post?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(post?.idempotency).toBeTruthy();
  });

  it('creates a dateless candidate for 미정, and never touches /items', async () => {
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: copy['addPlace.someday'] }));
    await user.click(
      screen.getByRole('button', { name: addNamed(firstResult?.name ?? '') }),
    );

    await waitFor(() => {
      expect(sent.some((r) => r.url.endsWith('/candidates'))).toBe(true);
    });
    // Invariant 1: a candidate is not an item. Invariant 2: it carries no date.
    expect(sent.some((r) => r.url.endsWith('/items'))).toBe(false);
    const post = sent.find((r) => r.url.endsWith('/candidates'));
    expect(post?.body).toEqual({
      placeId: firstResult?.id,
      source: { type: 'SEARCH' },
    });
    expect(post?.body).not.toHaveProperty('date');
    // No If-Match: saving a candidate does not change the schedule version.
    expect(post?.ifMatch).toBeNull();
  });

  it('defaults to 미정 rather than guessing a day', async () => {
    const user = await searchFor('서울');
    // Nothing chosen yet: the safe destination is the one that schedules
    // nothing.
    await user.click(
      screen.getByRole('button', { name: addNamed(firstResult?.name ?? '') }),
    );
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.url).toContain('/candidates');
  });

  it('will not add a place already scheduled on the chosen day', async () => {
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: /Day 1/ }));
    // 경복궁 is already on day 1 of this trip.
    expect(
      screen.getByRole('button', { name: addNamed(firstResult?.name ?? '') }),
    ).toBeDisabled();
  });

  it('still allows that place on a different day', async () => {
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: /Day 2/ }));
    // Visiting one place on two days is a real itinerary, not a mistake.
    expect(
      screen.getByRole('button', { name: addNamed(firstResult?.name ?? '') }),
    ).toBeEnabled();
  });

  it('appends to the end of the chosen day', async () => {
    const user = await searchFor('서울');
    // 10-06 is empty in the fixture.
    await user.click(screen.getByRole('button', { name: /Day 3/ }));
    await user.click(screen.getByRole('button', { name: addNamed(addable?.name ?? '') }));
    await waitFor(() => {
      expect(sent.some((r) => r.url.endsWith('/items'))).toBe(true);
    });
    expect(sent.find((r) => r.url.endsWith('/items'))?.body).toMatchObject({
      date: '2026-10-06',
      position: 0,
    });
  });
});

describe('FE-305-T2 the result is reported either way', () => {
  it('announces a scheduled add', async () => {
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: /Day 1/ }));
    await user.click(screen.getByRole('button', { name: addNamed(addable?.name ?? '') }));
    expect(
      await screen.findByText(
        copy['addPlace.addedItem']
          .replace('{name}', addable?.name ?? '')
          .replace('{day}', copy['trip.day'].replace('{n}', '1')),
      ),
    ).toBeInTheDocument();
  });

  it('says when the place was already saved rather than claiming a new one', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, () =>
        HttpResponse.json({
          candidate: {
            id: '018f4d40-1122-7a33-9b44-000000000009',
            tripId: trip.id,
            place: firstResult,
            status: 'ACTIVE',
            sources: [{ type: 'SEARCH', createdAt: '2026-09-08T02:00:00Z' }],
            createdAt: '2026-09-08T02:00:00Z',
          },
          duplicate: true,
          tripScheduleChanged: false,
        }),
      ),
    );
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: copy['addPlace.someday'] }));
    await user.click(
      screen.getByRole('button', { name: addNamed(firstResult?.name ?? '') }),
    );
    expect(
      await screen.findByText(
        copy['addPlace.duplicate'].replace('{name}', firstResult?.name ?? ''),
      ),
    ).toBeInTheDocument();
  });

  it('reports a version conflict without claiming the add succeeded', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items`, () => problemResponse('TRIP_CHANGED')),
    );
    const user = await searchFor('서울');
    await user.click(screen.getByRole('button', { name: /Day 1/ }));
    await user.click(screen.getByRole('button', { name: addNamed(addable?.name ?? '') }));
    expect(await screen.findByText(copy['trip.conflict'])).toBeInTheDocument();
  });
});

describe('FE-305-T3 the screen is reachable and named', () => {
  it('names every add button for its place', async () => {
    await searchFor('서울');
    // A column of identical "추가" buttons tells a screen reader nothing.
    for (const place of placeFixtures.searchPage.items) {
      expect(
        screen.getByRole('button', { name: addNamed(place.name) }),
      ).toBeInTheDocument();
    }
  });

  it('offers a named way back to the itinerary', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(await screen.findByRole('button', { name: copy['addPlace.back'] }));
    expect(
      await screen.findByRole('heading', { level: 1, name: trip.title }),
    ).toBeInTheDocument();
  });

  it('credits each result the way the server named it', async () => {
    await searchFor('서울');
    const credit = firstResult?.sourceAttribution?.attribution ?? '';
    expect(credit).not.toBe('');
    expect(screen.getAllByText(credit).length).toBeGreaterThan(0);
  });
});
