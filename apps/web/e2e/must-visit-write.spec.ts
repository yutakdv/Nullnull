import { expect, test, type Page } from '@playwright/test';
import { overflow } from './overflow.js';

// #185 in a real browser: the must-visit picks of S02-4B reach the new trip as
// candidates with `mustVisit: true` (#180 option B), and a partial failure is
// named rather than swallowed.
//
// Runs in BOTH suites — the file is not `*.mock.spec.ts` — so the same steps
// meet the MSW handlers locally and the real API in the docker-integration
// gate. Two things are driven from inside the page for that reason:
//
//   - The SEARCH is answered here, with the two places the gate seeds
//     (scripts/e2e/catalog-seed.sql: 경복궁 and 인사동). searchPlaces returns
//     only places with coordinates, and the seed gives coordinates to 서울숲
//     alone, so the real search can offer one pick at most and this flow needs
//     two. Only the search is replaced: createTrip, every addTripCandidate and
//     the candidate list go to whichever backend the suite runs against, so in
//     the gate the ids are real rows and the writes are real writes.
//   - The FAILED WRITE is injected by wrapping `fetch`, not with page.route.
//     page.route cannot see a request the MSW service worker answers
//     (live-replay-matrix.spec.ts measured that), so a route-based failure
//     would pass the gate and do nothing locally. The wrapper sits in front of
//     both, the way candidate-not-active.mock.spec.ts already does it.
//
// The wrapper also records each candidate write — key, trip and body — before
// deciding its fate, because the injected failure never reaches the network
// and the retry's key has to be compared with it.

