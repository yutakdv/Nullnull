import { expect, test, type Page } from '@playwright/test';

// FE-401-T4 / FE-402-T4 against the real API. Every test gets a fresh browser
// context, so the first request carries no session cookie: the shell's CSRF
// reissue answers the first-visit 401 and the shell bootstraps a session. A
// Live read that left before that bootstrap answered went out with no cookie
// and got the same 401; the list POST is never retried, so /live kept its
// load error after the session existed (observed on the public edge,
// 2026-09-23). These assert the ORDER on the wire, which a mocked server
// cannot show.
//
// No trace or screenshot: the recorded requests would carry the session cookie.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

// Invented on purpose, like screens.ts: the claim is about when the read goes
// out and that it is not refused for lack of a cookie, not about the place.
const LIVE_PLACE_PATH = '/live/places/018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';

/** API requests and responses in the order the browser saw them. */
function wireLog(page: Page): string[] {
  const events: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith('/api/v1/')) events.push(`> ${request.method()} ${path}`);
  });
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith('/api/v1/')) {
      events.push(`< ${response.status()} ${response.request().method()} ${path}`);
    }
  });
  return events;
}

function expectReadAfterBootstrap(events: string[], read: string) {
  const booted = events.findIndex((event) =>
    /^< 20[01] POST \/api\/v1\/demo\/sessions$/.test(event),
  );
  const sent = events.findIndex((event) => event.startsWith(`> ${read}`));
  expect(booted, events.join('\n')).toBeGreaterThanOrEqual(0);
  expect(sent, events.join('\n')).toBeGreaterThan(booted);
  expect(
    events.filter(
      (event) => event.startsWith('< 401') && event.includes('/api/v1/live/'),
    ),
    events.join('\n'),
  ).toEqual([]);
}

test('FE-401-T4 a first visit straight to /live asks for Live areas only after the session exists', async ({
  page,
}) => {
  const events = wireLog(page);
  const areas = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/live/areas' &&
      response.request().method() === 'POST',
  );
  await page.goto('/live');
  expect((await areas).status()).toBe(200);
  expectReadAfterBootstrap(events, 'POST /api/v1/live/areas');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('FE-402-T4 a first visit straight to /live/places/:id asks for the place only after the session exists', async ({
  page,
}) => {
  const events = wireLog(page);
  const detail = page.waitForResponse((response) =>
    new URL(response.url()).pathname.startsWith('/api/v1/live/places/'),
  );
  await page.goto(LIVE_PLACE_PATH);
  expect((await detail).status()).not.toBe(401);
  expectReadAfterBootstrap(events, 'GET /api/v1/live/places/');
});
