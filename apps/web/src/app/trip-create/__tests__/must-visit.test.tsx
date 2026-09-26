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
import {
  type InfiniteData,
  type QueryClient,
  QueryClientProvider,
} from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { crowdFixtures, placeFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import {
  NEXT_CURSOR,
  searchPages,
  servePlaceSearchPages,
} from '../../../shared/testing/msw/place-search-pages.js';
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
async function renderStep4(client = createQueryClient()) {
  const user = userEvent.setup();
  const router = createMemoryRouter(routes, { initialEntries: ['/start'] });
  render(
    <QueryClientProvider client={client}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );

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
    // MustVisitBadge keeps a Korean default for a render with no provider at
    // all. Inside the app the word is the locale's, whether this screen passes
    // it or the badge reads it itself (FE-001-T4) — this file runs in en-US, so
    // the default and the correct answer differ and the assertion is meaningful.
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

// No acceptance ID. These once carried the card's six-state clause, which is
// not what they render; they check the card against FCR-029 and CMP-ATT-003.
describe('the card shows only what the contract supplies', () => {
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

// #54 §4: the results past the first page. The contract pages searchPlaces by
// cursor and this screen used to render page one only, so a place past the
// 20th could not be kept here. The two pages are the fixture's own places
// (servePlaceSearchPages); the crowd handler at the top of this file answers
// each batch in its own order.
describe('FE-103-T17 the continuation goes once the last page is in', () => {
  // The hook half (no request after hasMore=false) is FE-103-T7 in
  // place-search.test.tsx; this is the control on screen.
  it('FE-103-T17 offers no control after a page that says there is no more', async () => {
    servePlaceSearchPages();
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await addButton(searchPages.next[0].name);

    // The last page: the control is gone rather than offering nothing.
    expect(
      screen.queryByRole('button', { name: copy['placeSearch.more'] }),
    ).not.toBeInTheDocument();
  });
});

describe('FE-103-T8 a continuation that fails keeps what was already received', () => {
  it('FE-103-T8 leaves the first page listed', async () => {
    servePlaceSearchPages({ failNext: 1 });
    const user = await searchFor('서울');
    const [a, b] = searchPages.first;
    await addButton(a.name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.retryMore'] });

    expect(await addButton(a.name)).toBeInTheDocument();
    expect(await addButton(b.name)).toBeInTheDocument();
  });
});

describe('FE-103-T9 a failed continuation says so', () => {
  it('FE-103-T9 announces that the next page failed', async () => {
    servePlaceSearchPages({ failNext: 1 });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));

    // Among all alerts rather than the only one: a second alert is
    // FE-103-T19's to report, and a single-alert query would throw on it here.
    await waitFor(() => {
      expect(screen.getAllByRole('alert').map((alert) => alert.textContent)).toContain(
        copy['placeSearch.moreFailed'],
      );
    });
  });
});

describe('FE-103-T19 a failed continuation is not a failed search', () => {
  it("FE-103-T19 adds no search failure of the screen's own when the next page fails", async () => {
    servePlaceSearchPages({ failNext: 1 });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.retryMore'] });

    expect(screen.queryByText(copy['mustVisit.searchError'])).toBeNull();
    // Nor an alert in other words: the continuation's is the only report.
    expect(
      screen
        .queryAllByRole('alert')
        .filter((alert) => alert.textContent !== copy['placeSearch.moreFailed']),
    ).toEqual([]);
  });
});

describe('FE-103-T18 the same control retries a failed continuation', () => {
  it('FE-103-T18 brings the page when the control is pressed again', async () => {
    servePlaceSearchPages({ failNext: 1 });
    const user = await searchFor('서울');
    const [c] = searchPages.next;
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));

    // The same control is the retry, and the retry is what brings the page.
    await user.click(
      await screen.findByRole('button', { name: copy['placeSearch.retryMore'] }),
    );
    expect(await addButton(c.name)).toBeInTheDocument();
    expect(screen.queryByText(copy['placeSearch.moreFailed'])).not.toBeInTheDocument();
  });
});

describe('FE-103-T10 a continuation pressed by keyboard lands on the first new result', () => {
  it('FE-103-T10 moves focus to the first result the page added', async () => {
    servePlaceSearchPages();
    const user = await searchFor('서울');
    const [c] = searchPages.next;
    await addButton(searchPages.first[0].name);

    const more = screen.getByRole('button', { name: copy['placeSearch.more'] });
    more.focus();
    await user.keyboard('{Enter}');

    // The result itself, not its 담기: the card is what a reader moves
    // between, and it is where the next Tab from the old last card would go.
    const added = (await addButton(c.name)).closest('li');
    await waitFor(() => {
      expect(added).toHaveFocus();
    });
  });
});

describe('FE-103-T11 a page past the first asks for its own crowd batch', () => {
  it('FE-103-T11 batches each page on its own', async () => {
    servePlaceSearchPages();
    const user = await searchFor('서울');
    const [c] = searchPages.next;
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await addButton(c.name);

    const firstIds = searchPages.first.map((place) => place.id);
    await waitFor(() => {
      expect(crowdBodies.at(-1)?.placeIds).toEqual([c.id]);
    });
    // No batch spans two pages: page two was asked for on its own, and page
    // one was not asked for again with it.
    for (const body of crowdBodies) {
      expect([firstIds, [c.id]]).toContainEqual(body.placeIds);
    }
  });
});

describe("FE-103-T16 a page's crowd batch is not asked for again when a later page arrives", () => {
  it("FE-103-T16 sends page one's batch once across the continuation", async () => {
    servePlaceSearchPages();
    const user = await searchFor('서울');
    const [c] = searchPages.next;
    const firstIds = searchPages.first.map((place) => place.id);
    const pageOneBatches = () =>
      crowdBodies.filter(
        (body) => JSON.stringify(body.placeIds) === JSON.stringify(firstIds),
      );
    await addButton(searchPages.first[0].name);
    await waitFor(() => {
      expect(pageOneBatches()).toHaveLength(1);
    });

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    // Page two's own batch has been answered and joined, so any batch that
    // page two's arrival set off has been sent by now.
    const card = (await addButton(c.name)).closest('li') as HTMLElement;
    await waitFor(() => {
      expect(card).toHaveTextContent('Relative concentration 72.5');
    });

    // Exactly one: a page's batch keeps its own cache entry, so a key that
    // changed with the page count would send page one's ids again here.
    expect(pageOneBatches()).toHaveLength(1);
  });
});

describe("FE-103-T12 a page's crowd answer is joined by the index within that page", () => {
  it('FE-103-T12 gives the first card of page two the first answer of its batch', async () => {
    servePlaceSearchPages();
    const user = await searchFor('서울');
    const [c] = searchPages.next;
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));

    // The top handler answers index 0 of any batch with the forecast. Joined
    // by the index within page two, the new card gets it; joined by its index
    // in the whole list, it would get nothing.
    const card = (await addButton(c.name)).closest('li') as HTMLElement;
    await waitFor(() => {
      expect(card).toHaveTextContent('Relative concentration 72.5');
    });
  });
});

