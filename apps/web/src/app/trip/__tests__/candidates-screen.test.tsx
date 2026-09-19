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

/**
 * Opens the schedule sheet for one candidate.
 *
 * Returns the sheet as well as the card because S07-10 `527:4732` puts the
 * dates in a <dialog>, not inside the card: a query scoped to the card can no
 * longer see them, and one scoped to the screen would match the sheet of
 * whichever card was opened last.
 */
async function openDates(name: string) {
  const user = userEvent.setup();
  renderPanel();
  await screen.findByRole('heading', { level: 2, name });
  const card = screen.getByRole('heading', { level: 2, name }).closest('article');
  if (!card) throw new Error('card not found');
  await user.click(within(card).getByRole('button', { name: copy['candidates.add'] }));
  const sheet = await within(card).findByRole('dialog', {
    name: copy['candidates.sheet.title'],
  });
  return { user, card, sheet };
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
    // Three rows in the fixture, of which two can be pressed. The blocked one
    // is in the same list, disabled — S07-10 `527:4732` draws every day of the
    // trip and greys the ones that cannot take the place, so the row count is
    // the trip's days and the ENABLED count is what the server allowed.
    const rows = within(dates).getAllByRole('button');
    expect(rows).toHaveLength(3);
    expect(rows.filter((row) => !row.hasAttribute('disabled'))).toHaveLength(2);
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
    const { sheet } = await openDates(page.items[1]?.place.name ?? '');
    // A date that simply is not there reads as a bug.
    expect(await within(sheet).findByText(/10\/4.*|.*10\. 4\./)).toBeInTheDocument();
    // The fixture's reasonCode is TIME_CONFLICT, and the row says what that
    // MEANS. Asserting the sentence rather than the code is the point: the
    // code is a server enum and putting it on screen is the defect this
    // checks for.
    expect(
      within(sheet).getByText(copy['candidates.sheet.blocked.TIME_CONFLICT']),
    ).toBeInTheDocument();
    expect(within(sheet).queryByText(/TIME_CONFLICT/)).toBeNull();
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

  it('replays the same key when the user retries the same date', async () => {
    // A present key is not the property that matters; a key held ACROSS a
    // retry is. The dangerous case is a request that commits server-side and
    // loses its response: the screen says 추가하지 못했어요 and re-enables the
    // date, the user presses it again, and a fresh key makes the server treat
    // that as a new command — scheduling the same place on the day twice.
    server.use(http.post(`${API_BASE}/trips/:tripId/items`, () => HttpResponse.error()));
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    const first = within(dates).getAllByRole('button')[0] as HTMLElement;
    await user.click(first);
    await screen.findByText(copy['candidates.addFailed']);
    await user.click(first);

    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });
    expect(sent[0]?.idempotency).toBe(sent[1]?.idempotency);
  });

  it('mints a new key for a different date, which is a different command', async () => {
    // The other direction, so the fix cannot be "hold one key forever".
    server.use(http.post(`${API_BASE}/trips/:tripId/items`, () => HttpResponse.error()));
    const { user } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    const buttons = within(dates).getAllByRole('button');
    await user.click(buttons[0] as HTMLElement);
    await screen.findByText(copy['candidates.addFailed']);
    await user.click(buttons[1] as HTMLElement);

    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });
    expect(sent[0]?.idempotency).not.toBe(sent[1]?.idempotency);
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

  it('says where to remove a scheduled place rather than failing generically', async () => {
    // The server refuses this one: BA-034's CandidateService throws
    // LOCK_CONFLICT for a candidate that is no longer ACTIVE. The test beside
    // this one checks the button EXISTS and never presses it, so nothing
    // noticed that pressing it produced the same 제거하지 못했어요 a network
    // error produces — a button that can never succeed, with no hint why.
    const user = userEvent.setup();
    renderPanel();
    await loaded();
    await user.click(
      screen.getByRole('button', {
        name: copy['candidates.removeNamed'].replace(
          '{name}',
          scheduled?.place.name ?? '',
        ),
      }),
    );

    expect(
      await screen.findByText(copy['candidates.removeScheduled']),
    ).toBeInTheDocument();
    expect(screen.queryByText(copy['candidates.removeFailed'])).toBeNull();
  });

  it('still reports an ordinary removal failure as a failure', async () => {
    // Only LOCK_CONFLICT means "remove it from the itinerary instead".
    server.use(
      http.delete(`${API_BASE}/trips/:tripId/candidates/:candidateId`, () =>
        problemResponse('RATE_LIMITED'),
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
    expect(screen.queryByText(copy['candidates.removeScheduled'])).toBeNull();
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
  it('names the screen with the trip total once it has arrived', async () => {
    renderPanel();
    await loaded();
    // The TOTAL, from the contract's `candidateCount` — not the length of the
    // page. This asserted the literal '3' (the page's length) while TripScreen
    // labelled its link with `candidateCount`, so the two screens reported
    // different totals for the same set one tap apart. The fixtures carry that
    // disagreement: candidateCount is greater than the bounded page length.
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      copy['candidates.open'].replace(
        '{count}',
        String(tripFixtures.detailScheduled.candidateCount),
      ),
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
    // "0" here reads as data loss to someone who saved places.
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
    // The card's own toggle, followed by identity rather than looked up again
    // by name: the sheet has a 취소 of its own and in en-US both render
    // "Cancel", so a name query matches two buttons once the sheet is open.
    await waitFor(() => {
      expect(toggle).toHaveAttribute('aria-expanded', 'true');
    });
    expect(toggle).toHaveAccessibleName(copy['candidates.cancel']);
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
    // Exactly one row owns the unattributed-card role. #287 moves that role
    // from the scheduled 명동 fixture to a new ACTIVE place; this assertion is
    // deliberately about the response meaning rather than either row's index.
    const unattributedItems = page.items.filter((item) => !item.place.sourceAttribution);
    expect(unattributedItems).toHaveLength(1);
    const unattributed = unattributedItems[0];
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

describe('FE-303-T3 focus survives the panel closing', () => {
  it('moves focus to the card rather than dropping it to the body', async () => {
    // Scheduling succeeds, the panel collapses, and the date button the user
    // was standing on is removed from the DOM. Focus then resets to
    // document.body, so the next Tab restarts from the top of the page and a
    // keyboard user loses their place in a list that can run to twenty cards.
    const { user, card } = await openDates(page.items[1]?.place.name ?? '');
    const dates = await screen.findByRole('list', { name: copy['candidates.pickDate'] });
    await user.click(within(dates).getAllByRole('button')[0] as HTMLElement);

    await waitFor(() => {
      expect(
        screen.queryByRole('list', { name: copy['candidates.pickDate'] }),
      ).not.toBeInTheDocument();
    });
    // Somewhere inside the card the user was working in, not nowhere.
    expect(document.activeElement).not.toBe(document.body);
    expect(card.contains(document.activeElement)).toBe(true);
  });
});

// CMP-ATT-001 at the screen boundary.
//
// PlaceThumbnail's own test proves the component refuses an uncredited image.
// What this asserts is that the card actually ROUTES through it. Nothing did:
// every place in the shared contract fixtures carries `thumbnailUrl: null`, so
// `place.thumbnailUrl && place.thumbnailAttribution` short-circuits on the
// first operand and the attribution half is never evaluated. Reverting this
// card to a bare <img> left the whole suite green.
//
// The fixtures are the shared BE/FE contract and are not edited here; the two
// states they cannot express are supplied per test.
describe('FE-303 a candidate card shows an image only when it can be credited', () => {
  const IMAGE = 'https://cdn.example.test/places/candidate.jpg';
  // Distinct from the row's sourceAttribution text on purpose: the card renders
  // "출처: ⓒ한국관광공사" as a source link regardless, so asserting on that
  // shared string would pass whether or not the thumbnail credit rendered.
  const CREDIT = '사진 출처: ⓒ한국관광공사 (이미지 심사 완료)';

  /** The candidate page with its first card's thumbnail fields overridden. */
  function candidatesReturning(
    thumbnailUrl: string | null,
    thumbnailAttribution: string | null,
  ) {
    const [first, ...rest] = page.items;
    if (!first) throw new Error('fixture has no candidates');
    server.use(
      http.get(`${API_BASE}/trips/:tripId/candidates`, () =>
        HttpResponse.json({
          ...page,
          items: [
            { ...first, place: { ...first.place, thumbnailUrl, thumbnailAttribution } },
            ...rest,
          ],
        }),
      ),
    );
  }

  it('renders the image and the server credit when both are present', async () => {
    candidatesReturning(IMAGE, CREDIT);
    renderPanel();
    await loaded();
    const image = await screen.findByRole('presentation', { hidden: true });
    expect(image).toHaveAttribute('src', IMAGE);
    // Verbatim, never composed by the client (CMP-ATT-003).
    expect(screen.getByText(CREDIT)).toBeInTheDocument();
  });

  it('shows no image when the server sent one it could not credit', async () => {
    // A contract-valid pair: thumbnailAttribution is null "when the reviewed
    // licence requires none", so an uncreditable image is a real response.
    // Showing it bare is the compliance failure, so the placeholder renders.
    candidatesReturning(IMAGE, null);
    renderPanel();
    await loaded();
    expect(screen.queryByRole('presentation', { hidden: true })).toBeNull();
    expect(screen.queryByText(CREDIT)).toBeNull();
  });
});

// S07-10 `527:4732`. The date choice moved out of the card and into a
// <dialog> because Figma draws it as a bottom sheet — and a sheet is not a
// styling choice: it is the focus trap, the Escape key and a title of its own,
// none of which an expanding card had. These are the clauses that were not
// provable before, so they are asserted rather than assumed.
describe('FE-303-T3 the date sheet is operable without a mouse', () => {
  it('opens focused on the one control every state has', async () => {
    const { sheet } = await openDates(page.items[1]?.place.name ?? '');
    // Cancel, not the first date: while the match is still being checked, or
    // came back NONE, there is no date to land on.
    expect(
      within(sheet).getByRole('button', { name: copy['candidates.sheet.cancel'] }),
    ).toHaveFocus();
  });

  it('closes on Escape without scheduling anything', async () => {
    const { user, card, sheet } = await openDates(page.items[1]?.place.name ?? '');
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(sheet).not.toBeVisible();
    });
    // And the itinerary was not touched: Escape is a cancel, and a sheet that
    // scheduled on the way out would violate invariant 1 silently.
    expect(sent).toHaveLength(0);
    expect(
      within(card).getByRole('button', { name: copy['candidates.add'] }),
    ).toHaveAttribute('aria-expanded', 'false');
  });

  it('says which place it is placing', async () => {
    // The sheet asks "어느 날에 추가할까요?" — about nothing in particular
    // unless it names the place, and that question is identical for every card.
    const name = page.items[1]?.place.name ?? '';
    const { sheet } = await openDates(name);
    expect(within(sheet).getByText(name)).toBeInTheDocument();
    expect(
      within(sheet).getByText(copy['candidates.sheet.newPlace']),
    ).toBeInTheDocument();
  });

  it('is titled, so it is not an unnamed dialog', async () => {
    const { sheet } = await openDates(page.items[1]?.place.name ?? '');
    expect(sheet).toHaveAccessibleName(copy['candidates.sheet.title']);
  });
});

describe('the saved-places count agrees with the screen that links here', () => {
  // TripScreen labels the link with `candidateCount`, the contract's own
  // field, and says why in a comment (TripScreen.tsx:150). This screen counted
  // `items.length` instead — one PAGE of the candidates — so the two screens
  // reported different totals for the same set, one tap apart. The fixtures
  // make it visible: candidateCount is larger than the bounded page.
  it('reports the trip total, not the length of one page', async () => {
    renderPanel();
    await loaded();
    const heading = await screen.findByRole('heading', { level: 1 });

    const total = tripFixtures.detailScheduled.candidateCount;
    expect(total).not.toBe(page.items.length); // the fixtures must disagree,
    // or this test would pass either way.
    expect(heading).toHaveTextContent(
      copy['candidates.open'].replace('{count}', String(total)),
    );
  });
});
