// @vitest-environment happy-dom
//
// FE-303 acceptance (FR-CAN-05, FR-CAN-07, FR-ITM-02), S07-8 `412:1912`.
//
// FE-303-T1: scheduling a candidate applies atomically and leaves no partial
//            state when it fails.
// FE-303-T2: default/loading/empty/error/offline/stale each render, and the
//            five match states each render as themselves.
// FE-303-T3: keyboard reach, focus, accessible names.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { candidateFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const page = candidateFixtures.page;
const active = page.items[0];
const scheduled = page.items[2];

interface Sent {
  ifMatch: string | null;
  idempotency: string | null;
  body: Record<string, unknown>;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method !== 'POST' || !request.url.includes('/items')) return;
    const clone = request.clone();
    void clone.json().then(
      (body: unknown) => {
        sent.push({
          ifMatch: request.headers.get('If-Match'),
          idempotency: request.headers.get('Idempotency-Key'),
          body: body as Record<string, unknown>,
        });
      },
      () => undefined,
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderPanel() {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/candidates`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function loaded() {
  return await screen.findByRole('heading', { level: 2, name: active?.place.name ?? '' });
}

/** Opens one candidate's date picker. */
async function openDates(name: string) {
  const user = userEvent.setup();
  renderPanel();
  await screen.findByRole('heading', { level: 2, name });
  const card = screen.getByRole('heading', { level: 2, name }).closest('article');
  if (!card) throw new Error('card not found');
  await user.click(within(card).getByRole('button', { name: copy['candidates.add'] }));
  return { user, card };
}

describe('FE-303-T2 the panel renders each state', () => {
  it('lists the saved places', async () => {
    renderPanel();
    await loaded();
    for (const item of page.items) {
      expect(
        screen.getByRole('heading', { level: 2, name: item.place.name }),
      ).toBeInTheDocument();
    }
  });

  it('reports loading before the list arrives', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates`, async () => {
        await delay('infinite');
        return HttpResponse.json(page);
      }),
    );
    renderPanel();
    expect(await screen.findByText(copy['candidates.loading'])).toBeInTheDocument();
  });

  it('offers a retry when the list fails', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates`, () => HttpResponse.error()),
    );
    renderPanel();
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['candidates.error']);
    expect(screen.getByRole('button', { name: copy['trip.retry'] })).toBeInTheDocument();
  });

  it('says so when nothing has been saved', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates`, () =>
        HttpResponse.json(candidateFixtures.pageEmpty),
      ),
    );
    renderPanel();
    expect(await screen.findByText(copy['candidates.empty'])).toBeInTheDocument();
  });

  it('offers no add action for a candidate already on the itinerary', async () => {
    renderPanel();
    await loaded();
    const card = screen
      .getByRole('heading', { level: 2, name: scheduled?.place.name ?? '' })
      .closest('article');
    expect(card).not.toBeNull();
    expect(
      within(card as HTMLElement).getByText(copy['candidates.scheduled']),
    ).toBeInTheDocument();
    // Adding it twice is exactly what this state prevents.
    expect(
      within(card as HTMLElement).queryByRole('button', { name: copy['candidates.add'] }),
    ).not.toBeInTheDocument();
  });
});

