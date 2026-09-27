// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-103 acceptance (FR-TRC-04, FR-TRC-05, FR-TRC-08).
//
// Two assertions carry most of the weight:
//   - the query never reaches a URL, because searchPlaces is a read-only POST
//     specifically so free-form text stays out of CDN, proxy and history logs;
//   - the separate crowd batch stays in search-result order and retains each
//     selected point's date, state and provenance.
import { QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  candidateFixtures,
  crowdFixtures,
  placeFixtures,
  sessionFixtures,
} from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';
import { readSnapshot } from '../wizard-storage.js';

const copy = messages['en-US'];
const [first, second] = placeFixtures.searchPage.items;

let requests: { method: string; url: string }[] = [];
let crowdBodies: { placeIds: string[] }[] = [];

beforeEach(() => {
  requests = [];
  crowdBodies = [];
  server.use(
    http.post(`${API_BASE}/places/crowd-forecasts/query`, async ({ request }) => {
      const body = (await request.json()) as { placeIds: string[] };
      crowdBodies.push(body);
      return HttpResponse.json({
        items: body.placeIds.map((id, index) =>
          index === 0
            ? { ...crowdFixtures.seriesForecast, placeId: body.placeIds[1] ?? id }
            : {
                ...crowdFixtures.seriesUnavailable,
                placeId: body.placeIds[0] ?? id,
                unavailableReason: 'PLACE_UNAVAILABLE' as const,
              },
        ),
      });
    }),
  );
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
});
afterEach(() => {
  // The locale is stored, so a test that sets it must put it back or every
  // later test inherits it.
  localStorage.clear();
  // And the wizard's snapshot, for a sharper reason: the screen restores its
  // step in `useState`'s initializer (FR-TRC-12), so a case that walks to step 4
  // leaves the next one starting THERE while `renderStep4` walks from step 1.
  // Twenty of these 21 cases failed on `Unable to find role="button"`, which
  // reads like a broken selector and was really the previous case's state.
  //
  // Within this file, not across files: vitest isolates per file (no `isolate`
  // override in vite.config.ts, and `test` is a bare `vitest run`), so storage
  // never reaches the next suite. So the exposure is not "renders the wizard" —
  // it is "has two or more cases that advance the wizard", which is why
  // wizard-screen.test.tsx needed the same clear and app-shell did not.
  //
  // Cleared here rather than in vitest.setup.ts on purpose: storage is an input
  // channel for other suites (wizard-screen seeds corrupt snapshots;
  // data-guide and profile seed the locale precisely to exercise the real
  // resolution path), and a global clear would be a trap for them the day one
  // moves its seed into `beforeEach`.
  sessionStorage.clear();
  server.events.removeAllListeners();
});

/** The router of the last render, so a case can read where the wizard went. */
let router: ReturnType<typeof createMemoryRouter>;

/**
 * Renders the wizard and walks it to step 4.
 *
 * The step used to have its own route and these tests entered at it directly.
 * Nothing linked to that route, so they were exercising a screen no traveller
 * could reach (#185). Going through steps 1-3 costs a few clicks and buys the
 * thing the old harness could not check: that the step is reachable at all, and
 * that it is reached by answering MUST_VISIT_ONLY rather than by any other
 * route through the wizard.
 */
/**
 * Mounts the app at /start, the way a load or a reload of that URL does.
 * `entries` puts pages before it in the history, for a case that goes Back.
 */
function mountWizard(entries: string[] = ['/start']) {
  router = createMemoryRouter(routes, { initialEntries: entries });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function renderStep4(entries?: string[]) {
  const user = userEvent.setup();
  mountWizard(entries);

  // Step 1: any day twice is a one-day range, which the contract allows.
  const day = await screen.findByRole('button', { name: '15' });
  await user.click(day);
  await user.click(day);
  await user.click(screen.getByRole('button', { name: /–/ }));

  // Step 2 asks for interests and the contract permits none.
  await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));

  // Step 3: the answer that leads here. NOTHING opens the recommendation
  // preview and MOSTLY_PLANNED goes to the input-method screen.
  await user.click(
    await screen.findByRole('button', {
      name: new RegExp(copy['wizard.planning.MUST_VISIT_ONLY.title']),
    }),
  );
  await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
  await screen.findByRole('searchbox');
  return user;
}

async function searchFor(text: string, entries?: string[]) {
  const user = await renderStep4(entries);
  await user.type(screen.getByRole('searchbox'), text);
  return user;
}

describe('searching for a place keeps the query out of every URL', () => {
  it('sends the query in a POST body, not a query string', async () => {
    let body: unknown = null;
    server.use(
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(placeFixtures.searchPage);
      }),
    );
    await searchFor('경복궁');

    await waitFor(() => {
      expect(body).toEqual({ query: '경복궁', locale: 'en-US' });
    });
    const search = requests.filter((r) => r.url.includes('/places/search'));
    expect(search.every((r) => r.method === 'POST')).toBe(true);
    // The text must not appear anywhere in a URL, encoded or not.
    for (const request of requests) {
      expect(decodeURIComponent(request.url)).not.toContain('경복궁');
    }
  });

  it('does not search on an empty field', async () => {
    await renderStep4();
    expect(requests.filter((r) => r.url.includes('/places/search'))).toEqual([]);
  });
});

/**
 * The add button for one result, by the place it adds.
 *
 * Every button's visible label is 담기, so a name query matched all of them
 * and the first one happened to be right. The accessible name carries the
 * place — the same shape the remove button already used — so these now say
 * which result they pressed.
 */
const addButton = (name: string) =>
  screen.findByRole('button', {
    name: copy['mustVisit.addNamed'].replace('{place}', name),
  });

