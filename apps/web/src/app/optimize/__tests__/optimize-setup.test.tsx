// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-501 acceptance (FR-OPT-01, FCR-010), S09-0 `415:2268`.
//
// FE-501-T1: only ITEM is enabled; DAY and TRIP send nothing.
// FE-501-T2: default/loading/error/empty/conflict each render.
// FE-501-T3: keyboard reach, focus and accessible names.
//
// T1 is checked at the wire. FCR-010's requirement is "DAY/TRIP은 숨기거나
// disabled `준비 중`이며 요청 0건" — a screen that merely greys the chips while
// still building a DAY body would look right and break the rule.
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
const first = trip.days[0]?.items[0];

interface Sent {
  body: unknown;
  ifMatch: string | null;
  idempotencyKey: string | null;
}

let sent: Sent[] = [];
/** Every request, so a stray trip write cannot hide behind a filter. */
let allRequests: { method: string; path: string }[] = [];

beforeEach(() => {
  sent = [];
  allRequests = [];
  server.events.on('request:start', ({ request }) => {
    allRequests.push({ method: request.method, path: new URL(request.url).pathname });
    if (request.method !== 'POST') return;
    if (!new URL(request.url).pathname.endsWith('/optimizations')) return;
    const clone = request.clone();
    void clone.json().then(
      (body) => {
        sent.push({
          body,
          ifMatch: request.headers.get('If-Match'),
          idempotencyKey: request.headers.get('Idempotency-Key'),
        });
      },
      () => undefined,
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderSetup() {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/optimize`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Renders and picks the first stop, which is the only way to enable submit. */
async function pickFirstStop(user: ReturnType<typeof userEvent.setup>) {
  renderSetup();
  // The heading renders in the loading branch too, so this waits for a
  // control that only exists once the trip has arrived.
  await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
  // The button's accessible name is the place plus its time, so this matches
  // on the name rather than demanding the whole string.
  await user.click(
    screen.getByRole('button', { name: new RegExp(first?.place.name ?? '') }),
  );
}

describe('FE-501-T1 only ITEM is offered, and the others send nothing', () => {
  it('enables ITEM and disables DAY and TRIP', async () => {
    renderSetup();
    expect(
      await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] }),
    ).toBeEnabled();
    expect(
      screen.getByRole('button', { name: copy['optimize.scope.DAY'] }),
    ).toBeDisabled();
    expect(
      screen.getByRole('button', { name: copy['optimize.scope.TRIP'] }),
    ).toBeDisabled();
  });

  it('says why the other scopes are unavailable', async () => {
    renderSetup();
    expect(
      await screen.findByText(copy['optimize.scope.comingSoon']),
    ).toBeInTheDocument();
  });

  it('sends an ITEM body with the chosen stop, version, ETag and key', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body).toEqual({
      scope: 'ITEM',
      targetItemId: first?.id,
      // What the run is computed against, read off the trip we loaded.
      inputTripVersion: trip.version,
      includeCandidates: false,
    });
    expect(sent[0]?.ifMatch).not.toBeNull();
    // Minted per submit: a retry must replay the run, not queue a second.
    expect(sent[0]?.idempotencyKey).not.toBeNull();
  });

  it('never sends a DAY or TRIP scope, whatever is pressed', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    // Press the disabled chips too — a disabled button fires nothing, and this
    // proves the submit path has no branch that could build another scope.
    await user.click(screen.getByRole('button', { name: copy['optimize.scope.DAY'] }));
    await user.click(screen.getByRole('button', { name: copy['optimize.scope.TRIP'] }));
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    for (const request of sent) {
      expect((request.body as { scope: string }).scope).toBe('ITEM');
    }
  });

  it('asks for a stop instead of sending an incomplete request', async () => {
    // targetItemId is required by the contract, so there is nothing to send
    // until the user picks one.
    const user = userEvent.setup();
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.needTarget'])).toBeInTheDocument();
    expect(sent).toHaveLength(0);
  });

  it('states that the itinerary does not change yet', async () => {
    // Invariant 3: the optimizer previews and the user applies. A screen that
    // did not say so invites the user to expect a changed trip.
    renderSetup();
    expect(await screen.findByText(copy['optimize.previewNote'])).toBeInTheDocument();
  });

  it('writes nothing to the trip when the run is queued', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // Queuing a preview is not a trip mutation (invariants 3 and 4). Checked
    // against EVERY request, not just the optimization POST: asserting on a
    // list that only ever collects optimization calls would say nothing about
    // trip writes at all.
    const tripWrites = allRequests.filter(
      (r) => r.method !== 'GET' && /\/trips\/[^/]+$/.test(r.path),
    );
    expect(tripWrites).toEqual([]);
    const itemWrites = allRequests.filter(
      (r) => r.method !== 'GET' && r.path.includes('/items'),
    );
    expect(itemWrites).toEqual([]);
  });
});

describe('FE-501-T2 the setup renders its states', () => {
  it('lists the stops the trip has', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    for (const item of trip.days.flatMap((day) => day.items)) {
      expect(
        screen.getByRole('button', { name: new RegExp(item.place.name) }),
      ).toBeInTheDocument();
    }
  });

  it('says so when the trip has no stops to optimize', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(tripFixtures.detailCreated, { headers: { ETag: '"1"' } }),
      ),
    );
    renderSetup();
    expect(await screen.findByText(copy['optimize.targetEmpty'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['optimize.submit'] })).toBeDisabled();
  });

  it('shows a loading state before the trip arrives', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, async () => {
        await delay(50);
        return HttpResponse.json(trip, {
          headers: { ETag: `"${String(trip.version)}"` },
        });
      }),
    );
    renderSetup();
    expect(await screen.findByRole('status')).toBeInTheDocument();
  });

  it('reports a conflict distinctly from a generic failure', async () => {
    // The contract's x-error-codes name TRIP_CHANGED for 409: the itinerary
    // moved on, so reloading is the recovery, not retrying blind.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.conflict'])).toBeInTheDocument();
    expect(screen.queryByText(copy['optimize.failed'])).toBeNull();
  });

  it('names a lock conflict rather than calling it a failure', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('LOCK_CONFLICT'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.locked'])).toBeInTheDocument();
  });
});

describe('FE-501-T3 keyboard and names', () => {
  it('reaches a stop by keyboard and selects it with Enter', async () => {
    const user = userEvent.setup();
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    const stop = screen.getByRole('button', {
      name: new RegExp(first?.place.name ?? ''),
    });
    stop.focus();
    expect(stop).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(stop).toHaveAttribute('aria-pressed', 'true');
  });

  it('groups the scope and the target under named legends', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    expect(
      screen.getByRole('group', { name: copy['optimize.scope'] }),
    ).toBeInTheDocument();
    const target = screen.getByRole('group', { name: copy['optimize.target'] });
    expect(within(target).getAllByRole('button').length).toBeGreaterThan(0);
  });

  it('offers a back control to the trip', async () => {
    const user = userEvent.setup();
    renderSetup();
    await user.click(await screen.findByRole('button', { name: copy['optimize.back'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'trip-heading',
      );
    });
  });
});
