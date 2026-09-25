import { readFileSync } from 'node:fs';
import { expect, test, type Page } from '@playwright/test';
import { overflow } from './overflow.js';

// FE-403 (#99): the Live list in LIVE and in REPLAY, at 360px and at 180px -
// 360px at 200% zoom - in ko and en, each from the approved example in
// packages/contracts/fixtures/live served with page.route.
//
// page.route cannot see a request the MSW worker answers. Measured: with the
// worker allowed it routed none of the Live reads and the page showed the MSW
// LIVE result; with the worker blocked it routed the area query and the page
// showed the replay. So this file blocks service workers and serves every call
// the Live list makes from the approved examples, and nothing reaches a server.
// That is also why it has no .mock/.integration suffix: the same inputs reach
// the page against the mock dev server and in the composed gate, which is what
// runs FE-403-T1, FE-403-T3, FE-401-T3 and FE-402-T3 in required CI — the
// gate's own Live screens show only the 403 of FEATURE_LIVE_DATA off, so this
// file is where the Live list and detail are measured there.
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

const AREA_RESULT = {
  LIVE: fixture('live/area-result-live.json'),
  REPLAY: fixture('live/area-result-replay.json'),
};
const AREA_PLACES = fixture('live/area-places.json');
const PLACE_DETAIL = fixture('live/place-detail-live.json');
/** The detail example's place — also the first place the area list offers. */
const DETAIL_PLACE = (JSON.parse(PLACE_DETAIL) as { place: { id: string; name: string } })
  .place;
const CSRF = fixture('session/csrf-token.json');

// messages.ts state.LIVE, state.REPLAY and crowd.observedAt. Both examples
// observed at 05:00Z, which is 14:00 in Seoul (formatReferenceTime).
const WORDS = {
  'ko-KR': {
    LIVE: '실시간 관측',
    REPLAY: '과거 관측 재생 · 실시간 아님',
    observed: '9. 20. 오후 2:00 관측 기준',
  },
  'en-US': {
    LIVE: 'Observed live',
    REPLAY: 'Replaying past observations · not live',
    observed: 'Observed 9/20, 2:00 PM',
  },
} as const;

/**
 * Serves the Live list's calls; returns the calls it did not expect, for the
 * test to assert empty. `detail` replaces the place detail the page is given
 * (read on every request, so a test may change it between visits), and
 * `queries` collects the area query bodies the page sent.
 */
async function serve(
  page: Page,
  mode: keyof typeof AREA_RESULT,
  options: { detail?: () => string; queries?: unknown[] } = {},
): Promise<string[]> {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const json = (body: string) =>
      route.fulfill({ status: 200, contentType: 'application/json', body });
    if (request.method() === 'POST' && pathname === '/api/v1/session/csrf')
      return json(CSRF);
    if (request.method() === 'POST' && pathname === '/api/v1/live/areas') {
      options.queries?.push(request.postDataJSON());
      return json(AREA_RESULT[mode]);
    }
    if (
      request.method() === 'GET' &&
      /^\/api\/v1\/live\/areas\/[^/]+\/places$/.test(pathname)
    ) {
      return json(AREA_PLACES);
    }
    if (
      request.method() === 'GET' &&
      pathname === `/api/v1/live/places/${DETAIL_PLACE.id}`
    ) {
      return json(options.detail?.() ?? PLACE_DETAIL);
    }
    // The shell asks for the owner after the CSRF reissue. Both approved owner
    // examples carry ko-KR, which would override the locale under test, so this
    // one is refused on purpose - and it is the only call refused that way.
    if (request.method() === 'GET' && pathname === '/api/v1/me') return route.abort();
    unexpected.push(`${request.method()} ${pathname}`);
    return route.abort();
  });
  await page.route('https://dapi.kakao.com/**', (route) => route.abort());
  return unexpected;
}