describe('FE-603-T5 both lists on this screen credit their places', () => {
  // The search results and the kept list both read `place`, so the static
  // scan (FE-603-T4) sees one group and cannot tell which list lost its
  // credit. Each list is checked through a control only its own rows have.
  const credit = first?.sourceAttribution;

  it('credits a place in the search results', async () => {
    if (!first || !credit) throw new Error('the search fixture lost its credited place');
    await searchFor('서울');
    const row = (await addButton(first.name)).closest('li') as HTMLElement;
    // The row's forecast credit reads the same words, so the place credit is
    // told apart by its own dataset page.
    const hrefs = within(row)
      .getAllByRole('link', { name: credit.attribution })
      .map((link) => link.getAttribute('href'));
    expect(hrefs).toContain(credit.officialUrl);
  });

  it('credits a place in the kept list', async () => {
    if (!first || !credit) throw new Error('the search fixture lost its credited place');
    const user = await searchFor('서울');
    await user.click(await addButton(first.name));
    const kept = await screen.findByRole('list', { name: copy['mustVisit.picked'] });
    const row = within(kept)
      .getByRole('button', { name: `${first.name} ${copy['mustVisit.remove']}` })
      .closest('li') as HTMLElement;
    expect(within(row).getByRole('link', { name: credit.attribution })).toHaveAttribute(
      'href',
      credit.officialUrl ?? '',
    );
  });
});

describe('FE-103-T1 results and the kept list', () => {
  it('lists what the search returned', async () => {
    await searchFor('서울');
    expect(await screen.findByText(first?.name ?? '')).toBeInTheDocument();
  });

  it('keeps a place and marks it as a must-visit', async () => {
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));

    const kept = await screen.findByRole('list', { name: copy['mustVisit.picked'] });
    expect(kept).toHaveTextContent(first?.name ?? '');
    expect(kept).toHaveTextContent(copy['mustVisit.badge']);
  });

  it('labels the badge in the selected locale', async () => {
    // MustVisitBadge keeps a Korean default so Storybook can mount it without
    // a provider. The screen must pass the chosen locale's word instead, or
    // the badge ignores the locale entirely — this file runs in en-US, so the
    // default and the correct answer differ and the assertion is meaningful.
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    const kept = await screen.findByRole('list', { name: copy['mustVisit.picked'] });
    expect(kept).toHaveTextContent(copy['mustVisit.badge']);
    expect(kept).not.toHaveTextContent('꼭 가요');
  });

  it('removes a kept place again', async () => {
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(
      await screen.findByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    );
    expect(await screen.findByText(copy['mustVisit.pickedEmpty'])).toBeInTheDocument();
  });

  it('names each add button for the place it adds', async () => {
    // A screen reader listing the controls used to hear 담기, 담기, 담기 with
    // no way to tell which place each one kept; the name was only recoverable
    // by arrowing back out of the button and re-reading the list item.
    await searchFor('서울');
    const buttons = await screen.findAllByRole('button', {
      name: new RegExp(copy['mustVisit.add']),
    });
    const names = buttons.map((b) => b.getAttribute('aria-label'));
    expect(names.length).toBeGreaterThan(1);
    // Every one distinct, and each carrying its own place.
    expect(new Set(names).size).toBe(names.length);
    expect(names[0]).toContain(first?.name ?? '');
  });

  it('will not keep the same place twice', async () => {
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await waitFor(async () => {
      expect(await addButton(first?.name ?? '')).toBeDisabled();
    });
  });

  it('says so when nothing matches', async () => {
    server.use(
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPageEmpty),
      ),
    );
    await searchFor('없는장소');
    expect(await screen.findByText(copy['mustVisit.noResults'])).toBeInTheDocument();
  });

  it('reports a failed search instead of showing nothing', async () => {
    server.use(http.post(`${API_BASE}/places/search`, () => HttpResponse.error()));
    await searchFor('서울');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['mustVisit.searchError'],
    );
  });
});

describe('FE-103-T2 the card shows only what the contract supplies', () => {
  it('renders the ordered crowd response without deriving a stage', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');
    await waitFor(() => {
      expect(crowdBodies).toHaveLength(1);
    });
    expect(crowdBodies[0]?.placeIds).toEqual(
      placeFixtures.searchPage.items.map((place) => place.id),
    );
    const firstCard = screen.getByText(first?.name ?? '').closest('li');
    const secondCard = screen.getByText(second?.name ?? '').closest('li');
    expect(firstCard as HTMLElement).toHaveTextContent('Relative concentration 72.5');
    expect(firstCard as HTMLElement).toHaveTextContent('Official crowd forecast');
    expect(firstCard as HTMLElement).not.toHaveTextContent(/Level \d/);
    expect(secondCard as HTMLElement).toHaveTextContent(
      'Crowd forecast is unavailable for this place',
    );
  });

  it('credits the source the server named (FCR-031, CMP-ATT-001)', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');

    // This assertion used to run the other way: PlaceSummary carried no source
    // at all, so the card could satisfy neither CMP-ATT-001 (a credit on every
    // KTO screen) nor CMP-ATT-003 (never imply one that was not given), and the
    // test guarded the absence. BA-022 added sourceAttribution, so it now
    // guards the presence instead of being deleted.
    const credit = first?.sourceAttribution?.attribution ?? '';
    expect(credit).not.toBe('');
    expect(screen.getAllByText(credit).length).toBeGreaterThan(0);
  });

  it('does not hardcode the provider name', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');

    // Every credit on screen has to be a string the response supplied. A card
    // that prints "ⓒ한국관광공사" for a place the server did not attribute is
    // exactly what CMP-ATT-003 forbids.
    //
    // "Supplied" covers both responses on this screen. The forecast's credit
    // reads the same as the place's, so its source is named beside it
    // (FE-603-T7) — and that name is the forecast response's own
    // `sourceDisplayName`, not ours. This set once held the place credits
    // alone and passed only because the two credits happened to match.
    const served = new Set(
      [
        ...placeFixtures.searchPage.items.map(
          (item) => item.sourceAttribution?.attribution,
        ),
        ...crowdFixtures.seriesForecast.points.flatMap((point) => [
          point.provenance.attribution,
          point.provenance.sourceDisplayName,
        ]),
      ].filter((text): text is string => typeof text === 'string'),
    );
    for (const node of screen.queryAllByText(/한국관광공사/)) {
      expect(served).toContain(node.textContent?.trim());
    }
  });

  it('shows the fields the contract does supply', async () => {
    await searchFor('서울');
    expect(await screen.findByText(first?.name ?? '')).toBeInTheDocument();
    // Every result renders, not just the first. getAllByText because a name can
    // also occur inside an address ("명동" is both a place and a street).
    expect(screen.getAllByText(second?.name ?? '').length).toBeGreaterThan(0);
    // The address is the contract field that is already human text.
    expect(screen.getByText(first?.address ?? '')).toBeInTheDocument();
  });

  it('does not show categoryCode, which is machine text', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');
    // categoryCode is a free string in the contract with no enum and no display
    // name, so there is nothing to map "ATTRACTION" onto for the user. Asked BE
    // for the vocabulary and its labels on #34 (BA-022).
    expect(screen.queryByText(new RegExp(first?.categoryCode ?? 'x'))).toBeNull();
  });
});

