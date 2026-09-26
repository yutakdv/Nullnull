// @vitest-environment happy-dom
//
// happy-dom rather than the suite's jsdom: these screens navigate, and
// react-router builds a Request to do it, which trips the undici/jsdom
// AbortSignal realm mismatch (#67). See vite.config.ts.
//
// FE-101 acceptance (FR-ONB-01, FR-ONB-02, FR-ONB-03, FR-SES-02).
//
// FE-101-T1: bootstrap failure and retry never produce a redirect loop, and
//            JA/ZH send no request.
// FE-101-T2: the splash renders its in-flight, server-error and offline
//            states.
// FE-101-T3: keyboard reach and accessible names. 360px and 200% zoom are the
//            other half, measured on /, /language and /intro by
//            e2e/responsive.spec.ts.
// FE-101-T4: Next saves the locale the screen shows.
// FE-101-T5: reduced motion — the skipped splash hold here, the collapse of
//            motion on /language and /intro in e2e/responsive.spec.ts.
// FE-101-T6: opened from the profile, the language screen returns there.
//
// T2 and T3 used to be the template's six states and six a11y clauses, with no
// testcase carrying either id. T2 is narrowed to the states these screens can
// enter: A-1 is one request that renders no list and no freshness claim, so
// `empty` and `stale` name states nothing here produces, and A-2/A-3 render no
// fetched data at all. T3 lost `focus 복귀` because none of A-1..A-3 opens a dialog or a sheet
// to return from, and `reduced motion` became T5 (the FE-104/FE-203 split).
//
// Requests are counted rather than asserted from the policy table: a table that
// says "no request" while the screen fires one would pass an inspection test
// and fail this one.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let requests: { method: string; url: string }[] = [];

/**
 * Reports a `prefers-reduced-motion` preference to the code under test.
 *
 * The splash holds its frame for MINIMUM_VISIBLE_MS before redirecting, and
 * honours a reduce preference by skipping that hold. Declaring the preference
 * here does double duty: it exercises the branch a real traveller with the OS
 * setting takes, and it keeps every test that merely passes THROUGH the splash
 * from spending most of a second doing it. Left at the happy-dom default of
 * no-preference, this file went from 241ms to 3.4s.
 *
 * The hold itself is asserted in its own describe block below, where the
 * preference is set to no-preference on purpose.
 */
function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduce && query.includes('prefers-reduced-motion: reduce'),
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
    addListener: () => {},
    removeListener: () => {},
  }));
}

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
  localStorage.clear();
  stubReducedMotion(true);
});

afterEach(() => {
  server.events.removeAllListeners();
  localStorage.clear();
  vi.unstubAllGlobals();
});

