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
// FE-101-T2: default/loading/empty/error/offline/stale states each render.
// FE-101-T3: keyboard reach, focus, accessible names, reduced motion.
//
// Requests are counted rather than asserted from the policy table: a table that
// says "no request" while the screen fires one would pass an inspection test
// and fail this one.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let requests: { method: string; url: string }[] = [];

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
  localStorage.clear();
});

afterEach(() => {
  server.events.removeAllListeners();
  localStorage.clear();
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

describe('A-1 splash bootstraps the anonymous session', () => {
  it('moves to the language screen once bootstrap succeeds', async () => {
    renderAt('/');
    expect(
      await screen.findByRole('heading', { name: /Choose your language/ }),
    ).toBeInTheDocument();
  });

  it('never sends an owner id: the server derives it from the session', async () => {
    renderAt('/');
    await screen.findByRole('heading', { name: /Choose your language/ });
    const bootstrap = requests.find((r) => r.url.includes('/demo/sessions'));
    expect(bootstrap).toBeDefined();
    expect(bootstrap?.url).not.toMatch(/owner|ownerId|userId/i);
  });

  it('offers a retry instead of a blank screen when bootstrap fails', async () => {
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

  it('does not retry a failed bootstrap on its own', async () => {
    server.use(http.post(`${API_BASE}/demo/sessions`, () => HttpResponse.error()));
    renderAt('/');
    await screen.findByRole('alert');
    const attempts = requests.filter((r) => r.url.includes('/demo/sessions')).length;
    expect(attempts).toBe(1);
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
});

describe('A-2 language selects Korean and English for real', () => {
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

  it('names each language in its own language for a screen reader', async () => {
    renderAt('/language');
    expect(await screen.findByRole('button', { name: /日本語/ })).toHaveAttribute(
      'lang',
      'ja',
    );
    expect(screen.getByRole('button', { name: /中文/ })).toHaveAttribute('lang', 'zh');
  });

  it('reaches every enabled control by keyboard', async () => {
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

describe('A-3 intro completes onboarding', () => {
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
});