describe('FE-103-T3 keyboard and continuation', () => {
  it('gives the remove control an accessible name that says which place', async () => {
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    expect(
      await screen.findByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    ).toBeInTheDocument();
  });

  it('continues without keeping anything', async () => {
    // 건너뛰기 is an answer, not a cancel: it creates the trip with no
    // must-visit places. It used to navigate('/feed') exactly like 이대로
    // 채우기, which is what #185 reported — the two exits were the same code.
    const user = await renderStep4();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.skip'] }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST' && r.url.endsWith('/trips'))).toBe(
        true,
      );
    });
  });
});

// CMP-ATT-001 at the screen boundary, for BOTH lists.
//
// This screen renders a place thumbnail twice — once per search result and
// once per kept pick — and each has its own guard. PlaceThumbnail's own test
// proves the component refuses an uncredited image; what nothing asserted is
// that these two lists route through it. Every place in the shared contract
// fixtures carries `thumbnailUrl: null`, so
// `place.thumbnailUrl && place.thumbnailAttribution` short-circuits on the
// first operand and the attribution half is never evaluated.
//
// The kept list is covered separately from the results list on purpose: they
// are two independent guards, and a test that only searched would leave the
// second one exactly as unprotected as before.
describe('FE-103 a place shows an image only when it can be credited', () => {
  const IMAGE = 'https://cdn.example.test/places/gyeongbokgung.jpg';
  // Distinct from the row's sourceAttribution text on purpose: the row renders
  // "출처: ⓒ한국관광공사" as a source line regardless, so asserting on that
  // shared string would pass whether or not the thumbnail credit rendered.
  const CREDIT = '사진 출처: ⓒ한국관광공사 (이미지 심사 완료)';

  /** The search page with its first result's thumbnail fields overridden. */
  function searchReturning(
    thumbnailUrl: string | null,
    thumbnailAttribution: string | null,
  ) {
    const [head, ...rest] = placeFixtures.searchPage.items;
    server.use(
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json({
          ...placeFixtures.searchPage,
          items: [{ ...head, thumbnailUrl, thumbnailAttribution }, ...rest],
        }),
      ),
    );
  }

  it('renders a credited search result with its image and credit', async () => {
    searchReturning(IMAGE, CREDIT);
    await searchFor('경복궁');
    await screen.findByText(first?.name ?? '');
    const image = await screen.findByRole('presentation', { hidden: true });
    expect(image).toHaveAttribute('src', IMAGE);
    // Verbatim, never composed by the client (CMP-ATT-003).
    expect(screen.getByText(CREDIT)).toBeInTheDocument();
  });

  it('shows no image in the results when it could not be credited', async () => {
    searchReturning(IMAGE, null);
    await searchFor('경복궁');
    await screen.findByText(first?.name ?? '');
    expect(screen.queryByRole('presentation', { hidden: true })).toBeNull();
    expect(screen.queryByText(CREDIT)).toBeNull();
  });

  it('keeps the credit when the place moves into the kept list', async () => {
    searchReturning(IMAGE, CREDIT);
    const user = await searchFor('경복궁');
    await user.click(await addButton(first?.name ?? ''));
    // Now rendered twice — once as a result, once as a pick — and both must
    // carry the credit.
    await waitFor(() => {
      expect(screen.getAllByRole('presentation', { hidden: true })).toHaveLength(2);
    });
    expect(screen.getAllByText(CREDIT)).toHaveLength(2);
  });

  it('shows no image in the kept list when it could not be credited', async () => {
    // The second guard, which a results-only test would never reach.
    searchReturning(IMAGE, null);
    const user = await searchFor('경복궁');
    await user.click(await addButton(first?.name ?? ''));
    // The pick is kept — only its uncreditable image is withheld.
    await waitFor(() => {
      expect(
        screen.getByRole('button', {
          name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
        }),
      ).toBeInTheDocument();
    });
    expect(screen.queryByRole('presentation', { hidden: true })).toBeNull();
  });
});

// #185, the write. 이대로 채우기 used to create the trip and drop every pick:
// `toCreateRequest` never carries them and nothing else sent them, so the
// judged promise on this screen ("이 장소는 그대로 지켜드리고") was broken for
// every traveller who answered 꼭 가고 싶은 곳만 정했어요. The owner settled the
// shape on #180 (option B): each pick becomes a candidate of the new trip, with
// `mustVisit: true`, after createTrip. Those N+1 requests are not one
// transaction (invariant 5), which is why the partial-failure cases below exist.
const TRIP = '018f4c00-0000-7000-8000-0000000000aa';
const candidateTemplate = candidateFixtures.page.items[0];

interface SavedCandidate {
  tripId: string;
  key: string | null;
  body: Record<string, unknown>;
}

/** createTrip requests, counted so a second trip is visible as a number. */
let createdTrips = 0;
/** createTrip requests answered, for a case that waits on the answer itself. */
let createsAnswered = 0;
/** Every addTripCandidate request, including the ones answered with a failure. */
let saved: SavedCandidate[] = [];
let patched: Record<string, unknown>[] = [];
/** Place ids whose candidate write fails, until a case takes them out again. */
let failing = new Set<string>();
/** When set, createTrip waits for it before answering (it is counted first). */
let createHeld: Promise<void> | null = null;
/** When set, every candidate write waits for it before answering (recorded first). */
let savesHeld: Promise<void> | null = null;

/** A promise a handler can wait on, and the call that lets it through. */
function hold() {
  let release: () => void = () => undefined;
  const wait = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { wait, release };
}

