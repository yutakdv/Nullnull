// @vitest-environment happy-dom
//
// TripAppliedPanel — the DATA half of the undo panel (S09-3, FE-504).
//
// Why a second file rather than more cases in applied-panel.test.tsx: that one
// renders `AppliedPanel`, which is presentational and takes its state as
// props. This one renders `TripAppliedPanel`, which decides what those props
// ARE from two server reads. Nothing was rendering that wrapper — measured,
// the only reference outside its own file was TripScreen's mount — so every
// line between `run.data` and the screen was covered by reading the code
// (#279).
//
// That gap is why #279 하4 could hide. `shouldReadRun` refused to fetch the run
// for a REVERT, so `run.data` stayed undefined, `TripAppliedPanel` returned
// null at its first guard, and a REVERTED panel that exists in full — Figma
// `724:4730`, copy in both locales — was unreachable. A unit test on
// `shouldReadRun` proves the gate opens; only rendering proves the panel
// arrives.
//
// msw rather than mocked hooks, following trip-screen.test.tsx: the wrapper's
// job IS the two requests and their order, and hooks replaced by stubs would
// assert the plan rather than the behaviour.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { describe, expect, it } from 'vitest';
import { optimizationFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { TripAppliedPanel } from '../TripAppliedPanel.js';

// Read from the catalogue rather than typed out: a copy change should move
// this test, not break it (trip-screen.test.tsx does the same).
const copy = messages['en-US'];
const run = optimizationFixtures.runApplied;
// `trip.applied.revert` carries a {from} placeholder, so the catalogue string
// is not what reaches the DOM. Substituted here the same way the panel does it:
// inputTripVersion is the version the undo goes back to.
const REVERT_LABEL = copy['trip.applied.revert'].replace(
  '{from}',
  String(optimizationFixtures.runApplied.inputTripVersion),
);
const TRIP_ID = run.tripId;

/** The history row the panel reads first; `decision` is what this file varies. */
function historyRow(decision: 'APPLY' | 'KEEP' | 'REVERT', decidedAt: string) {
  return {
    runId: run.id,
    tripId: TRIP_ID,
    tripTitle: '서울 가을 여행',
    scope: 'TRIP',
    status: 'DECIDED',
    decision,
    runLink: `/trip/${TRIP_ID}/optimizations/${run.id}`,
    queuedAt: run.queuedAt,
    decidedAt,
  };
}

/**
 * Serves the two reads the wrapper makes, and counts the second.
 *
 * The count is the point of the REVERT case: "the panel is missing" and "the
 * panel asked for nothing to draw it from" look identical on screen, and only
 * the second is the defect #279 하4 fixed.
 */
function serve(row: ReturnType<typeof historyRow>, detail: JsonBodyType) {
  const runReads: string[] = [];
  server.use(
    http.get(`${API_BASE}/optimizations`, () =>
      HttpResponse.json({ items: [row], nextCursor: null }),
    ),
    http.get(`${API_BASE}/optimizations/:runId`, ({ params }) => {
      runReads.push(String(params.runId));
      return HttpResponse.json(detail);
    }),
  );
  return runReads;
}

function renderPanel() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <TripAppliedPanel etag='"3"' tripId={TRIP_ID} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

// A revert of the applied decision, as the server reports it afterwards: the
// APPLY row stays and a REVERT row joins it, so the panel can say what was
// undone and when. revertAvailability turns REVERTED, which is the field the
// panel renders from — never the clock (AppliedPanel's own header).
const revertedDetail = {
  ...run,
  revertAvailability: 'REVERTED',
  decisions: [
    ...run.decisions,
    {
      id: '018f4a60-4d31-7e22-8c03-5f6a7b8c9d02',
      decision: 'REVERT',
      decidedAt: '2026-10-02T02:20:00Z',
      revertUntil: null,
      proposalId: null,
      resultingTripVersion: 4,
    },
  ],
};

describe('TripAppliedPanel', () => {
  it('draws the applied panel from what the two reads return', async () => {
    serve(historyRow('APPLY', '2026-10-02T01:13:40Z'), run);
    renderPanel();

    // The badge proves the whole chain ran: history → run → AppliedPanel.
    expect(
      await screen.findByText(copy['trip.applied.badge.available']),
    ).toBeInTheDocument();
  });

  it('still draws the panel once the optimization has been reverted', async () => {
    // #279 하4. Before the fix the wrapper skipped the second read for a
    // REVERT, so this rendered nothing at all — the panel vanished at the exact
    // moment it was meant to confirm the undo had worked.
    const runReads = serve(historyRow('REVERT', '2026-10-02T02:20:00Z'), revertedDetail);
    renderPanel();

    expect(
      await screen.findByText(copy['trip.applied.badge.reverted']),
    ).toBeInTheDocument();
    // Asserted separately from the text: a panel could in principle be drawn
    // from cached data, and it is the REQUEST that 하4 restored.
    expect(runReads, 'the run should be read for a REVERT too').toEqual([run.id]);
  });

  // FE-505-T2's `error` and `offline` clauses, at the level that owns them.
  //
  // applied-panel.test.tsx proves the `failure` prop renders; these prove the
  // wrapper DERIVES it, which is the half that was missing entirely:
  // `revert.isError` was read nowhere, so a failed undo looked exactly like an
  // unpressed one. The two cases differ in whether a retry is offered, and that
  // answer comes from problem-policy.ts rather than from this file.
  describe('FE-505-T2 a failed revert is reported rather than swallowed', () => {
    /**
     * Serves the two reads, then fails the revert.
     *
     * `problemResponse` rather than a hand-written body: `isProblem` requires
     * nine fields (instance, requestId and retryable among them) and a body
     * missing any of them is NOT a Problem, so the wrapper reads no code and
     * falls to the generic branch. Writing the JSON by hand here silently
     * tested that fallback instead of the code path — measured, it did.
     */
    function serveFailingRevert(code: 'INTERNAL_ERROR' | 'REVERT_WINDOW_EXPIRED' | null) {
      serve(historyRow('APPLY', '2026-10-02T01:13:40Z'), run);
      const attempts: string[] = [];
      server.use(
        http.post(
          `${API_BASE}/optimization-decisions/:decisionId/revert`,
          ({ request }) => {
            attempts.push(request.headers.get('Idempotency-Key') ?? '');
            // A transport failure: no response at all, so no Problem to read.
            if (code === null) return HttpResponse.error();
            return problemResponse(code);
          },
        ),
      );
      return attempts;
    }

    it('reports a retryable failure and replays the SAME command', async () => {
      // A 503-shaped failure: the command can be sent again. The assertion that
      // matters is the KEY — a retry that minted a fresh one would be a second
      // revert of the same decision, which invariant 6 forbids and which the
      // server would answer as a new command rather than a replay.
      const attempts = serveFailingRevert('INTERNAL_ERROR');
      const user = userEvent.setup();
      renderPanel();

      await user.click(await screen.findByRole('button', { name: REVERT_LABEL }));

      const retry = await screen.findByRole('button', {
        name: copy['trip.applied.retry'],
      });
      expect(
        screen.getByRole('alert'),
        'the traveller must be told the itinerary did not change',
      ).toHaveTextContent(copy['trip.applied.failed.retryable']);
      expect(retry).toBeEnabled();

      await user.click(retry);
      await expect.poll(() => attempts.length).toBe(2);
      expect(attempts[0], 'the retry must replay the first key').toBe(attempts[1]);
      expect(attempts[0]).not.toBe('');
    });

    it('offers no retry when the undo window has closed', async () => {
      // REVERT_WINDOW_EXPIRED is retry:'none' and recovery:'none'. Without this
      // case a fix that reported every failure identically would look complete.
      const attempts = serveFailingRevert('REVERT_WINDOW_EXPIRED');
      const user = userEvent.setup();
      renderPanel();

      await user.click(await screen.findByRole('button', { name: REVERT_LABEL }));

      const retry = await screen.findByRole('button', {
        name: copy['trip.applied.retry'],
      });
      expect(screen.getByRole('alert')).toHaveTextContent(
        copy['trip.applied.failed.expired'],
      );
      expect(retry, 'a press the server has already refused for good').toBeDisabled();

      await user.click(retry);
      // Still one: the first attempt, and nothing the disabled button added.
      await expect.poll(() => attempts.length).toBe(1);
    });

    it('reports a transport failure the same way, with the retry kept', async () => {
      // The `offline` clause. The request never reaches the server, so there is
      // no Problem and no code — `isProblem` is false — and the wrapper must
      // read that as retryable rather than defaulting to the terminal branch.
      serveFailingRevert(null);
      const user = userEvent.setup();
      renderPanel();

      await user.click(await screen.findByRole('button', { name: REVERT_LABEL }));

      expect(
        await screen.findByRole('button', { name: copy['trip.applied.retry'] }),
      ).toBeEnabled();
      expect(screen.getByRole('alert')).toHaveTextContent(
        copy['trip.applied.failed.retryable'],
      );
    });
  });

  it('draws nothing for a KEEP, which changed no schedule', async () => {
    // The other side of the same gate. Widening it to every decision would
    // make the trip screen issue a second request on visits with nothing to
    // undo, which is what `shouldReadRun` exists to avoid.
    const runReads = serve(historyRow('KEEP', '2026-10-02T01:13:40Z'), run);
    const { container } = renderPanel();

    await expect.poll(() => runReads).toEqual([]);
    expect(container).toBeEmptyDOMElement();
  });
});
