// @vitest-environment happy-dom
//
// happy-dom because the router builds a Request to navigate (#67).
//
// A cookie-less deep link onto /live used to send its read-only POST
// /live/areas before the first-visit session existed. The server answered 401
// (no cookie), `useLiveAreas` does not retry a POST, and the screen kept its
// "couldn't check live areas" alert after the session had been created a few
// milliseconds later. Observed once on the public edge (2026-09-23).
//
// The simulated server below keeps one bit of state, "has the browser been
// given a session cookie yet", which is what the real cookie jar decides: every
// request before POST /demo/sessions carries no cookie and gets the contract's
// first-visit 401, every request after it is served normally.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { http, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { clearCsrfTokenForTest, createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const LIVE_PLACE_ID = '01a0b9d9-c30f-71c3-b67d-07d9252c563a';

let paths: string[] = [];
let cookieIssued = false;
/** Live reads the server saw before the browser held a cookie. */
let liveReadsWithoutCookie: string[] = [];

beforeEach(() => {
  clearCsrfTokenForTest();
  paths = [];
  cookieIssued = false;
  liveReadsWithoutCookie = [];
  server.events.on('request:start', ({ request }) => {
    paths.push(`${request.method} ${new URL(request.url).pathname}`);
  });
});

afterEach(() => {
  server.events.removeAllListeners();
  clearCsrfTokenForTest();
});

const firstVisit401 = () =>
  problemResponse('UNAUTHORIZED', {}, { missingCredential: 'SESSION_COOKIE' });

function liveRead(request: Request) {
  if (cookieIssued) return undefined;
  liveReadsWithoutCookie.push(new URL(request.url).pathname);
  return firstVisit401();
}

/**
 * A browser with no cookie yet. Returning nothing from a resolver hands the
 * request to the default handler, so once the cookie exists every endpoint
 * answers exactly as the rest of the suite expects.
 */
function firstVisitServer(bootstrapDelayMs = 30) {
  server.use(
    http.post(`${API_BASE}/session/csrf`, () =>
      cookieIssued ? undefined : firstVisit401(),
    ),
    http.post(`${API_BASE}/demo/sessions`, async () => {
      // The real bootstrap takes a round trip; the screen's own read must not
      // be able to overtake it and stay failed.
      await delay(bootstrapDelayMs);
      cookieIssued = true;
      return undefined;
    }),
    http.post(`${API_BASE}/live/areas`, ({ request }) => liveRead(request)),
    http.get(`${API_BASE}/live/places/:placeId`, ({ request }) => liveRead(request)),
    http.get(`${API_BASE}/live/areas/:areaId/places`, ({ request }) => liveRead(request)),
    http.get(`${API_BASE}/trips`, () => (cookieIssued ? undefined : firstVisit401())),
  );
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('a cookie-less deep link onto Live reads after the session exists', () => {
  it('FE-401-T4 shows the Live area list, not the load error, on a first visit to /live', async () => {
    firstVisitServer();
    renderAt('/live');

    expect(await screen.findByText('광화문·덕수궁', {}, { timeout: 3000 })).toBeTruthy();
    expect(screen.queryByText(messages['en-US']['live.error'])).toBeNull();
    expect(screen.queryByText(messages['en-US']['live.refreshError'])).toBeNull();
    // Not only recovered: the read waited, so nothing left without a cookie.
    expect(liveReadsWithoutCookie).toEqual([]);
    // The contract's "at most one new session per page load".
    expect(paths.filter((p) => p === 'POST /api/v1/demo/sessions')).toHaveLength(1);
  });

  it('FE-402-T4 shows the place, not the load error, on a first visit to /live/places/:id', async () => {
    firstVisitServer();
    renderAt(`/live/places/${LIVE_PLACE_ID}`);

    await waitFor(
      () => {
        expect(paths).toContain(`GET /api/v1/live/places/${LIVE_PLACE_ID}`);
        expect(cookieIssued).toBe(true);
      },
      { timeout: 3000 },
    );
    await waitFor(
      () => {
        expect(screen.queryByText(messages['en-US']['live.detail.error'])).toBeNull();
        expect(screen.getByRole('heading', { level: 1 })).toBeTruthy();
      },
      { timeout: 3000 },
    );
    // A GET would recover on its own after a 1s retry; the defect is that it
    // left before the session existed at all.
    expect(liveReadsWithoutCookie).toEqual([]);
    expect(paths.filter((p) => p === 'POST /api/v1/demo/sessions')).toHaveLength(1);
  });

  it('still does not start a session when a cookie was sent and failed', async () => {
    // The other direction, so the fix cannot widen into "any 401 bootstraps":
    // an expired, revoked or forged cookie must never mint a different
    // anonymous owner (SessionSafetyIT.expiration), on Live as anywhere else.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
      http.post(`${API_BASE}/live/areas`, () => problemResponse('UNAUTHORIZED')),
    );
    renderAt('/live');

    await screen.findByText(messages['en-US']['session.expired']);
    expect(paths).not.toContain('POST /api/v1/demo/sessions');
  });
});

describe('a read refused only for want of a cookie is asked again once the session exists', () => {
  // The class behind the Live defect. Most mount-time reads are GETs, which the
  // contract retries once after 1s on UNAUTHORIZED; that recovers only when the
  // first-visit bootstrap is quicker than the retry. On a slow network it is
  // not, and the screen kept its error after the session existed.
  it('FE-001-T3 recovers the trips chooser when the bootstrap outlasts the GET retry', async () => {
    firstVisitServer(1_500);
    renderAt('/trips/select');

    await waitFor(
      () => {
        expect(cookieIssued).toBe(true);
      },
      { timeout: 4000 },
    );
    await waitFor(
      () => {
        expect(screen.queryByText(messages['en-US']['tripSelect.error'])).toBeNull();
        expect(screen.queryByText(messages['en-US']['tripSelect.loading'])).toBeNull();
      },
      { timeout: 4000 },
    );
    expect(paths.filter((p) => p === 'POST /api/v1/demo/sessions')).toHaveLength(1);
  }, 10_000);

  it('does not ask again when the refusal was for a cookie that was sent', async () => {
    // An ended session answers 401 WITHOUT missingCredential; repeating that
    // read cannot help and the shell shows the ended-session screen instead.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
      http.get(`${API_BASE}/trips`, () => problemResponse('UNAUTHORIZED')),
    );
    renderAt('/trips/select');

    await screen.findByText(messages['en-US']['session.expired']);
    expect(paths).not.toContain('POST /api/v1/demo/sessions');
  });
});
