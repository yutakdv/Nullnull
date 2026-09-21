// @vitest-environment happy-dom
//
// FE-305 slice 2 acceptance (FR-ITM-03, FR-ITM-05), S07-10 `521:3976`.
//
// FE-305-T1: a move that collides with a lock does not proceed without an
//            explicit confirmation.
// FE-305-T2: default/pending/error/conflict each render.
// FE-305-T3: keyboard reach, focus restore, accessible names, live region.
//
// The wire is asserted rather than the component's own state: a screen that
// believes it moved one item while sending a partial ordering would pass an
// inspection test and collide with the (trip, date, position) uniqueness in
// production.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { crowdFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

function fixtureItem(dayIndex: number, itemIndex: number) {
  const found = trip.days[dayIndex]?.items[itemIndex];
  if (!found) throw new Error('fixture shape changed');
  return found;
}

// 10-04: 경복궁 (MUST_VISIT + DATE) then 인사동 (TIME). 10-05: 명동. 10-06/07 empty.
const gyeongbok = fixtureItem(0, 0);
const insadong = fixtureItem(0, 1);
const myeongdong = fixtureItem(1, 0);

interface Sent {
  url: string;
  ifMatch: string | null;
  idempotency: string | null;
  body: { items: { itemId: string; date: string; position: number }[] } | null;
}

let sent: Sent[] = [];
let crowdRequests: URL[] = [];

beforeEach(() => {
  sent = [];
  crowdRequests = [];
  server.events.on('request:start', ({ request }) => {
    if (request.url.includes('/crowd-forecast')) {
      crowdRequests.push(new URL(request.url));
    }
    if (!request.url.includes('/items/reorder')) return;
    const clone = request.clone();
    const base = {
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
      idempotency: request.headers.get('Idempotency-Key'),
    };
    void clone.json().then(
      (body) => {
        sent.push({ ...base, body: body as Sent['body'] });
      },
      () => {
        sent.push({ ...base, body: null });
      },
    );
  });
});

describe('#105 FCR-029 the move sheet reads dated crowd only when opened', () => {
  it('makes no mounted-sheet requests, then one exact KST-window request', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    expect(crowdRequests).toHaveLength(0);

    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await waitFor(() => {
      expect(crowdRequests).toHaveLength(1);
    });

    expect(crowdRequests[0]?.pathname).toContain(
      `/places/${insadong.place.id}/crowd-forecast`,
    );
    expect(crowdRequests[0]?.searchParams.get('from')).toBe('2026-10-03T15:00:00.000Z');
    expect(crowdRequests[0]?.searchParams.get('to')).toBe('2026-10-07T14:59:59.999999Z');
    expect(within(sheet).getByText('Relative concentration 34')).toBeInTheDocument();
    expect(within(sheet).getAllByText('Official crowd forecast').length).toBeGreaterThan(
      0,
    );
    expect(within(sheet).getAllByText('출처: ⓒ한국관광공사').length).toBeGreaterThan(0);
    // The 10/4 row has no exact KST target point. It stays without a made-up
    // number or provider credit instead of borrowing 10/5's reading.
    const dayOne = within(sheet).getByRole('button', { name: /Day 1/ }).closest('li');
    expect(dayOne).not.toBeNull();
    expect(
      within(dayOne as HTMLElement).queryByText(/Relative concentration/),
    ).toBeNull();
    expect(within(dayOne as HTMLElement).queryByText(/한국관광공사/)).toBeNull();
  });

  it('states NO_COVERAGE instead of making an unavailable series look empty', async () => {
    server.use(
      http.get(`${API_BASE}/places/:placeId/crowd-forecast`, () =>
        HttpResponse.json({
          ...crowdFixtures.seriesUnavailable,
          unavailableReason: 'NO_COVERAGE',
        }),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));
    expect(
      await screen.findByText('No crowd forecast for these dates'),
    ).toBeInTheDocument();
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

async function cardFor(name: string) {
  await screen.findByRole('heading', { level: 3, name });
  const edit = screen.queryByRole('button', { name: copy['trip.editStart'] });
  if (edit) await userEvent.setup().click(edit);
  const heading = await screen.findByRole('heading', { level: 3, name });
  const card = heading.closest('article');
  if (!card) throw new Error('card not found');
  const menu = within(card).queryByRole('button', {
    name: copy['trip.item.actions'].replace('{name}', name),
  });
  if (menu?.getAttribute('aria-expanded') === 'false') {
    await userEvent.setup().click(menu);
  }
  return card;
}

const upName = (name: string) => copy['trip.reorder.up'].replace('{name}', name);
const downName = (name: string) => copy['trip.reorder.down'].replace('{name}', name);
const moveName = (name: string) => copy['trip.move.open'].replace('{name}', name);

describe('FE-305-T2 the controls reflect where the item sits', () => {
  it('disables up on the first item and down on the last', async () => {
    renderTrip();
    const first = await cardFor('경복궁');
    expect(within(first).getByRole('button', { name: upName('경복궁') })).toBeDisabled();
    expect(within(first).getByRole('button', { name: downName('경복궁') })).toBeEnabled();

    const last = await cardFor('인사동');
    expect(within(last).getByRole('button', { name: downName('인사동') })).toBeDisabled();
  });

  it('disables both on a lone item', async () => {
    renderTrip();
    const only = await cardFor('명동');
    expect(within(only).getByRole('button', { name: upName('명동') })).toBeDisabled();
    expect(within(only).getByRole('button', { name: downName('명동') })).toBeDisabled();
  });
});

// Invariant 6: a retry of the same press must replay one command, not queue a
// second one.
//
// The key used to be minted inside the mutate() call, so every press produced a
// fresh UUID while the comment above `send` claimed the opposite — "the key is
// minted per user action ... so a retry of this press reuses it". Pressing the
// same arrow again after a failure was therefore a SECOND move to the server.
// The existing assertion could not see it: `expect(idempotency).toBeTruthy()`
// is satisfied by any UUID, including a different one each time.
describe('FE-305-T1 a retry replays the move rather than queueing another', () => {
  it('reuses the key when the same press is retried after a failure', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () =>
        problemResponse('INTERNAL_ERROR'),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    const up = within(card).getByRole('button', { name: upName('인사동') });

    await user.click(up);
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    await user.click(up);
    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });

    expect(sent[0]?.idempotency).toBeTruthy();
    expect(sent[1]?.idempotency).toBe(sent[0]?.idempotency);
  });

  it('mints a new key when the user aims at a different day', async () => {
    // A different destination is a different command. If it replayed the first
    // key the server would treat the second move as a repeat of the first and
    // the item would land on the wrong day — or nowhere.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () =>
        problemResponse('INTERNAL_ERROR'),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');

    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 3/ }));
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });

    const nextCard = await cardFor('인사동');
    await user.click(within(nextCard).getByRole('button', { name: moveName('인사동') }));
    const again = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(again).getByRole('button', { name: /Day 4/ }));
    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });

    expect(sent[1]?.idempotency).not.toBe(sent[0]?.idempotency);
  });
});