/**
 * Holds every navigation to a trip until released, and records it.
 *
 * React Router commits a navigation inside startTransition (RouterProvider,
 * read at 7.18), so the wizard renders once more between asking for the trip
 * and the trip's route replacing it (#185 review). In a browser that render is
 * brief; holding the navigation keeps it on screen long enough to look at.
 * useNavigate calls `router.navigate` at the time of the call, so replacing it
 * on this router is what the screen reaches.
 */
function holdTripNavigation() {
  const gate = hold();
  const requested: string[] = [];
  const navigateNow = router.navigate.bind(router) as (
    to: unknown,
    options?: unknown,
  ) => Promise<void>;
  router.navigate = ((to: unknown, options?: unknown) => {
    if (typeof to !== 'string' || !to.startsWith('/trip/'))
      return navigateNow(to, options);
    requested.push(to);
    return gate.wait.then(() => navigateNow(to, options));
  }) as typeof router.navigate;
  return { requested, release: gate.release };
}

/**
 * Lets the run a case released finish inside that case.
 *
 * What follows createTrip's answer outlives the wizard on purpose (T54), so a
 * write a case let go of and then abandoned would land in the NEXT case's
 * handler and be counted there. Measured: T45's released create sent its two
 * picks into T51's held handler.
 */
async function finishReleasedRun() {
  await waitFor(() => {
    expect(saved).toHaveLength(2);
  });
  await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
}

function answerWrites() {
  createdTrips = 0;
  createsAnswered = 0;
  saved = [];
  patched = [];
  failing = new Set();
  createHeld = null;
  savesHeld = null;
  server.use(
    http.post(`${API_BASE}/trips`, async () => {
      createdTrips += 1;
      if (createHeld) await createHeld;
      createsAnswered += 1;
      return HttpResponse.json({ id: TRIP }, { status: 201 });
    }),
    http.patch(`${API_BASE}/me`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      patched.push(body);
      return HttpResponse.json({ ...sessionFixtures.owner, ...body });
    }),
    http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ request, params }) => {
      const body = (await request.json()) as Record<string, unknown>;
      saved.push({
        tripId: String(params.tripId),
        key: request.headers.get('Idempotency-Key'),
        body,
      });
      if (savesHeld) await savesHeld;
      if (failing.has(String(body.placeId))) return HttpResponse.error();
      const place = placeFixtures.searchPage.items.find((p) => p.id === body.placeId);
      return HttpResponse.json(
        {
          candidate: {
            ...candidateTemplate,
            id: crypto.randomUUID(),
            tripId: TRIP,
            place,
            status: 'ACTIVE',
            scheduledTripItemId: null,
            mustVisit: true,
          },
          duplicate: false,
          tripScheduleChanged: false,
        },
        { status: 201 },
      );
    }),
  );
}

/** Keeps the first two search results and presses 이대로 채우기. */
async function keepTwoAndFill() {
  const user = await searchFor('서울');
  await user.click(await addButton(first?.name ?? ''));
  await user.click(await addButton(second?.name ?? ''));
  await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));
  return user;
}

const byPlace = (a: SavedCandidate, b: SavedCandidate) =>
  String(a.body.placeId).localeCompare(String(b.body.placeId));

describe('FE-103-T30 이대로 채우기 writes each pick as a must-visit candidate of the new trip', () => {
  beforeEach(answerWrites);

  it('sends one SEARCH candidate per pick, to the created trip', async () => {
    await keepTwoAndFill();

    await waitFor(() => {
      expect(saved).toHaveLength(2);
    });
    // The whole body, not a field of it: `mustVisit: false` or a missing
    // source would each be a different request the server stores differently.
    expect([...saved].sort(byPlace).map((s) => s.body)).toEqual(
      [first, second]
        .map((place) => ({
          placeId: place?.id,
          source: { type: 'SEARCH' },
          mustVisit: true,
        }))
        .sort((a, b) => String(a.placeId).localeCompare(String(b.placeId))),
    );
    // The trip the create answered with, not the hook's own (null) trip.
    expect(saved.map((s) => s.tripId)).toEqual([TRIP, TRIP]);
    expect(createdTrips).toBe(1);
  });

  it('sends each pick once when 이대로 채우기 is pressed twice in one tick', async () => {
    // Both presses run before React renders the pending state. The shared
    // attempt guard keeps the second one from reaching createTrip at all.
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    const fill = screen.getByRole('button', { name: copy['mustVisit.next'] });
    act(() => {
      fill.click();
      fill.click();
    });

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    // Time for a second round of writes, if one was started, to reach the
    // handler.
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
    expect(createdTrips).toBe(1);
    expect([...saved].sort(byPlace).map((s) => s.body.placeId)).toEqual(
      [first?.id, second?.id].sort((a, b) => String(a).localeCompare(String(b))),
    );
  });
});

describe('FE-103-T53 each pick is sent under an Idempotency-Key of its own', () => {
  beforeEach(answerWrites);

  it('gives the two writes two different keys', async () => {
    await keepTwoAndFill();

    await waitFor(() => {
      expect(saved).toHaveLength(2);
    });
    // One key per place: a shared key would make the server replay the first
    // place's answer for the second and save only one of them.
    const keys = saved.map((s) => s.key);
    expect(keys.every((key) => /^[0-9a-f-]{36}$/i.test(key ?? ''))).toBe(true);
    expect(new Set(keys).size).toBe(2);
  });
});

describe('FE-103-T31 once every pick is saved the created trip becomes the active one, as before', () => {
  beforeEach(answerWrites);

  it('points the active trip at the created trip only after the picks landed', async () => {
    await keepTwoAndFill();

    await waitFor(() => {
      expect(patched).toEqual([{ activeTripId: TRIP }]);
    });
    // After, not instead: the writes go one after another, so both have been
    // sent by the time the pointer moves.
    expect(saved).toHaveLength(2);
  });
});

describe('FE-103-T48 once every pick is saved the wizard replaces itself with the trip, as before', () => {
  beforeEach(answerWrites);

  it('opens /trip/{id} after the picks landed, in place of /start', async () => {
    await keepTwoAndFill();

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    expect(saved).toHaveLength(2);
    expect(router.state.historyAction).toBe('REPLACE');
  });
});

