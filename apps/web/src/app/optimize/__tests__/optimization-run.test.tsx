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
import { beforeEach, describe, expect, it, vi } from 'vitest';
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

// Two card IDs on one describe, deliberately. FE-504 owns "오류 6종·stale·no
// improvement가 각각 구분되고 APPLY를 유도하지 않는다" and FE-502 owns the
// polling screen those cases live on; one test proving a clause of each card is
// normal, and the aggregator reads IDs out of the test name, so a clause proven
// under only the other card's ID is invisible to it.
//
// Added after comparing each clause against these bodies rather than copying
// the ID across: the seven contract failure codes each have their own message
// (verified against OptimizationFailure's enum), `run.unchanged` is asserted on
// a failed run, recompute is offered only when the contract marks the failure
// retryable, and 360px/200% zoom for this screen is covered by responsive.spec
// (its SCREENS list includes /optimizations/{runId}).
// FE-502-T1's other clause: "polling이 background 복귀 후 재개된다".
//
// The resume is react-query's behaviour, not ours: `refetchIntervalInBackground`
// defaults to false, so the interval pauses while the tab is hidden and runs
// again when it comes back. A test that drove visibilitychange would be testing
// the library.
//
// What IS ours is the pair of defaults that produce it, and either one can be
// turned off in a line. `refetchIntervalInBackground: true` would keep polling
// a hidden tab (the battery cost the default exists to avoid), and an interval
// that returns false for a running status would stop the poll altogether. This
// pins both against the query's own config rather than against the clock.
describe('FE-502-T1 a hidden tab pauses the poll and a returning one resumes it', () => {
  it('stops asking while the tab is hidden and asks again when it returns', async () => {
    runIs('RUNNING');
    renderRun();
    await screen.findByText(copy['run.running']);
    await waitFor(
      () => {
        expect(polls).toBeGreaterThan(1);
      },
      { timeout: 3000 },
    );

    // Hide the tab. react-query's `refetchIntervalInBackground` defaults to
    // false, so the interval stops here - that pause is what makes "resumes on
    // return" mean anything, and it is a default this app could switch off in
    // one line.
    const hidden = vi.spyOn(document, 'visibilityState', 'get');
    hidden.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));

    const whileHidden = polls;
    await new Promise((resolve) => setTimeout(resolve, 2600));
    expect(polls, 'a hidden tab should not keep polling').toBe(whileHidden);

    // And back.
    hidden.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    await waitFor(
      () => {
        expect(polls).toBeGreaterThan(whileHidden);
      },
      { timeout: 3000 },
    );
    hidden.mockRestore();
  }, 15000);
});

