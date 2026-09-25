import { expect, test, type Page } from '@playwright/test';

// FE-401-T4 / FE-402-T4 against the real API. Every test gets a fresh browser
// context, so the first request carries no session cookie: the shell's CSRF
// reissue answers the first-visit 401 and the shell bootstraps a session. A
// Live read that left before that bootstrap answered went out with no cookie
// and got the same 401; the list POST is never retried, so /live kept its
// load error after the session existed (observed on the public edge,
// 2026-09-23). These assert the ORDER on the wire, which a mocked server
// cannot show - the default MSW handlers answer /session/csrf without a cookie,
// so a mock run never bootstraps; playwright.config.ts runs *.integration.spec
// files only against the composed API.
//
// The gate runs with FEATURE_LIVE_DATA at its default (off), so a Live read
// that carries the session is answered 403 FORBIDDEN by the service, after the
// session check. Only the first-visit 401 means the read left without a cookie.
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
  expect((await areas).status()).not.toBe(401);
  expectReadAfterBootstrap(events, 'POST /api/v1/live/areas');
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

// The other side of the first-visit rule. A session cookie that was SENT and
// refused must never start a session: that would mint a different anonymous
// owner and strand every trip the traveller had (AppShell's noCookieSent).
// The unit tests fake the refusal with MSW; only the composed API refuses a
// real cookie, so only here is the Live deep link measured end to end.
//
// Two ways a real cookie is refused are made here: one revoked by
// deleteCurrentSession, and one altered after it was issued. The server answers
// every refused cookie the same way, body and headers - expired, revoked,
// forged (BA-010-T5) - so the client has nothing to tell an expired one apart
// by. An expired cookie cannot be made in the gate: the session lives for days
// and this API runs on the real clock.

/** Visits the app with no cookie, which mints a session; returns its CSRF token. */
async function mintSession(page: Page): Promise<string> {
  const minted = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/demo/sessions' &&
      response.request().method() === 'POST',
  );
  await page.goto('/feed');
  const response = await minted;
  expect(response.status()).toBe(201);
  return ((await response.json()) as { csrfToken: string }).csrfToken;
}

/** The session cookie the browser holds. */
async function sessionCookie(page: Page) {
  const cookie = (await page.context().cookies()).find((each) =>
    each.name.endsWith('nullnull_session'),
  );
  expect(cookie, 'the browser holds no session cookie').toBeDefined();
  return cookie!;
}

/**
 * Opens `path` holding a refused cookie and asserts no session was started:
 * the refusal reached the shell, the ended-session screen shows, no
 * POST /demo/sessions left, and the browser still holds the same cookie.
 */
async function expectNoNewOwner(page: Page, path: string, held: string) {
  const events = wireLog(page);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1 })).toHaveAttribute(
    'id',
    'session-heading',
  );
  // The cookie was sent and refused, which is what makes this the ended-session
  // case and not the first-visit one.
  expect(
    events.filter((event) => event === '< 401 POST /api/v1/session/csrf'),
    events.join('\n'),
  ).not.toEqual([]);
  expect(
    events.filter((event) => event.endsWith('POST /api/v1/demo/sessions')),
    events.join('\n'),
  ).toEqual([]);
  expect((await sessionCookie(page)).value).toBe(held);
}

for (const [id, path] of [
  ['FE-401-T6', '/live'],
  ['FE-402-T5', LIVE_PLACE_PATH],
] as const) {
  test(`${id} a revoked session cookie on a direct visit to ${path} starts no new session`, async ({
    page,
  }) => {
    const csrf = await mintSession(page);
    const revoked = await page.evaluate(
      async ({ token, key }) =>
        (
          await fetch('/api/v1/session', {
            method: 'DELETE',
            headers: { 'X-CSRF-Token': token, 'Idempotency-Key': key },
          })
        ).status,
      { token: csrf, key: `revoke-${crypto.randomUUID()}` },
    );
    expect(revoked).toBe(202);
    const held = (await sessionCookie(page)).value;

    await expectNoNewOwner(page, path, held);
  });

  test(`${id} an altered session cookie on a direct visit to ${path} starts no new session`, async ({
    page,
  }) => {
    await mintSession(page);
    const issued = await sessionCookie(page);
    // One character changed in the middle, so the value keeps its length and
    // alphabet and differs only from what the server issued.
    const middle = Math.floor(issued.value.length / 2);
    const altered =
      issued.value.slice(0, middle) +
      (issued.value[middle] === 'A' ? 'B' : 'A') +
      issued.value.slice(middle + 1);
    await page.context().addCookies([{ ...issued, value: altered }]);
    // The browser really holds the altered value: were it refused, this test
    // would visit with the issued cookie and measure a live session.
    expect((await sessionCookie(page)).value).toBe(altered);

    await expectNoNewOwner(page, path, altered);
  });
}
