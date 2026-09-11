// @vitest-environment happy-dom
//
// FE-102 screen behaviour (FR-TRC-01, FR-TRC-02, FR-TRC-03).
//
// The rules themselves are tested in wizard.test.ts without rendering. What is
// asserted here is what only the screen can get wrong: that the draft survives
// moving between steps, that a submit cannot be fired twice, and that the
// request carries an Idempotency-Key.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let created: { key: string | null; body: unknown }[] = [];

beforeEach(() => {
  created = [];
  server.use(
    http.post(`${API_BASE}/trips`, async ({ request }) => {
      created.push({
        key: request.headers.get('idempotency-key'),
        body: await request.json(),
      });
      return HttpResponse.json(
        { id: '018f4c00-0000-7000-8000-000000000001' },
        { status: 201 },
      );
    }),
  );
});
afterEach(() => {
  server.events.removeAllListeners();
});

function renderWizard() {
  const router = createMemoryRouter(routes, { initialEntries: ['/start'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Picks the first two selectable days in the visible month. */
async function pickDates(user: ReturnType<typeof userEvent.setup>) {
  const days = await screen.findAllByRole('button', { pressed: false });
  const numbered = days.filter((d) => /^\d+$/.test(d.textContent ?? ''));
  await user.click(numbered[0] as HTMLElement);
  await user.click(numbered[3] as HTMLElement);
}

describe('step 1 will not let an invalid range continue', () => {
  it('keeps the CTA disabled until a range is complete', async () => {
    renderWizard();
    expect(
      await screen.findByRole('button', { name: copy['wizard.dates.pick'] }),
    ).toBeDisabled();
  });

  it('enables it once both ends are picked', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: copy['wizard.dates.pick'] }),
      ).toBeNull();
    });
  });
});

describe('the draft survives moving through the steps', () => {
  it('carries dates and interests into the create request', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);

    // Step 2: pick one interest, then continue.
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(
      await screen.findByRole('button', { name: copy['interest.FRIENDS'] }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    // Step 3: choose a planning level and submit.
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    const body = created[0]?.body as Record<string, unknown>;
    expect(body.planningLevel).toBe('NOTHING');
    expect(body.interests).toEqual([{ code: 'FRIENDS', weight: 3 }]);
    expect(body.startDate).toBeTruthy();
    expect(body.endDate).toBeTruthy();
  });

  it('accepts no interests at all, which the contract allows', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(
      await screen.findByRole('button', { name: copy['wizard.interests.later'] }),
    );
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    expect((created[0]?.body as Record<string, unknown>).interests).toEqual([]);
  });
});

describe('creating the trip', () => {
  it('sends an Idempotency-Key, so a repeat cannot make a second trip', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    // Invariant 6: retryable commands carry a key. A missing one is how a
    // double submit becomes two trips.
    expect(created[0]?.key).toMatch(/^[0-9a-f-]{36}$/i);
  });

  it('replays the same key when the user retries a failed submit', async () => {
    // The previous test submits once, so it cannot see the failure that
    // matters: a key minted per attempt is still a valid UUID. What makes the
    // key worth sending is that a RETRY carries the one before it.
    //
    // The scenario is the ordinary one. The request reaches the server and
    // commits, the response is lost, the screen says 만들지 못했어요 and
    // re-enables the CTA. The user presses again. With a fresh key the server
    // has no way to know it is the same command and creates a second trip;
    // with the same key it replays the first.
    let attempt = 0;
    server.use(
      http.post(`${API_BASE}/trips`, async ({ request }) => {
        created.push({
          key: request.headers.get('idempotency-key'),
          body: await request.json(),
        });
        attempt += 1;
        if (attempt === 1) return HttpResponse.error();
        return HttpResponse.json(
          { id: '018f4c00-0000-7000-8000-000000000001' },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await screen.findByText(copy['wizard.createFailed']);
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(2);
    });
    expect(created[0]?.key).toBe(created[1]?.key);
  });

  it('mints a new key once a draft change makes it a different trip', async () => {
    // The other direction, so the fix cannot be "hold one key forever". A
    // retry of the same request replays; a changed request is a new command
    // and must not be replayed against the old one.
    server.use(
      http.post(`${API_BASE}/trips`, async ({ request }) => {
        created.push({
          key: request.headers.get('idempotency-key'),
          body: await request.json(),
        });
        return HttpResponse.error();
      }),
    );
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await screen.findByText(copy['wizard.createFailed']);

    // Change the draft, then submit again. Step 3 still shows the planning
    // options after a failure, so picking a different one is a real change to
    // the request the user is about to send.
    await user.click(
      screen.getByRole('button', {
        name: new RegExp(copy['wizard.planning.MOSTLY_PLANNED.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(2);
    });
    expect(created[0]?.key).not.toBe(created[1]?.key);
  });

  it('blocks a second submit while the first is in flight', async () => {
    server.use(
      http.post(`${API_BASE}/trips`, async ({ request }) => {
        created.push({
          key: request.headers.get('idempotency-key'),
          body: await request.json(),
        });
        await delay(80);
        return HttpResponse.json(
          { id: '018f4c00-0000-7000-8000-000000000001' },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );

    const submit = screen.getByRole('button', { name: copy['wizard.next'] });
    await user.click(submit);
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: copy['wizard.creating'] }),
      ).toBeDisabled();
    });
    expect(created).toHaveLength(1);
  });

  it('reports a failure instead of leaving the button spinning', async () => {
    server.use(http.post(`${API_BASE}/trips`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['wizard.createFailed'],
    );
  });
});
