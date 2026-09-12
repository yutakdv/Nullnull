// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-307 acceptance (FR-CON-01, FR-CON-03), S07-2 `411:1837`.
//
// FE-307-T1: setting one lock leaves the others alone, and RESERVATION is
//            never offered as a toggle.
// FE-307-T2: default/pending/error each render.
// FE-307-T3: keyboard reach and accessible names.
//
// The assertion that carries the weight is the request itself. The contract
// gives each lock its own endpoint — PUT /constraints/{constraintType} with
// the body's `type` equal to the path's — so invariant 7's independence is a
// property of what goes on the wire, not of what the component believes.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

interface Sent {
  method: string;
  path: string;
  body: unknown;
  ifMatch: string | null;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  // Observed rather than stubbed: the default handler already answers this
  // route, and replacing it would hide a wrong path or a missing If-Match.
  server.events.on('request:start', ({ request }) => {
    if (request.method !== 'PUT') return;
    const path = new URL(request.url).pathname;
    if (!path.includes('/constraints/')) return;
    const clone = request.clone();
    void clone.json().then(
      (body) => {
        sent.push({
          method: request.method,
          path,
          body,
          ifMatch: request.headers.get('If-Match'),
        });
      },
      () => undefined,
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderTrip() {
  const router = createMemoryRouter(routes, { initialEntries: [`/trip/${trip.id}`] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Renders the trip, then returns one item's card so queries stay scoped. */
async function itemCard(name: string): Promise<HTMLElement> {
  renderTrip();
  // By heading, not by text: a place name also occurs inside an address
  // ("명동" is both a place and a street), so a text query matches twice.
  const heading = await screen.findByRole('heading', { level: 3, name });
  return heading.closest('article') as HTMLElement;
}

const setName = (lock: string) => copy['trip.lock.apply'].replace('{lock}', lock);

describe('FE-307-T1 a user can set a lock, one request per lock', () => {
  it('offers the locks an item does not already carry', async () => {
    // 명동 carries nothing in the fixture, so it is the item with something to
    // set. Before FE-307 this row rendered nothing at all.
    const card = await itemCard('명동');
    expect(
      within(card).getByRole('button', { name: setName(copy['trip.lock.MUST_VISIT']) }),
    ).toBeInTheDocument();
  });

  it('does not offer a lock the item already has', async () => {
    // 경복궁 already carries MUST_VISIT and DATE; setting either again would
    // send a request that changes nothing.
    const card = await itemCard('경복궁');
    expect(
      within(card).queryByRole('button', {
        name: setName(copy['trip.lock.MUST_VISIT']),
      }),
    ).toBeNull();
    expect(
      within(card).queryByRole('button', { name: setName(copy['trip.lock.DATE']) }),
    ).toBeNull();
  });

  it('sends one PUT naming exactly that lock, with the trip ETag', async () => {
    const user = userEvent.setup();
    const card = await itemCard('명동');
    await user.click(
      within(card).getByRole('button', { name: setName(copy['trip.lock.MUST_VISIT']) }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    const [request] = sent;
    expect(request?.method).toBe('PUT');
    // The path decides the lock; the body repeats it as the discriminator.
    expect(request?.path).toMatch(/\/constraints\/MUST_VISIT$/);
    expect(request?.body).toEqual({ type: 'MUST_VISIT', locked: true });
    expect(request?.ifMatch).not.toBeNull();
  });

  it('touches no other lock type (invariant 7)', async () => {
    const user = userEvent.setup();
    const card = await itemCard('명동');
    await user.click(
      within(card).getByRole('button', { name: setName(copy['trip.lock.MUST_VISIT']) }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // One request, one constraintType. Nothing here can clear or set another.
    for (const type of ['DATE', 'TIME', 'RESERVATION']) {
      expect(sent.some((r) => r.path.endsWith(`/constraints/${type}`))).toBe(false);
    }
  });

  it('pins the date the item already has rather than inventing one', async () => {
    const user = userEvent.setup();
    const card = await itemCard('명동');
    await user.click(
      within(card).getByRole('button', { name: setName(copy['trip.lock.DATE']) }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    const scheduled = trip.days
      .flatMap((day) => day.items)
      .find((candidate) => candidate.place.name === '명동');
    expect(sent[0]?.body).toEqual({
      type: 'DATE',
      locked: true,
      date: scheduled?.date,
    });
  });
});

describe('FE-307-T1 RESERVATION is never offered as a toggle', () => {
  it('offers no control to set a reservation lock', async () => {
    // COMPONENT_CATALOG: `reservation-locked`는 toggle로 제공하지 않는다. The
    // contract agrees — its body needs a date and a startTime, so it is an
    // input, not a switch. The input has no design yet (FCR-033).
    const card = await itemCard('명동');
    expect(
      within(card).queryByRole('button', {
        name: setName(copy['trip.lock.RESERVATION']),
      }),
    ).toBeNull();
  });

  it('never sends a RESERVATION body from this screen', async () => {
    const user = userEvent.setup();
    const card = await itemCard('명동');
    for (const button of within(card).getAllByRole('button')) {
      if (button.getAttribute('aria-label')?.startsWith('Set')) {
        await user.click(button);
      }
    }
    await waitFor(() => {
      expect(sent.length).toBeGreaterThan(0);
    });
    expect(sent.some((r) => r.path.endsWith('/constraints/RESERVATION'))).toBe(false);
  });

  it('offers TIME for an item that has a time to pin', async () => {
    // Every item in this fixture is scheduled, so the offered case is what the
    // screen can show here; the no-time case is covered as a unit in
    // locks.test.ts, where an item without a startTime can be built.
    const scheduled = trip.days
      .flatMap((day) => day.items)
      .find((candidate) => candidate.place.name === '명동');
    expect(scheduled?.startTime).not.toBeNull();
    const card = await itemCard('명동');
    expect(
      within(card).getByRole('button', { name: setName(copy['trip.lock.TIME']) }),
    ).toBeInTheDocument();
  });
});

describe('FE-307-T2 the set action shows its states', () => {
  it('says it is working while the request is in flight', async () => {
    server.use(
      http.put(
        `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
        async () => {
          await delay(60);
          return HttpResponse.json(
            { trip, changedItemIds: [] },
            { headers: { ETag: '"2"' } },
          );
        },
      ),
    );
    const user = userEvent.setup();
    const card = await itemCard('명동');
    await user.click(
      within(card).getByRole('button', { name: setName(copy['trip.lock.MUST_VISIT']) }),
    );
    expect(await screen.findByText(copy['trip.lock.applying'])).toBeInTheDocument();
  });

  it('reports a failure to set, not a failure to release', async () => {
    // RATE_LIMITED rather than TRIP_CHANGED: a conflict now has its own
    // message and its own recovery (the trip is refetched), so it would no
    // longer exercise the set-vs-release distinction this test is about.
    server.use(
      http.put(
        `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
        () => problemResponse('RATE_LIMITED'),
      ),
    );
    const user = userEvent.setup();
    const card = await itemCard('명동');
    await user.click(
      within(card).getByRole('button', { name: setName(copy['trip.lock.MUST_VISIT']) }),
    );
    // The two actions share a row; a message naming the wrong one tells the
    // user something that did not happen.
    expect(await screen.findByText(copy['trip.lock.applyFailed'])).toBeInTheDocument();
    expect(screen.queryByText(copy['trip.lock.releaseFailed'])).toBeNull();
  });
});

describe('FE-307-T3 keyboard and names', () => {
  it('names each control for what pressing it does', async () => {
    const card = await itemCard('명동');
    const button = within(card).getByRole('button', {
      name: setName(copy['trip.lock.MUST_VISIT']),
    });
    // Visible text too, not only the accessible name: a release control and a
    // set control that both read "Must visit" are indistinguishable on screen.
    expect(button).toHaveTextContent(setName(copy['trip.lock.MUST_VISIT']));
  });

  it('reaches the control by keyboard and activates it with Enter', async () => {
    const user = userEvent.setup();
    const card = await itemCard('명동');
    const button = within(card).getByRole('button', {
      name: setName(copy['trip.lock.MUST_VISIT']),
    });
    button.focus();
    expect(button).toHaveFocus();
    await user.keyboard('{Enter}');
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
  });
});
