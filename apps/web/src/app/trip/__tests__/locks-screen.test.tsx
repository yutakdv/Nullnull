// @vitest-environment happy-dom
//
// FE-304 acceptance (FR-CON-02, FR-CON-05), S07-7 `413:2081` / S07-10b `527:3876`.
//
// FE-304-T1: the four locks work independently and none releases on its own.
// FE-304-T2: default/loading/error/conflict each render.
// FE-304-T3: keyboard reach, focus, accessible names.
//
// Independence is asserted from what goes on the wire and what comes back, not
// from the component's own state: a screen that believed it released one lock
// while sending a request that clears several would pass an inspection test and
// silently break invariant 7.
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
// 경복궁 carries MUST_VISIT and DATE; 인사동 carries TIME.
const withTwoLocks = trip.days[0]?.items[0];
const withTime = trip.days[0]?.items[1];

let sent: { method: string; url: string; ifMatch: string | null }[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method !== 'DELETE') return;
    sent.push({
      method: request.method,
      url: request.url,
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

/** The card for one place, once the trip has loaded. */
async function cardFor(name: string) {
  const heading = await screen.findByRole('heading', { level: 3, name });
  const card = heading.closest('article');
  if (!card) throw new Error('card not found');
  return card;
}

function releaseButton(card: HTMLElement, lockKey: 'MUST_VISIT' | 'DATE' | 'TIME') {
  return within(card).getByRole('button', {
    name: copy['trip.lock.release'].replace('{lock}', copy[`trip.lock.${lockKey}`]),
  });
}

describe('FE-304-T2 the locks an item carries are shown', () => {
  it('shows each lock with a name, not an icon alone', async () => {
    renderTrip();
    const card = await cardFor('경복궁');
    expect(within(card).getByText(copy['trip.lock.MUST_VISIT'])).toBeInTheDocument();
    expect(within(card).getByText(copy['trip.lock.DATE'])).toBeInTheDocument();
    // TIME is on the other item, so it is absent here.
    expect(within(card).queryByText(copy['trip.lock.TIME'])).not.toBeInTheDocument();
  });

  it('names each control for what pressing it does', async () => {
    renderTrip();
    const card = await cardFor('경복궁');
    // "날짜 고정" as a button name reads as if it applies the lock.
    expect(releaseButton(card, 'DATE')).toBeInTheDocument();
  });

  it('renders nothing for an item with no locks', async () => {
    renderTrip();
    const card = await cardFor('명동');
    expect(within(card).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('FE-304-T1 releasing one lock leaves the others alone', () => {
  it('asks before releasing MUST_VISIT, and says what carries over', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'MUST_VISIT'));

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(copy['trip.lock.mustVisit.body']);
    // The surviving lock is named, so "the rest stay" is checkable rather than
    // merely reassuring.
    expect(dialog).toHaveTextContent(copy['trip.lock.DATE']);
  });

  it('sends one request naming only that constraint', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'MUST_VISIT'));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.lock.mustVisit.confirm'],
      }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // DELETE /constraints/{type}: one lock per request, so there is no shape
    // in which two could be cleared at once.
    expect(sent[0]?.url).toContain(
      `/items/${withTwoLocks?.id ?? ''}/constraints/MUST_VISIT`,
    );
    expect(sent[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
  });

  it('leaves the other lock in place afterwards', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'MUST_VISIT'));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.lock.mustVisit.confirm'],
      }),
    );

    await waitFor(() => {
      expect(
        within(card).queryByText(copy['trip.lock.MUST_VISIT']),
      ).not.toBeInTheDocument();
    });
    // Invariant 7: DATE was never named in the request and must survive it.
    expect(within(card).getByText(copy['trip.lock.DATE'])).toBeInTheDocument();
  });

  it('releases nothing when the confirm is cancelled', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'DATE'));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.lock.cancel'],
      }),
    );

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(sent).toHaveLength(0);
    expect(within(card).getByText(copy['trip.lock.DATE'])).toBeInTheDocument();
  });

  it('releases TIME without a confirm, because no frame asks for one', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(releaseButton(card, 'TIME'));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.url).toContain(`/items/${withTime?.id ?? ''}/constraints/TIME`);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('never releases a lock without a press', async () => {
    renderTrip();
    await cardFor('경복궁');
    // Rendering the screen, loading the trip and opening no dialog must not
    // touch a constraint. Nothing releases automatically (invariant 7).
    expect(sent).toEqual([]);
  });
});

describe('FE-304-T2 a failed release says so and changes nothing', () => {
  it('reports a conflict without dropping the lock from the screen', async () => {
    server.use(
      http.delete(
        `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
        () => problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(releaseButton(card, 'TIME'));

    expect(await screen.findByText(copy['trip.lock.releaseFailed'])).toBeInTheDocument();
    // The lock is still there: the client does not pretend a failed delete
    // succeeded.
    expect(within(card).getByText(copy['trip.lock.TIME'])).toBeInTheDocument();
  });

  it('reports a plain failure too', async () => {
    server.use(
      http.delete(
        `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
        () => HttpResponse.error(),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(releaseButton(card, 'TIME'));
    expect(await screen.findByText(copy['trip.lock.releaseFailed'])).toBeInTheDocument();
  });
});

describe('FE-304-T3 the controls are reachable and named', () => {
  it('focuses the safe choice in the confirm', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'DATE'));
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => {
      expect(
        within(dialog).getByRole('button', { name: copy['trip.lock.cancel'] }),
      ).toHaveFocus();
    });
  });

  it('returns focus to the lock that opened the confirm', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    const trigger = releaseButton(card, 'DATE');
    await user.click(trigger);
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.lock.cancel'],
      }),
    );
    await waitFor(() => {
      expect(trigger).toHaveFocus();
    });
  });

  it('closes the confirm on Escape without releasing', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(releaseButton(card, 'DATE'));
    await screen.findByRole('dialog');
    await user.keyboard('{Escape}');

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(sent).toEqual([]);
  });
});
