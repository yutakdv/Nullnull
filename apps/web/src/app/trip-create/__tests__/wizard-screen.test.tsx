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
import { sessionFixtures } from '@nullnull/contracts';
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
/** Preference patches the wizard sent, so the active-trip write is observable. */
let patched: Record<string, unknown>[] = [];

beforeEach(() => {
  created = [];
  patched = [];
  server.use(
    http.patch(`${API_BASE}/me`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      patched.push(body);
      return HttpResponse.json({ ...sessionFixtures.owner, ...body });
    }),
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

describe('the paste path is reachable from the wizard', () => {
  // FE-104. The route existing is not the same as the route being reachable:
  // /start/must-visit sat in routes.tsx with nothing linking to it until #185
  // moved must-visit into the wizard as step 4 and deleted the route. This
  // asserts the step 3 secondary actually lands on the paste screen, which is
  // still the only way in.
  it('reaches the paste screen from step 3', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await screen.findByText(`${copy['wizard.step']} 3`);

    await user.click(screen.getByRole('button', { name: copy['import.start'] }));

    expect(
      await screen.findByRole('heading', { name: copy['import.title'] }),
    ).toBeInTheDocument();
  });
});

describe('FE-102-T3 the user can go back a step without losing the draft', () => {
  // Reproduced in a browser before this existed: pick 9/15-9/18, press the
  // CTA, and step 2 offers only 다음 and 나중에 고를래요. There is no back
  // control and no tab bar, and the steps are component state rather than
  // routes, so browser Back leaves /start altogether — it landed on the
  // previously visited page and the dates were gone. A mistyped date range
  // could only be fixed by redoing the whole wizard.
  it('offers a way back from step 2', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await screen.findByText(`${copy['wizard.step']} 2`);
    expect(screen.getByRole('button', { name: copy['wizard.back'] })).toBeInTheDocument();
  });

  it('returns to step 1 with the dates still chosen', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    const range = screen.getByRole('button', { name: /–/ }).textContent;
    await user.click(screen.getByRole('button', { name: /–/ }));
    await screen.findByText(`${copy['wizard.step']} 2`);
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));

    // Back to step 1, and the CTA still names the range the user picked —
    // FIGMA_HANDOFF's rule is that moving back preserves what was entered.
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /–/ })).toHaveTextContent(range ?? '');
  });

  it('keeps the interests when stepping back from step 3', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    const interest = await screen.findByRole('button', {
      name: copy['interest.ALONE'],
    });
    await user.click(interest);
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await screen.findByText(`${copy['wizard.step']} 3`);
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));

    expect(
      await screen.findByRole('button', { name: copy['interest.ALONE'] }),
    ).toHaveAttribute('aria-pressed', 'true');
  });

  it('leaves the flow from step 1, where there is no previous step', async () => {
    const user = userEvent.setup();
    renderWizard();
    await screen.findByText(`${copy['wizard.step']} 1`);
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'feed-heading',
      );
    });
  });
});

describe('FE-102-T2 step 1 will not let an invalid range continue', () => {
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

describe('FE-102-T1 creating the trip', () => {
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

    // Change the draft, then submit again.
    //
    // An INTEREST rather than the planning level, which is what this used to
    // change: only NOTHING creates the trip from step 3 now, because
    // MUST_VISIT_ONLY and MOSTLY_PLANNED each continue to a step 4 of their
    // own (`438:3158` / `400:1201`). Picking another level would leave this
    // measuring navigation instead of the key. Interests ride in the request
    // body, so toggling one is a real change to what is about to be sent while
    // keeping the answer that submits from here.
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));
    const interest = await screen.findByRole('button', {
      name: copy['interest.FOOD'],
    });
    await user.click(interest);
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created.length).toBeGreaterThanOrEqual(2);
    });
    // The key changed with the draft. Compared as first-vs-last rather than by
    // an exact count: what this measures is that a CHANGED request is not
    // replayed under the old key, and pinning the number of attempts would
    // make the test fail whenever the route to step 3 gains or loses a press.
    expect(created[0]?.key).not.toBe(created[created.length - 1]?.key);
    // And the two really are different requests, so the keys differing is not
    // the trivial case of one draft being sent twice under fresh keys.
    expect(created[0]?.body).not.toEqual(created[created.length - 1]?.body);
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

