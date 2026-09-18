// @vitest-environment happy-dom
//
// The undo wired end to end on the trip screen (S09-3, FE-504).
//
// `applied-panel.test.tsx` renders the four states from props and
// `applied-revert.test.ts` covers the rules; neither sends anything. This file
// is the one that watches the wire, and it exists because the two below can
// both be green while the request is wrong:
//
//   - If-Match is REQUIRED by the contract. A revert sent without it would
//     undo an apply on top of an edit this tab never saw. Measured: removing
//     the header from the mutation left every other test in the app green.
//   - The trip cache has to be invalidated, or the itinerary on screen keeps
//     showing what the undo just took back.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import {
  API_BASE,
  MOCK_RUN_ID,
  resetMockState,
} from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

interface Sent {
  method: string;
  url: string;
  ifMatch: string | null;
  idempotencyKey: string | null;
}

let sent: Sent[] = [];

beforeEach(() => {
  resetMockState();
  sent = [];
  server.events.on('request:start', ({ request }) => {
    sent.push({
      method: request.method,
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
      idempotencyKey: request.headers.get('Idempotency-Key'),
    });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
  resetMockState();
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

/** Applies the mock run, which is what gives the trip something to undo. */
async function applyTheRun() {
  await fetch(`${API_BASE}/optimizations/${MOCK_RUN_ID}/decisions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'If-Match': 'W/"trip-1"' },
    body: JSON.stringify({
      proposalId: '018f4a50-2b11-7c33-8a01-4e5f6a7b8c01',
      decision: 'APPLY',
    }),
  });
}

const revertRequests = () =>
  sent.filter((entry) => entry.method === 'POST' && entry.url.includes('/revert'));

describe('the undo reaches the server with what the contract requires', () => {
  it('sends If-Match and an Idempotency-Key when the traveller undoes', async () => {
    // The assertion is on the wire, not on the component: a panel that
    // believed it sent If-Match while sending nothing would pass an
    // inspection test and then overwrite a concurrent edit.
    await applyTheRun();
    const user = userEvent.setup();
    renderTrip();

    // The button's label carries the version, which the mock decides, so it
    // is matched by its stable prefix rather than by a number this test would
    // otherwise have to predict.
    // Matched by the label's stable prefix: the version in it comes from the
    // mock's trip state, and a test that predicted the number would break
    // whenever an unrelated fixture moved.
    const undoName = new RegExp(
      copy['trip.applied.revert'].split('{from}')[0]?.trim() ?? '',
    );
    const undo = await screen.findByRole('button', { name: undoName });
    await user.click(undo);

    await waitFor(() => {
      expect(revertRequests()).toHaveLength(1);
    });
    const request = revertRequests()[0];
    expect(request?.ifMatch, 'the contract requires If-Match').toBeTruthy();
    expect(
      request?.idempotencyKey,
      'the contract requires Idempotency-Key; a retry must replay, not re-run',
    ).toBeTruthy();
  });

  it('re-reads the trip after the undo, so the itinerary is not stale', async () => {
    // The revert writes a new revision. Without the invalidation the screen
    // keeps rendering the applied itinerary the undo just took back — and,
    // worse, keeps the old ETag, which the next mutation would send.
    //
    // Measured: dropping the invalidation left every other test in the app
    // green, which is why this one counts GETs on the wire rather than
    // inspecting the cache.
    await applyTheRun();
    const user = userEvent.setup();
    renderTrip();

    const undoName = new RegExp(
      copy['trip.applied.revert'].split('{from}')[0]?.trim() ?? '',
    );
    const undo = await screen.findByRole('button', { name: undoName });

    const tripReadsBefore = sent.filter(
      (entry) => entry.method === 'GET' && entry.url.includes(`/trips/${trip.id}`),
    ).length;

    await user.click(undo);

    await waitFor(() => {
      expect(revertRequests()).toHaveLength(1);
    });
    await waitFor(() => {
      const after = sent.filter(
        (entry) => entry.method === 'GET' && entry.url.includes(`/trips/${trip.id}`),
      ).length;
      expect(after, 'the trip must be read again after the undo').toBeGreaterThan(
        tripReadsBefore,
      );
    });
  });

  it('sends nothing at all when there is no applied run to undo', async () => {
    // resetMockState leaves the run undecided, so the history row carries
    // decision: null and the second request must not go out either.
    renderTrip();
    await screen.findByRole('heading', { level: 1 });

    await waitFor(() => {
      expect(sent.some((entry) => entry.url.includes('/optimizations'))).toBe(true);
    });
    expect(revertRequests()).toEqual([]);
    expect(
      sent.filter((entry) => entry.url.includes(`/optimizations/${MOCK_RUN_ID}`)),
      'an undecided run is not worth a second read',
    ).toEqual([]);
  });
});
