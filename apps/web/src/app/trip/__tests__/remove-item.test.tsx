// @vitest-environment happy-dom
//
// FR-ITM-06 (P0): taking a stop off the itinerary, and choosing what happens to
// the saved place. The hook, the msw handler and eighteen translated strings
// all existed with no control rendering them, so nothing exercised this at all.
//
// The assertion that carries the weight is the disposition on the wire. Both
// outcomes remove the item from the day, so a test that only checked the list
// would pass whichever button was pressed — and the difference between them is
// the whole feature.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
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
const firstItem = trip.days[0]?.items[0];
const itemName = firstItem?.place.name ?? '';

/** Every DELETE the screen sent, with the disposition it asked for. */
let deletes: { path: string; disposition: string | null; ifMatch: string | null }[] = [];

beforeEach(() => {
  deletes = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method !== 'DELETE') return;
    const url = new URL(request.url);
    if (!/\/items\//.test(url.pathname)) return;
    deletes.push({
      path: url.pathname,
      disposition: url.searchParams.get('disposition'),
      ifMatch: request.headers.get('If-Match'),
    });
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

/** Opens the confirm for the first stop. */
async function openConfirm(user: ReturnType<typeof userEvent.setup>) {
  renderTrip();
  await screen.findByRole('heading', { level: 1, name: trip.title });
  await user.click(
    screen.getByRole('button', {
      name: copy['trip.remove.open'].replace('{name}', itemName),
    }),
  );
  return screen.getByRole('dialog', { name: copy['trip.remove.title'] });
}

describe('FE-305 removing a stop asks what happens to the saved place', () => {
  it('asks before removing anything', async () => {
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    expect(dialog).toBeInTheDocument();
    // Nothing has been sent yet: the press opens a question, not a deletion.
    expect(deletes).toHaveLength(0);
  });

  it('keeps the place as a candidate when that is the answer', async () => {
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.keepCandidate'] }),
    );

    await waitFor(() => {
      expect(deletes).toHaveLength(1);
    });
    // The disposition IS the choice. Both answers take the item off the day, so
    // this is the only thing that distinguishes them on the wire.
    expect(deletes[0]?.disposition).toBe('RESTORE_CANDIDATE');
    expect(deletes[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
  });

  it('discards the place entirely when that is the answer', async () => {
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.discard'] }),
    );

    await waitFor(() => {
      expect(deletes).toHaveLength(1);
    });
    expect(deletes[0]?.disposition).toBe('REMOVE');
  });

  it('says which outcome happened rather than a single "removed"', async () => {
    // One message for both would hide whether the place is still in the saved
    // list, which is the only thing the user was asked to decide.
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.keepCandidate'] }),
    );
    expect(
      await screen.findByText(
        copy['trip.remove.keptAsCandidate'].replace('{name}', itemName),
      ),
    ).toBeInTheDocument();
  });

  it('sends nothing when the user cancels', async () => {
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.cancel'] }),
    );
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(deletes).toHaveLength(0);
  });

  it('closes on Escape without removing anything', async () => {
    const user = userEvent.setup();
    await openConfirm(user);
    await user.keyboard('{Escape}');
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(deletes).toHaveLength(0);
  });

  it('reports a conflict instead of leaving a dead control', async () => {
    // A stale ETag means the trip moved on. Reporting without refetching would
    // leave every later press failing the same way until a page reload.
    server.use(
      http.delete(`${API_BASE}/trips/:tripId/items/:itemId`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.discard'] }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['trip.conflict']);
  });

  it('reports a plain failure as a failure, not as a conflict', async () => {
    server.use(
      http.delete(`${API_BASE}/trips/:tripId/items/:itemId`, () => HttpResponse.error()),
    );
    const user = userEvent.setup();
    const dialog = await openConfirm(user);
    await user.click(
      within(dialog).getByRole('button', { name: copy['trip.remove.discard'] }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['trip.remove.failed'],
    );
  });
});
