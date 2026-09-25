import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { messages } from '../src/i18n/messages.js';

// BA-092-T16: the core flow's decisions can be made by keyboard alone. Trip
// creation and itinerary edits are measured against the real API in
// keyboard-flow.spec.ts; an optimization decision is not, because the gate
// runs with FEATURE_OPTIMIZATION_ITEM off and no real run ever reaches READY
// there. So this file serves the approved examples with page.route, as
// live-replay-matrix.spec.ts does, and the same READY run reaches the page in
// the mock run and in the gate. Nothing here clicks: every step is Tab or
// Enter, and the request the server would have received is what is asserted.
//
// REVERT is not here because the product has no control for it: the trip
// screen's undo block was retired (applied-panel.spec.ts keeps it retired), so
// there is no keyboard path to measure.
test.use({ serviceWorkers: 'block' });

const fixture = (path: string) =>
  readFileSync(
    new URL(`../../../packages/contracts/fixtures/${path}`, import.meta.url),
    'utf8',
  );

interface Decision {
  id: string;
  runId: string;
  proposalId: string;
  decision: string;
}
interface Run {
  id: string;
  tripId: string;
  status: string;
  inputTripVersion: number;
  proposals: { id: string }[];
  decisions: Decision[];
}

const READY = JSON.parse(fixture('optimizations/run-ready.json')) as Run;
const TRIP = fixture('trips/trip-detail-scheduled.json');
const TRIP_VERSION = (JSON.parse(TRIP) as { id: string; version: number }).version;
const CSRF = fixture('session/csrf-token.json');
const EN = messages['en-US'];

interface Sent {
  path: string;
  ifMatch: string | null;
  idempotencyKey: string | null;
  body: unknown;
}

/**
 * Serves the run screen from the approved examples.
 * `run` is read on every request so a decision can move it, as the server's
 * would. Returns the commands sent and the calls this file does not serve.
 */
async function serve(page: Page, initial: Run) {
  let run = structuredClone(initial);
  const sent: Sent[] = [];
  const unexpected: string[] = [];
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const method = request.method();
    const json = (body: string, headers: Record<string, string> = {}) =>
      route.fulfill({ status: 200, contentType: 'application/json', headers, body });
    if (method === 'POST' && pathname === '/api/v1/session/csrf') return json(CSRF);
    // The approved owner examples carry ko-KR and would override the locale.
    if (method === 'GET' && pathname === '/api/v1/me') return route.abort();
    if (method === 'GET' && pathname === `/api/v1/trips/${run.tripId}`)
      return json(TRIP, { ETag: `"${String(TRIP_VERSION)}"` });
    if (method === 'GET' && pathname === `/api/v1/optimizations/${run.id}`)
      return json(JSON.stringify(run));
    const record = () =>
      sent.push({
        path: pathname,
        ifMatch: request.headers()['if-match'] ?? null,
        idempotencyKey: request.headers()['idempotency-key'] ?? null,
        body: request.postDataJSON(),
      });
    if (method === 'POST' && pathname === `/api/v1/optimizations/${run.id}/decisions`) {
      record();
      const { decision, proposalId } = request.postDataJSON() as {
        decision: 'APPLY' | 'KEEP';
        proposalId: string;
      };
      const example = JSON.parse(
        fixture(`optimizations/decision-${decision.toLowerCase()}.json`),
      ) as Decision;
      const made = { ...example, runId: run.id, proposalId };
      run = {
        ...run,
        status: decision === 'APPLY' ? 'APPLIED' : 'KEPT',
        decisions: [made],
      };
      return json(JSON.stringify(made));
    }
    unexpected.push(`${method} ${pathname}`);
    return route.abort();
  });
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
  });
  return { sent, unexpected };
}

/**
 * Presses Tab until `target` has focus, as a keyboard user would reach it.
 * Fails rather than clicking when the control is not in the Tab order.
 */
async function tabTo(page: Page, target: Locator, limit = 60): Promise<void> {
  await expect(target).toBeVisible();
  for (let press = 0; press < limit; press += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`not reached by Tab within ${String(limit)} presses`);
}

test.describe('BA-092-T16 an optimization is decided by keyboard alone', () => {
  for (const decision of ['APPLY', 'KEEP'] as const) {
    test(`BA-092-T16 ${decision} is chosen and sent by keyboard alone`, async ({
      page,
    }) => {
      const { sent, unexpected } = await serve(page, READY);
      await page.goto(`/trip/${READY.tripId}/optimizations/${READY.id}`);

      const control = page.getByRole('button', {
        name: decision === 'APPLY' ? EN['decision.apply'] : EN['decision.keep'],
      });
      await tabTo(page, control);
      await page.keyboard.press('Enter');

      // The screen says what happened, and the server received exactly one
      // command: this decision, on the proposal the page had chosen, guarded
      // by the trip's ETag.
      await expect(
        page.getByText(decision === 'APPLY' ? EN['run.applied'] : EN['run.kept']),
      ).toBeVisible();
      expect(sent).toHaveLength(1);
      expect(sent[0]?.body).toEqual({
        decision,
        proposalId: READY.proposals[0]?.id,
      });
      expect(sent[0]?.ifMatch).toBe(`"${String(TRIP_VERSION)}"`);
      expect(sent[0]?.idempotencyKey).toBeTruthy();
      expect(unexpected, 'calls this file does not serve').toEqual([]);
    });
  }
});
