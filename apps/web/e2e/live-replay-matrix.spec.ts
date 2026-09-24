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
// runs FE-403-T1 and FE-403-T3 in required CI.
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

/** Serves the Live list's calls; returns the calls it did not expect, for the test to assert empty. */
async function serve(page: Page, mode: keyof typeof AREA_RESULT): Promise<string[]> {
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const json = (body: string) =>
      route.fulfill({ status: 200, contentType: 'application/json', body });
    if (request.method() === 'POST' && pathname === '/api/v1/session/csrf')
      return json(CSRF);
    if (request.method() === 'POST' && pathname === '/api/v1/live/areas') {
      return json(AREA_RESULT[mode]);
    }
    if (
      request.method() === 'GET' &&
      /^\/api\/v1\/live\/areas\/[^/]+\/places$/.test(pathname)
    ) {
      return json(AREA_PLACES);
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
      test(`FE-403-T1 FE-403-T3 ${mode} at ${String(width)}px in ${locale} says which it is and fits`, async ({
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