/** The partial-failure message for these place names, as the screen words it. */
const unsavedText = (names: string[]) =>
  copy['mustVisit.unsaved']
    .replace('{count}', String(names.length))
    .replace('{places}', names.join(', '));

/** Waits for the partial-failure state and returns its message. */
async function unsavedState(names: string[]) {
  const message = await screen.findByText(unsavedText(names));
  // Announced, not only painted: the press that led here gave no other sign.
  expect(message.closest('[role="alert"]')).not.toBeNull();
  return message;
}

describe('FE-103-T32 when a pick cannot be saved, the wizard stays on /start', () => {
  beforeEach(answerWrites);

  it('does not leave for the trip once the writes have finished', async () => {
    failing.add(second?.id ?? '');
    await keepTwoAndFill();

    // 다시 시도 appears when the writes are done with one left over — the
    // point at which the all-saved case opens the trip. Waited on rather than
    // the message, so this clause does not lean on T42's wording.
    await screen.findByRole('button', { name: copy['mustVisit.retry'] });
    expect(saved).toHaveLength(2);
    // Still here. Opening the trip would have dropped the failed place
    // without a word, which is the defect #185 reported in the first place.
    expect(router.state.location.pathname).toBe('/start');
  });
});

describe('FE-103-T42 the partial-failure alert names only the places that failed', () => {
  beforeEach(answerWrites);

  it('says the trip exists and names the place that failed, not the one that was saved', async () => {
    failing.add(second?.id ?? '');
    await keepTwoAndFill();

    const message = await unsavedState([second?.name ?? '']);
    expect(message).not.toHaveTextContent(first?.name ?? '');
  });
});

describe('FE-103-T33 다시 시도 re-sends only the failed places, under their first keys', () => {
  beforeEach(answerWrites);

  it('retries the one that failed with the same key, on the same trip', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);
    const firstKey = saved.find((s) => s.body.placeId === second?.id)?.key;

    failing.clear();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));

    await waitFor(() => {
      expect(saved.length).toBeGreaterThan(2);
    });
    // Time for any further write of this retry to reach the handler. Waited
    // on rather than the trip opening, so this clause does not lean on T49.
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
    const retried = saved.slice(2);
    // Only the failed place: the one that landed is not sent twice.
    expect(retried.map((s) => s.body.placeId)).toEqual([second?.id]);
    // The same key, so a first attempt that DID commit before its response was
    // lost is replayed by the server rather than saved a second time.
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/i);
    expect(retried[0]?.key).toBe(firstKey);
    expect(retried[0]?.tripId).toBe(TRIP);
  });
});

describe('FE-103-T49 once 다시 시도 has saved the rest, the wizard opens the trip', () => {
  beforeEach(answerWrites);

  it('leaves for the created trip when the retried place lands', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    failing.clear();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    expect(saved).toHaveLength(3);
  });
});

const planningNothing = () =>
  screen.queryByRole('button', {
    name: new RegExp(copy['wizard.planning.NOTHING.title']),
  });

/**
 * Keeps two places, then presses 이대로 채우기 and — before the wizard renders
 * again — the back control, and waits until step 3 is on screen.
 *
 * This is the #185 review's path to a second trip: back pressed while the trip
 * was being created. T45 now hides back from the press on, so an ordinary
 * click can no longer take it. Two presses inside one tick still can: both
 * handlers run before React renders the first one's update, and the back
 * control is hidden only when createTrip's pending state has been published
 * (TanStack Query publishes it on a zero-delay timer). That is the one road
 * left onto the path, so it is how the layers behind T45 are measured. The
 * step-3 wait is the proof that the road was taken, not assumed.
 */
async function fillThenBackInOneTick() {
  const user = await searchFor('서울');
  await user.click(await addButton(first?.name ?? ''));
  await user.click(await addButton(second?.name ?? ''));
  const fill = screen.getByRole('button', { name: copy['mustVisit.next'] });
  const back = screen.getByRole('button', { name: copy['wizard.back'] });
  act(() => {
    fill.click();
    back.click();
  });
  await waitFor(() => {
    expect(planningNothing()).not.toBeNull();
  });
  return user;
}

describe('FE-103-T34 while a created trip is held, nothing creates a second one', () => {
  beforeEach(answerWrites);

  it('keeps one trip when the traveller reached step 3 while it was being created', async () => {
    // The #185 review's probe: back while createTrip is in flight, a pick
    // then fails, and every create the screen offers is pressed. It made two
    // trips. With T46 in place the held step is back on screen and step 3
    // offers nothing, so the block below runs only if that layer is gone —
    // and then it is submit()'s own refusal that keeps the count at one.
    const create = hold();
    createHeld = create.wait;
    failing.add(second?.id ?? '');
    const user = await fillThenBackInOneTick();
    create.release();
    await waitFor(() => {
      expect(saved).toHaveLength(2);
    });

    const nothing = planningNothing();
    if (nothing) {
      await user.click(nothing);
      await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
      const start = await screen.findByRole('button', {
        name: copy['draftPreview.start'],
      });
      await waitFor(() => {
        expect(start).toBeEnabled();
      });
      await user.click(start);
      // Time for a second createTrip to reach the handler, if one was sent.
      await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
    }
    expect(createdTrips).toBe(1);
  });

  it('keeps one trip across a retry that fails again', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    await user.click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));
    await waitFor(() => {
      expect(saved).toHaveLength(3);
    });
    await unsavedState([second?.name ?? '']);
    expect(createdTrips).toBe(1);
  });

  it('offers no way back to a step that could submit again', async () => {
    // Every other branch ends in a createTrip, so a back control here would
    // be a road to a second trip. The trip exists; what is left is to finish
    // its picks or open it without them.
    failing.add(second?.id ?? '');
    await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    expect(screen.queryByRole('button', { name: copy['wizard.back'] })).toBeNull();
    expect(screen.queryByRole('button', { name: copy['mustVisit.next'] })).toBeNull();
    expect(screen.queryByRole('button', { name: copy['mustVisit.skip'] })).toBeNull();
    expect(createdTrips).toBe(1);
  });

  it('locks the exits after 건너뛰기 while the trip route is committing', async () => {
    const user = await searchFor('서울');
    const navigation = holdTripNavigation();
    const skip = screen.getByRole('button', { name: copy['mustVisit.skip'] });
    await user.click(skip);
    await waitFor(() => {
      expect(navigation.requested).toEqual([`/trip/${TRIP}`]);
    });
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(skip).toBeDisabled();
    expect(screen.getByRole('button', { name: copy['mustVisit.next'] })).toBeDisabled();
    expect(createdTrips).toBe(1);
    navigation.release();
  });
});