for (const mode of ['LIVE', 'REPLAY'] as const) {
  for (const width of [360, 180] as const) {
    for (const locale of ['ko-KR', 'en-US'] as const) {
      test(`FE-401-T3 FE-403-T1 FE-403-T3 ${mode} at ${String(width)}px in ${locale} says which it is and fits`, async ({
        page,
      }) => {
        const words = WORDS[locale];
        await page.setViewportSize({ width, height: 400 });
        const unexpected = await serve(page, mode);
        await page.addInitScript((value) => {
          localStorage.setItem('nullnull.locale', value);
        }, locale);
        await page.goto('/live');

        // Which one it is, in words, and never the other's.
        const state = page.getByTestId('live-persistent-state');
        await expect(state).toContainText(words[mode]);
        await expect(state).not.toContainText(words[mode === 'LIVE' ? 'REPLAY' : 'LIVE']);
        // The observation in Seoul time, never the raw instant.
        await expect(state).toContainText(words.observed);
        await expect(state).not.toContainText('2026-09-20T05:00:00Z');

        // Every area row says the same in its own badge: the list is where a
        // traveller reads it, not only the header.
        const rows = page.locator('button[aria-expanded]');
        await expect(rows).toHaveCount(
          (JSON.parse(AREA_RESULT[mode]) as { areas: unknown[] }).areas.length,
        );
        for (const row of await rows.all()) {
          await expect(row).toContainText(words[mode]);
          await expect(row).not.toContainText(words[mode === 'LIVE' ? 'REPLAY' : 'LIVE']);
        }

        const area = page.getByRole('button', { name: /광화문·덕수궁/ });
        await area.focus();
        await expect(area).toBeFocused();
        await page.keyboard.press('Enter');
        await expect(area).toHaveAttribute('aria-expanded', 'true');

        // The state stays in view at the end of a list that scrolls.
        const main = page.getByRole('main');
        expect(
          await main.evaluate((element) => element.scrollHeight > element.clientHeight),
        ).toBe(true);
        await main.evaluate((element) => {
          element.scrollTop = element.scrollHeight;
        });
        await expect(state).toBeInViewport();

        // Nothing reaches past the viewport and no text is cut off. The
        // document's scrollWidth alone missed a 300px-wide badge at 180px:
        // the app's scroll container holds it, so the page never scrolls.
        const measured = await overflow(page);
        expect(measured.spilling, `spills: ${measured.widest.join(', ')}`).toEqual([]);
        expect(measured.clipped, 'clips its own text').toEqual([]);
        // A call this file does not serve would have been aborted silently.
        expect(unexpected, 'calls this file does not serve').toEqual([]);
      });
    }
  }
}

/** Serves the calls and sets the UI locale before the app reads it. */
async function open(page: Page, locale: 'ko-KR' | 'en-US'): Promise<string[]> {
  const unexpected = await serve(page, 'LIVE');
  await page.addInitScript((value) => {
    localStorage.setItem('nullnull.locale', value);
  }, locale);
  return unexpected;
}

/**
 * Motion left on the page under `prefers-reduced-motion: reduce`, measured the
 * way responsive.spec.ts measures every other screen: an inline 600ms
 * transition and 900ms animation the reduce rule has to override, and every
 * element's own declared durations.
 */
async function motionUnderReduce(page: Page) {
  return page.evaluate(() => {
    const probe = document.createElement('div');
    probe.style.transitionProperty = 'opacity';
    probe.style.transitionDuration = '600ms';
    probe.style.animationName = 'nn-motion-probe';
    probe.style.animationDuration = '900ms';
    document.body.appendChild(probe);
    const probed = getComputedStyle(probe);
    const result = {
      probe: [probed.transitionDuration, probed.animationDuration],
      own: [...document.querySelectorAll('*')]
        .map((element) => getComputedStyle(element))
        .filter(
          (style) =>
            style.animationDuration !== '0s' || style.transitionDuration !== '0s',
        )
        .map((style) => `${style.animationDuration}/${style.transitionDuration}`),
    };
    probe.remove();
    return result;
  });
}