describe('FE-303-T2 the five match states each say their own thing', () => {
  it('shows eligible dates for an EXACT match', async () => {
    await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    // Two eligible slots in the fixture; the third is blocked.
    expect(within(dates).getAllByRole('button')).toHaveLength(2);
  });

  it('names a SIMILAR match as similar rather than presenting it as exact', async () => {
    await openDates(active?.place.name ?? '');
    expect(await screen.findByText(copy['candidates.match.SIMILAR'])).toBeInTheDocument();
  });

  it('does not present CHECKING as "no dates"', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates/:candidateId/matches`, () =>
        HttpResponse.json(candidateFixtures.matchChecking),
      ),
    );
    const { card } = await openDates(active?.place.name ?? '');
    // Scoped to one card: every unscheduled card now shows its own relation
    // badge, so a document-wide query matches each of them.
    expect(
      await within(card).findByText(copy['candidates.match.CHECKING']),
    ).toBeInTheDocument();
    // The server has not finished looking, so it must not claim nothing works.
    expect(
      within(card).queryByText(copy['candidates.match.NONE']),
    ).not.toBeInTheDocument();
  });

  it('does not present UNKNOWN as "no dates" either', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates/:candidateId/matches`, () =>
        HttpResponse.json(candidateFixtures.matchUnknown),
      ),
    );
    const { card } = await openDates(active?.place.name ?? '');
    expect(
      await within(card).findByText(copy['candidates.match.UNKNOWN']),
    ).toBeInTheDocument();
    expect(
      within(card).queryByText(copy['candidates.match.NONE']),
    ).not.toBeInTheDocument();
  });

  it('says NONE only when the server actually decided nothing fits', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates/:candidateId/matches`, () =>
        HttpResponse.json(candidateFixtures.matchNone),
      ),
    );
    const { card } = await openDates(active?.place.name ?? '');
    expect(
      await within(card).findByText(copy['candidates.match.NONE']),
    ).toBeInTheDocument();
    expect(
      within(card).queryByRole('list', { name: copy['candidates.pickDate'] }),
    ).not.toBeInTheDocument();
  });

  it('shows a blocked date with a reason rather than dropping it', async () => {
    await openDates(page.items[1]?.place.name ?? '');
    // A date that simply is not there reads as a bug.
    expect(await screen.findByText(/10\/4.*|.*10\. 4\./)).toBeInTheDocument();
    expect(screen.getByText(new RegExp(copy['candidates.blocked']))).toBeInTheDocument();
  });
});

describe('FE-303-T1 scheduling is one atomic request', () => {
  it('sends candidateId with the item so the server does both together', async () => {
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // One request, not two: adding the item and marking the candidate
    // scheduled is a single transaction (invariant 5).
    expect(sent[0]?.body.candidateId).toBe(page.items[1]?.id);
    expect(sent[0]?.body.placeId).toBe(page.items[1]?.place.id);
  });

  it('carries If-Match and an Idempotency-Key', async () => {
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // If-Match because it changes the schedule; Idempotency-Key because a
    // repeated submit must not add the place twice (invariant 6).
    expect(sent[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(sent[0]?.ifMatch).toMatch(/^"[1-9][0-9]*"$/);
    expect(sent[0]?.idempotency).toBeTruthy();
  });

  it('appends to the end of the chosen day', async () => {
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    // The first eligible slot is 2026-10-05, which already holds one item.
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body.date).toBe('2026-10-05');
    expect(sent[0]?.body.position).toBe(1);
  });

  it('leaves no partial state when the schedule moved underneath', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/items`, () => problemResponse('TRIP_CHANGED')),
    );
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    expect(await screen.findByText(copy['candidates.conflict'])).toBeInTheDocument();
    // The candidate is still a candidate: nothing was marked scheduled by the
    // client on its own.
    expect(
      screen.getByRole('heading', { level: 2, name: page.items[1]?.place.name ?? '' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(copy['candidates.scheduled'])).toBeInTheDocument();
  });

  it('reports a plain failure without claiming the place was added', async () => {
    server.use(http.post(`${API_BASE}/trips/:tripId/items`, () => HttpResponse.error()));
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    expect(await screen.findByText(copy['candidates.addFailed'])).toBeInTheDocument();
  });

  it('marks the candidate scheduled once the server confirms it', async () => {
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    // Two scheduled cards afterwards: the one that already was, and this one.
    await waitFor(() => {
      expect(screen.getAllByText(copy['candidates.scheduled'])).toHaveLength(2);
    });
  });
});

describe('FE-303 removing a saved place (FR-CAN-06)', () => {
  it('offers a remove action named after the place', async () => {
    renderPanel();
    await loaded();
    // Named, not a bare "제거": three identical buttons in a list tell a screen
    // reader nothing about which place they act on.
    expect(
      screen.getByRole('button', {
        name: copy['candidates.removeNamed'].replace('{name}', active?.place.name ?? ''),
      }),
    ).toBeInTheDocument();
  });

  it('removes the candidate from the list', async () => {
    const user = userEvent.setup();
    renderPanel();
    await loaded();
    await user.click(
      screen.getByRole('button', {
        name: copy['candidates.removeNamed'].replace('{name}', active?.place.name ?? ''),
      }),
    );
    await waitFor(() => {
      expect(
        screen.queryByRole('heading', { level: 2, name: active?.place.name ?? '' }),
      ).not.toBeInTheDocument();
    });
  });

  it('offers removal for a scheduled candidate too, without touching the item', async () => {
    renderPanel();
    await loaded();
    // Dismissing a candidate never changes the schedule (invariant 1), so the
    // action stays available; the item it produced is removed separately.
    expect(
      screen.getByRole('button', {
        name: copy['candidates.removeNamed'].replace(
          '{name}',
          scheduled?.place.name ?? '',
        ),
      }),
    ).toBeInTheDocument();
  });

  it('reports a failed removal instead of hiding the row anyway', async () => {
    server.use(
      http.delete(`${API_BASE}/trips/:tripId/candidates/:candidateId`, () =>
        HttpResponse.error(),
      ),
    );
    const user = userEvent.setup();
    renderPanel();
    await loaded();
    await user.click(
      screen.getByRole('button', {
        name: copy['candidates.removeNamed'].replace('{name}', active?.place.name ?? ''),
      }),
    );
    expect(await screen.findByText(copy['candidates.removeFailed'])).toBeInTheDocument();
    // Still listed: the client does not pretend a failed delete succeeded.
    expect(
      screen.getByRole('heading', { level: 2, name: active?.place.name ?? '' }),
    ).toBeInTheDocument();
  });
});

