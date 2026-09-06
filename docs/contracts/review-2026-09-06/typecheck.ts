import createClient, { type Middleware } from 'openapi-fetch';
import type { components, operations, paths } from './schema';

type Create = components['schemas']['CreateOptimizationRequest'];
type Initial = operations['decideOptimization']['responses'][200]['content']['application/json'];
type History = components['schemas']['OptimizationDecision'];
type Provenance = components['schemas']['DataProvenance'];
type Run = components['schemas']['OptimizationRun'];

const item: Create = { scope: 'ITEM', targetItemId: 'fixture-id', inputTripVersion: 7, includeCandidates: false };
// @ts-expect-error ITEM must have targetItemId.
const missingTarget: Create = { scope: 'ITEM', inputTripVersion: 7, includeCandidates: false };
// @ts-expect-error ITEM cannot carry a DAY target.
const wrongTarget: Create = { scope: 'ITEM', targetDate: '2026-09-07', inputTripVersion: 7, includeCandidates: false };

function decide(value: Initial) {
  switch (value.decision) {
    case 'APPLY': return value.revertUntil;
    case 'KEEP': {
      // @ts-expect-error KEEP does not contain revertUntil.
      const forbidden = value.revertUntil;
      return value.decidedAt;
    }
    default: { const exhaustive: never = value; return exhaustive; }
  }
}
function fromHistory(value: History) {
  if (value.decision === 'REVERT') {
    // @ts-expect-error REVERT is never an initial-decision response.
    const invalid: Initial = value;
    return value.revertedDecisionId;
  }
  return decide(value);
}

const unknownCredit: Provenance['attributionShort'] = undefined;
const nullCredit: Provenance['attributionShort'] = null;
const nullableObservedAt: Provenance['observedAt'] = null;
const availability: Run['revertAvailability'] = 'AVAILABLE';
// @ts-expect-error Arbitrary client clock states are outside the contract.
const invalidAvailability: Run['revertAvailability'] = 'CLIENT_CLOCK_READY';
const errorCode: components['schemas']['Problem']['code'] = 'REVERT_WINDOW_EXPIRED';
// @ts-expect-error Error codes must be generated, not free strings.
const unknownError: components['schemas']['Problem']['code'] = 'UNREVIEWED_CODE';

// Type-only demonstration of a single wrapper; no request is sent by this file.
const client = createClient<paths>({ baseUrl: '/api/v1', credentials: 'same-origin' });
const middleware: Middleware = {
  onRequest({ request }) { return request; },
  onResponse({ response }) { return response; },
};
client.use(middleware);
void [item, missingTarget, wrongTarget, fromHistory, unknownCredit, nullCredit, nullableObservedAt,
  availability, invalidAvailability, errorCode, unknownError];
