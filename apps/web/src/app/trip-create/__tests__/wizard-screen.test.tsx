// @vitest-environment happy-dom
//
// FE-102 screen behaviour (FR-TRC-01, FR-TRC-02, FR-TRC-03).
//
// The rules themselves are tested in wizard.test.ts without rendering. What is
// asserted here is what only the screen can get wrong: that the draft survives
// moving between steps, that a submit cannot be fired twice, and that the
// request carries an Idempotency-Key.
import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import {
  crowdFixtures,
  placeFixtures,
  problemFixtures,
  sessionFixtures,
  tripDraftFixtures,
} from '@nullnull/contracts';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import {
  NEXT_CURSOR,
  searchPages,
  servePlaceSearchPages,
} from '../../../shared/testing/msw/place-search-pages.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

let created: { key: string | null; body: unknown }[] = [];
let previews: unknown[] = [];
/** Preference patches the wizard sent, so the active-trip write is observable. */
let patched: Record<string, unknown>[] = [];

beforeEach(() => {
  created = [];
  previews = [];
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
    http.post(`${API_BASE}/trip-drafts/preview`, async ({ request }) => {
      previews.push(await request.json());
      return HttpResponse.json(tripDraftFixtures.ready, {
        headers: { 'Cache-Control': 'private, no-store' },
      });
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  server.events.removeAllListeners();
  // The wizard persists its draft now (FR-TRC-12), and happy-dom keeps one
  // Storage for the whole file. Without this every test after the first would
  // start on whatever step its predecessor reached — the tests would pass or
  // fail depending on their order, which is the bug this line prevents rather
  // than a tidiness habit.
  sessionStorage.clear();
});

function renderWizard() {
  const router = createMemoryRouter(routes, { initialEntries: ['/start'] });
  const result = render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
  return { ...result, router };
}

/** Picks the first two selectable days in the visible month. */
async function pickDates(user: ReturnType<typeof userEvent.setup>) {
  const days = await screen.findAllByRole('button', { pressed: false });
  const numbered = days.filter((d) => /^\d+$/.test(d.textContent ?? ''));
  await user.click(numbered[0] as HTMLElement);
  await user.click(numbered[3] as HTMLElement);
}

/** Reaches the P0 deterministic recommendation preview from NOTHING. */
async function reachRecommendedPreview(user: ReturnType<typeof userEvent.setup>) {
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
}

async function startRecommendedTrip(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('heading', { name: copy['draftPreview.title'] });
  await user.click(screen.getByRole('button', { name: copy['draftPreview.start'] }));
}

describe('FE-102-T4 FR-TRC-10 deterministic recommendation preview', () => {
  it('previews before creating and confirms the approved stops as seedItems', async () => {
    // Break caught: the NOTHING branch calling createTrip directly, or mapping
    // preview stops without their date/position/null time/Pick constraint.
    const user = userEvent.setup();
    await reachRecommendedPreview(user);

    expect(
      await screen.findByRole('heading', { name: "Here's a plan to start with" }),
    ).toBeInTheDocument();
    expect(created).toHaveLength(0);
    expect(previews).toHaveLength(1);
    expect(Object.keys(previews[0] as Record<string, unknown>).sort()).toEqual([
      'endDate',
      'startDate',
      'timezone',
    ]);

    expect(screen.getByText('경복궁')).toBeInTheDocument();
    expect(screen.getByText('북촌한옥마을')).toBeInTheDocument();
    expect(screen.getByText('명동')).toBeInTheDocument();

    const picks = screen.getAllByRole('button', { name: /^Mark .* must visit$/ });
    expect(picks).toHaveLength(3);
    await user.click(picks[0] as HTMLElement);
    await user.click(screen.getByRole('button', { name: 'Start with this plan' }));

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    const body = created[0]?.body as {
      seedItems?: Array<{
        placeId: string;
        date: string;
        position: number;
        startTime: string | null;
        constraints?: Array<{ type: string; locked: boolean }>;
      }>;
    };
    expect(body.seedItems).toEqual([
      {
        placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01',
        date: '2026-10-04',
        position: 0,
        startTime: null,
        constraints: [{ type: 'MUST_VISIT', locked: true }],
      },
      {
        placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03',
        date: '2026-10-04',
        position: 1,
        startTime: null,
      },
      {
        placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02',
        date: '2026-10-05',
        position: 0,
        startTime: null,
      },
    ]);
  });
});

describe('FE-102-T5 recommendation preview non-ready states', () => {
  it('renders EMPTY as no recommendation and returns to dates without creating a trip', async () => {
    server.use(
      http.post(`${API_BASE}/trip-drafts/preview`, async ({ request }) => {
        previews.push(await request.json());
        return HttpResponse.json(tripDraftFixtures.empty);
      }),
    );
    const user = userEvent.setup();
    await reachRecommendedPreview(user);

    expect(
      await screen.findByRole('heading', { name: "We couldn't fill these dates" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start with this plan' })).toBeNull();
    expect(created).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Change dates' }));
    expect(
      await screen.findByRole('heading', { name: copy['wizard.dates.title'] }),
    ).toBeInTheDocument();
    expect(previews).toHaveLength(1);
    expect(created).toHaveLength(0);
  });

  it('keeps a retryable 503 distinct from EMPTY and recovers on explicit retry', async () => {
    let attempt = 0;
    server.use(
      http.post(`${API_BASE}/trip-drafts/preview`, async ({ request }) => {
        previews.push(await request.json());
        attempt += 1;
        if (attempt === 1) {
          return HttpResponse.json(problemFixtures.SOURCE_UNAVAILABLE, { status: 503 });
        }
        return HttpResponse.json(tripDraftFixtures.ready);
      }),
    );
    const user = userEvent.setup();
    await reachRecommendedPreview(user);

    expect(
      await screen.findByRole('heading', { name: 'Recommendations are unavailable' }),
    ).toBeInTheDocument();
    expect(screen.queryByText("We couldn't fill these dates")).toBeNull();
    expect(previews).toHaveLength(1);
    expect(created).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(
      await screen.findByRole('heading', { name: "Here's a plan to start with" }),
    ).toBeInTheDocument();
    expect(previews).toHaveLength(2);
  });

  it('does not offer a retry for a non-retryable 500', async () => {
    server.use(
      http.post(`${API_BASE}/trip-drafts/preview`, () =>
        HttpResponse.json(problemFixtures.INTERNAL_ERROR, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    await reachRecommendedPreview(user);

    expect(
      await screen.findByRole('heading', { name: "We couldn't build this plan" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
    expect(created).toHaveLength(0);
  });

  it('treats a malformed 2xx preview as a non-retryable failure', async () => {
    server.use(
      http.post(`${API_BASE}/trip-drafts/preview`, async ({ request }) => {
        previews.push(await request.json());
        return HttpResponse.json({
          ...tripDraftFixtures.ready,
          days: undefined,
        });
      }),
    );
    const user = userEvent.setup();
    await reachRecommendedPreview(user);

    expect(
      await screen.findByRole('heading', {
        name: copy['draftPreview.failedTitle'],
      }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: copy['draftPreview.start'] })).toBeNull();
    expect(screen.queryByRole('button', { name: copy['draftPreview.retry'] })).toBeNull();
    expect(previews).toHaveLength(1);
    expect(created).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: copy['draftPreview.back'] }));
    expect(await screen.findByText(`${copy['wizard.step']} 3`)).toBeInTheDocument();
    expect(previews).toHaveLength(1);
  });
});

describe('the wizard keeps every step label in the app bar', () => {
  it('places STEP 1 beside the back control instead of in the date content', async () => {
    renderWizard();

    const marker = await screen.findByText(`${copy['wizard.step']} 1`);
    const back = screen.getByRole('button', { name: copy['wizard.back'] });
    expect(marker.closest('header')).toBe(back.closest('header'));
  });
});

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
    await startRecommendedTrip(user);

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
    await startRecommendedTrip(user);

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
    await startRecommendedTrip(user);

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
    await startRecommendedTrip(user);

    await screen.findByText(copy['wizard.createFailed']);
    await user.click(screen.getByRole('button', { name: copy['draftPreview.start'] }));

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
    await startRecommendedTrip(user);
    await screen.findByText(copy['wizard.createFailed']);

    // Change the draft, then submit again.
    //
    // An INTEREST rather than the planning level, which is what this used to
    // change: only NOTHING opens this recommendation path from step 3,
    // MUST_VISIT_ONLY and MOSTLY_PLANNED each continue to a step 4 of their
    // own (`438:3158` / `400:1201`). Picking another level would leave this
    // measuring navigation instead of the key. Interests ride in the request
    // body, so toggling one is a real change to what is about to be sent while
    // keeping the answer that submits from here.
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));
    const interest = await screen.findByRole('button', {
      name: copy['interest.FOOD'],
    });
    await user.click(interest);
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await startRecommendedTrip(user);

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

    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await screen.findByRole('heading', { name: copy['draftPreview.title'] });
    await user.click(screen.getByRole('button', { name: copy['draftPreview.start'] }));
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
    await startRecommendedTrip(user);

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
    const result = renderWizard();
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
    return result;
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
    const { router } = await reachMethod(user);
    await user.click(
      await screen.findByRole('button', { name: new RegExp(copy['method.paste']) }),
    );
    expect(await screen.findByLabelText(copy['import.label'])).toBeInTheDocument();
    expect(router.state.location.state).toMatchObject({
      wizardDraft: {
        startDate: expect.any(String),
        endDate: expect.any(String),
        interests: ['FOOD'],
        planningLevel: 'MOSTLY_PLANNED',
      },
    });
  });

  it('moves focus to the method heading after the step changes', async () => {
    const user = userEvent.setup();
    await reachMethod(user);

    const heading = await screen.findByRole('heading', {
      name: `${copy['method.title1']} ${copy['method.title2']}`,
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
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

  it('FE-603-T5 a stop it collected keeps its place credit', async () => {
    // The search result row credited the place and the row it became did not:
    // once picked, the name stood alone (CMP-ATT-001). The stop row is found
    // through its own remove button, so the credit asserted is the row's and
    // not the search result's that was on screen a moment earlier.
    const place = placeFixtures.searchPage.items[0];
    const credit = place?.sourceAttribution;
    if (!place || !credit) throw new Error('the search fixture lost its credited place');
    const user = userEvent.setup();
    await reachManual(user);
    await addPlaceTo(user, 0);

    const remove = await screen.findByRole('button', {
      name: copy['manual.removeNamed'].replace('{place}', place.name),
    });
    const row = remove.closest('li');
    if (!row) throw new Error('the stop is not a list item');
    expect(within(row).getByRole('link', { name: credit.attribution })).toHaveAttribute(
      'href',
      credit.officialUrl ?? '',
    );
  });

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

  it('reaches a place past the first page of results (#54)', async () => {
    // This step's list is its own markup, so the continuation FE-103-T5..T21
    // prove (place-search.test.tsx, must-visit.test.tsx) is wired here separately: a place on page two
    // becomes a stop like any other.
    const served = servePlaceSearchPages();
    const [next] = searchPages.next;
    const user = userEvent.setup();
    await reachManual(user);
    const adds = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.addToDay'].replace('{day}', '.+')),
    });
    await user.click(adds[0] as HTMLElement);
    await user.type(await screen.findByLabelText(copy['manual.searchLabel']), '서울');
    await user.click(
      await screen.findByRole('button', { name: copy['placeSearch.more'] }),
    );

    await user.click(
      await screen.findByRole('button', {
        name: copy['manual.addNamed'].replace('{place}', next.name),
      }),
    );
    expect(
      await screen.findByRole('button', {
        name: copy['manual.removeNamed'].replace('{place}', next.name),
      }),
    ).toBeInTheDocument();
    expect(served.bodies.at(-1)?.cursor).toBe(NEXT_CURSOR);
  });

  it('FE-103-T8 FE-103-T19 a failed next page keeps the results and adds no search failure', async () => {
    // This step gated its list on `isSuccess`, which a failed page two turns
    // false, so the results received already would vanish with it. And the
    // step's own alert is for a search that failed outright.
    servePlaceSearchPages({ failNext: 1 });
    const user = userEvent.setup();
    await reachManual(user);
    const adds = await screen.findAllByRole('button', {
      name: new RegExp(copy['manual.addToDay'].replace('{day}', '.+')),
    });
    await user.click(adds[0] as HTMLElement);
    await user.type(await screen.findByLabelText(copy['manual.searchLabel']), '서울');
    await user.click(
      await screen.findByRole('button', { name: copy['placeSearch.more'] }),
    );
    await screen.findByRole('button', { name: copy['placeSearch.retryMore'] });

    for (const place of searchPages.first) {
      expect(
        screen.getByRole('button', {
          name: copy['manual.addNamed'].replace('{place}', place.name),
        }),
      ).toBeInTheDocument();
    }
    expect(screen.queryByText(copy['manual.searchError'])).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(copy['placeSearch.moreFailed']);
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

  it('credits the picked place on its stop even before any forecast loads', async () => {
    // CMP-ATT-001: a KTO place shown by name carries its credit. The confirm
    // card rendered only the forecast's credit, so with no forecast in view a
    // KTO stop showed none at all.
    const user = userEvent.setup();
    await reachConfirm(user);
    const place = placeFixtures.searchPage.items[0];
    const credit = place?.sourceAttribution;
    if (!place || !credit?.officialUrl) throw new Error('search fixture changed');
    const card = screen.getByText(place.name).closest('[data-crowd-stop]');
    expect(card).not.toBeNull();
    const links = within(card as HTMLElement).getAllByRole('link', {
      name: credit.attribution,
    });
    expect(links.map((link) => link.getAttribute('href'))).toEqual([credit.officialUrl]);
  });

  it('FE-603-T7 names the forecast source beside the place credit on a stop', async () => {
    // Both credits read `출처: ⓒ한국관광공사` and link two datasets. The forecast
    // is drawn after the place, so it is the one that names its source.
    // No IntersectionObserver: the stop takes its immediate-load fallback.
    vi.stubGlobal('IntersectionObserver', undefined);
    const point = crowdFixtures.seriesForecast.points[0];
    const place = placeFixtures.searchPage.items[0];
    if (!point || !place) throw new Error('fixtures changed');
    expect(point.provenance.attribution).toBe(place.sourceAttribution?.attribution);
    server.use(
      http.get(`${API_BASE}/places/:placeId/crowd-forecast`, ({ request }) => {
        const targetAt = new URL(request.url).searchParams.get('from');
        return HttpResponse.json({
          ...crowdFixtures.seriesForecast,
          points: [{ ...point, provenance: { ...point.provenance, targetAt } }],
        });
      }),
    );
    const user = userEvent.setup();
    await reachConfirm(user);

    const card = screen.getByText(place.name).closest('[data-crowd-stop]') as HTMLElement;
    await within(card).findByText(/Relative concentration/);
    const forecastLink = within(card)
      .getAllByRole('link', { name: point.provenance.attribution ?? '' })
      .find((link) => link.getAttribute('href') === point.provenance.officialUrl);
    expect(forecastLink).toHaveAccessibleDescription(point.provenance.sourceDisplayName);
  });

  it('loads an exact dated crowd point only when the stop approaches view', async () => {
    let reveal: () => void = () => {
      throw new Error('forecast observer was not created');
    };
    let observed: Element | null = null;
    let requests = 0;
    class Observer {
      constructor(callback: IntersectionObserverCallback) {
        reveal = () => {
          callback([{ isIntersecting: true } as IntersectionObserverEntry], this);
        };
      }
      observe(target: Element) {
        observed = target;
      }
      disconnect() {}
      unobserve() {}
      takeRecords() {
        return [];
      }
      readonly root = null;
      readonly rootMargin = '160px 0px';
      readonly thresholds = [0];
    }
    vi.stubGlobal('IntersectionObserver', Observer);
    server.use(
      http.get(`${API_BASE}/places/:placeId/crowd-forecast`, ({ request }) => {
        requests += 1;
        const targetAt = new URL(request.url).searchParams.get('from');
        const point = crowdFixtures.seriesForecast.points[0];
        if (!point || !targetAt) throw new Error('forecast fixture changed');
        return HttpResponse.json({
          ...crowdFixtures.seriesForecast,
          points: [
            { ...point, value: 61, provenance: { ...point.provenance, targetAt } },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    await reachConfirm(user);
    expect(requests).toBe(0);
    expect(observed).toHaveAttribute('data-crowd-stop');

    act(() => {
      reveal();
    });
    expect(await screen.findByText('Relative concentration 61')).toBeInTheDocument();
    expect(requests).toBe(1);
    expect(screen.getByText('Official crowd forecast')).toBeInTheDocument();
    // Scoped to the stop's card and told apart by link: the place's own credit
    // renders there too and reads the same words, but each keeps its own
    // dataset's link (SOURCE_CATALOG, provenance primitives).
    const card = observed as unknown as HTMLElement;
    const forecastUrl = crowdFixtures.seriesForecast.points[0]?.provenance.officialUrl;
    const placeUrl = placeFixtures.searchPage.items[0]?.sourceAttribution?.officialUrl;
    expect(forecastUrl).toBeTruthy();
    expect(placeUrl).toBeTruthy();
    expect(forecastUrl).not.toBe(placeUrl);
    const hrefs = within(card)
      .getAllByRole('link', { name: '출처: ⓒ한국관광공사' })
      .map((link) => link.getAttribute('href'));
    expect(hrefs).toEqual([placeUrl, forecastUrl]);
    // KTO supplies no ordinal, so no stage is synthesized from 61.
    expect(document.body).not.toHaveTextContent(/Level \d/);
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
    await startRecommendedTrip(user);

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
    await startRecommendedTrip(user);

    await waitFor(() => {
      expect(created).toHaveLength(1);
    });
    // The trip screen is reached all the same.
    await waitFor(() => {
      expect(screen.queryByText(`${copy['wizard.step']} 3`)).toBeNull();
    });
  });
});

// FR-TRC-12: the draft survives a reload and a browser Back.
//
// The steps are component state rather than routes, so leaving /start threw the
// draft away — TripWizardScreen's `goBack` comment recorded the repro months
// before anything acted on it, and a judge who reloads mid-wizard starts over.
// FIGMA_HANDOFF:165 is the rule: "step별 입력은 sessionStorage/local state에
// 복구 가능하게 저장한다. 붙여넣기 원문은 persistence 대상에서 제외한다."
//
// Unmounting and rendering again is what a reload does to this component: the
// module-level state goes, the Storage stays. It is the closest thing to F5
// that a jsdom-family environment offers.
describe('FR-TRC-12 the wizard recovers a draft it was interrupted in', () => {
  it('reopens on the step the user reached, with the dates still chosen', async () => {
    const user = userEvent.setup();
    const first = renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await screen.findByText(`${copy['wizard.step']} 2`);

    first.unmount();
    renderWizard();

    // Step 2, not step 1: the screen came back where it was left.
    expect(await screen.findByText(`${copy['wizard.step']} 2`)).toBeInTheDocument();
    // And the dates came with it — going back shows them still selected, which
    // is the half a "step number survived" assertion would miss entirely.
    await user.click(screen.getByRole('button', { name: copy['wizard.back'] }));
    expect(await screen.findByRole('button', { name: /–/ })).toBeEnabled();
  });

  it('starts fresh once the draft has become a trip', async () => {
    const user = userEvent.setup();
    const first = renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await user.click(
      await screen.findByRole('button', {
        name: new RegExp(copy['wizard.planning.NOTHING.title']),
      }),
    );
    await user.click(screen.getByRole('button', { name: copy['wizard.next'] }));
    await startRecommendedTrip(user);
    await waitFor(() => {
      expect(created).toHaveLength(1);
    });

    first.unmount();
    renderWizard();

    // Step 1 again. Keeping the snapshot here would make the NEXT trip inherit
    // this one's dates, which is worse than losing the draft.
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
  });

  // A draft is a convenience, not a record. An older build's shape, or a
  // hand-edited value, must not make /start unreachable — so a bad snapshot is
  // the same answer as no snapshot.
  //
  // One case per reason, and each case is valid in every OTHER way, because a
  // fixture that is invalid for two reasons only proves whichever check runs
  // first. Measured twice: `{"step":99,"draft":null}` stayed green with the
  // step-range check deleted (the null draft was caught a line later), and so
  // did `{"step":99,"draft":{}}` — an empty draft fails the date check, since
  // `undefined` is neither null nor a string. The step case below therefore
  // carries a fully-formed draft and differs from a usable snapshot only in its
  // step number.
  const VALID_DRAFT =
    '{"startDate":null,"endDate":null,"interests":[],"planningLevel":null,"stops":[],"mustVisit":[]}';
  it.each([
    ['a step the wizard does not have', `{"step":99,"draft":${VALID_DRAFT}}`],
    ['a draft that is not an object', '{"step":2,"draft":null}'],
    ['a draft whose fields are the wrong type', '{"step":2,"draft":{"interests":7}}'],
    ['a value that is not JSON at all', 'not json'],
  ])('ignores %s instead of failing to open', async (_reason, stored) => {
    sessionStorage.setItem('nullnull.wizard.v1', stored);
    renderWizard();
    expect(await screen.findByText(`${copy['wizard.step']} 1`)).toBeInTheDocument();
  });

  it('keeps no pasted itinerary text in any storage the page has', async () => {
    // Invariant 10. The canary is checked against EVERY Storage rather than
    // against sessionStorage by name: naming the storages that exist today is
    // the same shape as a check that lists today's columns and keeps passing
    // when someone adds one. The pasted text should never reach any of them —
    // it lives in ImportPasteScreen's own state, on a different route.
    const canary = 'CANARY-9/15 경복궁 10:00 인사동 14:00';
    const user = userEvent.setup();
    renderWizard();
    await pickDates(user);
    await user.click(screen.getByRole('button', { name: /–/ }));
    await user.click(await screen.findByRole('button', { name: copy['wizard.next'] }));
    await screen.findByText(`${copy['wizard.step']} 3`);
    await user.click(screen.getByRole('button', { name: copy['import.start'] }));
    await user.type(await screen.findByLabelText(copy['import.label']), canary);

    const dumps = [sessionStorage, localStorage].map((store) =>
      Object.keys(store)
        .map((k) => `${k}=${store.getItem(k) ?? ''}`)
        .join('\n'),
    );

    // NOT VACUOUS: the wizard really did persist something on the way here.
    // Measured — with `writeSnapshot` deleted this test stayed green, because
    // "the canary is absent" is trivially true of empty storage, and a broken
    // feature would have read as proof of invariant 10. The dates are the part
    // that IS supposed to be stored, so finding them is what makes the absence
    // of the pasted text mean anything.
    expect(dumps[0], 'the wizard should have persisted its draft').toContain(
      'nullnull.wizard.v1',
    );

    for (const dump of dumps) {
      expect(dump).not.toContain('경복궁');
      expect(dump).not.toContain('CANARY');
    }
  });
});