describe('FE-303-T3 the panel is reachable and named', () => {
  it('names the screen with the count once the list has arrived', async () => {
    renderPanel();
    await loaded();
    // Three saved, one of them already scheduled — all three are listed.
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      copy['candidates.open'].replace('{count}', '3'),
    );
  });

  it('does not claim zero saved places while the list is still loading', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates`, async () => {
        await delay('infinite');
        return HttpResponse.json(page);
      }),
    );
    renderPanel();
    const heading = await screen.findByRole('heading', { level: 1 });
    // "0" here reads as data loss to someone who saved three places.
    expect(heading).not.toHaveTextContent(
      copy['candidates.open'].replace('{count}', '0'),
    );
  });

  it('offers a way back to the itinerary', async () => {
    const user = userEvent.setup();
    renderPanel();
    // A NavBar button, not a link: it navigates to a named destination rather
    // than calling history.go(-1), which on a deep link or a reload would send
    // the user off this app entirely (COMPONENT_CATALOG C49).
    const back = await screen.findByRole('button', { name: copy['candidates.back'] });
    await user.click(back);
    // And it actually lands on the itinerary.
    expect(
      await screen.findByRole('heading', { level: 1, name: trip.title }),
    ).toBeInTheDocument();
  });

  it('announces whether a card is expanded', async () => {
    renderPanel();
    await loaded();
    const card = screen
      .getByRole('heading', { level: 2, name: active?.place.name ?? '' })
      .closest('article') as HTMLElement;
    const toggle = within(card).getByRole('button', { name: copy['candidates.add'] });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.setup().click(toggle);
    await waitFor(() => {
      expect(
        within(card).getByRole('button', { name: copy['candidates.cancel'] }),
      ).toHaveAttribute('aria-expanded', 'true');
    });
  });

  it('names the date list so its buttons are not bare numbers', async () => {
    await openDates(page.items[1]?.place.name ?? '');
    expect(
      await screen.findByRole('list', { name: copy['candidates.pickDate'] }),
    ).toBeInTheDocument();
  });
});

describe('FE-303 credits each source the way the server named it', () => {
  it('shows the approved credit on a place that has one (FCR-031)', async () => {
    renderPanel();
    await loaded();
    // This ran the other way until BA-022: PlaceSummary carried no source, so
    // the card could satisfy neither CMP-ATT-001 nor CMP-ATT-003 and the test
    // guarded the absence. It now guards the presence.
    const credit = active?.place.sourceAttribution?.attribution ?? '';
    expect(credit).not.toBe('');
    expect(screen.getAllByText(credit).length).toBeGreaterThan(0);
  });

  it('shows no credit for a place the server did not attribute', async () => {
    renderPanel();
    await loaded();
    // sourceAttribution is null for a record with no external source. Printing
    // a provider there would imply an origin that was never granted, which is
    // what CMP-ATT-003 forbids.
    const unattributed = page.items.find((item) => !item.place.sourceAttribution);
    expect(unattributed).toBeDefined();
    const card = screen
      .getByRole('heading', { level: 2, name: unattributed?.place.name ?? '' })
      .closest('article') as HTMLElement;
    expect(within(card).queryByText(/한국관광공사/)).not.toBeInTheDocument();
  });

  it('prints no credit the response did not supply', async () => {
    renderPanel();
    await loaded();
    const served = new Set(
      page.items
        .map((item) => item.place.sourceAttribution?.attribution)
        .filter((text): text is string => typeof text === 'string'),
    );
    for (const node of screen.queryAllByText(/한국관광공사/)) {
      expect(served).toContain(node.textContent?.trim());
    }
  });
});