describe('FE-103-T13 a continuation in flight says so', () => {
  it('FE-103-T13 names the control as loading until the page arrives', async () => {
    const served = servePlaceSearchPages({ hold: true });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    const busy = await screen.findByRole('button', {
      name: copy['placeSearch.loadingMore'],
    });
    expect(busy).toHaveAttribute('aria-busy', 'true');

    served.release();
    expect(await addButton(searchPages.next[0].name)).toBeInTheDocument();
  });
});

describe('FE-103-T14 a second press while loading sends nothing', () => {
  it('FE-103-T14 asks for the next page once however often it is pressed', async () => {
    const served = servePlaceSearchPages({ hold: true });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);

    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    const busy = await screen.findByRole('button', {
      name: copy['placeSearch.loadingMore'],
    });
    await user.click(busy);
    await user.click(busy);

    served.release();
    await addButton(searchPages.next[0].name);
    expect(served.bodies.filter((body) => body.cursor === NEXT_CURSOR)).toHaveLength(1);
  });
});

describe('FE-103-T15 a continuation that settles after its search changed moves no focus', () => {
  // The press awaits page two; by the time it settles the traveller may have
  // typed a new query. fetchNextPage then resolves with the observer's CURRENT
  // result, which belongs to the new search, so nothing it says is about the
  // page that was pressed for. Found by a reviewer's probe: typing while page
  // two loaded moved focus from the search box onto a result of the new search.
  //
  // Focus left in the box is now held for a reason of its own (FE-103-T22),
  // so each case first puts focus back where a press leaves it. The search's
  // change is then the only thing that can keep it there.
  //
  // Two cases because the press has two ways to move focus, split by how many
  // results the new search holds against the count at the press.

  /** The press's own page two has landed in the old search's cache entry. */
  async function pageTwoSettled(client: QueryClient) {
    await waitFor(() => {
      const pageCounts = client
        .getQueryCache()
        .findAll({ queryKey: ['places', 'search'] })
        .map(
          (query) =>
            (query.state.data as InfiniteData<unknown> | undefined)?.pages.length,
        );
      expect(pageCounts).toContain(2);
    });
  }

  it("FE-103-T15 leaves focus on the new search's continuation when that search has no more than before", async () => {
    const client = createQueryClient();
    const served = servePlaceSearchPages({ hold: true });
    const user = await renderStep4(client);
    const box = screen.getByRole('searchbox');
    await user.type(box, '서울');
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.loadingMore'] });

    // A new search while page two is out. It answers the same two places, so
    // its count equals the count at the press.
    await user.type(box, 'x');
    const more = await screen.findByRole('button', { name: copy['placeSearch.more'] });
    expect(served.bodies.some((body) => body.query === '서울x')).toBe(true);
    // A keyboard reader moves on to the new search's own continuation.
    more.focus();

    served.release();
    await pageTwoSettled(client);
    expect(more).toHaveFocus();
  });

  it('FE-103-T15 leaves focus on the document when the new search has more than before', async () => {
    const client = createQueryClient();
    const served = servePlaceSearchPages({ hold: true });
    // The new query answers three places on one page, past the count of two
    // at the press. Every other body falls through to the two-page server.
    server.use(
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        const body = (await request.clone().json()) as { query: string };
        if (body.query !== '서울x') return undefined;
        return HttpResponse.json({
          items: [...searchPages.first, ...searchPages.next],
          page: { nextCursor: null, hasMore: false },
        });
      }),
    );
    const user = await renderStep4(client);
    const box = screen.getByRole('searchbox');
    await user.type(box, '서울');
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.loadingMore'] });

    await user.type(box, 'x');
    await addButton(searchPages.next[0].name);
    // The new search has no continuation to move on to. A click outside the
    // box leaves focus on the document, as a press on Safari does.
    await user.click(document.body);
    expect(document.body).toHaveFocus();

    served.release();
    await pageTwoSettled(client);
    expect(document.body).toHaveFocus();
  });
});