describe('FE-103-T45 while the trip is being created, the wizard offers no way back', () => {
  beforeEach(answerWrites);

  it('hides the back control from the press of 이대로 채우기 until createTrip answers', async () => {
    // The #185 review's second trip started here: back while createTrip was in
    // flight, and step 3's CTA creates a trip.
    const create = hold();
    createHeld = create.wait;
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    // Offered up to the press, so the absence below is the press's doing and
    // not a query that could never find it.
    expect(screen.getByRole('button', { name: copy['wizard.back'] })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));

    await waitFor(() => {
      expect(createdTrips).toBe(1);
    });
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: copy['wizard.back'] })).toBeNull();
    });
    expect(screen.getByRole('searchbox')).toBeInTheDocument();
    create.release();
    await finishReleasedRun();
  });
});

describe('FE-103-T51 while the picks are first being sent, the wizard offers no way back', () => {
  beforeEach(answerWrites);

  it('hides the back control while the first save is in flight', async () => {
    // The trip exists and createTrip is no longer pending, so what hides back
    // here is the held trip, not the create.
    const saves = hold();
    savesHeld = saves.wait;
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    expect(screen.getByRole('button', { name: copy['wizard.back'] })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));

    await waitFor(() => {
      expect(saved).toHaveLength(1);
    });
    // createTrip's settled state is published on a timer (query-core
    // notifyManager); give it that time so the create no longer hides back.
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(screen.queryByRole('button', { name: copy['wizard.back'] })).toBeNull();
    expect(screen.getByRole('searchbox')).toBeInTheDocument();
    saves.release();
    await finishReleasedRun();
  });
});

describe('FE-103-T46 a held trip shows its partial failure whatever step the wizard was on', () => {
  beforeEach(answerWrites);

  it('brings back the kept places and names the one that failed', async () => {
    // Reached step 3 while the trip was being created (see
    // fillThenBackInOneTick). The review measured what followed: the trip
    // arrived, a pick failed, and step 3 said nothing — the failed place was
    // dropped without a word, which is #185 itself.
    const create = hold();
    createHeld = create.wait;
    failing.add(second?.id ?? '');
    await fillThenBackInOneTick();
    create.release();

    await unsavedState([second?.name ?? '']);
    expect(
      screen.getByRole('button', { name: copy['mustVisit.retry'] }),
    ).toBeInTheDocument();
    expect(planningNothing()).toBeNull();
    expect(router.state.location.pathname).toBe('/start');
  });
});

/**
 * Keeps two places, presses 이대로 채우기 and leaves /start while the first
 * save is in flight, as browser Back does; then lets the saves finish. The
 * wizard unmounts; the loop sending the picks does not.
 */
async function leaveMidSave() {
  const saves = hold();
  savesHeld = saves.wait;
  await keepTwoAndFill();
  await waitFor(() => {
    expect(saved).toHaveLength(1);
  });
  await act(async () => {
    await router.navigate('/feed');
  });
  expect(document.getElementById('wizard-heading')).toBeNull();
  saves.release();
  // The loop may finish its requests — each is idempotent under its key.
  await waitFor(() => {
    expect(saved).toHaveLength(2);
  });
  // Time for the loop's tail, where the trip used to be opened.
  await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
}

/**
 * Keeps two places (when `fill`) or none, presses 이대로 채우기 or 건너뛰기,
 * and goes browser Back while createTrip is in flight; then lets it answer.
 *
 * The #185 review's second-trip path after T45 closed the in-app one:
 * TanStack Query drops a mutate() callback once the component's observer
 * unsubscribes, so whatever followed the answer used to not happen at all.
 */
async function backWhileCreating(press: 'fill' | 'skip') {
  const create = hold();
  createHeld = create.wait;
  const user = await searchFor('서울', ['/feed', '/start']);
  if (press === 'fill') {
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
  }
  await user.click(
    screen.getByRole('button', {
      name: copy[press === 'fill' ? 'mustVisit.next' : 'mustVisit.skip'],
    }),
  );
  await waitFor(() => {
    expect(createdTrips).toBe(1);
  });
  await act(async () => {
    await router.navigate(-1);
  });
  expect(router.state.location.pathname).toBe('/feed');
  expect(document.getElementById('wizard-heading')).toBeNull();

  create.release();
  await waitFor(() => {
    expect(createsAnswered).toBe(1);
  });
  // Time for what follows the answer to run.
  await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
}

describe('FE-103-T47 once the wizard has gone, what finishes after it does not take the traveller to the trip', () => {
  beforeEach(answerWrites);

  it('stays where they went when the last save finishes after they left', async () => {
    await leaveMidSave();
    expect(router.state.location.pathname).toBe('/feed');
  });

  it('stays where they went when createTrip answers after they left', async () => {
    await backWhileCreating('skip');
    expect(router.state.location.pathname).toBe('/feed');
  });
});

describe('FE-103-T52 once the wizard has gone, what finishes after it does not change the active trip', () => {
  beforeEach(answerWrites);

  it('leaves the active trip alone when the last save finishes after they left', async () => {
    await leaveMidSave();
    expect(patched).toEqual([]);
  });

  it('leaves the active trip alone when createTrip answers after they left', async () => {
    await backWhileCreating('skip');
    expect(patched).toEqual([]);
  });
});

describe('FE-103-T54 leaving while the trip is being created still puts the picks on it', () => {
  beforeEach(answerWrites);

  it('sends each kept place to the created trip, under a key, after the wizard has gone', async () => {
    await backWhileCreating('fill');

    await waitFor(() => {
      expect(saved).toHaveLength(2);
    });
    expect([...saved].sort(byPlace).map((s) => s.body)).toEqual(
      [first, second]
        .map((place) => ({
          placeId: place?.id,
          source: { type: 'SEARCH' },
          mustVisit: true,
        }))
        .sort((a, b) => String(a.placeId).localeCompare(String(b.placeId))),
    );
    expect(saved.map((s) => s.tripId)).toEqual([TRIP, TRIP]);
    expect(saved.every((s) => /^[0-9a-f-]{36}$/i.test(s.key ?? ''))).toBe(true);
  });
});