function expectInstant(measured: Awaited<ReturnType<typeof motionUnderReduce>>): void {
  const instant = /^(?:0s|1e-05s|0\.00001s)$/;
  // Measured at all: an empty duration means the probe never rendered.
  expect(
    measured.probe.every((value) => value !== ''),
    'the probe did not render',
  ).toBe(true);
  for (const value of measured.probe)
    expect(value, 'a probe duration survived').toMatch(instant);
  for (const value of measured.own) {
    for (const part of value.split('/'))
      expect(part, `motion left: ${value}`).toMatch(instant);
  }
}

test('FE-401-T3 FE-403-T3 the Live list collapses motion under reduce', async ({
  page,
}) => {
  // Was only in live-accessibility.mock.spec.ts, which the gate skips.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const unexpected = await open(page, 'en-US');
  await page.goto('/live');
  // The list is really there before its motion is measured.
  await expect(page.locator('button[aria-expanded]')).toHaveCount(
    (JSON.parse(AREA_RESULT.LIVE) as { areas: unknown[] }).areas.length,
  );
  expectInstant(await motionUnderReduce(page));
  expect(unexpected, 'calls this file does not serve').toEqual([]);
});

test.describe('FE-402-T3 the Live place detail', () => {
  test('opens and returns by keyboard, focus landing on the link it followed', async ({
    page,
  }) => {
    const unexpected = await open(page, 'en-US');
    await page.goto('/live');
    const area = page.getByRole('button', { name: /광화문·덕수궁/ });
    await area.focus();
    await page.keyboard.press('Enter');
    const link = page.getByRole('link', {
      name: `View Live information for ${DETAIL_PLACE.name}`,
    });
    await link.focus();
    await page.keyboard.press('Enter');

    // Named by the place it shows; the way back is a named control.
    await expect(
      page.getByRole('heading', { level: 1, name: DETAIL_PLACE.name }),
    ).toBeVisible();
    const back = page.getByRole('button', { name: 'Back to Live' });
    await back.focus();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/live$/);
    await expect(
      page.getByRole('link', { name: `View Live information for ${DETAIL_PLACE.name}` }),
    ).toBeFocused();
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });

  for (const width of [360, 180] as const) {
    for (const locale of ['ko-KR', 'en-US'] as const) {
      test(`fits at ${String(width)}px in ${locale}`, async ({ page }) => {
        // 180px is 360px at 200% zoom; en-US carries the longer strings.
        await page.setViewportSize({ width, height: 640 });
        const unexpected = await open(page, locale);
        await page.goto(`/live/places/${DETAIL_PLACE.id}`);
        await expect(
          page.getByRole('heading', { level: 1, name: DETAIL_PLACE.name }),
        ).toBeVisible();
        const measured = await overflow(page);
        expect(measured.spilling, `spills: ${measured.widest.join(', ')}`).toEqual([]);
        expect(measured.clipped, 'clips its own text').toEqual([]);
        expect(unexpected, 'calls this file does not serve').toEqual([]);
      });
    }
  }

  test('collapses motion under reduce', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    const unexpected = await open(page, 'en-US');
    await page.goto(`/live/places/${DETAIL_PLACE.id}`);
    await expect(
      page.getByRole('heading', { level: 1, name: DETAIL_PLACE.name }),
    ).toBeVisible();
    expectInstant(await motionUnderReduce(page));
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });
});

// BA-091-T2, T29 and T30 (#97), which the backend card hands to the browser:
// the Live list with the map off, every relation state, and no fake delta -
// one clause each, so each is proven by its own test.
const RELATED = JSON.parse(fixture('places/related-page.json')) as {
  items: { relation: string; place: { name: string } }[];
};
const RELATION_STATES = {
  EXACT: 'A verified equivalent is available',
  SIMILAR: 'Similar places are available',
  NONE: 'No valid alternative is available right now',
  CHECKING: 'Checking alternatives',
  UNKNOWN: "We haven't verified an alternative yet",
} as const;