describe('FE-103-T22 a continuation that settles after focus left it moves no focus', () => {
  // The same search this time: the traveller only moved focus while page two
  // loaded, and the page's arrival must not pull it back. Found by a
  // reviewer's probe: a click into the search box, before a letter was typed,
  // and page two landing took focus onto its first result.
  //
  // Two cases because the press has two ways to move focus: to the first
  // result the page added, or, when it added none, to the last result.

  it('FE-103-T22 leaves focus in the search box when the page adds results', async () => {
    const served = servePlaceSearchPages({ hold: true });
    const user = await searchFor('서울');
    const box = screen.getByRole('searchbox');
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.loadingMore'] });

    await user.click(box);
    served.release();
    await addButton(searchPages.next[0].name);
    expect(box).toHaveFocus();
  });

  it('FE-103-T22 leaves focus in the search box when the last page adds nothing', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    servePlaceSearchPages();
    server.use(
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        const body = (await request.clone().json()) as { cursor?: string | null };
        if (body.cursor !== NEXT_CURSOR) return undefined;
        await held;
        return HttpResponse.json({
          items: [],
          page: { nextCursor: null, hasMore: false },
        });
      }),
    );
    const user = await searchFor('서울');
    const box = screen.getByRole('searchbox');
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await screen.findByRole('button', { name: copy['placeSearch.loadingMore'] });

    await user.click(box);
    release();
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: copy['placeSearch.loadingMore'] }),
      ).toBeNull();
    });
    expect(box).toHaveFocus();
  });
});

