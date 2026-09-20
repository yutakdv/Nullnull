// @vitest-environment happy-dom
//
// FE sign-in screen (#265). Figma A-4 `804:4537`.
//
// The promise this file guards is narrow and easy to break by accident: the
// screen renders a real credential form and sends NOTHING. There is no auth
// operation in docs/api/openapi.yaml (#264 asks BE for one), so any request
// leaving this screen would be aimed at a guessed path — it would 404 and read
// to the traveller as "my password is wrong".
//
// The first test is the one that matters. The rest describe the form so that
// whoever wires `useSignIn` has to face a red assertion rather than a silent
// change of meaning.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let requests: { method: string; url: string }[] = [];

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderSignIn() {
  const router = createMemoryRouter(routes, { initialEntries: ['/sign-in'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('the sign-in screen validates the local demo credentials without sending them', () => {
  it('sends no request when the form is submitted', async () => {
    // The whole point of the screen existing before its contract. If this goes
    // red, either an endpoint was guessed or a real one landed — and in the
    // second case this test should be replaced, not relaxed.
    const user = userEvent.setup();
    renderSignIn();

    await user.click(screen.getByRole('button', { name: copy['signIn.submit'] }));

    // The press is done when the feed has rendered. Waiting on a screen the
    // navigation leaves behind would race it.
    await screen.findByRole('heading', { name: copy['feed.title'] });

    // Two filters, because either alone can be satisfied by the wrong thing.
    //
    // By name: an endpoint guessed from the route would be called /sign-in,
    // /login or /auth.
    const auth = requests.filter((r) => /login|auth|sign-?in|account/i.test(r.url));
    expect(auth).toEqual([]);

    // By method: a POST anywhere carries a body, and the body here would be
    // the traveller's password. The shell's own session bootstrap is the one
    // exception — AppShell posts /session/csrf on every screen, before this
    // one renders, and it is not this form submitting.
    //
    // Written after measuring: the first version of this assertion was
    // `method !== 'GET'` with no exemption and it went red on that bootstrap,
    // which would have read as "the form posted" to anyone who trusted it.
    const writes = requests.filter(
      (r) => r.method !== 'GET' && !r.url.includes('/session/csrf'),
    );
    expect(writes).toEqual([]);
  });

  it('moves to the feed when the form is submitted', async () => {
    // Where the old screen showed a notice and stopped, this one carries on.
    // The traveller is not verified — there is nothing to verify against — but
    // they are not stuck either.
    const user = userEvent.setup();
    renderSignIn();

    await user.click(screen.getByRole('button', { name: copy['signIn.submit'] }));

    expect(
      await screen.findByRole('heading', { name: copy['feed.title'] }),
    ).toBeInTheDocument();
  });

  it('rejects credentials that are not an exact match', async () => {
    const user = userEvent.setup();
    renderSignIn();

    const account = await screen.findByLabelText(copy['signIn.id.label']);
    await user.type(account, ' ');
    await user.click(screen.getByRole('button', { name: copy['signIn.submit'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The ID or password is incorrect',
    );
    expect(
      screen.getByRole('heading', { level: 1, name: copy['signIn.title'] }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: copy['feed.title'] })).toBeNull();
  });

  it('prefills the demo credentials and enables submit', async () => {
    renderSignIn();

    expect(await screen.findByLabelText(copy['signIn.id.label'])).toHaveValue('openapi');
    expect(screen.getByLabelText(copy['signIn.password.label'])).toHaveValue(
      '2026openapi!',
    );
    const submit = await screen.findByRole('button', { name: copy['signIn.submit'] });
    expect(submit).toBeEnabled();
  });

  it('names both fields and masks the password', async () => {
    renderSignIn();

    // findByLabelText is the assertion: a field with no label is unreachable
    // by a screen reader, and a placeholder is not a label.
    const account = await screen.findByLabelText(copy['signIn.id.label']);
    const password = screen.getByLabelText(copy['signIn.password.label']);

    expect(account).toHaveAttribute('type', 'text');
    expect(password).toHaveAttribute('type', 'password');
    // Credentials must not be offered back by the browser's generic autofill.
    expect(account).toHaveAttribute('autocomplete', 'username');
    expect(password).toHaveAttribute('autocomplete', 'current-password');
  });

  it('moves to the feed when continuing without an account', async () => {
    // AGENTS.md rule 14: the anonymous path stays whole. A sign-in screen with
    // no way out would turn an addition into a gate.
    const user = userEvent.setup();
    renderSignIn();
    await user.click(
      await screen.findByRole('button', { name: copy['signIn.anonymous'] }),
    );

    expect(
      await screen.findByRole('heading', { name: copy['feed.title'] }),
    ).toBeInTheDocument();
  });

  it('renders in English without leaking Korean copy', async () => {
    // The screen is reached from the profile, which is where a traveller
    // switches locale, so an untranslated string here is reachable in one tap.
    renderSignIn();
    await screen.findByRole('heading', { name: copy['signIn.title'] });

    const body = document.body.textContent ?? '';
    expect(
      body.length,
      'nothing rendered, so the check below is vacuous',
    ).toBeGreaterThan(20);
    expect(body).not.toMatch(/[가-힣]/);
    expect(body).toContain('Cross-device trip access is still coming soon.');
    expect(body).not.toContain('saved to your account');
  });
});
