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
// showed the replay. So this file blocks service workers and routes the three
// calls the Live list makes - the CSRF reissue, the area query and an area's
// places - and aborts anything else, so a call the screen starts making later
// fails here instead of reaching the dev server's proxy.
//
// Mock-only: the composed gate runs no *.mock.spec.ts (playwright.config.ts).
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url), 'utf8');

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

async function serve(page: Page, mode: keyof typeof AREA_RESULT): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const json = (body: string) =>
      route.fulfill({ status: 200, contentType: 'application/json', body });
    if (request.method() === 'POST' && pathname === '/api/v1/session/csrf') return json(CSRF);
    if (request.method() === 'POST' && pathname === '/api/v1/live/areas') {
      return json(AREA_RESULT[mode]);
    }
    if (request.method() === 'GET' && /^\/api\/v1\/live\/areas\/[^/]+\/places$/.test(pathname)) {
      return json(AREA_PLACES);
    }
    return route.abort();
  });
  await page.route('https://dapi.kakao.com/**', (route) => route.abort());
}

for (const mode of ['LIVE', 'REPLAY'] as const) {
  for (const width of [360, 180] as const) {
    for (const locale of ['ko-KR', 'en-US'] as const) {
      test(`FE-403-T1 FE-403-T3 ${mode} at ${String(width)}px in ${locale} says which it is and fits`, async ({
        page,
      }) => {
        const words = WORDS[locale];
        await page.setViewportSize({ width, height: 400 });
        await serve(page, mode);
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

        const area = page.getByRole('button', { name: /광화문·덕수궁/ });
        await area.focus();
        await expect(area).toBeFocused();
        await page.keyboard.press('Enter');
        await expect(area).toHaveAttribute('aria-expanded', 'true');

        // The state stays in view at the end of a list that scrolls.
        const main = page.getByRole('main');
        expect(await main.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(
          true,
        );
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
      });
    }
  }
}
