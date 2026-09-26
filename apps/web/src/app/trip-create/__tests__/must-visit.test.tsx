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
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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
/** Mounts the app at /start, the way a load or a reload of that URL does. */
function mountWizard() {
  router = createMemoryRouter(routes, { initialEntries: ['/start'] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function renderStep4() {
  const user = userEvent.setup();
  mountWizard();

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

async function searchFor(text: string) {
  const user = await renderStep4();
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
/** Every addTripCandidate request, including the ones answered with a failure. */
let saved: SavedCandidate[] = [];
let patched: Record<string, unknown>[] = [];
/** Place ids whose candidate write fails, until a case takes them out again. */
let failing = new Set<string>();

function answerWrites() {
  createdTrips = 0;
  saved = [];
  patched = [];
  failing = new Set();
  server.use(
    http.post(`${API_BASE}/trips`, () => {
      createdTrips += 1;
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

  it('sends one SEARCH candidate per pick, to the created trip, each with its own key', async () => {
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
    // One key per place: a shared key would make the server replay the first
    // place's answer for the second and save only one of them.
    const keys = saved.map((s) => s.key);
    expect(keys.every((key) => /^[0-9a-f-]{36}$/i.test(key ?? ''))).toBe(true);
    expect(new Set(keys).size).toBe(2);
    expect(createdTrips).toBe(1);
  });
});

describe('FE-103-T31 once every pick is saved the wizard opens the trip, as before', () => {
  beforeEach(answerWrites);

  it('opens the created trip only after the picks landed, and makes it the active one', async () => {
    await keepTwoAndFill();

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    // After, not instead: the trip is opened once both writes answered.
    expect(saved).toHaveLength(2);
    expect(router.state.historyAction).toBe('REPLACE');
    await waitFor(() => {
      expect(patched).toEqual([{ activeTripId: TRIP }]);
    });
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

describe('FE-103-T32 when a pick cannot be saved, the wizard stays and names it', () => {
  beforeEach(answerWrites);

  it('says the trip exists and names only the place that failed, without leaving', async () => {
    failing.add(second?.id ?? '');
    await keepTwoAndFill();

    const message = await unsavedState([second?.name ?? '']);
    expect(message).not.toHaveTextContent(first?.name ?? '');
    expect(saved).toHaveLength(2);
    // Still here. Opening the trip would have dropped the failed place
    // without a word, which is the defect #185 reported in the first place.
    expect(router.state.location.pathname).toBe('/start');
  });
});

describe('FE-103-T33 다시 시도 re-sends only the failed places, under their first keys', () => {
  beforeEach(answerWrites);

  it('retries the one that failed with the same key, then opens the trip', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);
    const firstKey = saved.find((s) => s.body.placeId === second?.id)?.key;

    failing.clear();
    await user.click(screen.getByRole('button', { name: copy['mustVisit.retry'] }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
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

describe('FE-103-T34 while a created trip is held, nothing creates a second one', () => {
  beforeEach(answerWrites);

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
});

describe('FE-103-T35 여행으로 가기 opens the created trip without the failed places', () => {
  beforeEach(answerWrites);

  it('leaves for the trip and sends nothing more', async () => {
    failing.add(second?.id ?? '');
    const user = await keepTwoAndFill();
    await unsavedState([second?.name ?? '']);

    await user.click(screen.getByRole('button', { name: copy['mustVisit.openTrip'] }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/trip/${TRIP}`);
    });
    expect(saved).toHaveLength(2);
    expect(createdTrips).toBe(1);
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

describe('FE-103-T37 a reload after the trip exists does not restore a draft that would create it again', () => {
  beforeEach(answerWrites);

  it('starts again at step 1 rather than on the kept places', async () => {
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

    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
    expect(createdTrips).toBe(1);
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
