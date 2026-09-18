// @vitest-environment happy-dom
//
// FE-503/FE-505 wiring: the READY preview and the decision that acts on it
// (FR-OPT-04, FR-OPT-05, BA-052).
//
// Separate from optimization-run.test.tsx because the weight is different.
// That file counts polls; this one counts WRITES — what leaves the browser,
// with which headers, and when. Invariants 3 and 4 are claims about requests,
// not about pixels, so the assertions are made of the request log.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { optimizationFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, MOCK_RUN_ID } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const READY = optimizationFixtures.runReady;

/** Every non-GET the browser sent, with the headers the contract cares about. */
interface Write {
  method: string;
  path: string;
  ifMatch: string | null;
  idempotencyKey: string | null;
  body: unknown;
}
let writes: Write[] = [];
/** GETs of the trip, so a cache invalidation is countable. */
let tripReads = 0;

beforeEach(() => {
  writes = [];
  tripReads = 0;
});

/**
 * Records every write the screen makes.
 *
 * `/session/csrf` is excluded: the shell posts it on every screen to hold a
 * tab token, so counting it would make "this screen wrote nothing" impossible
 * to state. It is not a trip mutation and carries no If-Match.
 */
function recordWrites() {
  server.use(
    http.all(`${API_BASE}/*`, async ({ request }) => {
      if (request.method === 'GET') return;
      const path = new URL(request.url).pathname;
      if (path.endsWith('/session/csrf')) return;
      writes.push({
        method: request.method,
        path,
        ifMatch: request.headers.get('If-Match'),
        idempotencyKey: request.headers.get('Idempotency-Key'),
        body: await request.clone().json(),
      });
      return;
    }),
  );
}

/** A READY run carrying the approved proposal fixture. */
function readyRun(extra: Record<string, unknown> = {}) {
  server.use(
    http.get(`${API_BASE}/optimizations/:runId`, () =>
      HttpResponse.json({ ...READY, ...extra }),
    ),
    http.get(`${API_BASE}/trips/:tripId`, () => {
      tripReads += 1;
      return HttpResponse.json(trip, { headers: { ETag: 'W/"trip-7"' } });
    }),
  );
}

/**
 * The decision endpoint, answering however the caller wants — and recording
 * the request.
 *
 * The recording happens HERE rather than in a catch-all installed earlier.
 * msw resolves the most recently registered matching handler, so a later
 * `server.use` for this same path wins outright and a general recorder
 * registered before it never runs. Measured: three cases asserted on an empty
 * `writes` array while the requests were in fact being sent and answered.
 */
function decisionAnswers(answer: () => Response) {
  server.use(
    http.post(`${API_BASE}/optimizations/:runId/decisions`, async ({ request }) => {
      writes.push({
        method: request.method,
        path: new URL(request.url).pathname,
        ifMatch: request.headers.get('If-Match'),
        idempotencyKey: request.headers.get('Idempotency-Key'),
        body: await request.clone().json(),
      });
      return answer();
    }),
  );
}