// The calendar's weekday headers follow the active locale.
//
// They were the literal ['일','월','화','수','목','금','토'], so an English user
// read a Korean calendar — the only part of the wizard that never went through
// i18n, and the kind of thing no existing check looked at because
// no-hardcoded-copy.test.ts scans shared/ui/components only and this screen is
// not in that directory.
describe('the calendar names its weekdays in the reader locale', () => {
  it('shows English weekday headers to an English reader', async () => {
    renderWizard();
    await screen.findByRole('heading', { level: 1 });

    // Intl is the source, so this asserts the same way the screen derives them
    // rather than hardcoding a second copy that could drift.
    const expected = new Intl.DateTimeFormat('en-US', { weekday: 'short' }).format(
      new Date(Date.UTC(2021, 0, 3)),
    );
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0);
    // And the Korean literal is gone rather than merely joined.
    expect(screen.queryByText('일')).toBeNull();
    expect(screen.queryByText('월')).toBeNull();
  });
});

// S02-4C `400:1201` (FR-TRC-05). The screen existed in Figma and nowhere in
// the code: answering 거의 다 세우고 왔어요 created a trip with empty days
// immediately, which contradicted the answer the traveller had just given.
describe('FE-103 the input-method branch is reachable and keeps the draft', () => {
  async function reachMethod(user: ReturnType<typeof userEvent.setup>) {
    renderWizard();
    await pickDates(user);
    // Step 1's CTA is the range itself, not 다음.
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['interest.FOOD'] }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.MOSTLY_PLANNED.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
  }

  it('offers both ways in rather than creating the trip', async () => {
    const user = userEvent.setup();
    await reachMethod(user);

    expect(
      await screen.findByRole('button', { name: new RegExp(copy['method.paste']) }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: new RegExp(copy['method.manual']) }),
    ).toBeInTheDocument();
    // The answer was "거의 다 세우고 왔어요". Creating a trip with empty days
    // at this point is the defect this screen exists to stop.
    expect(created).toHaveLength(0);
  });

  it('carries the dates and interests into the paste screen', async () => {
    // The reason this is a STEP and not a route: /start/import starts from
    // EMPTY_DRAFT, so sending the traveller there used to throw away
    // everything steps 1-2 collected. Reaching it from inside the wizard keeps
    // the draft alive behind it.
    const user = userEvent.setup();
    await reachMethod(user);
    await user.click(
      await screen.findByRole('button', { name: new RegExp(copy['method.paste']) }),
    );
    expect(await screen.findByLabelText(copy['import.label'])).toBeInTheDocument();
  });

  it('goes back to the planning question rather than out of the flow', async () => {
    const user = userEvent.setup();
    await reachMethod(user);
    await user.click(await screen.findByRole('button', { name: copy['wizard.back'] }));
    expect(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.MOSTLY_PLANNED.title']),
      }),
    ).toBeInTheDocument();
  });
});

