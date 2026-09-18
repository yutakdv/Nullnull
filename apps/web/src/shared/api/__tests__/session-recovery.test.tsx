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
import { messages } from '../../../i18n/messages.js';
import {
  clearCsrfTokenForTest,
  createQueryClient,
  currentCsrfToken,
  getApiClient,
  reissueCsrfToken,
  toProblem,
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

  it('replaces a stale tab token when a mutation is rejected for it', async () => {
    // Proven before the fix: after a 403 the dead token stayed in memory, so
    // the user's own retry sent it again and failed identically — a loop with
    // no way out but a reload.
    await reissueCsrfToken();
    const stale = currentCsrfToken();
    server.use(
      http.delete(`${API_BASE}/session`, () => problemResponse('CSRF_INVALID')),
      http.post(`${API_BASE}/session/csrf`, () =>
        HttpResponse.json({
          csrfToken: 'ZnJlc2gtdG9rZW4tYWZ0ZXItY3NyZi1pbnZhbGlkLW9uZQ',
          expiresAt: '2026-09-11T12:00:00Z',
        }),
      ),
    );

    const client = createQueryClient();
    await client
      .getMutationCache()
      .build(client, {
        mutationFn: async () => {
          const { data, error, response } = await getApiClient().DELETE('/session', {
            params: { header: { 'Idempotency-Key': 'test-key' } },
          } as never);
          if (!data) {
            const problem = toProblem(error);
            if (problem) throw problem;
            throw new Error(String(response.status));
          }
          return data;
        },
      })
      .execute(undefined)
      .catch(() => undefined);

    await waitFor(() => {
      expect(currentCsrfToken()).not.toBe(stale);
    });
    expect(currentCsrfToken()).not.toBeNull();
  });

  it('does not replay the mutation it just repaired the token for', async () => {
    // The contract says reissue then re-confirm with the user, never replay:
    // a non-idempotent mutation sent twice is what invariant 6's guards exist
    // to prevent.
    await reissueCsrfToken();
    let deletes = 0;
    server.use(
      http.delete(`${API_BASE}/session`, () => {
        deletes += 1;
        return problemResponse('CSRF_INVALID');
      }),
    );

    const client = createQueryClient();
    await client
      .getMutationCache()
      .build(client, {
        mutationFn: async () => {
          const { data, error, response } = await getApiClient().DELETE('/session', {
            params: { header: { 'Idempotency-Key': 'test-key' } },
          } as never);
          if (!data) {
            const problem = toProblem(error);
            if (problem) throw problem;
            throw new Error(String(response.status));
          }
          return data;
        },
      })
      .execute(undefined)
      .catch(() => undefined);

    await waitFor(() => {
      expect(paths).toContain('/api/v1/session/csrf');
    });
    expect(deletes).toBe(1);
  });

  it('does not retry a failed reissue on its own', async () => {
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
    );
    await expect(reissueCsrfToken()).rejects.toBeDefined();
    expect(paths.filter((p) => p === '/api/v1/session/csrf')).toHaveLength(1);
  });
});