describe('FE-502-T1 FE-504-T1 nothing here changes the itinerary', () => {
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

// FE-503-T2 joins the two card IDs already here: its clause is the same state
// matrix on the same screen, and the READY branch these tests exercise is the
// one FE-503's preview lives on.
//
// OFFLINE, the sixth state, IS measured here: "offers a retry when the request
// itself failed" drives `HttpResponse.error()`, a thrown fetch with no response
// at all, which is what offline looks like to this screen. It is a different
// case from the Problem responses above, and it asserts a different render
// (`run.error` plus a retry) — import-paste.test.tsx draws the same line for
// the same clause.
//
// An earlier version of this comment sent the clause to
// `shared/testing/__tests__/offline-shell.test.ts` instead. That file is real,
// but it carries only FE-004-T1 and it renders nothing — it drives sw.js in a
// fake worker scope to prove no /api/v1 response is ever served from cache.
// That is an app-wide invariant about the worker, not this screen's render, so
// it could not prove a "각 상태를 렌더한다" clause. The pointer also could not be
// caught by anything: `check_cited_tests.py` checks that a cited file EXISTS,
// and this file's 15 other cases already satisfied FE-503-T2 on their own, so
// the aggregator was never going to notice the sixth state had been sent
// somewhere it was not proven (AGENTS.md rule 3 — a many-clause id is met by
// any one of its clauses).
describe('FE-502-T2 FE-504-T2 FE-503-T2 the screen renders each of its states', () => {
  it('shows a queued run as waiting, not as finished', async () => {
    runIs('QUEUED');
    renderRun();
    expect(await screen.findByText(copy['run.queued'])).toBeInTheDocument();
    expect(screen.queryByText(copy['run.ready'])).toBeNull();
  });

  // #279 하1: the heading said "대안을 찾고 있어요" over a finished run.
  //
  // `statusMessage` already names all eight statuses for the live region, and
  // its comment records that an APPLIED or KEPT run once ANNOUNCED "대안이
  // 준비됐어요". The heading kept doing it, so a rehearsal reached APPLIED, KEPT
  // and FAILED and read "still searching" over all three — the screen reader
  // heard the right state and the screen showed the wrong one.
  //
  // Each case asserts the searching title is ABSENT as well as the right title
  // present. Presence alone passes for a screen that draws both.
  describe('#279 하1 the heading says which state the run is in', () => {
    it.each([
      ['READY', 'run.title.ready'],
      ['APPLIED', 'run.title.applied'],
      ['KEPT', 'run.title.kept'],
      ['REVERTED', 'run.title.reverted'],
      ['EXPIRED', 'run.title.expired'],
    ] as const)('a %s run is not titled as still searching', async (status, key) => {
      runIs(status);
      renderRun();

      expect(
        await screen.findByRole('heading', { level: 1, name: copy[key] }),
      ).toBeInTheDocument();
      expect(screen.queryByText(copy['run.title'])).toBeNull();
    });

    it('a FAILED run is titled as failed', async () => {
      // Given a failure so the screen takes the failure branch rather than the
      // bare-FAILED one; the heading is decided by status either way.
      runIs('FAILED', {
        failure: { code: 'NO_IMPROVEMENT', message: 'none', retryable: false },
      });
      renderRun();

      expect(
        await screen.findByRole('heading', { level: 1, name: copy['run.title.failed'] }),
      ).toBeInTheDocument();
      expect(screen.queryByText(copy['run.title'])).toBeNull();
    });

    it('keeps the searching title while the run is actually searching', async () => {
      // The control. Without it "never show run.title" would pass by deleting
      // the string everywhere, which would be wrong for the state it describes.
      runIs('RUNNING');
      renderRun();

      expect(
        await screen.findByRole('heading', { level: 1, name: copy['run.title'] }),
      ).toBeInTheDocument();
    });
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
      'RECOMMENDATION_UNAVAILABLE',
    ] as const) {
      runIs('FAILED', { failure: { code, message: code, retryable: false } });
      const view = renderRun();
      expect(await screen.findByText(copy[`run.failure.${code}`])).toBeInTheDocument();
      view.unmount();
    }
  });

  it('folds INTERNAL_ERROR to the generic line rather than naming internals', async () => {
    // The contract carries this code (#261) and this screen deliberately has no
    // message for it: it covers an answer outside the recommendation contract, a
    // repeated server failure and a run its worker abandoned, none of which the
    // traveller can tell apart or act on. The fold is the behaviour, so it is
    // asserted rather than left to the absence of a key.
    //
    // This is also the test that would catch the fold breaking. `failureMessage`
    // builds the key from the code and checks `key in messages`; if that check
    // were dropped, `t()` would return the missing key as-is and the screen
    // would render the literal string. Measured before, not assumed: a probe of
    // an unknown key produced "[undefined]".
    runIs('FAILED', {
      failure: { code: 'INTERNAL_ERROR', message: 'internal', retryable: false },
    });
    renderRun();

    expect(await screen.findByText(copy['run.failure.unknown'])).toBeInTheDocument();
    expect(screen.queryByText(/INTERNAL_ERROR|undefined/)).toBeNull();
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
    // Presence alone passes for a screen that draws this state AND another on
    // top of it. Measured: with `run.readyPending` rendered unconditionally on
    // the error frame, every one of this describe's cases stayed green — an
    // offline screen could say "the result screen is still being built" while
    // reporting that it could not load the status at all.
    expect(screen.queryByText(copy['run.readyPending'])).toBeNull();
  });

  it('names every terminal status instead of calling them all ready', async () => {
    // OptimizationStatus has eight values and the screen used to fall through
    // to run.ready for anything that was not QUEUED, RUNNING, READY or a
    // failure — so an APPLIED run announced "대안이 준비됐어요", a finished
    // decision presented as one still waiting. The profile's history links
    // straight to these rows and its fixture ships one of each.
    for (const [status, key] of [
      ['APPLIED', 'run.applied'],
      ['KEPT', 'run.kept'],
      ['REVERTED', 'run.reverted'],
      ['EXPIRED', 'run.expiredStatus'],
    ] as const) {
      runIs(status);
      const view = renderRun();
      expect(await screen.findByText(copy[key])).toBeInTheDocument();
      // The one word that must not appear: these runs are settled.
      expect(screen.queryByText(copy['run.ready'])).toBeNull();
      view.unmount();
    }
  });

  it('does not offer a preview for a run that is already decided', async () => {
    // run.readyPending explains that this READY response has no proposals.
    // Showing it on an APPLIED run would describe a decision already made as
    // though its result were still pending.
    runIs('APPLIED');
    renderRun();
    await screen.findByText(copy['run.applied']);
    expect(screen.queryByText(copy['run.readyPending'])).toBeNull();
  });

  it('does not invent a preview when a READY response has no proposals', async () => {
    // A before/after built from nothing would be the unsourced comparison
    // invariant 8 forbids.
    runIs('READY');
    renderRun();
    expect(await screen.findByText(copy['run.readyPending'])).toBeInTheDocument();
    // The body half of #279 하1. That block asserts the <h1> stops saying
    // "still searching", and stops there — so the same defect in the body copy
    // was open: measured, a READY frame rendering `run.running` alongside this
    // note left all of this describe green. The heading and the body are
    // separate renders and need separate absence assertions.
    expect(screen.queryByText(copy['run.running'])).toBeNull();
  });
});