describe('S02-4C-C the manual branch collects an itinerary (FE-103, FR-TRC-05)', () => {
  async function reachManual(user: ReturnType<typeof userEvent.setup>) {
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.MOSTLY_PLANNED.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', { name: new RegExp(copy['method.manual']) }),
    );
  }

  /** Adds the first search result to the given day. */
  async function addPlaceTo(
    user: ReturnType<typeof userEvent.setup>,
    dayButtonIndex: number,
  ) {
    // Queried by the accessible name, not the visible `+ Add a place`: every
    // day's button shows the same words, so each one names its own day for a
    // screen reader (manual.addToDay) and that label is what the role query
    // sees.
    const adds = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.addToDay'].replace('{day}', '.+')),
    });
    await user.click(adds[dayButtonIndex] as HTMLElement);
    await user.type(await screen.findByLabelText(copy['manual.searchLabel']), '서울');
    const pick = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.pick']),
    });
    await user.click(pick[0] as HTMLElement);
  }

  it('renders a step rather than the blank screen 직접 입력 used to reach', async () => {
    // The defect this closes: InputMethodStep's 직접 입력 called setStep(5)
    // and nothing rendered at step 5, so choosing it showed the wizard shell
    // with no content and no way on — a reachable dead end (#185).
    const user = userEvent.setup();
    await reachManual(user);

    expect(
      await screen.findByRole('heading', { name: copy['manual.title'] }),
    ).toBeInTheDocument();
    expect(created).toHaveLength(0);
  });

  it('offers one day header per day of the chosen range', async () => {
    // The days come from the trip's own range, so this screen cannot offer a
    // day the trip does not have. pickDates picks a 4-day span.
    const user = userEvent.setup();
    await reachManual(user);
    await screen.findByRole('heading', { name: copy['manual.title'] });

    const days = screen.getAllByRole('heading', { level: 2 });
    expect(days).toHaveLength(4);
  });

  it('sends the entered stops as seedItems, with no invented time', async () => {
    // The whole point of the screen, and the one assertion that guards the
    // decision behind it: the card offers only 오전/오후, so no clock time was
    // ever chosen and none may be sent (see wizard.ts seedItemsOf).
    //
    // The submit happens one step later than it used to. This step's CTA now
    // leads to the confirm step (S02-5C), which is where the must-visit picks
    // are made, so the request is sent from there.
    const user = userEvent.setup();
    await reachManual(user);
    await screen.findByRole('heading', { name: copy['manual.title'] });
    await addPlaceTo(user, 0);

    await user.click(screen.getByRole('button', { name: copy['manual.next'] }));
    await screen.findByRole('heading', { name: copy['confirm.title'] });
    await user.click(screen.getByRole('button', { name: copy['confirm.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    const body = created[0]?.body as { seedItems?: { startTime: unknown }[] };
    expect(body.seedItems).toHaveLength(1);
    expect(body.seedItems?.[0]?.startTime).toBeNull();
  });

  it('creates the trip directly when nothing was entered to confirm', async () => {
    // The confirm step reads the itinerary back and asks which places must
    // stay. With no stops there is nothing to read back and nothing to pick,
    // so showing it would be an empty page with two buttons.
    const user = userEvent.setup();
    await reachManual(user);
    await screen.findByRole('heading', { name: copy['manual.title'] });

    await user.click(screen.getByRole('button', { name: copy['manual.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    expect((created[0]?.body as { seedItems?: unknown }).seedItems).toBeUndefined();
  });

  it('does not carry the stops when the traveller says there are none', async () => {
    // 건너뛰기 is an answer, not a cancel. This failed before submit() took the
    // draft as an argument: setDraft is queued, so the cleared draft had not
    // been applied yet and submit read the stops it was meant to drop —
    // sending exactly what the user had just said to leave out.
    const user = userEvent.setup();
    await reachManual(user);
    await screen.findByRole('heading', { name: copy['manual.title'] });
    await addPlaceTo(user, 0);

    await user.click(screen.getByRole('button', { name: copy['manual.skip'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    expect((created[0]?.body as { seedItems?: unknown }).seedItems).toBeUndefined();
  });

  it('goes back to the method choice rather than out of the flow', async () => {
    const user = userEvent.setup();
    await reachManual(user);
    await screen.findByRole('heading', { name: copy['manual.title'] });

    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));

    expect(
      await screen.findByRole('button', { name: new RegExp(copy['method.manual']) }),
    ).toBeInTheDocument();
  });
});

describe('S02-5C the confirm step picks what must stay (FE-103, FR-TRC-05)', () => {
  /** Through the wizard to the manual step, with one stop entered on day 1. */
  async function reachConfirm(user: ReturnType<typeof userEvent.setup>) {
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.MOSTLY_PLANNED.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', { name: new RegExp(copy['method.manual']) }),
    );
    await screen.findByRole('heading', { name: copy['manual.title'] });

    const adds = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.addToDay'].replace('{day}', '.+')),
    });
    await user.click(adds[0] as HTMLElement);
    await user.type(await screen.findByLabelText(copy['manual.searchLabel']), '서울');
    const picks = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.pick']),
    });
    await user.click(picks[0] as HTMLElement);

    await user.click(screen.getByRole('button', { name: copy['manual.next'] }));
    await screen.findByRole('heading', { name: copy['confirm.title'] });
  }

  /** The Pick toggle of the first (only) stop. */
  function pickToggle() {
    return screen.getAllByRole('button', {
      name: new RegExp(copy['confirm.pickNamed'].replace('{place}', '.+')),
    })[0] as HTMLElement;
  }

  it('confirms before creating rather than submitting from the entry step', async () => {
    // 이 일정으로 시작하기 on the manual step leads here, not to the server:
    // this is where the must-visit picks are made, so submitting earlier would
    // skip the question the screen exists to ask.
    const user = userEvent.setup();
    await reachConfirm(user);
    expect(created).toHaveLength(0);
  });

  it('offers a pick toggle per stop, off until the traveller presses it', async () => {
    // The toggle FIGMA_HANDOFF:154 left out of its description. It reports its
    // own state, so the pin glyph is not the only thing carrying it.
    const user = userEvent.setup();
    await reachConfirm(user);
    expect(pickToggle()).toHaveAttribute('aria-pressed', 'false');
  });

  it('sends MUST_VISIT for a picked stop', async () => {
    const user = userEvent.setup();
    await reachConfirm(user);
    await user.click(pickToggle());
    expect(pickToggle()).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: copy['confirm.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    const body = created[0]?.body as {
      seedItems?: { constraints?: { type: string; locked: boolean }[] }[];
    };
    expect(body.seedItems?.[0]?.constraints).toEqual([
      { type: 'MUST_VISIT', locked: true },
    ]);
  });

  it('sends no constraint for a stop left unpicked', async () => {
    // Absence, not `locked: false`: the four locks are independent and never
    // auto-released (invariant 7), so not-locked is said by saying nothing.
    const user = userEvent.setup();
    await reachConfirm(user);

    await user.click(screen.getByRole('button', { name: copy['confirm.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    const body = created[0]?.body as { seedItems?: { constraints?: unknown }[] };
    expect(body.seedItems?.[0]?.constraints).toBeUndefined();
  });

  it('renders no crowd figure, because nothing here can source one', async () => {
    // The frame draws `4 · 혼잡`, a CrowdBar and the ⓒ한국관광공사 source line
    // on every card. PlaceSummary carries no crowd field, so a number here
    // would be one nobody measured (invariant 8) — and crediting KTO for a
    // figure not shown would imply a source that was not granted
    // (CMP-ATT-003). #105 / FCR-029 tracks it.
    const user = userEvent.setup();
    await reachConfirm(user);

    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/혼잡/);
    expect(body).not.toMatch(/한국관광공사/);
  });

  it('keeps the picks when going back to fix the itinerary', async () => {
    // 다시 고칠래요 is not a cancel. Every other step of this wizard preserves
    // what was entered when moving back, and this one drops nothing either.
    const user = userEvent.setup();
    await reachConfirm(user);
    await user.click(pickToggle());

    await user.click(screen.getByRole('button', { name: copy['confirm.edit'] }));
    await screen.findByRole('heading', { name: copy['manual.title'] });
    await user.click(screen.getByRole('button', { name: copy['manual.next'] }));

    await screen.findByRole('heading', { name: copy['confirm.title'] });
    expect(pickToggle()).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('a created trip becomes the owner active trip (BA-011)', () => {
  // Why the wizard writes this at all: `owners.active_trip_id` is only ever set
  // by PATCH /me — the server never fills it on create, and the trip module
  // only clears it through ON DELETE SET NULL. Without this call a traveller
  // can own four trips and the 내 여행 tab still has nowhere to go, which is
  // exactly how it read: pressing it opened 내 정보 with the trips below the
  // fold and the wrong tab lit up.
  it('points the tab at the trip it just created', async () => {
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    await waitFor(() => {
      expect(patched).toEqual([{ activeTripId: '018f4c00-0000-7000-8000-000000000001' }]);
    });
  });

  it('still opens the trip when the preference write fails', async () => {
    // Best effort: the trip EXISTS. Blocking navigation on a preference write
    // would strand the traveller on the wizard after a successful create, and
    // a rejection only leaves the pointer where it already was — the same
    // state as before this call, which the tab's fallback already handles.
    server.use(http.patch(`${API_BASE}/me`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    // The trip screen is reached all the same.
    await waitFor(() => {
      expect(screen.queryByText(`${copy['wizard.step']} 3`)).toBeNull();
    });
  });
});
