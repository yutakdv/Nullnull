// @vitest-environment happy-dom
//
// happy-dom because the router builds a Request to navigate (#67).
//
// FE-101 acceptance for FR-SES-02 and FR-SES-03.
//
// The rule these protect: a tab must be able to recover its CSRF token
// WITHOUT minting a session. POST /demo/sessions reuses a live session, but on
// an expired one it creates a different anonymous owner
// (SessionSafetyIT.expiration asserts the id differs) — so re-bootstrapping to
// fix an auth failure would silently strand every trip the user had.
//
// POST /session/csrf is the contract's answer: "Safe bootstrap endpoint for
// refresh and new tabs", cookie only, no existing token needed.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import {
  clearCsrfTokenForTest,
  createQueryClient,
  currentCsrfToken,
  reissueCsrfToken,
} from '../index.js';
import { API_BASE, problemResponse } from '../../testing/msw/handlers.js';
import { server } from '../../testing/msw/server.js';
import { routes } from '../../../app/routes.js';

/** Every request the server saw, by path. */
let paths: string[] = [];

beforeEach(() => {
  clearCsrfTokenForTest();
  paths = [];
  server.events.on('request:start', ({ request }) => {
    paths.push(new URL(request.url).pathname);
  });
});

afterEach(() => {
  server.events.removeAllListeners();
  clearCsrfTokenForTest();
});

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

describe('FR-SES-03 a tab gets its own CSRF token without a new session', () => {
  it('recovers a token when the app opens away from the splash screen', async () => {
    // The defect: only SplashScreen bootstraps, so a refresh or a deep link
    // onto any other route left the token null and every mutation would have
    // been rejected. Verified by loading /feed directly before the fix.
    expect(currentCsrfToken()).toBeNull();
    renderAt('/feed');
    await screen.findByRole('heading', { level: 1 });
    await waitFor(() => {
      expect(currentCsrfToken()).not.toBeNull();
    });
  });

  it('asks /session/csrf and never mints a session to get a token', async () => {
    renderAt('/feed');
    await waitFor(() => {
      expect(currentCsrfToken()).not.toBeNull();
    });
    expect(paths).toContain('/api/v1/session/csrf');
    // The one that matters: a bootstrap here would create a new anonymous
    // owner on an expired session and strand the user's data.
    expect(paths).not.toContain('/api/v1/demo/sessions');
  });

  it('does not ask again when the tab already holds a token', async () => {
    await reissueCsrfToken();
    const held = currentCsrfToken();
    paths = [];

    renderAt('/feed');
    await screen.findByRole('heading', { level: 1 });
    // Tokens are tab-local and the server evicts the least recently used once
    // a sixth exists, so asking when we already hold one can evict another
    // tab's token for nothing.
    expect(paths).not.toContain('/api/v1/session/csrf');
    expect(currentCsrfToken()).toBe(held);
  });
});

describe('FR-SES-02 recovery does not multiply requests or owners', () => {
  it('collapses concurrent reissues into one request', async () => {
    // Several mutations can fail at once. Each asking for its own token walks
    // the server's five-token limit and evicts tokens that are still in use.
    server.use(
      http.post(`${API_BASE}/session/csrf`, async () => {
        await delay(30);
        return HttpResponse.json({
          csrfToken: 'dGVzdC10b2tlbi1zaW5nbGUtZmxpZ2h0LW9ubHktb25l',
          expiresAt: '2026-09-11T12:00:00Z',
        });
      }),
    );

    const [a, b, c] = await Promise.all([
      reissueCsrfToken(),
      reissueCsrfToken(),
      reissueCsrfToken(),
    ]);

    expect(paths.filter((p) => p === '/api/v1/session/csrf')).toHaveLength(1);
    expect(a).toBe(b);
    expect(b).toBe(c);
  });

  it('allows a later reissue once the first has settled', async () => {
    await reissueCsrfToken();
    await reissueCsrfToken();
    // Single-flight must not become single-shot: a token that expires later in
    // the session still needs replacing.
    expect(paths.filter((p) => p === '/api/v1/session/csrf')).toHaveLength(2);
  });

  it('surfaces a dead session instead of bootstrapping a replacement owner', async () => {
    // A 401 here means the cookie names a session that is gone. The contract's
    // recovery is the user's, not an automatic new session: POST /demo/sessions
    // would succeed and hand back a DIFFERENT owner, so the user would appear
    // to be signed in with every trip missing.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
    );

    await expect(reissueCsrfToken()).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
    expect(currentCsrfToken()).toBeNull();
    expect(paths).not.toContain('/api/v1/demo/sessions');
  });

  it('does not retry a failed reissue on its own', async () => {
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
    );
    await expect(reissueCsrfToken()).rejects.toBeDefined();
    expect(paths.filter((p) => p === '/api/v1/session/csrf')).toHaveLength(1);
  });
});