test('BA-091-T2 map OFF: the area list carries every area and its places, from the list-only query', async ({
  page,
}) => {
  const queries: unknown[] = [];
  const unexpected = await serve(page, 'LIVE', { queries });
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
  });
  await page.goto('/live');

  // The map is off - no key in the gate, the SDK refused in a mock run - and
  // says so where it would have been.
  await expect(
    page.getByRole('region', { name: 'Live map' }).getByRole('status'),
  ).toContainText('Could not load the map');
  // Every area the answer holds is a row of the list.
  const areas = (JSON.parse(AREA_RESULT.LIVE) as { areas: { name: string }[] }).areas;
  await expect(page.locator('button[aria-expanded]')).toHaveCount(areas.length);
  for (const area of areas)
    await expect(
      page.locator('button[aria-expanded]', { hasText: area.name }),
    ).toBeVisible();
  // Asked as a list: the approved list-only query, no viewport.
  const listOnly: unknown = JSON.parse(fixture('live/area-query.json'));
  expect(queries.length, 'no area query was sent').toBeGreaterThan(0);
  for (const query of queries) expect(query).toEqual(listOnly);
  // An area opens onto its places, each a named way to its detail.
  await page.locator('button[aria-expanded]', { hasText: '광화문·덕수궁' }).click();
  const places = JSON.parse(AREA_PLACES) as { place: { name: string } }[];
  for (const item of places) {
    await expect(
      page.getByRole('link', { name: `View Live information for ${item.place.name}` }),
    ).toBeVisible();
  }
  expect(unexpected, 'calls this file does not serve').toEqual([]);
});

test('BA-091-T29 every relation state reads as itself on the place detail', async ({
  page,
}) => {
  // NONE, CHECKING and UNKNOWN are approved Live examples. EXACT and SIMILAR
  // carry places, which only the catalogue relation example has; the Live
  // detail example is given that example's place of the same relation.
  const bodies: Record<keyof typeof RELATION_STATES, string> = {
    NONE: fixture('live/place-detail-related-none.json'),
    CHECKING: fixture('live/place-detail-related-checking.json'),
    UNKNOWN: PLACE_DETAIL,
    EXACT: '',
    SIMILAR: '',
  };
  for (const relation of ['EXACT', 'SIMILAR'] as const) {
    const item = RELATED.items.find((each) => each.relation === relation);
    if (!item) throw new Error(`related-page.json has no ${relation} place`);
    const detail = JSON.parse(PLACE_DETAIL) as { related: object };
    bodies[relation] = JSON.stringify({
      ...detail,
      related: { ...detail.related, state: relation, items: [item] },
    });
  }
  let current = '';
  const unexpected = await serve(page, 'LIVE', { detail: () => current });
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
  });

  for (const state of Object.keys(RELATION_STATES) as (keyof typeof RELATION_STATES)[]) {
    current = bodies[state];
    expect(
      (JSON.parse(current) as { related: { state: string } }).related.state,
      'the body serves the state under test',
    ).toBe(state);
    await page.goto(`/live/places/${DETAIL_PLACE.id}`);
    const related = page.getByRole('region', { name: 'Other places to consider' });
    await expect(related).toContainText(RELATION_STATES[state]);
    for (const other of Object.keys(
      RELATION_STATES,
    ) as (keyof typeof RELATION_STATES)[]) {
      if (other !== state)
        await expect(related).not.toContainText(RELATION_STATES[other]);
    }
    // Only NONE sends the traveller elsewhere; a state that is still being
    // checked or not yet verified is not "nothing to offer".
    await expect(related.getByRole('link', { name: 'Browse other areas' })).toHaveCount(
      state === 'NONE' ? 1 : 0,
    );
    // A state that carries places names them.
    const items = (
      JSON.parse(current) as { related: { items: { place: { name: string } }[] } }
    ).related.items;
    for (const item of items)
      await expect(related.getByRole('link', { name: item.place.name })).toBeVisible();
  }
  expect(unexpected, 'calls this file does not serve').toEqual([]);
});