// CURSOR_EXPIRED (410) and CURSOR_INVALID (400) refuse the cursor itself, so
// the plain retry, which resends it, could never succeed: a dead end found by a
// reviewer's probe. The contract's UI mapping for both is 첫 page부터 다시 조회.
describe('FE-103-T20 a refused cursor restarts the search from page one', () => {
  it.each(['CURSOR_EXPIRED', 'CURSOR_INVALID'] as const)(
    'FE-103-T20 answers %s by asking for page one again, without the cursor',
    async (code) => {
      const served = servePlaceSearchPages({ failNext: 1, failWith: code });
      const user = await searchFor('서울');
      await addButton(searchPages.first[0].name);
      await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));

      // The control offers the contract's recovery, named by its CTA.
      await user.click(
        await screen.findByRole('button', { name: copy[`error.${code}.cta`] }),
      );
      await screen.findByRole('button', { name: copy['placeSearch.more'] });

      // Typing sent a request per keystroke; these are the whole query's.
      expect(served.bodies.filter((body) => body.query === '서울')).toEqual([
        { query: '서울', locale: 'en-US' },
        { query: '서울', locale: 'en-US', cursor: NEXT_CURSOR },
        { query: '서울', locale: 'en-US' },
      ]);
    },
  );
});

describe('FE-103-T21 restarting after a refused cursor is not a failed search', () => {
  it('FE-103-T21 shows no search failure while page one is asked for again', async () => {
    const served = servePlaceSearchPages({
      failNext: 1,
      failWith: 'CURSOR_EXPIRED',
      holdRestart: true,
    });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);
    await user.click(screen.getByRole('button', { name: copy['placeSearch.more'] }));
    await user.click(
      await screen.findByRole('button', { name: copy['error.CURSOR_EXPIRED.cta'] }),
    );
    await served.restartRequested;

    // The query still holds the cursor error while page one is out again,
    // which a first-page check that reads only isFetchNextPageError would
    // take for a failed search.
    expect(screen.queryByText(copy['mustVisit.searchError'])).toBeNull();

    served.releaseRestart();
    await screen.findByRole('button', { name: copy['placeSearch.more'] });
  });
});

describe('FE-103-T23 a second press while restarting sends no refused cursor', () => {
  it('FE-103-T23 sends the refused cursor only the once it was refused', async () => {
    const served = servePlaceSearchPages({
      failNext: 1,
      failWith: 'CURSOR_EXPIRED',
      holdRestart: true,
    });
    const user = await searchFor('서울');
    await addButton(searchPages.first[0].name);
    const control = screen.getByRole('button', { name: copy['placeSearch.more'] });
    await user.click(control);
    await user.click(
      await screen.findByRole('button', { name: copy['error.CURSOR_EXPIRED.cta'] }),
    );
    await served.restartRequested;

    // Pressed again while page one is out. A refetch is not a next-page
    // fetch, so the query's own flags read idle; a press taken as a
    // continuation would cancel the restart and send the refused cursor again.
    await user.click(control);
    served.releaseRestart();
    // Settled either way: the restart's page one has come back, or the
    // continuation's page two has and the control is gone.
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: copy['placeSearch.loadingMore'] }),
      ).toBeNull();
    });

    expect(served.bodies.filter((body) => body.cursor === NEXT_CURSOR)).toHaveLength(1);
  });
});
