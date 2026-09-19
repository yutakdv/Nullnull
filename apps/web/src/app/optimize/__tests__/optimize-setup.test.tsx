// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-501 acceptance (FR-OPT-01, FCR-010), S09-0 `415:2268`.
//
// FE-501-T1: only ITEM is enabled; DAY and TRIP send nothing.
// FE-501-T2: default/loading/error/empty/conflict each render.
// FE-501-T3: keyboard reach, focus and accessible names.
//
// T1 is checked at the wire. FCR-010's requirement is "DAY/TRIP은 숨기거나
// disabled `준비 중`이며 요청 0건" — a screen that merely greys the chips while
// still building a DAY body would look right and break the rule.
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const items = trip.days.flatMap((day) => day.items);
const first = items.find(
  (item) => !item.constraints.some((constraint) => constraint.type === 'DATE'),
);
const dateLocked = items.find((item) =>
  item.constraints.some((constraint) => constraint.type === 'DATE'),
);

interface Sent {
  body: unknown;
  ifMatch: string | null;
  idempotencyKey: string | null;
}

let sent: Sent[] = [];
/** Every request, so a stray trip write cannot hide behind a filter. */
let allRequests: { method: string; path: string }[] = [];

beforeEach(() => {
  sent = [];
  allRequests = [];
  server.events.on('request:start', ({ request }) => {
    allRequests.push({ method: request.method, path: new URL(request.url).pathname });
    if (request.method !== 'POST') return;
    if (!new URL(request.url).pathname.endsWith('/optimizations')) return;
    const clone = request.clone();
    void clone.json().then(
      (body) => {
        sent.push({
          body,
          ifMatch: request.headers.get('If-Match'),
          idempotencyKey: request.headers.get('Idempotency-Key'),
        });
      },
      () => undefined,
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderSetup() {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/optimize`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Renders and picks the first movable stop, which is the only way to enable submit. */
async function pickFirstStop(user: ReturnType<typeof userEvent.setup>) {
  renderSetup();
  // The heading renders in the loading branch too, so this waits for a
  // control that only exists once the trip has arrived.
  await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
  // The button's accessible name is the place plus its time, so this matches
  // on the name rather than demanding the whole string.
  await user.click(
    screen.getByRole('button', { name: new RegExp(first?.place.name ?? '') }),
  );
}

describe('FE-501-T1 only ITEM is offered (FCR-010 trace)', () => {
  it('enables ITEM and disables DAY and TRIP', async () => {
    renderSetup();
    expect(
      await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] }),
    ).toBeEnabled();
    expect(
      screen.getByRole('button', { name: copy['optimize.scope.DAY'] }),
    ).toBeDisabled();
    expect(
      screen.getByRole('button', { name: copy['optimize.scope.TRIP'] }),
    ).toBeDisabled();
  });

  it('says why the other scopes are unavailable', async () => {
    renderSetup();
    expect(
      await screen.findByText(copy['optimize.scope.comingSoon']),
    ).toBeInTheDocument();
  });

  // The candidate option is 준비 중: the server refuses includeCandidates=true
  // unconditionally (CreateOptimizationCommand's compact constructor, ahead of
  // every capability and state check), so a pressable box here is a control
  // whose every use is a 422.
  //
  // Three clauses, three tests, because each one alone passes for a screen
  // that is wrong in a different way. "It is disabled" passes if the row is
  // deleted — and deleting it would put the frame at odds with
  // FIGMA_HANDOFF's "후보 포함 OFF". "It is present and says 준비 중" passes if
  // the box is still pressable. And both pass while the request still carries
  // whatever the box last held.
  it('still shows the candidate option, and says it is 준비 중', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    expect(screen.getByText(copy['optimize.includeCandidates'])).toBeInTheDocument();
    expect(
      screen.getByText(copy['optimize.includeCandidates.comingSoon']),
    ).toBeInTheDocument();
  });

  it('disables the candidate option, so there is nothing to press', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    expect(screen.getByRole('checkbox')).toBeDisabled();
  });

  it('sends includeCandidates false even after a click on the box', async () => {
    // Clicked rather than merely read, because the failure this guards is a
    // box that still toggles: `false` in the body proves nothing if nothing
    // ever tried to make it true. userEvent refuses a disabled control, so
    // the click goes through fireEvent to reach the element regardless.
    //
    // Sent rather than omitted: all three request variants list
    // includeCandidates in `required` with additionalProperties: false, so a
    // body without it is invalid against the contract.
    const user = userEvent.setup();
    await pickFirstStop(user);
    fireEvent.click(screen.getByRole('checkbox'));
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body).toMatchObject({ includeCandidates: false });
  });

  it('sends an ITEM body with the chosen stop, version, ETag and key', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body).toEqual({
      scope: 'ITEM',
      targetItemId: first?.id,
      // What the run is computed against, read off the trip we loaded.
      inputTripVersion: trip.version,
      includeCandidates: false,
    });
    expect(sent[0]?.ifMatch).not.toBeNull();
    // Minted per submit: a retry must replay the run, not queue a second.
    expect(sent[0]?.idempotencyKey).not.toBeNull();
  });

  it('keeps a DATE-locked stop visible but disabled and sends nothing for it', async () => {
    expect(dateLocked).toBeDefined();
    if (!dateLocked) return;

    const user = userEvent.setup();
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    const locked = screen.getByRole('button', {
      name: new RegExp(dateLocked.place.name),
    });
    expect(locked).toBeDisabled();
    expect(locked).toHaveAccessibleDescription(copy['optimize.targetDateLocked']);

    await user.click(locked);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));
    expect(await screen.findByText(copy['optimize.needTarget'])).toBeInTheDocument();
    expect(sent).toHaveLength(0);
  });

  it('replays the same key when the user retries a failed submit', async () => {
    // Without this the screen minted a fresh UUID per press, so a user
    // pressing submit again after a failure queued a SECOND run against the
    // same stop — the duplicate command invariant 6 exists to prevent.
    // Reproduced before the fix: the two keys differed.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('RATE_LIMITED'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));
    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });
    expect(sent[0]?.idempotencyKey).toBe(sent[1]?.idempotencyKey);
  });

  it('uses a new key once the request itself changes', async () => {
    // Single-flight must not become single-shot: choosing a different stop is
    // a different command and deserves its own key.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('RATE_LIMITED'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });

    const second = trip.days.flatMap((day) => day.items)[1];
    await user.click(
      screen.getByRole('button', { name: new RegExp(second?.place.name ?? '') }),
    );
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));
    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });
    expect(sent[0]?.idempotencyKey).not.toBe(sent[1]?.idempotencyKey);
  });

  it('never sends a DAY or TRIP scope, whatever is pressed', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    // Press the disabled chips too — a disabled button fires nothing, and this
    // proves the submit path has no branch that could build another scope.
    await user.click(screen.getByRole('button', { name: copy['optimize.scope.DAY'] }));
    await user.click(screen.getByRole('button', { name: copy['optimize.scope.TRIP'] }));
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    for (const request of sent) {
      expect((request.body as { scope: string }).scope).toBe('ITEM');
    }
  });

  it('asks for a stop instead of sending an incomplete request', async () => {
    // targetItemId is required by the contract, so there is nothing to send
    // until the user picks one.
    const user = userEvent.setup();
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.needTarget'])).toBeInTheDocument();
    expect(sent).toHaveLength(0);
  });

  it('states that the itinerary does not change yet', async () => {
    // Invariant 3: the optimizer previews and the user applies. A screen that
    // did not say so invites the user to expect a changed trip.
    renderSetup();
    expect(await screen.findByText(copy['optimize.previewNote'])).toBeInTheDocument();
  });

  it('writes nothing to the trip when the run is queued', async () => {
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // Queuing a preview is not a trip mutation (invariants 3 and 4). Checked
    // against EVERY request, not just the optimization POST: asserting on a
    // list that only ever collects optimization calls would say nothing about
    // trip writes at all.
    const tripWrites = allRequests.filter(
      (r) => r.method !== 'GET' && /\/trips\/[^/]+$/.test(r.path),
    );
    expect(tripWrites).toEqual([]);
    const itemWrites = allRequests.filter(
      (r) => r.method !== 'GET' && r.path.includes('/items'),
    );
    expect(itemWrites).toEqual([]);
  });
});

describe('FE-501-T2 the setup renders its states', () => {
  it('lists the stops the trip has', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    for (const item of trip.days.flatMap((day) => day.items)) {
      expect(
        screen.getByRole('button', { name: new RegExp(item.place.name) }),
      ).toBeInTheDocument();
    }
  });

  it('says so when the trip has no stops to optimize', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(tripFixtures.detailCreated, { headers: { ETag: '"1"' } }),
      ),
    );
    renderSetup();
    expect(await screen.findByText(copy['optimize.targetEmpty'])).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['optimize.submit'] })).toBeDisabled();
  });

  it('shows a loading state before the trip arrives', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, async () => {
        await delay(50);
        return HttpResponse.json(trip, {
          headers: { ETag: `"${String(trip.version)}"` },
        });
      }),
    );
    renderSetup();
    expect(await screen.findByRole('status')).toBeInTheDocument();
  });

  it('reports a conflict distinctly from a generic failure', async () => {
    // The contract's x-error-codes name TRIP_CHANGED for 409: the itinerary
    // moved on, so reloading is the recovery, not retrying blind.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.conflict'])).toBeInTheDocument();
    expect(screen.queryByText(copy['optimize.failed'])).toBeNull();
  });

  it('names a lock conflict rather than calling it a failure', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('LOCK_CONFLICT'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.locked'])).toBeInTheDocument();
  });

  it('says the capability is off rather than inviting a retry', async () => {
    // BA-050 made FORBIDDEN reachable: the server refuses with 403 when the
    // optimization capability is OFF, and it chose 403 over 503 precisely so a
    // client would STOP asking. `optimize.failed` reads as "we couldn't start
    // it" — a hiccup — which would send the user back to a button that can
    // never work on this server.
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('FORBIDDEN'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(await screen.findByText(copy['optimize.unavailable'])).toBeInTheDocument();
    expect(screen.queryByText(copy['optimize.failed'])).toBeNull();
  });
});

// BA-050 (503 SOURCE_UNAVAILABLE): the catalog being closed is temporary,
// unlike FORBIDDEN above (capability off, permanent). The submit button must
// stay usable — a fresh press just returns the same answer until the server
// opens, so nothing here should read as "press again right now".
describe('FE-501 a closed catalog does not disable the submit button', () => {
  it('shows its own copy for SOURCE_UNAVAILABLE, not the generic failure', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('SOURCE_UNAVAILABLE'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(
      await screen.findByText(copy['optimize.sourceUnavailable']),
    ).toBeInTheDocument();
    // Not just an addition alongside the generic line: it must replace it.
    expect(screen.queryByText(copy['optimize.failed'])).not.toBeInTheDocument();
  });

  it('keeps the submit control enabled, unlike a permanent FORBIDDEN', async () => {
    server.use(
      http.post(`${API_BASE}/trips/:tripId/optimizations`, () =>
        problemResponse('SOURCE_UNAVAILABLE'),
      ),
    );
    const user = userEvent.setup();
    await pickFirstStop(user);
    await user.click(screen.getByRole('button', { name: copy['optimize.submit'] }));

    expect(
      await screen.findByText(copy['optimize.sourceUnavailable']),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: copy['optimize.submit'] })).toBeEnabled();
  });
});

describe('FE-501-T3 keyboard and names', () => {
  it('reaches a stop by keyboard and selects it with Enter', async () => {
    const user = userEvent.setup();
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    const stop = screen.getByRole('button', {
      name: new RegExp(first?.place.name ?? ''),
    });
    stop.focus();
    expect(stop).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(stop).toHaveAttribute('aria-pressed', 'true');
  });

  it('groups the scope and the target under named legends', async () => {
    renderSetup();
    await screen.findByRole('button', { name: copy['optimize.scope.ITEM'] });
    expect(
      screen.getByRole('group', { name: copy['optimize.scope'] }),
    ).toBeInTheDocument();
    const target = screen.getByRole('group', { name: copy['optimize.target'] });
    expect(within(target).getAllByRole('button').length).toBeGreaterThan(0);
  });

  it('offers a back control to the trip', async () => {
    const user = userEvent.setup();
    renderSetup();
    await user.click(await screen.findByRole('button', { name: copy['optimize.back'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'trip-heading',
      );
    });
  });
});

// CMP-ATT-001 on /trip/{id}/optimize, submission screenshot #5.
//
// This screen lists the trip's stops so the user can pick one to optimize.
// Those rows are KTO place records and the screen rendered their names with no
// credit at all — DataAttribution was not even imported. There was no test to
// mutate, because the behaviour had never been written.
//
// The state is supplied by an override rather than by editing the shared BE/FE
// fixture. That was once forced — no trip fixture carried sourceAttribution at
// all — and is now a choice: #281 filled in the two trip fixtures that hold
// items, but this case asserts one exact credit string, and reading it from a
// shared fixture would make the assertion move whenever BE re-authors it.
describe('FE-501 the stop list credits the places it lists', () => {
  const CREDIT = '출처: ⓒ한국관광공사 (최적화 화면 검증용)';

  function tripWithCredit() {
    const [firstDay, ...restDays] = trip.days;
    if (!firstDay) throw new Error('fixture has no days');
    const [firstItem, ...restItems] = firstDay.items;
    if (!firstItem) throw new Error('fixture day has no items');
    return {
      ...trip,
      days: [
        {
          ...firstDay,
          items: [
            {
              ...firstItem,
              place: {
                ...firstItem.place,
                sourceAttribution: {
                  source: 'KTO_KOR_SERVICE_2',
                  sourceDisplayName: '한국관광공사 국문 관광정보',
                  sourceRegistryVersion: 4,
                  attribution: CREDIT,
                  officialUrl: 'https://www.data.go.kr/data/15101578/openapi.do',
                  licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
                  license: '이용허락범위 제한 없음',
                },
              },
            },
            ...restItems,
          ],
        },
        ...restDays,
      ],
    };
  }

  it('shows the server credit on the stop it belongs to', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(tripWithCredit(), { headers: { ETag: '"3"' } }),
      ),
    );
    renderSetup();
    const name = trip.days[0]?.items[0]?.place.name ?? '';
    const row = (await screen.findByText(name)).closest('li');
    expect(row).not.toBeNull();
    // Scoped to the row and to the link role: DataAttribution renders its text
    // inside the source link, so getByText matches both the wrapper and the
    // anchor, and a document-wide query would not prove it landed on this row.
    expect(
      within(row as HTMLElement).getByRole('link', { name: CREDIT }),
    ).toBeInTheDocument();
  });
});