function renderAt(path: string, client = createQueryClient()) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <QueryClientProvider client={client}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('A-1 splash bootstraps the anonymous session', () => {
  it('moves to the language screen once bootstrap succeeds', async () => {
    renderAt('/');
    expect(
      await screen.findByRole('heading', { name: /Choose your language/ }),
    ).toBeInTheDocument();
  });

  it('sends a returning visitor past the flow they already completed', async () => {
    // FR-ONB-03: "완료 후 재방문 redirect". The shared fixture carries
    // onboardingCompleted false — it is a BE/FE artifact, so the state it does
    // not hold is supplied by an override here rather than by editing it.
    server.use(
      http.post(`${API_BASE}/demo/sessions`, () =>
        HttpResponse.json(
          {
            ...sessionFixtures.bootstrap,
            owner: { ...sessionFixtures.bootstrap.owner, onboardingCompleted: true },
          },
          { status: 201 },
        ),
      ),
    );
    renderAt('/');

    expect(
      await screen.findByRole('heading', { name: copy['feed.title'] }),
    ).toBeInTheDocument();
    // And the language screen is not merely passed through: it is never shown.
    expect(
      screen.queryByRole('heading', { name: /Choose your language/ }),
    ).not.toBeInTheDocument();
    // The flag rides on the bootstrap, so recognising the visitor costs no
    // second request. /me here would delay the redirect behind a round trip.
    expect(requests.filter((r) => r.url.includes('/me'))).toHaveLength(0);
  });

  it('never sends an owner id: the server derives it from the session', async () => {
    renderAt('/');
    await screen.findByRole('heading', { name: /Choose your language/ });
    const bootstrap = requests.find((r) => r.url.includes('/demo/sessions'));
    expect(bootstrap).toBeDefined();
    expect(bootstrap?.url).not.toMatch(/owner|ownerId|userId/i);
  });

  it('FE-101-T2 says it is starting while the bootstrap is in flight', async () => {
    // Reduced motion is on (beforeEach), so there is no hold: the status line
    // here can only come from the request being in flight.
    server.use(
      http.post(`${API_BASE}/demo/sessions`, async () => {
        await delay('infinite');
        return HttpResponse.json(sessionFixtures.bootstrap, { status: 201 });
      }),
    );
    renderAt('/');
    expect(await screen.findByRole('status')).toHaveTextContent(copy['splash.loading']);
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'splash-heading',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('FE-101-T2 offers a retry when the server refuses the bootstrap', async () => {
    // A Problem body, which is a different input from the dropped connection
    // below even though both land in the same branch. Bootstrap is
    // `retry: false`, so the answer shows at once.
    server.use(
      http.post(`${API_BASE}/demo/sessions`, () => problemResponse('INTERNAL_ERROR')),
    );
    renderAt('/');
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['splash.failed']);
    expect(
      screen.getByRole('button', { name: copy['splash.retry'] }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'splash-heading',
    );
  });

  it('FE-101-T2 offers a retry instead of a blank screen when bootstrap fails', async () => {
    // The offline case: HttpResponse.error() is a network failure with no
    // response at all.
    server.use(http.post(`${API_BASE}/demo/sessions`, () => HttpResponse.error()));
    renderAt('/');
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['splash.failed']);
    expect(
      screen.getByRole('button', { name: copy['splash.retry'] }),
    ).toBeInTheDocument();
    // Still on the splash: a failed bootstrap must not bounce the user onward.
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'splash-heading',
    );
  });

  it('does not make a failed bootstrap wait out the hold', async () => {
    // FR-ONB-01 asks for a retry instead of a blank screen. The hold gates the
    // redirect, not the failure: making somebody wait to be told the app did
    // not start would be the opposite of that acceptance criterion. Run with
    // the hold ENABLED so this measures the failure path rather than the
    // preference.
    stubReducedMotion(false);
    server.use(http.post(`${API_BASE}/demo/sessions`, () => HttpResponse.error()));
    const started = Date.now();
    renderAt('/');

    expect(await screen.findByRole('alert')).toHaveTextContent(copy['splash.failed']);
    expect(Date.now() - started).toBeLessThan(400);
    // The status line gives way to the alert rather than stacking beneath it.
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('does not retry a failed bootstrap on its own', async () => {
    server.use(http.post(`${API_BASE}/demo/sessions`, () => HttpResponse.error()));
    renderAt('/');
    await screen.findByRole('alert');
    const attempts = requests.filter((r) => r.url.includes('/demo/sessions')).length;
    expect(attempts).toBe(1);
  });

  it('holds the brand on screen before redirecting', async () => {
    // The point of the hold: without it the wordmark rendered for about a
    // frame against a warm cache and the first thing anyone saw was the
    // language list. Asserted as "still on the splash after the response has
    // landed", which is the user-visible claim; a timer spy would pass on a
    // screen that started the timer and redirected anyway.
    stubReducedMotion(false);
    renderAt('/');

    // Wait for the request to come back, so what holds the screen afterwards
    // is the floor rather than an in-flight fetch.
    await waitFor(() => {
      expect(requests.filter((r) => r.url.includes('/demo/sessions'))).toHaveLength(1);
    });
    expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'splash-heading',
    );
    // And it is not a dead frame: the screen keeps saying it is starting up.
    expect(screen.getByRole('status')).toBeInTheDocument();

    // It does eventually move on. findBy* retries past the floor.
    expect(
      await screen.findByRole('heading', { name: /Choose your language/ }),
    ).toBeInTheDocument();
  });

  it('FE-101-T5 skips the hold when the traveller asked for reduced motion', async () => {
    // styles.css already collapses animation for this preference. A held
    // splash is time spent withholding content, which is the same bargain,
    // so it is skipped rather than shortened.
    //
    // beforeEach sets reduce for every test in this file; stated here too
    // because this test is ABOUT the preference, and a reader should not have
    // to hold the default in their head to see what is being claimed.
    stubReducedMotion(true);
    const started = Date.now();
    renderAt('/');

    await screen.findByRole('heading', { name: /Choose your language/ });
    // Comfortably under the 800ms floor without pinning the exact value: this
    // asserts the floor was not applied, not how fast the machine is.
    expect(Date.now() - started).toBeLessThan(400);
  });

  it('retries only when the user asks, and then proceeds', async () => {
    let failed = false;
    server.use(
      http.post(`${API_BASE}/demo/sessions`, () => {
        if (failed) return undefined;
        failed = true;
        return HttpResponse.error();
      }),
    );
    const user = userEvent.setup();
    renderAt('/');
    await user.click(await screen.findByRole('button', { name: copy['splash.retry'] }));
    expect(
      await screen.findByRole('heading', { name: /Choose your language/ }),
    ).toBeInTheDocument();
  });

  it('FE-101-T3 reaches the retry by keyboard and starts the app with it', async () => {
    // The retry is the splash's only control, and it exists only once the
    // bootstrap has failed. The case above clicks it; without a pointer a
    // failed start is a dead end unless Tab reaches it and Enter works it.
    let failed = false;
    server.use(
      http.post(`${API_BASE}/demo/sessions`, () => {
        if (failed) return undefined;
        failed = true;
        return HttpResponse.error();
      }),
    );
    const user = userEvent.setup();
    renderAt('/');
    const retry = await screen.findByRole('button', { name: copy['splash.retry'] });
    for (let i = 0; i < 10 && document.activeElement !== retry; i += 1) {
      await user.tab();
    }
    expect(retry).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(
      await screen.findByRole('heading', { name: /Choose your language/ }),
    ).toBeInTheDocument();
  });
});

describe('FE-101-T1 A-2 language selection (FCR-001 trace)', () => {
  it('marks the active locale and switches copy when another is chosen', async () => {
    const user = userEvent.setup();
    renderAt('/language');
    const korean = await screen.findByRole('button', { name: /한국어/ });
    await user.click(korean);
    // The description is locale-specific copy, so it proves the switch.
    expect(
      await screen.findByText(messages['ko-KR']['language.description']),
    ).toBeInTheDocument();
  });

  it('restores the chosen locale after a remount', async () => {
    const user = userEvent.setup();
    const first = renderAt('/language');
    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    await screen.findByText(messages['ko-KR']['language.description']);
    first.unmount();

    renderAt('/language');
    expect(
      await screen.findByText(messages['ko-KR']['language.description']),
    ).toBeInTheDocument();
  });

  it('sends only the selected supported locale in each preference patch', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    renderAt('/language');

    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'ko-KR' }]);
    });

    await user.click(screen.getByRole('button', { name: /English/ }));
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'ko-KR' }, { locale: 'en-US' }]);
    });
  });

  it('shows Japanese and Chinese as disabled and sends nothing for them', async () => {
    const user = userEvent.setup();
    renderAt('/language');
    const japanese = await screen.findByRole('button', { name: /日本語/ });
    const chinese = screen.getByRole('button', { name: /中文/ });
    expect(japanese).toBeDisabled();
    expect(chinese).toBeDisabled();

    await user.click(japanese);
    await user.click(chinese);
    expect(requests.filter((r) => r.method === 'PATCH')).toHaveLength(0);
    expect(localStorage.getItem('nullnull.locale')).toBeNull();
  });

  it('FE-101-T3 names each language in its own language for a screen reader', async () => {
    renderAt('/language');
    expect(await screen.findByRole('button', { name: /日本語/ })).toHaveAttribute(
      'lang',
      'ja',
    );
    expect(screen.getByRole('button', { name: /中文/ })).toHaveAttribute('lang', 'zh');
  });

  it('FE-101-T3 reaches every enabled control by keyboard', async () => {
    const user = userEvent.setup();
    renderAt('/language');
    await screen.findByRole('button', { name: /한국어/ });

    await user.tab();
    expect(screen.getByRole('button', { name: /한국어/ })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: /English/ })).toHaveFocus();
    // Disabled options are skipped, landing on the CTA.
    await user.tab();
    expect(screen.getByRole('button', { name: copy['language.next'] })).toHaveFocus();
  });

  // WEB-RT-1: the UI starts in the browser's language, and the owner record in
  // ko-KR. Place names are projected in the OWNER locale while search follows
  // the UI, so a traveller who read the screen in English and pressed Next
  // without touching an option saw English search results beside Korean trip
  // and place names. Next saves what the screen is showing.
  it('FE-101-T4 saves the locale on screen when continuing without choosing', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    renderAt('/language');
    // The screen is in the browser's language before anything is chosen.
    await screen.findByText(copy['language.description']);
    expect(localStorage.getItem('nullnull.locale')).toBeNull();

    await user.click(screen.getByRole('button', { name: copy['language.next'] }));
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'en-US' }]);
    });
  });

  it('FE-101-T4 does not save the same choice twice on Next', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    const client = createQueryClient();
    renderAt('/language', client);
    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    // The tap's save has landed before Next is pressed.
    await waitFor(() => {
      expect(client.isMutating()).toBe(0);
    });
    await user.click(
      await screen.findByRole('button', { name: messages['ko-KR']['language.next'] }),
    );
    await screen.findByRole('heading', { level: 1, name: /./ });
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'ko-KR' }]);
    });
  });

  // Codex on #399: "saved" was written before the PATCH answered, so a tap
  // whose save FAILED still counted as saved and Next sent nothing - WEB-RT-1
  // stayed open in exactly that case.
  // #399 review, third pass: Next used to trust a tap whose save was still in
  // flight. If that save then failed after the screen had moved on, nothing
  // sent the locale again. PATCH /me has no If-Match and is idempotent, so a
  // second identical request is harmless while a skipped one is permanent.
  it('FE-101-T4 saves on Next even while the tap is still saving, and wins if the tap fails', async () => {
    const bodies: unknown[] = [];
    let failTap: (() => void) | undefined;
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        bodies.push(await request.json());
        if (bodies.length === 1) {
          await new Promise<void>((resolve) => {
            failTap = resolve;
          });
          return HttpResponse.json(
            { type: 'about:blank', title: 'Unavailable', status: 503 },
            { status: 503 },
          );
        }
        return HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    const client = createQueryClient();
    renderAt('/language', client);
    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    await waitFor(() => {
      expect(bodies).toHaveLength(1);
    });
    // The tap's save is still waiting for the server when Next is pressed.
    await user.click(
      await screen.findByRole('button', { name: messages['ko-KR']['language.next'] }),
    );
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'ko-KR' }, { locale: 'ko-KR' }]);
    });
    // The tap fails after the screen has moved on; Next's save already went.
    failTap?.();
    await waitFor(() => {
      expect(client.isMutating()).toBe(0);
    });
    expect(bodies).toEqual([{ locale: 'ko-KR' }, { locale: 'ko-KR' }]);
  });

  it('FE-101-T4 sends the locale again on Next when the tap failed to save it', async () => {
    const bodies: unknown[] = [];
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        bodies.push(await request.json());
        return bodies.length === 1
          ? HttpResponse.json(
              { type: 'about:blank', title: 'Unavailable', status: 503 },
              { status: 503 },
            )
          : HttpResponse.json(sessionFixtures.owner);
      }),
    );
    const user = userEvent.setup();
    const client = createQueryClient();
    renderAt('/language', client);
    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    await waitFor(() => {
      expect(client.isMutating()).toBe(0);
    });
    await user.click(
      await screen.findByRole('button', { name: messages['ko-KR']['language.next'] }),
    );
    await waitFor(() => {
      expect(bodies).toEqual([{ locale: 'ko-KR' }, { locale: 'ko-KR' }]);
    });
  });

  it('continues to the intro screen', async () => {
    const user = userEvent.setup();
    renderAt('/language');
    await user.click(await screen.findByRole('button', { name: copy['language.next'] }));
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute(
      'id',
      'intro-heading',
    );
  });
});