describe('FE-103-T35 여행으로 가기 sends nothing more', () => {
  beforeEach(answerWrites);

  it('writes no further candidate and creates no second trip', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    await user.click(screen.getByRole('button', { name: copy['mustVisit.openTrip'] }));

    // Time for a write, had the press sent one, to reach the handler. Waited
    // on rather than the trip opening, so this clause does not lean on T50.
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)));
    expect(saved).toHaveLength(2);
    expect(createdTrips).toBe(1);
  });
});

describe('FE-103-T50 여행으로 가기 opens the created trip without the failed places', () => {
  beforeEach(answerWrites);

  it('leaves for the trip', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    await user.click(screen.getByRole('button', { name: copy['mustVisit.openTrip'] }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
  });
});

describe('FE-103-T55 after the last save lands, the step cannot be pressed until the trip opens', () => {
  beforeEach(answerWrites);

  it('keeps both exits and the kept list locked in the render before the route commits', async () => {
    // The render between asking for the trip and its route committing used to
    // show every control enabled: 이대로 채우기 and 건너뛰기, whose press
    // mints a new key because the draft's was dropped with createTrip's
    // answer, and 담기·빼기, which rewrite the draft after it was cleared
    // (#185 review).
    const third = placeFixtures.searchPage.items[2];
    const user = await searchFor('서울');
    const navigation = holdTripNavigation();
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));

    await waitFor(() => {
      expect(navigation.requested).toEqual([`/trip/${TRIP}`]);
    });
    // Time for React to render what the last save left behind.
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(router.state.location.pathname).toBe('/start');
    expect(screen.getByRole('button', { name: copy['mustVisit.next'] })).toBeDisabled();
    expect(screen.getByRole('button', { name: copy['mustVisit.skip'] })).toBeDisabled();
    expect(await addButton(third?.name ?? '')).toBeDisabled();
    expect(
      screen.getByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    ).toBeDisabled();
    navigation.release();
  });
});

describe('FE-103-T56 a retry that saves every place does not report the partial failure again', () => {
  beforeEach(answerWrites);

  it('shows no partial-failure alert in the render before the trip opens', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);
    const navigation = holdTripNavigation();

    failing.clear();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));
    await waitFor(() => {
      expect(navigation.requested).toEqual([`/trip/${TRIP}`]);
    });
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(router.state.location.pathname).toBe('/start');
    // Announced the moment the retry had succeeded, this would tell a screen
    // reader the opposite of what just happened.
    expect(screen.queryByText(unsavedText([second?.name ?? '']))).toBeNull();
    navigation.release();
  });
});

describe('FE-103-T36 건너뛰기 saves no candidate, even after places were kept', () => {
  beforeEach(answerWrites);

  it('creates the trip and writes nothing onto it', async () => {
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(screen.getByRole('button', { name: copy['mustVisit.skip'] }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    expect(createdTrips).toBe(1);
    expect(saved).toEqual([]);
  });
});

describe('FE-103-T37 reopening /start after the trip exists does not restore a draft that would create it again', () => {
  beforeEach(answerWrites);

  it('recovers failed picks separately, then starts fresh once the traveller opens the trip', async () => {
    // The held trip lives in component state, which a reload drops. Had the
    // draft survived with it, the reload would reopen step 4 with the same
    // picks and 이대로 채우기 would make a second trip. The draft became a
    // trip when createTrip answered, so it goes then — not when the wizard
    // finally leaves.
    failing.add(second?.id ?? '');
    await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    // Unmounting and mounting again is what a reload does to this component:
    // the state goes, the Storage stays (FR-TRC-12's own harness).
    cleanup();
    mountWizard();

    await unsavedState([second?.name ?? '']);
    expect(createdTrips).toBe(1);
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: copy['mustVisit.openTrip'] }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/trip/${TRIP}`));
    cleanup();
    mountWizard();
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
    expect(createdTrips).toBe(1);
  });

  it('starts again at step 1 after leaving while the trip was being created and coming back', async () => {
    // Browser Back while createTrip was in flight used to leave the draft in
    // storage, because what cleared it was a callback TanStack Query drops
    // once the wizard unmounts. Coming back restored step 4 with the kept
    // places, and 이대로 채우기 made a second trip under a fresh key.
    await backWhileCreating('fill');
    expect(readSnapshot()).toBeNull();

    // Forward, the way browser Forward (or the next 새 여행) comes back.
    await act(async () => {
      await router.navigate(1);
    });
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: copy['mustVisit.next'] })).toBeNull();
    expect(createdTrips).toBe(1);
  });

  it('starts again at step 1 when a place was kept in the render before the trip opened', async () => {
    // 건너뛰기 leaves no picks to save, so nothing keeps the step busy in the
    // render between createTrip's answer and the trip's route committing (see
    // T34's 건너뛰기 case). 담기 there used to write the draft back after it
    // had been cleared — step 4, the old dates and the kept place — and the
    // next /start opened on it.
    const user = await searchFor('서울');
    const navigation = holdTripNavigation();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.skip'] }));
    await waitFor(() => {
      expect(navigation.requested).toEqual([`/trip/${TRIP}`]);
    });
    // The step remains visible while the route commits, but no new pick can
    // be accepted after its trip has been created.
    const keep = await addButton(first?.name ?? '');
    expect(keep).toBeDisabled();
    navigation.release();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });

    // The next 새 여행.
    await act(async () => {
      await router.navigate('/start');
    });
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
    expect(createdTrips).toBe(1);
  });
});

describe('FE-103-T57 returning before createTrip answers cannot make another trip', () => {
  beforeEach(answerWrites);

  it('holds the same attempt across browser Back and Forward until its answer is handled', async () => {
    const create = hold();
    createHeld = create.wait;
    const user = await searchFor('서울', ['/feed', '/start']);
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));
    await waitFor(() => expect(createdTrips).toBe(1));

    await act(async () => {
      await router.navigate(-1);
    });
    expect(document.getElementById('wizard-heading')).toBeNull();
    await act(async () => {
      await router.navigate(1);
    });
    const fill = await screen.findByRole('button', { name: copy['mustVisit.next'] });
    const lockedWhileOldRequestRuns = fill.hasAttribute('disabled');
    if (!lockedWhileOldRequestRuns) await user.click(fill);
    create.release();
    await waitFor(() => expect(createsAnswered).toBeGreaterThanOrEqual(1));
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)));

    expect(lockedWhileOldRequestRuns).toBe(true);
    expect(createdTrips).toBe(1);
    expect(saved).toHaveLength(2);
    expect(patched).toEqual([]);
    expect(router.state.location.pathname).toBe('/start');
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
  });
});

describe('FE-103-T58 a failed background pick stays recoverable after returning', () => {
  beforeEach(answerWrites);

  it('names the missing place and retries it under the original key', async () => {
    failing.add(second?.id ?? '');
    await leaveMidSave();
    const firstKey = saved.find((entry) => entry.body.placeId === second?.id)?.key;
    expect(patched).toEqual([]);

    await act(async () => {
      await router.navigate('/start');
    });
    await unsavedState([second?.name ?? '']);
    expect(screen.queryByRole('button', { name: copy['wizard.back'] })).toBeNull();
    expect(createdTrips).toBe(1);

    failing.clear();
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/trip/${TRIP}`));
    expect(saved.slice(2).map((entry) => entry.body.placeId)).toEqual([second?.id]);
    expect(saved[2]?.key).toBe(firstKey);
    expect(createdTrips).toBe(1);
  });
});