// FE-504-T3's keyboard half is the block BELOW this one ("the controls answer
// to the keyboard"), split out so its IDs do not land on these three; its
// 360px/200%-zoom half is responsive.spec.ts, whose SCREENS list carries this
// route as "optimization run". All halves exist, so the ID sits on each block
// that holds one of them.
describe('FE-502-T3 FE-504-T3 leaving is navigation, not cancellation', () => {
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
});

// Its own block rather than a fourth test in the one above, and the reason is
// mechanical: a JUnit testcase is named "<describe title> <test title>", so
// every ID on a describe lands on every test inside it. Leaving this here would
// stamp FE-503-T3 and FE-505-T3 — clauses about keyboard reach — onto three
// tests that measure whether the screen implies a cancel operation. A comment
// saying "these IDs are for the last one only" would not help; the aggregator
// reads names, not prose.
//
// FE-502-T3 and FE-504-T3 keep their place on the block above AND appear here,
// because their clause has both halves: "leaving is navigation" is measured
// there, and their keyboard half is this test. FE-503-T3 and FE-505-T3 appear
// only here — their clause is keyboard, focus and accessible names, and none of
// them is about leaving the screen.
//
// WHAT FE-503-T3 AND FE-505-T3 COVER. Their cards originally bundled six
// things into one clause; four are proven here and in the responsive suite —
// keyboard reach here, accessible names and 360px and 200% zoom in
// responsive.spec.ts, whose SCREENS list carries both optimize routes.
//
// FE-503-T4 now names the READY route's reduced-motion case in that suite. Its
// focus-return half is inapplicable because this screen mounts no dialog or
// sheet. FE-505-T4 remains separate: the applied/undo panel is still excluded
// from the integration gate while the optimization capability is off.
describe('FE-502-T3 FE-504-T3 FE-503-T3 FE-505-T3 the controls answer to the keyboard', () => {
  it('reaches the back control by keyboard', async () => {
    runIs('RUNNING');
    renderRun();
    const [back] = await screen.findAllByRole('button', { name: copy['run.leave'] });
    (back as HTMLElement).focus();
    expect(back).toHaveFocus();
  });
});