describe('FE-305-T1 a reorder sends the whole day in one request', () => {
  it('carries If-Match, an Idempotency-Key, and every item of the day', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(sent[0]?.idempotency).toBeTruthy();
    // Both items of 10-04, renumbered from zero — not just the one that moved.
    expect(sent[0]?.body).toEqual({
      items: [
        { itemId: insadong.id, date: '2026-10-04', position: 0 },
        { itemId: gyeongbok.id, date: '2026-10-04', position: 1 },
      ],
    });
  });

  it('reorders the rendered list', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));

    await waitFor(() => {
      const names = screen
        .getAllByRole('heading', { level: 3 })
        .map((h) => h.textContent);
      expect(names.slice(0, 2)).toEqual(['인사동', '경복궁']);
    });
  });

  it('announces the result, because the visual change is the only other signal', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));

    expect(
      await screen.findByText(
        copy['trip.reorder.moved'].replace('{name}', '인사동').replace('{position}', '1'),
      ),
    ).toBeInTheDocument();
  });
});

describe('FE-305-T1 a date move asks before releasing a lock', () => {
  it('opens the sheet showing the current day as unpickable', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));

    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    // Shown rather than hidden: the user needs to see where it is now.
    expect(within(sheet).getByText(copy['trip.move.current'])).toBeInTheDocument();
    expect(within(sheet).getByRole('button', { name: /Day 1/ })).toBeDisabled();
  });

  it('states what the move preserves', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));

    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    expect(within(sheet).getByText(copy['trip.move.keepsTime'])).toBeInTheDocument();
  });

  it('moves an unlocked-for-date item without a confirm', async () => {
    const user = userEvent.setup();
    renderTrip();
    // 인사동 carries TIME only, which the frame says carries over.
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 3/ }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    const entries = sent[0]?.body?.items ?? [];
    // Source day closes its gap AND the target receives the item: both days in
    // one payload.
    expect(entries).toContainEqual({
      itemId: gyeongbok.id,
      date: '2026-10-04',
      position: 0,
    });
    expect(entries).toContainEqual({
      itemId: insadong.id,
      date: '2026-10-06',
      position: 0,
    });
  });

  it('asks first when a DATE lock pins the item, and names what releases', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(within(card).getByRole('button', { name: moveName('경복궁') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 2/ }));

    const confirm = await screen.findByRole('dialog', {
      name: copy['trip.move.dateLock.title'],
    });
    expect(confirm).toHaveTextContent(copy['trip.move.dateLock.body']);
    // Nothing has gone to the server yet.
    expect(sent).toHaveLength(0);
  });

  it('moves only after the confirm is accepted', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(within(card).getByRole('button', { name: moveName('경복궁') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 2/ }));
    const confirm = await screen.findByRole('dialog', {
      name: copy['trip.move.dateLock.title'],
    });
    await user.click(
      within(confirm).getByRole('button', { name: copy['trip.move.dateLock.confirm'] }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body?.items).toContainEqual({
      itemId: gyeongbok.id,
      date: '2026-10-05',
      position: 1,
      // The dialog asked to release the DATE lock and the user said yes; the
      // contract makes naming it here the only way it is released.
      releaseConstraints: ['DATE'],
    });
  });

  it('carries the released lock on the moved item only', async () => {
    // The consent is about one stop. Naming the lock on every entry would ask
    // the server to release locks the user was never asked about, which is the
    // auto-release invariant 7 forbids.
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(within(card).getByRole('button', { name: moveName('경복궁') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 2/ }));
    await user.click(
      within(
        await screen.findByRole('dialog', { name: copy['trip.move.dateLock.title'] }),
      ).getByRole('button', { name: copy['trip.move.dateLock.confirm'] }),
    );

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    const items = sent[0]?.body?.items ?? [];
    const named = items.filter(
      (entry: Record<string, unknown>) => entry.releaseConstraints !== undefined,
    );
    expect(named).toHaveLength(1);
    expect((named[0] as Record<string, unknown>).itemId).toBe(gyeongbok.id);
  });

  it('releases nothing when no lock stood in the way', async () => {
    // 명동 carries no locks, so its move needs no consent and must not claim
    // any: a releaseConstraints on an unlocked entry is a request to delete
    // rows the user never saw.
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('명동');
    await user.click(within(card).getByRole('button', { name: moveName('명동') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 1/ }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    for (const entry of sent[0]?.body?.items ?? []) {
      expect((entry as Record<string, unknown>).releaseConstraints).toBeUndefined();
    }
  });

  it('sends nothing when the confirm is cancelled', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('경복궁');
    await user.click(within(card).getByRole('button', { name: moveName('경복궁') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(within(sheet).getByRole('button', { name: /Day 2/ }));
    const confirm = await screen.findByRole('dialog', {
      name: copy['trip.move.dateLock.title'],
    });
    await user.click(
      within(confirm).getByRole('button', { name: copy['trip.lock.cancel'] }),
    );

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: copy['trip.move.dateLock.title'] }),
      ).not.toBeInTheDocument();
    });
    expect(sent).toEqual([]);
  });

  it('refuses to move a reservation-pinned item at all', async () => {
    const reserved = {
      ...trip,
      days: trip.days.map((day) =>
        day.date === '2026-10-05'
          ? {
              ...day,
              items: day.items.map((i) => ({
                ...i,
                constraints: [
                  {
                    type: 'RESERVATION' as const,
                    locked: true as const,
                    source: 'IMPORT' as const,
                    date: '2026-10-05',
                    startTime: '19:00:00',
                    endTime: null,
                  },
                ],
              })),
            }
          : day,
      ),
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(reserved, { headers: { ETag: `"${String(trip.version)}"` } }),
      ),
    );
    renderTrip();
    const card = await cardFor('명동');
    // The booking lives elsewhere, so this screen cannot honour a confirm.
    expect(within(card).getByRole('button', { name: moveName('명동') })).toBeDisabled();
  });
});