describe('FR-SES-03 an expired session is a screen state, not silence', () => {
  // PROBLEM_POLICY marks UNAUTHORIZED severity 'screen' with recovery
  // 'restart-session'. AppShell called useCsrfToken() and discarded the
  // result, so a deep link or refresh onto /feed with an expired cookie
  // rendered the normal screen: the reissue 401'd with retry:false, the feed's
  // own /trips 401'd too, and FeedScreen gates the feed on trips.isSuccess —
  // so the user watched "불러오는 중" for ever with no error, no retry and no
  // way back. Reproduced in a browser before this test existed.
  function expiredSession() {
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
      http.get(`${API_BASE}/trips`, () => problemResponse('UNAUTHORIZED')),
    );
  }

  it('tells the user the session ended instead of loading for ever', async () => {
    expiredSession();
    renderAt('/feed');
    // Asserted as a HEADING, not as an alert. The sentence used to carry
    // role="alert" on the <h1> itself, which replaced the implicit heading
    // role and left this screen with no heading at all (#240) - the shape
    // every h1-checking spec tripped over. The announcement now lives on a
    // wrapping live region, so both hold.
    // Waited for by NAME: the feed renders its own <h1> first, so asking for
    // "the level-1 heading" matched "Browse" and failed on the frame before
    // the session screen replaced it.
    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: messages['en-US']['session.expired'],
      }),
    ).toBeInTheDocument();
  });

  it('offers the restart the contract names as the recovery', async () => {
    expiredSession();
    renderAt('/feed');
    expect(
      await screen.findByRole('button', {
        name: messages['en-US']['session.restart'],
      }),
    ).toBeInTheDocument();
  });

  it('does not mint a replacement session behind the user', async () => {
    // A fresh bootstrap on an expired session creates a DIFFERENT anonymous
    // owner (SessionSafetyIT.expiration), stranding every trip the user had.
    // The recovery has to be the user's deliberate act, not an automatic one.
    expiredSession();
    renderAt('/feed');
    await screen.findByRole('heading', { level: 1 });
    expect(paths.filter((p) => p === '/api/v1/demo/sessions')).toHaveLength(0);
  });

  it('does not call a network failure an ended session', async () => {
    // A dropped connection is recoverable and the trips are still there.
    // Showing "세션이 만료됐어요" for one would tell the user their session is
    // gone when it is not, and push them at a restart they do not need. Only
    // the contract's UNAUTHORIZED means the session ended.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => HttpResponse.error()),
      http.get(`${API_BASE}/trips`, () => HttpResponse.error()),
    );
    renderAt('/feed');
    // Wait until the reissue has actually failed, or the assertion below is
    // just observing the moment before the error arrives and passes for the
    // wrong reason — checked by making AppShell treat every error as an ended
    // session and watching this fail.
    await waitFor(() => {
      expect(paths).toContain('/api/v1/session/csrf');
    });
    // Let the failure settle, so this is not observing the moment before the
    // error arrives — without the wait it passes even when AppShell treats
    // every error as an ended session.
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(
      screen.queryByRole('button', { name: messages['en-US']['session.restart'] }),
    ).toBeNull();
    expect(screen.queryByText(messages['en-US']['session.expired'])).toBeNull();
  });

  it('does not call a FIRST VISIT an ended session', async () => {
    // The gate's e2e failed on this for days and it read as infrastructure.
    //
    // On a first visit there is no cookie, so AppShell's reissue 401s by
    // design while SplashScreen's POST /demo/sessions mints the session a
    // moment later. Measured inside the gate's own container:
    //
    //   50ms  401 /api/v1/session/csrf
    //   55ms  201 /api/v1/demo/sessions
    //   final h1 id = session-heading        ← wrong, and it never cleared
    //
    // Every route rendered "세션이 만료됐어요", which is why shell.spec looked
    // for `not-found-heading` and reported "element(s) not found" (#240).
    //
    // The distinction is the token, not the status: a 401 followed by a token
    // means the bootstrap won the race and this tab is usable; a 401 with no
    // token means the session really is gone, which the cases above pin.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
    );
    renderAt('/');
    await waitFor(() => {
      expect(paths).toContain('/api/v1/session/csrf');
    });
    // The splash bootstrap supplies a token even though the reissue failed.
    await waitFor(() => {
      expect(currentCsrfToken()).not.toBeNull();
    });
    // Let any re-render settle, so this is not observing the frame before the
    // session screen would have appeared.
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(screen.queryByText(messages['en-US']['session.expired'])).toBeNull();
  });

  it('leaves a working session alone', async () => {
    renderAt('/feed');
    // The ordinary case still renders the feed, not the session screen.
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull();
    });
    expect(
      screen.queryByRole('button', { name: messages['en-US']['session.restart'] }),
    ).toBeNull();
  });
});

describe('BA-010 a deep link with no cookie starts a session; an ended one does not', () => {
  // The server now says WHICH of the two a 401 is (#240). Before it did, the
  // client could not tell them apart — the cookie is httpOnly — so a deep link
  // onto /feed showed the session-ended screen even on a first visit, and 13
  // e2e specs failed on exactly that.
  //
  // The two directions are asserted separately because widening this condition
  // is how trips get lost: bootstrapping on an EXPIRED session mints a
  // different anonymous owner (SessionSafetyIT.expiration) and strands every
  // trip the traveller had. A test that only proved the happy half would let
  // that through.

  const noCookie = () =>
    http.post(`${API_BASE}/session/csrf`, () =>
      problemResponse('UNAUTHORIZED', {}, { missingCredential: 'SESSION_COOKIE' }),
    );

  it('bootstraps when the request carried no session cookie at all', async () => {
    server.use(noCookie());
    renderAt('/feed');

    await waitFor(() => {
      expect(paths).toContain('/api/v1/demo/sessions');
    });
  });

  it('does not bootstrap when a cookie was sent and failed', async () => {
    // No `missingCredential` — the contract sets it ONLY when nothing was sent,
    // so its absence covers expired, revoked, forged and malformed alike. None
    // of those may mint a new owner.
    server.use(
      http.post(`${API_BASE}/session/csrf`, () => problemResponse('UNAUTHORIZED')),
    );
    renderAt('/feed');

    await screen.findByText(messages['en-US']['session.expired']);
    expect(paths).not.toContain('/api/v1/demo/sessions');
  });

  it('shows the ordinary screen rather than the ended one on a first visit', async () => {
    // The user-visible half: what made this look broken was the wrong SCREEN,
    // not the missing request.
    server.use(noCookie());
    renderAt('/feed');

    await waitFor(() => {
      expect(paths).toContain('/api/v1/demo/sessions');
    });
    expect(screen.queryByText(messages['en-US']['session.expired'])).toBeNull();
  });

  it('starts at most one session per page load', async () => {
    // The contract's own words: "a client should start at most one new session
    // per page load". AppShell and SplashScreen share one queryKey, so
    // react-query dedupes them — this pins that rather than trusting it.
    server.use(noCookie());
    renderAt('/feed');

    await waitFor(() => {
      expect(paths).toContain('/api/v1/demo/sessions');
    });
    expect(paths.filter((p) => p === '/api/v1/demo/sessions')).toHaveLength(1);
  });
});
