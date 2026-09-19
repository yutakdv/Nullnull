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
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { describe, expect, it } from 'vitest';
import { optimizationFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { TripAppliedPanel } from '../TripAppliedPanel.js';

// Read from the catalogue rather than typed out: a copy change should move
// this test, not break it (trip-screen.test.tsx does the same).
const copy = messages['en-US'];
const run = optimizationFixtures.runApplied;
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