describe('FE-305-T2 a failed move says so and changes nothing', () => {
  it('reports a version conflict without reordering the list', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));

    expect(await screen.findByText(copy['trip.conflict'])).toBeInTheDocument();
    const names = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(names.slice(0, 2)).toEqual(['경복궁', '인사동']);
  });

  it('reloads the trip so the next press carries a current ETag', async () => {
    // The cached ETag is stale the moment the server says TRIP_CHANGED, so
    // without a refetch every later press sends the same If-Match and fails
    // identically — every move control on every item is dead until the page is
    // reloaded. LockRow documents the same defect it already fixed.
    let reads = 0;
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () => {
        reads += 1;
        return HttpResponse.json(trip, {
          headers: { ETag: `"${String(trip.version)}"` },
        });
      }),
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    const before = reads;
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));
    await screen.findByText(copy['trip.conflict']);

    await waitFor(() => {
      expect(reads).toBeGreaterThan(before);
    });
  });

  it('does not reload for an ordinary failure', async () => {
    // Only a conflict means the cached trip is stale. A dropped connection
    // leaves it current, and refetching would hide that nothing changed.
    let reads = 0;
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () => {
        reads += 1;
        return HttpResponse.json(trip, {
          headers: { ETag: `"${String(trip.version)}"` },
        });
      }),
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () => HttpResponse.error()),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));
    await screen.findByText(copy['trip.move.failed']);
    const after = reads;
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(reads).toBe(after);
  });

  it('reports a plain failure too', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items/reorder`, () => HttpResponse.error()),
    );
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: upName('인사동') }));
    expect(await screen.findByText(copy['trip.move.failed'])).toBeInTheDocument();
  });
});

describe('FE-305-T3 the sheet behaves like a dialog', () => {
  it('focuses the safe control and closes on Escape', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    await user.click(within(card).getByRole('button', { name: moveName('인사동') }));
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await waitFor(() => {
      expect(
        within(sheet).getByRole('button', { name: copy['trip.move.cancel'] }),
      ).toHaveFocus();
    });

    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: copy['trip.move.title'] }),
      ).not.toBeInTheDocument();
    });
    expect(sent).toEqual([]);
  });

  it('returns focus to the control that opened it', async () => {
    const user = userEvent.setup();
    renderTrip();
    const card = await cardFor('인사동');
    const trigger = within(card).getByRole('button', { name: moveName('인사동') });
    await user.click(trigger);
    const sheet = await screen.findByRole('dialog', { name: copy['trip.move.title'] });
    await user.click(
      within(sheet).getByRole('button', { name: copy['trip.move.cancel'] }),
    );
    await waitFor(() => {
      expect(trigger).toHaveFocus();
    });
  });

  it('names every control for the item it acts on', async () => {
    renderTrip();
    const card = await cardFor('명동');
    // Three identical "↑" buttons in a list tell a screen reader nothing.
    expect(
      within(card).getByRole('button', { name: upName('명동') }),
    ).toBeInTheDocument();
    expect(
      within(card).getByRole('button', { name: moveName('명동') }),
    ).toBeInTheDocument();
    expect(myeongdong.place.name).toBe('명동');
  });
});

describe('each move sheet is labelled by its OWN heading', () => {
  // This screen mounts one MoveDaySheet per item, and the sheet used a
  // hardcoded `id="move-day-title"` for both the <h2> and its
  // aria-labelledby. getElementById returns the first match in document
  // order, so on a trip with N stops every sheet after the first was named by
  // the FIRST sheet's heading — a screen-reader user opening "move 명동" heard
  // the heading belonging to 경복궁's sheet. ConfirmDialog already used
  // useId(); these two did not.
  it('gives every sheet a distinct title id', async () => {
    renderTrip();
    await screen.findByRole('heading', { level: 3, name: '경복궁' });
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: copy['trip.editStart'] }));
    await cardFor('경복궁');
    // Waiting for the move control, not just the h1: the sheets mount with the
    // item cards, and the h1 arrives before them.
    await screen.findByRole('button', {
      name: moveName('경복궁'),
    });

    // querySelectorAll, not getAllByRole: a closed <dialog> is not exposed
    // with the dialog role, and these are all closed until one is opened.
    const ids = [...document.querySelectorAll('dialog[aria-labelledby]')].map((d) =>
      d.getAttribute('aria-labelledby'),
    );

    expect(ids.length).toBeGreaterThan(1); // or this asserts nothing
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('points each aria-labelledby at an element that exists', async () => {
    renderTrip();
    await screen.findByRole('heading', { level: 3, name: '경복궁' });
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: copy['trip.editStart'] }));
    await cardFor('경복궁');
    await screen.findByRole('button', {
      name: moveName('경복궁'),
    });

    for (const dialog of document.querySelectorAll('dialog[aria-labelledby]')) {
      const id = dialog.getAttribute('aria-labelledby');
      if (!id) continue;
      // Scoped to the dialog itself: a document-wide lookup would find the
      // first sheet's heading and pass even when the id is shared.
      expect(dialog.querySelector(`#${CSS.escape(id)}`)).not.toBeNull();
    }
  });
});