describe('FE-103-T59 a create retry uses the picks currently on screen', () => {
  beforeEach(answerWrites);

  it('drops a removed pick, adds a new one, and keeps the unchanged pick key', async () => {
    const third = placeFixtures.searchPage.items[2];
    const createKeys: (string | null)[] = [];
    server.use(
      http.post(`${API_BASE}/trips`, ({ request }) => {
        createdTrips += 1;
        createKeys.push(request.headers.get('Idempotency-Key'));
        if (createdTrips === 1) return HttpResponse.error();
        return HttpResponse.json({ id: TRIP }, { status: 201 });
      }),
    );
    const user = await searchFor('서울');
    await user.click(await addButton(first?.name ?? ''));
    await user.click(await addButton(second?.name ?? ''));
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));
    await waitFor(() => expect(createdTrips).toBe(1));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: copy['mustVisit.next'] })).toBeEnabled(),
    );
    const original = JSON.parse(
      sessionStorage.getItem('nullnull.wizard.attempt.v1') ?? '{}',
    ) as { picks?: { place: { id: string }; key: string }[] };
    const firstKey = original.picks?.find((pick) => pick.place.id === first?.id)?.key;

    await user.click(
      screen.getByRole('button', {
        name: `${second?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    );
    await user.click(await addButton(third?.name ?? ''));
    await user.click(screen.getByRole('button', { name: copy['mustVisit.next'] }));
    await waitFor(() => expect(saved).toHaveLength(2));

    expect(saved.map((entry) => entry.body.placeId)).toEqual([first?.id, third?.id]);
    expect(saved[0]?.key).toBe(firstKey);
    expect(createKeys[0]).toBe(createKeys[1]);
  });
});

describe('FE-103-T60 recovery records only unconfirmed candidate writes', () => {
  beforeEach(answerWrites);

  it('removes a confirmed first pick before the second write finishes', async () => {
    const secondGate = hold();
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ request, params }) => {
        const body = (await request.json()) as Record<string, unknown>;
        saved.push({
          tripId: String(params.tripId),
          key: request.headers.get('Idempotency-Key'),
          body,
        });
        if (body.placeId === second?.id) await secondGate.wait;
        const place = placeFixtures.searchPage.items.find(
          (item) => item.id === body.placeId,
        );
        return HttpResponse.json(
          {
            candidate: {
              ...candidateTemplate,
              id: crypto.randomUUID(),
              tripId: TRIP,
              place,
              status: 'ACTIVE',
              scheduledTripItemId: null,
              mustVisit: true,
            },
            duplicate: false,
            tripScheduleChanged: false,
          },
          { status: 201 },
        );
      }),
    );
    await keepTwoAndFill();
    await waitFor(() => expect(saved).toHaveLength(2));
    const attempt = JSON.parse(
      sessionStorage.getItem('nullnull.wizard.attempt.v1') ?? '{}',
    ) as { pending?: { place: { id: string } }[] };
    secondGate.release();
    await waitFor(() => expect(router.state.location.pathname).toBe(`/trip/${TRIP}`));

    expect(attempt.pending?.map((pick) => pick.place.id)).toEqual([second?.id]);
  });
});

describe('FE-103-T38 the kept list cannot change once sending starts', () => {
  beforeEach(answerWrites);

  it('offers neither keeping nor removing while the picks are being sent', async () => {
    // The picks are read when 이대로 채우기 is pressed. A place kept during the
    // writes would be shown as kept and never sent.
    const third = placeFixtures.searchPage.items[2];
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post(`${API_BASE}/trips/:tripId/candidates`, async () => {
        await held;
        return HttpResponse.error();
      }),
    );
    await keepTwoAndFill();

    await waitFor(async () => {
      expect(await addButton(third?.name ?? '')).toBeDisabled();
    });
    expect(
      screen.getByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    ).toBeDisabled();
    release();
    // Both writes fail, so the run ends on the partial failure. Waited on so
    // its second write does not land in the next case (finishReleasedRun).
    await screen.findByRole('button', { name: copy['mustVisit.retry'] });
  });

  it('offers neither keeping nor removing while the unsaved picks are held', async () => {
    // A place kept now would not be among the picks 다시 시도 sends, and one
    // removed would still be sent: either way the list would say something
    // the trip does not.
    const third = placeFixtures.searchPage.items[2];
    failing.add(second?.id ?? '');
    await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    expect(await addButton(third?.name ?? '')).toBeDisabled();
    expect(
      screen.getByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    ).toBeDisabled();
  });
});