function renderRun() {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/optimizations/${READY.id}`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-503 invariant 3: nothing writes before the user decides', () => {
  it('sends no mutation on opening a READY run', async () => {
    recordWrites();
    readyRun();
    renderRun();

    // The preview is on screen, so the screen has finished its work.
    await screen.findByText(copy['decision.apply']);

    // Not "no APPLY" — no write of ANY kind. A screen that reached the server
    // some other way would still have moved the itinerary without being asked.
    expect(writes).toEqual([]);
  });
});

describe('FE-505 the decision carries the guards the contract requires', () => {
  it('sends If-Match and an Idempotency-Key with the chosen proposal', async () => {
    const user = userEvent.setup();
    recordWrites();
    readyRun();
    decisionAnswers(() =>
      HttpResponse.json(
        {
          id: '018f6b00-0000-7000-8000-000000000001',
          runId: READY.id,
          proposalId: READY.proposals[0]?.id,
          decision: 'APPLY',
          decidedAt: '2026-09-11T06:10:00Z',
          resultingTripVersion: trip.version + 1,
        },
        { headers: { ETag: 'W/"trip-8"' } },
      ),
    );
    renderRun();

    await user.click(await screen.findByRole('button', { name: copy['decision.apply'] }));

    await waitFor(() => {
      expect(writes).toHaveLength(1);
    });
    const write = writes[0];
    expect(write?.method).toBe('POST');
    expect(write?.path).toContain('/decisions');
    // The ETag the trip GET returned, not a guess.
    expect(write?.ifMatch).toBe('W/"trip-7"');
    expect(write?.idempotencyKey).toBeTruthy();
    expect(write?.body).toMatchObject({
      proposalId: READY.proposals[0]?.id,
      decision: 'APPLY',
    });
  });

  it('replays a failed apply with the SAME key rather than sending a second command', async () => {
    const user = userEvent.setup();
    recordWrites();
    readyRun();
    // 503 APPLY_FAILED: the contract says a failed APPLY "records no decision",
    // so the retry must be a replay of the first command.
    decisionAnswers(
      () =>
        new HttpResponse(
          JSON.stringify({
            type: 'https://nullnull.app/problems/apply-failed',
            title: 'Apply failed',
            status: 503,
            code: 'APPLY_FAILED',
            retryable: true,
          }),
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    );
    renderRun();

    await user.click(await screen.findByRole('button', { name: copy['decision.apply'] }));
    await waitFor(() => {
      expect(writes).toHaveLength(1);
    });

    // The bar offers a recovery; press the apply path again.
    const retry = await screen.findByRole('button', {
      name: copy['decision.failedAction'],
    });
    await user.click(retry);
    await waitFor(() => {
      expect(writes.length).toBeGreaterThan(1);
    });

    // A fresh key would be a SECOND destructive command while the first may
    // yet have landed. This is the assertion a `crypto.randomUUID()` inside
    // the mutationFn fails.
    expect(writes[1]?.idempotencyKey).toBe(writes[0]?.idempotencyKey);
  });
});

describe('FE-505 invariant 4: a retry never changes what was decided', () => {
  it('replays a failed KEEP as a KEEP, not as an APPLY', async () => {
    const user = userEvent.setup();
    recordWrites();
    readyRun();
    decisionAnswers(
      () =>
        new HttpResponse(
          JSON.stringify({
            type: 'https://nullnull.app/problems/apply-failed',
            title: 'Apply failed',
            status: 503,
            code: 'APPLY_FAILED',
            retryable: true,
          }),
          { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    );
    renderRun();

    await user.click(await screen.findByRole('button', { name: copy['decision.keep'] }));
    await waitFor(() => {
      expect(writes).toHaveLength(1);
    });

    await user.click(
      await screen.findByRole('button', { name: copy['decision.failedAction'] }),
    );
    await waitFor(() => {
      expect(writes.length).toBeGreaterThan(1);
    });

    // The user declined. A retry that applies instead is the one substitution
    // invariant 4 cannot survive.
    expect(writes[1]?.body).toMatchObject({ decision: 'KEEP' });
    expect(writes[1]?.idempotencyKey).toBe(writes[0]?.idempotencyKey);
  });
});

describe('FE-505 invariant 4: KEEP changes nothing', () => {
  it('does not reload the itinerary after a KEEP', async () => {
    const user = userEvent.setup();
    recordWrites();
    readyRun();
    decisionAnswers(() =>
      HttpResponse.json({
        id: '018f6b00-0000-7000-8000-000000000002',
        runId: READY.id,
        proposalId: READY.proposals[0]?.id,
        decision: 'KEEP',
        decidedAt: '2026-09-11T06:10:00Z',
        resultingTripVersion: null,
      }),
    );
    renderRun();

    await screen.findByText(copy['decision.keep']);
    const readsBeforeDecision = tripReads;

    await user.click(screen.getByRole('button', { name: copy['decision.keep'] }));
    await waitFor(() => {
      expect(writes).toHaveLength(1);
    });

    // The contract: "KEEP records intent but does not increment trip version".
    // Refetching the trip would make the itinerary reload for a decision that
    // changed nothing, which reads on screen as a change that did not happen.
    await waitFor(() => {
      expect(writes[0]?.body).toMatchObject({ decision: 'KEEP' });
    });
    expect(tripReads).toBe(readsBeforeDecision);
  });
});

describe('FE-505 the run settles into what was decided', () => {
  // Uses the DEFAULT handlers rather than this file's own: they carry the
  // state that makes the transition observable (a decided run answers APPLIED
  // or KEPT on the next poll). A local handler that always re-answered READY
  // would let this pass against a run that never moved, which is the failure
  // this case exists to rule out.
  it('reports the applied decision after the trip cache is invalidated', async () => {
    const user = userEvent.setup();
    const router = createMemoryRouter(routes, {
      initialEntries: [`/trip/${trip.id}/optimizations/${MOCK_RUN_ID}`],
    });
    render(
      <QueryClientProvider client={createQueryClient()}>
        <I18nProvider>
          <RouterProvider router={router} />
        </I18nProvider>
      </QueryClientProvider>,
    );

    // The default handler walks QUEUED → RUNNING → READY as it is polled.
    await user.click(
      await screen.findByRole(
        'button',
        { name: copy['decision.apply'] },
        { timeout: 10000 },
      ),
    );

    // The run now answers APPLIED, so the live region says so and the bar
    // stops offering a decision that has already been made.
    await screen.findByText(copy['run.applied'], undefined, { timeout: 10000 });
    expect(screen.queryByRole('button', { name: copy['decision.apply'] })).toBeNull();
  }, 20000);
});

describe('FE-503 a run that no longer matches the trip is not offered', () => {
  it('asks the user to recalculate instead of applying a stale run', async () => {
    recordWrites();
    // The run was computed against version 7; the trip has moved to 9.
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () => HttpResponse.json(READY)),
      http.get(`${API_BASE}/trips/:tripId`, () => {
        tripReads += 1;
        return HttpResponse.json(
          { ...trip, version: trip.version + 2 },
          { headers: { ETag: 'W/"trip-9"' } },
        );
      }),
    );
    renderRun();

    // `decisionPhase` returns `stale`, and the bar offers recovery rather than
    // a decision. Reading `status === 'READY'` alone would offer APPLY here.
    await screen.findByText(copy['decision.staleMessage']);
    expect(screen.queryByRole('button', { name: copy['decision.apply'] })).toBeNull();
    expect(writes).toEqual([]);
  });
});

describe('FE-503 choosing between proposals', () => {
  /**
   * A run carrying two proposals, built by cloning the approved one.
   *
   * The contract example has exactly one, so this path — the radiogroup, the
   * keyboard handling, `aria-checked` — had never executed: `selectable` is
   * false with a single card and every assertion above rendered the
   * non-selectable branch. Cloning here rather than editing the fixture keeps
   * the approved example the single source for SHAPE while letting a test
   * exercise a count it does not cover.
   *
   * Rank 2 is deliberately listed FIRST so "the default is rank 1" cannot pass
   * by accident on array order.
   */
  function twoProposals() {
    const first = READY.proposals[0];
    if (!first) throw new Error('run-ready.json has no proposal to clone');
    const second = {
      ...JSON.parse(JSON.stringify(first)),
      id: '018f6d00-0000-7000-8000-000000000002',
      rank: 2,
      summary: 'SECOND_PROPOSAL_SUMMARY',
    } as typeof first;
    return { ...READY, proposals: [second, { ...first, rank: 1 }] };
  }

  function renderTwo() {
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () =>
        HttpResponse.json(twoProposals()),
      ),
      http.get(`${API_BASE}/trips/:tripId`, () => {
        tripReads += 1;
        return HttpResponse.json(trip, { headers: { ETag: 'W/"trip-7"' } });
      }),
    );
    return renderRun();
  }

  it('offers the proposals as one exclusive choice', async () => {
    renderTwo();

    // `radio`, not `checkbox` or `button`: the run takes ONE decision, so a
    // screen reader should announce "1 of 2" rather than two toggles that
    // could both be on.
    const options = await screen.findAllByRole('radio');
    expect(options).toHaveLength(2);
    expect(screen.getByRole('radiogroup')).toBeInTheDocument();
  });

  it('starts on the proposal the server ranked first', async () => {
    renderTwo();

    const options = await screen.findAllByRole('radio');
    // Rank 1 is the optimizer's own answer. Starting on "nothing selected"
    // would make the user pick before they can act, and starting on array
    // order would follow whatever the server happened to serialize first.
    const checked = options.filter((o) => o.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0]?.textContent).toContain(READY.proposals[0]?.summary);
  });

  it('moves the choice with the keyboard, not the pointer alone', async () => {
    const user = userEvent.setup();
    renderTwo();

    const options = await screen.findAllByRole('radio');
    const second = options.find(
      (o) => o.getAttribute('aria-checked') === 'false',
    );
    expect(second).toBeDefined();
    if (!second) return;

    second.focus();
    await user.keyboard(' ');

    // A card reachable by Tab that does nothing when pressed is worse than one
    // that is not focusable at all: it promises an action and withholds it.
    expect(second.getAttribute('aria-checked')).toBe('true');
    expect(
      options.filter((o) => o.getAttribute('aria-checked') === 'true'),
    ).toHaveLength(1);
  });

  it('decides on the proposal the user chose, not on the default', async () => {
    const user = userEvent.setup();
    recordWrites();
    renderTwo();
    decisionAnswers(() =>
      HttpResponse.json({
        id: '018f6b00-0000-7000-8000-000000000003',
        runId: READY.id,
        proposalId: '018f6d00-0000-7000-8000-000000000002',
        decision: 'KEEP',
        decidedAt: '2026-09-11T06:10:00Z',
      }),
    );

    const options = await screen.findAllByRole('radio');
    const second = options.find((o) => o.getAttribute('aria-checked') === 'false');
    if (!second) throw new Error('both cards already selected');
    await user.click(second);
    await user.click(screen.getByRole('button', { name: copy['decision.keep'] }));

    await waitFor(() => {
      expect(writes).toHaveLength(1);
    });
    // The whole point of the selection UI: applying the default while the user
    // is looking at their choice is the failure this asserts against.
    expect(writes[0]?.body).toMatchObject({
      proposalId: '018f6d00-0000-7000-8000-000000000002',
    });
  });
});
