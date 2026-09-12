// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-502 acceptance (FR-OPT-03), S09-1 `415:2413`.
//
// FE-502-T1: polling stops at a terminal status and an expired run is never
//            shown as live.
// FE-502-T2: queued/running/ready/failed/expired/not-found/error each render.
// FE-502-T3: keyboard reach, focus and accessible names.
//
// The weight is on T1. A screen that polls a settled run for ever and a screen
// that never polls at all both LOOK right in a snapshot, so the request count
// is what the assertions are made of.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;
const RUN_ID = '018f6a00-0000-7000-8000-000000000001';

/**
 * Every GET the screen made for the run, so polling is countable.
 *
 * Counted inside each handler rather than through `server.events`: the msw
 * server is shared by the whole suite, and `removeAllListeners` in an
 * afterEach here would also drop listeners that other test files installed —
 * which left unrelated files (feed pagination, trip edit, the wizard) hanging
 * for their full timeout when the suite ran together.
 */
let polls = 0;

beforeEach(() => {
  polls = 0;
});

/** Answers with one fixed run body. */
function runIs(status: string, extra: Record<string, unknown> = {}) {
  server.use(
    http.get(`${API_BASE}/optimizations/:runId`, () => {
      polls += 1;
      return HttpResponse.json(
        {
          id: RUN_ID,
          tripId: trip.id,
          scope: 'ITEM',
          status,
          inputTripVersion: trip.version,
          includeCandidates: false,
          queuedAt: '2026-09-11T06:00:00Z',
          proposals: [],
          snapshotSetIds: [],
          decisions: [],
          ...extra,
        },
        // Short so a polling test does not wait a real second.
        status === 'QUEUED' || status === 'RUNNING'
          ? { headers: { 'Retry-After': '1' } }
          : undefined,
      );
    }),
  );
}

function renderRun(runId: string = RUN_ID) {
  const router = createMemoryRouter(routes, {
    initialEntries: [`/trip/${trip.id}/optimizations/${runId}`],
  });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-502-T1 polling follows the run and then stops', () => {
  it('keeps asking while the run is still working', { timeout: 10000 }, async () => {
    runIs('RUNNING');
    renderRun();
    await screen.findByText(copy['run.running']);
    // The contract sends Retry-After "for QUEUED/RUNNING responses"; the
    // screen has to act on it or a queued run never resolves on its own.
    // Retry-After is in seconds and the hook floors it at one, so this waits
    // past a whole interval rather than the default 1s waitFor budget.
    await waitFor(
      () => {
        expect(polls).toBeGreaterThan(1);
      },
      { timeout: 3000 },
    );
  });

  it('stops asking once the run is ready', { timeout: 10000 }, async () => {
    runIs('READY');
    renderRun();
    await screen.findByText(copy['run.ready']);
    const settled = polls;
    // Well past one poll interval. Retry-After is in seconds and the hook
    // floors it at one, so a window shorter than that cannot tell a stopped
    // poll from one that simply has not fired yet — checked by making the
    // hook poll settled runs and watching this fail.
    await new Promise((resolve) => setTimeout(resolve, 2600));
    expect(polls).toBe(settled);
  });

  it('stops asking once the run has failed', { timeout: 10000 }, async () => {
    runIs('FAILED', {
      failure: { code: 'NO_IMPROVEMENT', message: 'nothing better', retryable: false },
    });
    renderRun();
    await screen.findByText(copy['run.failure.NO_IMPROVEMENT']);
    const settled = polls;
    await new Promise((resolve) => setTimeout(resolve, 2600));
    expect(polls).toBe(settled);
  });

  it(
    'does not retry an expired preview, which cannot un-expire',
    { timeout: 10000 },
    async () => {
      server.use(
        http.get(`${API_BASE}/optimizations/:runId`, () => {
          polls += 1;
          return problemResponse('PREVIEW_EXPIRED');
        }),
      );
      renderRun();
      await screen.findByText(copy['run.expired']);
      const settled = polls;
      await new Promise((resolve) => setTimeout(resolve, 2600));
      expect(polls).toBe(settled);
    },
  );
});

describe('FE-502-T1 nothing here changes the itinerary', () => {
  it('sends no write of any kind, whatever the run says', async () => {
    const writes: string[] = [];
    const record = ({ request }: { request: Request }) => {
      if (request.method !== 'GET') writes.push(`${request.method} ${request.url}`);
    };
    server.events.on('request:start', record);
    try {
      runIs('READY');
      renderRun();
      await screen.findByText(copy['run.ready']);
      // Invariants 3 and 4: a run is a preview, and reading one must not move
      // a trip. Session bootstrap and the CSRF reissue are the only writes the
      // shell makes.
      expect(
        writes.filter((w) => !w.includes('/session') && !w.includes('/demo')),
      ).toEqual([]);
    } finally {
      // Removed by reference, never with removeAllListeners: the server is
      // shared and that would drop other files' listeners too.
      server.events.removeListener('request:start', record);
    }
  });

  it('says the itinerary is untouched when the run failed', async () => {
    runIs('FAILED', {
      failure: { code: 'LOCK_CONFLICT', message: 'locked', retryable: false },
    });
    renderRun();
    // "계산하지 못했어요" alone reads as though something might have
    // half-happened. The screen states that it did not.
    expect(await screen.findByText(copy['run.unchanged'])).toBeInTheDocument();
  });
});