// FE-101-T6. The profile's language row (FE-105-T6) opens this screen with
// `?from=profile`. Next used to go to /intro unconditionally, which walked a
// traveller who only wanted to switch KO/EN back through the intro and the
// sign-in screen. The value is compared, never followed: only `profile`
// changes where Next goes, so the query string cannot send anyone to a path
// of its choosing.
describe('FE-101-T6 the language screen returns to the profile it was opened from', () => {
  it('FE-101-T6 goes back to the profile, in the language just chosen', async () => {
    const user = userEvent.setup();
    renderAt('/language?from=profile');
    await user.click(await screen.findByRole('button', { name: /한국어/ }));
    await user.click(
      await screen.findByRole('button', { name: messages['ko-KR']['language.next'] }),
    );
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'profile-heading',
      );
    });
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      messages['ko-KR']['profile.title'],
    );
  });

  it('FE-101-T6 continues onboarding for any other return target', async () => {
    const user = userEvent.setup();
    renderAt('/language?from=%2Ffeed');
    await user.click(await screen.findByRole('button', { name: copy['language.next'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'intro-heading',
      );
    });
  });
});

describe('A-3 intro completes onboarding', () => {
  it('renders the supplied Nulli artwork before the onboarding copy', async () => {
    const { container } = renderAt('/intro');
    expect(container.querySelector('img[src="/figma/nulli.png"]')).toBeInTheDocument();
  });

  it('records completion with only the contracted field', async () => {
    const bodies: unknown[] = [];
    const contentTypes: (string | null)[] = [];
    server.use(
      http.patch(`${API_BASE}/me`, async ({ request }) => {
        contentTypes.push(request.headers.get('content-type'));
        bodies.push(await request.json());
        return HttpResponse.json({ ok: true });
      }),
    );
    const user = userEvent.setup();
    renderAt('/intro');
    await user.click(await screen.findByRole('button', { name: copy['intro.start'] }));

    await waitFor(() => {
      expect(bodies).toHaveLength(1);
    });
    // UpdatePreferencesRequest is additionalProperties:false and BA-003 turns
    // an unknown field into 400, so the body must carry nothing extra.
    expect(bodies[0]).toEqual({ onboardingCompleted: true });
    // The contract declares application/merge-patch+json and BA-011's
    // controller enforces it with `consumes`. openapi-fetch defaults to
    // application/json, which the server answers with 415, so this is asserted
    // rather than assumed.
    expect(contentTypes[0]).toBe('application/merge-patch+json');
  });

  it('states that no sign-in is needed', async () => {
    renderAt('/intro');
    expect(await screen.findByText(copy['intro.noLogin'])).toBeInTheDocument();
  });

  it('opens sign-in before entering the feed', async () => {
    const user = userEvent.setup();
    renderAt('/intro');
    await user.click(await screen.findByRole('button', { name: copy['intro.start'] }));

    expect(
      await screen.findByRole('heading', { name: copy['signIn.title'] }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: copy['feed.title'] }),
    ).not.toBeInTheDocument();
  });
});
