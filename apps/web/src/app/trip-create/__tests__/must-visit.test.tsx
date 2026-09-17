// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-103 acceptance (FR-TRC-04, FR-TRC-05, FR-TRC-08).
//
// Two assertions carry most of the weight:
//   - the query never reaches a URL, because searchPlaces is a read-only POST
//     specifically so free-form text stays out of CDN, proxy and history logs;
//   - the card shows no crowd figure, because the contract has no such field
//     and inventing one is the unsourced comparison invariant 8 forbids.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { placeFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const [first, second] = placeFixtures.searchPage.items;

let requests: { method: string; url: string }[] = [];

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
});
afterEach(() => {
  // The locale is stored, so a test that sets it must put it back or every
  // later test inherits it.
  localStorage.clear();
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
async function renderStep4() {
  const user = userEvent.setup();
  const router = createMemoryRouter(routes, { initialEntries: ['/start'] });
  render(
    <QueryClientProvider client={createQueryClient()}>
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

  // Step 3: the answer that leads here. NOTHING would create the trip and
  // MOSTLY_PLANNED would go to the paste screen.
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
      expect(body).toEqual({ query: '경복궁' });
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
  it('renders no crowd figure, because PlaceSummary still has none', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');

    // FCR-029, still open. Figma shows "4 · 혼잡" and a forecast badge here.
    // BA-023 added crowd as a separate dated series (getPlaceCrowdForecast),
    // deliberately not as a scalar on PlaceSummary, so the card still has no
    // single value to show and a number here would be invented.
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/혼잡/);
    expect(body).not.toMatch(/공식 혼잡 예측/);
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
    const served = new Set(
      placeFixtures.searchPage.items
        .map((item) => item.sourceAttribution?.attribution)
        .filter((text): text is string => typeof text === 'string'),
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