describe('FE-502-T2 the screen renders each of its states', () => {
  it('shows a queued run as waiting, not as finished', async () => {
    runIs('QUEUED');
    renderRun();
    expect(await screen.findByText(copy['run.queued'])).toBeInTheDocument();
    expect(screen.queryByText(copy['run.ready'])).toBeNull();
  });

  it('names the two things it is actually checking', async () => {
    // FCR-005: P0 has no route provider, so route and travel-time copy was
    // removed from this frame. A `경로 계산` step would describe work that
    // nothing performs.
    runIs('RUNNING');
    renderRun();
    expect(await screen.findByText(copy['run.step.crowd'])).toBeInTheDocument();
    expect(screen.getByText(copy['run.step.locks'])).toBeInTheDocument();
    expect(screen.queryByText(/route|경로/i)).toBeNull();
  });

  it('distinguishes each failure code rather than showing one generic error', async () => {
    for (const code of [
      'TRIP_CHANGED',
      'DATA_CHANGED',
      'LOCK_CONFLICT',
      'ROUTE_UNAVAILABLE',
      'NO_IMPROVEMENT',
      'APPLY_FAILED',
    ] as const) {
      runIs('FAILED', { failure: { code, message: code, retryable: false } });
      const view = renderRun();
      expect(await screen.findByText(copy[`run.failure.${code}`])).toBeInTheDocument();
      view.unmount();
    }
  });

  it('offers a recompute only when the contract says the failure is retryable', async () => {
    runIs('FAILED', {
      failure: { code: 'NO_IMPROVEMENT', message: 'x', retryable: false },
    });
    const view = renderRun();
    await screen.findByText(copy['run.failure.NO_IMPROVEMENT']);
    // retryable:false means asking again cannot help; offering it would invite
    // the user to burn a run on the same answer.
    expect(screen.queryByRole('button', { name: copy['run.recompute'] })).toBeNull();
    view.unmount();

    runIs('FAILED', {
      failure: { code: 'TRIP_CHANGED', message: 'x', retryable: true },
    });
    renderRun();
    await screen.findByText(copy['run.failure.TRIP_CHANGED']);
    expect(
      screen.getByRole('button', { name: copy['run.recompute'] }),
    ).toBeInTheDocument();
  });

  it('separates a missing run from a failed request', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () => {
        polls += 1;
        return problemResponse('NOT_FOUND');
      }),
    );
    renderRun();
    expect(await screen.findByText(copy['run.notFound'])).toBeInTheDocument();
    // Nothing to retry: the run does not exist.
    expect(screen.queryByRole('button', { name: copy['optimize.retry'] })).toBeNull();
  });

  it('offers a retry when the request itself failed', async () => {
    server.use(http.get(`${API_BASE}/optimizations/:runId`, () => HttpResponse.error()));
    renderRun();
    expect(await screen.findByText(copy['run.error'])).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: copy['optimize.retry'] }),
    ).toBeInTheDocument();
  });

  it('does not claim a preview exists before FE-503 can show one', async () => {
    // proposals is empty until BA-051 computes them. A before/after built from
    // nothing would be the unsourced comparison invariant 8 forbids.
    runIs('READY');
    renderRun();
    expect(await screen.findByText(copy['run.readyPending'])).toBeInTheDocument();
  });
});

describe('FE-502-T3 leaving is navigation, not cancellation', () => {
  it('says the run continues when the user goes back', async () => {
    // FCR-014: P0 has no cancel operation, so the screen must not imply one.
    runIs('RUNNING');
    renderRun();
    expect(await screen.findByText(copy['run.keepsRunning'])).toBeInTheDocument();
  });

  it('never offers a control that claims to cancel the run', async () => {
    runIs('RUNNING');
    renderRun();
    await screen.findByText(copy['run.running']);
    expect(screen.queryByRole('button', { name: /cancel|취소/i })).toBeNull();
  });

  it('returns to the trip without waiting for the run', async () => {
    runIs('RUNNING');
    const user = userEvent.setup();
    renderRun();
    const [back] = await screen.findAllByRole('button', { name: copy['run.leave'] });
    await user.click(back as HTMLElement);
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'trip-heading',
      );
    });
  });

  it('reaches the back control by keyboard', async () => {
    runIs('RUNNING');
    renderRun();
    const [back] = await screen.findAllByRole('button', { name: copy['run.leave'] });
    (back as HTMLElement).focus();
    expect(back).toHaveFocus();
  });
});