const GYEONGBOKGUNG = { id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01', name: '경복궁' };
const INSADONG = { id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03', name: '인사동' };

interface Attempt {
  tripId: string;
  key: string | null;
  body: { placeId?: string; source?: { type?: string }; mustVisit?: boolean };
  /** The saved place's name as the backend answered it, once it answered. */
  savedName?: string;
}

const COPY = {
  'en-US': {
    title: 'Add your trip dates',
    next: 'Next',
    mustVisitOnly: /Only the must-visits/,
    search: 'Search by place name',
    keep: (name: string) => `Keep ${name}`,
    fill: 'Fill the rest',
    unsaved: (names: string[]) =>
      `Your trip was created, but ${String(names.length)} of your places couldn't be saved: ${names.join(', ')}`,
    retry: 'Try again',
    openTrip: 'Go to trip',
    saved: /^Saved places \d+$/,
    badge: 'Must visit',
  },
  'ko-KR': {
    title: '여행 일정 등록',
    next: '다음',
    mustVisitOnly: /꼭 가고 싶은 곳만 정했어요/,
    search: '장소 이름으로 검색',
    keep: (name: string) => `${name} 담기`,
    fill: '이대로 채우기',
    unsaved: (names: string[]) =>
      `여행은 만들었어요. ${String(names.length)}곳을 담지 못했어요: ${names.join(', ')}`,
    retry: '다시 시도',
    openTrip: '여행으로 가기',
    saved: /^담아둔 장소 \d+$/,
    badge: '꼭 가요',
  },
} as const;

type Locale = keyof typeof COPY;

/**
 * Answers the search with the gate's two seeded places and records (and, for
 * `failOnce`, fails the first) addTripCandidate request.
 */
async function interceptWrites(page: Page, failOnce: string | null) {
  await page.addInitScript(
    ({ places, failOnce }) => {
      const attempts: Attempt[] = [];
      (window as unknown as { __candidateAttempts: Attempt[] }).__candidateAttempts =
        attempts;
      let failed = false;
      const nativeFetch = window.fetch.bind(window);
      window.fetch = async (...args) => {
        const [input, init] = args;
        const request = input instanceof Request ? input : new Request(input, init);
        const { pathname } = new URL(request.url, location.origin);
        if (request.method === 'POST' && pathname.endsWith('/places/search')) {
          return new Response(
            JSON.stringify({
              items: places.map((place) => ({
                ...place,
                categoryCode: 'HS',
                regionCode: '11',
                categoryName: null,
                regionName: null,
                thumbnailUrl: null,
                thumbnailAttribution: null,
                address: null,
                sourceAttribution: null,
              })),
              page: { nextCursor: null, hasMore: false },
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          );
        }
        const write = /\/trips\/([^/]+)\/candidates$/.exec(pathname);
        if (request.method !== 'POST' || !write) return nativeFetch(...args);

        const attempt: Attempt = {
          tripId: write[1] ?? '',
          key: request.headers.get('Idempotency-Key'),
          body: JSON.parse(await request.clone().text()) as Attempt['body'],
        };
        attempts.push(attempt);
        if (!failed && attempt.body.placeId === failOnce) {
          failed = true;
          // What a lost connection looks like to the client.
          throw new TypeError('Failed to fetch');
        }
        const response = await nativeFetch(...args);
        if (response.ok) {
          const saved = (await response.clone().json()) as {
            candidate: { place: { name: string } };
          };
          attempt.savedName = saved.candidate.place.name;
        }
        return response;
      };
    },
    { places: [GYEONGBOKGUNG, INSADONG], failOnce },
  );
}

async function attempts(page: Page): Promise<Attempt[]> {
  return page.evaluate(
    () => (window as unknown as { __candidateAttempts: Attempt[] }).__candidateAttempts,
  );
}

/**
 * Starts a session, walks the wizard to S02-4B, keeps both seeded places and
 * presses 이대로 채우기.
 *
 * `/` first, as seeded-trip.ts does: the session is minted there, and the
 * wizard's createTrip needs its cookie and CSRF token against the real API.
 */
async function keepBothAndFill(page: Page, locale: Locale = 'en-US') {
  const copy = COPY[locale];
  if (locale !== 'en-US') {
    await page.addInitScript((value) => {
      localStorage.setItem('nullnull.locale', value);
    }, locale);
  }
  await page.goto('/');
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });
  await page.goto('/start');
  await expect(page.getByRole('heading', { name: copy.title })).toBeVisible();

  await page.getByRole('button', { name: '20', exact: true }).click();
  await page.getByRole('button', { name: '23', exact: true }).click();
  await page.getByRole('button', { name: /–/ }).click();
  await page.getByRole('button', { name: copy.next }).click();
  await page.getByRole('button', { name: copy.mustVisitOnly }).click();
  await page.getByRole('button', { name: copy.next }).click();

  await page.getByLabel(copy.search).fill('서울');
  await page.getByRole('button', { name: copy.keep(GYEONGBOKGUNG.name) }).click();
  await page.getByRole('button', { name: copy.keep(INSADONG.name) }).click();
  await page.getByRole('button', { name: copy.fill }).click();
}

const TRIP_PATH = /\/trip\/([0-9a-f-]{36})$/i;

test('FE-103-T30 FE-303-T4 kept places become must-visit candidates of the new trip', async ({
  page,
}) => {
  await interceptWrites(page, null);
  await keepBothAndFill(page);

  await page.waitForURL(TRIP_PATH, { timeout: 15_000 });
  const tripId = TRIP_PATH.exec(new URL(page.url()).pathname)?.[1];

  // The request bodies, whole: a `mustVisit: false` or a missing source is a
  // different candidate on the server.
  const sent = await attempts(page);
  expect(sent.map((a) => a.body)).toEqual(
    [GYEONGBOKGUNG, INSADONG].map((place) => ({
      placeId: place.id,
      source: { type: 'SEARCH' },
      mustVisit: true,
    })),
  );
  expect(sent.map((a) => a.tripId)).toEqual([tripId, tripId]);
  expect(new Set(sent.map((a) => a.key)).size).toBe(2);

  // And the trip's candidate panel shows them as must-visits. Reached by the
  // trip's own link rather than page.goto: a reload would restart the MSW
  // handlers and drop the candidates the mock suite just saved.
  const savedNames = sent.map((a) => a.savedName);
  expect(savedNames.every((name) => typeof name === 'string')).toBe(true);
  await page.getByRole('link', { name: COPY['en-US'].saved }).click();
  const badged = page.getByRole('article').filter({ hasText: COPY['en-US'].badge });
  await expect(badged).toHaveCount(2);
  const headings = await badged.getByRole('heading', { level: 2 }).allTextContents();
  expect(headings.sort()).toEqual([...(savedNames as string[])].sort());
});

test('FE-103-T32 FE-103-T42 FE-103-T33 FE-103-T39 a place that fails is named, and the retry re-sends it with its key', async ({
  page,
}) => {
  const copy = COPY['en-US'];
  await interceptWrites(page, INSADONG.id);
  await keepBothAndFill(page);

  const message = page.getByText(copy.unsaved([INSADONG.name]));
  await expect(message).toBeVisible();
  await expect(message).toBeInViewport();
  // Announced, and the wizard did not leave: the trip exists, the place is not
  // on it, and the traveller is told which one.
  await expect(page.getByRole('alert').filter({ hasText: INSADONG.name })).toHaveCount(1);
  expect(new URL(page.url()).pathname).toBe('/start');

  // FE-103-T39: focus is where the traveller pressed, now 다시 시도. The CTA
  // was disabled while the writes ran, which drops focus to <body> in
  // Chromium; without putting it back, the next Tab starts at the top.
  const retry = page.getByRole('button', { name: copy.retry });
  await expect(retry).toBeFocused();
  await page.keyboard.press('Enter');

  await page.waitForURL(TRIP_PATH, { timeout: 15_000 });
  const sent = await attempts(page);
  expect(sent.map((a) => a.body.placeId)).toEqual([
    GYEONGBOKGUNG.id,
    INSADONG.id,
    INSADONG.id,
  ]);
  // The first key again, on the same trip: a first attempt that did commit is
  // replayed by the server, not saved twice.
  expect(sent[2]?.key).toBe(sent[1]?.key);
  expect(sent[2]?.tripId).toBe(sent[1]?.tripId);
});

/**
 * Walks to the partial-failure state at one size and locale and returns its
 * message. 180px is 360px at 200% zoom, the way the other reflow checks
 * emulate it.
 */
async function partialFailureAt(page: Page, locale: Locale, width: 180 | 360 | 390) {
  const copy = COPY[locale];
  await page.setViewportSize({
    width,
    height: { 180: 400, 360: 800, 390: 844 }[width],
  });
  await interceptWrites(page, INSADONG.id);
  await keepBothAndFill(page, locale);
  const message = page.getByText(copy.unsaved([INSADONG.name]));
  await expect(message).toBeVisible();
  return { copy, message };
}

// One test per clause, so a failure names the clause it broke: the three
// layout clauses used to share one test and one ID, and only the bar clause
// had ever been seen to fail (#185 review). 390px Korean is here because it
// failed where the others passed: the message was already inside the scroll
// box, just under the fixed bar, and a 'nearest' scroll left it hidden there.
for (const [locale, width] of [
  ['ko-KR', 180],
  ['en-US', 360],
  ['ko-KR', 390],
] as const) {
  const at = `at ${String(width)}px in ${locale}`;

  test(`FE-103-T40 the partial-failure state does not spill sideways ${at}`, async ({
    page,
  }) => {
    await partialFailureAt(page, locale, width);
    const layout = await overflow(page);
    expect(layout.spilling, `spills: ${layout.widest.join(', ')}`).toEqual([]);
  });

  test(`FE-103-T43 the partial-failure state's own text is not cut off ${at}`, async ({
    page,
  }) => {
    // Scoped to the message and the two buttons rather than asserted over
    // `overflow().clipped`: at 180px the search result cards above already
    // clip their names and crowd labels before this state exists (measured on
    // the same flow without a failure), and that belongs to the step's card
    // layout, not to #185.
    const { copy, message } = await partialFailureAt(page, locale, width);
    const state = [
      message,
      page.getByRole('button', { name: copy.retry }),
      page.getByRole('button', { name: copy.openTrip }),
    ];
    for (const locator of state) {
      const cut = await locator.evaluate(
        (el) =>
          el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1,
      );
      expect(cut, `${await locator.innerText()} is cut off`).toBe(false);
    }
  });

  test(`FE-103-T44 the partial-failure message sits above the fixed bar ${at}`, async ({
    page,
  }) => {
    // Readable where it lands: above the fixed bar, not scrolled under it.
    const { message } = await partialFailureAt(page, locale, width);
    const [text, bar] = await Promise.all([
      message.boundingBox(),
      page.locator('[data-fixed="true"]').boundingBox(),
    ]);
    expect(text).not.toBeNull();
    expect(bar).not.toBeNull();
    expect((text?.y ?? 0) + (text?.height ?? 0)).toBeLessThanOrEqual((bar?.y ?? 0) + 1);
    expect(text?.y ?? -1).toBeGreaterThanOrEqual(0);
  });

  test(`FE-103-T41 the partial-failure actions are 44px touch targets ${at}`, async ({
    page,
  }) => {
    const { copy } = await partialFailureAt(page, locale, width);
    for (const name of [copy.retry, copy.openTrip]) {
      const box = await page.getByRole('button', { name }).boundingBox();
      expect(box, `${name} is rendered`).not.toBeNull();
      expect(box?.width ?? 0, `${name} width`).toBeGreaterThanOrEqual(44);
      expect(box?.height ?? 0, `${name} height`).toBeGreaterThanOrEqual(44);
    }
  });
}