// messages.ts live.related.ineligible.
const INELIGIBLE = {
  'ko-KR': '비교 기준이 달라 혼잡 수치를 나란히 표시하지 않아요',
  'en-US': 'This crowd value uses a different basis, so it is not shown for comparison.',
} as const;

// A delta is a figure one reading holds over another: a signed number, a step
// or percent difference, "less crowded". The contract carries none, so none
// may be drawn, not even between two readings the metrics allow side by side.
const DELTA = {
  'ko-KR':
    /(?<![\p{L}\p{N}])[+\-−±]\s*\d|\d\s*%|\d+\s*단계\s*(?:낮|높|덜|더)|(?:덜|더)\s*(?:붐|한산|혼잡)|돌아가도|\+\s*\d+\s*분/u,
  'en-US':
    /(?<![\p{L}\p{N}])[+\-−±]\s*\d|\d\s*%|\b\d+\s*(?:levels?|steps?)\s+(?:lower|higher|quieter|busier)\b|\b(?:less|more)\s+(?:crowded|busy)\b|\b(?:quieter|busier)\s+than\b|\bdetour\b/iu,
} as const;

for (const locale of ['ko-KR', 'en-US'] as const) {
  test(`BA-091-T30 no fake delta on the Live detail or list in ${locale}`, async ({
    page,
  }) => {
    const detail = JSON.parse(PLACE_DETAIL) as {
      crowd: { ordinalLevel: string; provenance: Record<string, unknown> };
      related: object;
    };
    // Both readings SPATIAL-comparable in one group and one snapshot set, so
    // the page draws them side by side - the case a difference is tempting in.
    const comparable = {
      ...detail.crowd.provenance,
      comparisonAxis: 'SPATIAL',
      comparisonEligible: true,
      comparisonGroupId: 'seoul-live-e2e',
    };
    const [similar, other] = RELATED.items;
    if (!similar || !other) throw new Error('related-page.json needs two places');
    const body = JSON.stringify({
      ...detail,
      crowd: { ...detail.crowd, ordinalLevel: '3', provenance: comparable },
      related: {
        ...detail.related,
        state: 'SIMILAR',
        items: [
          {
            ...similar,
            relation: 'SIMILAR',
            crowd: { ...detail.crowd, ordinalLevel: '1', provenance: comparable },
          },
          {
            ...other,
            relation: 'SIMILAR',
            crowd: {
              ...detail.crowd,
              ordinalLevel: '4',
              provenance: { ...comparable, comparisonEligible: false },
            },
          },
        ],
      },
    });
    const unexpected = await serve(page, 'LIVE', { detail: () => body });
    await page.addInitScript((value) => {
      localStorage.setItem('nullnull.locale', value);
    }, locale);

    await page.goto(`/live/places/${DETAIL_PLACE.id}`);
    const main = page.getByRole('main');
    const rows = main.locator('li', {
      has: page.getByRole('link', { name: similar.place.name }),
    });
    // The comparable alternative shows its own reading, the other says why it
    // does not: both readings are really on the page before their gap is not.
    await expect(rows.getByRole('img')).toHaveCount(1);
    const ineligible = main.locator('li', {
      has: page.getByRole('link', { name: other.place.name }),
    });
    await expect(ineligible).toContainText(INELIGIBLE[locale]);
    await expect(ineligible.getByRole('img')).toHaveCount(0);
    expect(await main.getByRole('img').count()).toBeGreaterThanOrEqual(2);
    expect(await main.innerText()).not.toMatch(DELTA[locale]);

    await page.goto('/live');
    await page.locator('button[aria-expanded]', { hasText: '광화문·덕수궁' }).click();
    const places = JSON.parse(AREA_PLACES) as { place: { name: string } }[];
    await expect(main.locator('li a[data-live-place-link]')).toHaveCount(places.length);
    expect(await main.innerText()).not.toMatch(DELTA[locale]);
    expect(unexpected, 'calls this file does not serve').toEqual([]);
  });
}