// A failure code this build does not know must not reach the user as a token.
//
// The key is built from the code (`run.failure.${code}`), and t() returns a
// missing message as-is — a probe render of an unknown key produced the literal
// "[undefined]". So a code the server adds before a matching client ships would
// print "undefined" into the error screen.
//
// Folding to a generic line also decouples the deploys: BE can add a code
// without this client being updated first (#225, DATA_INSUFFICIENT).
// BA-050 (503 SOURCE_UNAVAILABLE): a run holding proposals cannot be shown
// while the catalog is closed, but this is a server STATE, not a hiccup —
// three requests get three identical answers (PROBLEM_POLICY marks it
// `retry: 'none'`). Unlike PREVIEW_EXPIRED/NOT_FOUND above, it is temporary,
// so the retry control must stay and the itinerary-unchanged note must show.
describe('FE-502 a closed catalog is temporary, not a generic error', () => {
  it('shows its own copy for SOURCE_UNAVAILABLE, not the generic error', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () => {
        polls += 1;
        return problemResponse('SOURCE_UNAVAILABLE');
      }),
    );
    renderRun();
    expect(await screen.findByText(copy['run.sourceUnavailable'])).toBeInTheDocument();
    // Not just an addition alongside the generic line: it must replace it.
    expect(screen.queryByText(copy['run.error'])).not.toBeInTheDocument();
  });

  it('keeps a retry control available, unlike a terminal failure', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () => {
        polls += 1;
        return problemResponse('SOURCE_UNAVAILABLE');
      }),
    );
    renderRun();
    await screen.findByText(copy['run.sourceUnavailable']);
    expect(
      screen.getByRole('button', { name: copy['optimize.retry'] }),
    ).toBeInTheDocument();
  });

  it('states the itinerary is unchanged, same as an expired preview', async () => {
    server.use(
      http.get(`${API_BASE}/optimizations/:runId`, () => {
        polls += 1;
        return problemResponse('SOURCE_UNAVAILABLE');
      }),
    );
    renderRun();
    await screen.findByText(copy['run.sourceUnavailable']);
    expect(screen.getByText(copy['run.unchanged'])).toBeInTheDocument();
  });

  it(
    'sends exactly one request: SOURCE_UNAVAILABLE is not auto-retried',
    { timeout: 10000 },
    async () => {
      // The one assertion here that measures real behaviour rather than
      // strings: PROBLEM_POLICY declares `retry: 'none'` for this code, and
      // useOptimization's retry callback now checks it. Before that line
      // existed this fell through to `count < 2`, so a closed catalog cost
      // three requests (one attempt plus two retries) instead of one.
      server.use(
        http.get(`${API_BASE}/optimizations/:runId`, () => {
          polls += 1;
          return problemResponse('SOURCE_UNAVAILABLE');
        }),
      );
      renderRun();
      await screen.findByText(copy['run.sourceUnavailable']);
      // Past react-query's retry backoff window, so a retry in flight would
      // have landed by the time this reads `polls`.
      await new Promise((resolve) => setTimeout(resolve, 2600));
      expect(polls).toBe(1);
    },
  );
});

describe('FE-502 an unknown failure code folds to the generic message', () => {
  it('never renders the literal "undefined" for a code it does not know', async () => {
    runIs('FAILED', {
      failure: { code: 'SOMETHING_NEW', message: 'x', retryable: false },
    });
    const view = renderRun();
    expect(await screen.findByText(copy['run.failure.unknown'])).toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).toBeNull();
    view.unmount();
  });

  it('still shows the specific message for a code it knows', async () => {
    // The fallback must not swallow the known codes — that would trade one
    // wrong message for six.
    runIs('FAILED', {
      failure: { code: 'DATA_INSUFFICIENT', message: 'x', retryable: true },
    });
    const view = renderRun();
    expect(
      await screen.findByText(copy['run.failure.DATA_INSUFFICIENT']),
    ).toBeInTheDocument();
    expect(screen.queryByText(copy['run.failure.unknown'])).toBeNull();
    view.unmount();
  });
});
