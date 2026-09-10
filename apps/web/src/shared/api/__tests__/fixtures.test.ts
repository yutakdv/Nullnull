// Every fixture in @nullnull/contracts must satisfy docs/api/openapi.yaml.
//
// TEST_STRATEGY.md:113 forbids FE from hand-writing a parallel model of the
// contract. FE authored these fixtures anyway to unblock FE-003 (see
// packages/contracts/README.md), so this test is what keeps that honest: a
// fixture that drifts from the schema fails the build.
//
// The validator setup mirrors docs/contracts/review-2026-09-06/verify.cjs,
// which BE already runs against this same spec.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { load } from 'js-yaml';
import { describe, expect, it } from 'vitest';
import {
  optimizationFixtures,
  placeFixtures,
  problemFixtures,
  sessionFixtures,
  tripFixtures,
} from '@nullnull/contracts';

// vitest runs with apps/web as the root, so the spec is two levels up.
const specPath = resolve(process.cwd(), '../../docs/api/openapi.yaml');
const api = load(readFileSync(specPath, 'utf8')) as {
  components: { schemas: Record<string, unknown> };
};

const ajv = new Ajv2020({ strict: false, allErrors: true, logger: false });
addFormats(ajv);
ajv.addFormat('int64', true);

/** Compile a validator bound to one component schema, as verify.cjs does. */
function validatorFor(schemaName: string) {
  return ajv.compile({
    components: api.components,
    $ref: `#/components/schemas/${schemaName}`,
  });
}

describe('contract fixtures satisfy the OpenAPI schema', () => {
  const validateProblem = validatorFor('Problem');

  it.each(Object.entries(problemFixtures))('Problem fixture %s', (code, fixture) => {
    const valid = validateProblem(fixture);
    expect(validateProblem.errors ?? []).toEqual([]);
    expect(valid).toBe(true);
    // The fixture must actually be the case it claims to be.
    expect(fixture.code).toBe(code);
  });

  it.each([
    ['SessionBootstrap', sessionFixtures.bootstrap],
    ['CsrfTokenResponse', sessionFixtures.csrfToken],
    ['OwnerProfile', sessionFixtures.owner],
    // Provisional mock data (FE-105): no approved example exists for these two
    // operations yet, so the schema is the only thing holding them honest.
    ['TripPage', tripFixtures.page],
    ['TripPage', tripFixtures.pageEmpty],
    ['OptimizationHistoryPage', optimizationFixtures.historyPage],
    ['OptimizationHistoryPage', optimizationFixtures.historyPageEmpty],
    ['PlaceSearchPage', placeFixtures.searchPage],
    ['PlaceSearchPage', placeFixtures.searchPageEmpty],
    ['TripDetail', tripFixtures.detailCreated],
    ['TripDetail', tripFixtures.detailWithInterests],
    ['TripDetail', tripFixtures.detailScheduled],
    ['DeletionReceipt', sessionFixtures.deletionReceipt],
    ['DeletionRequestStatus', sessionFixtures.deletionStatus],
  ])('%s fixture', (schemaName, fixture) => {
    const validate = validatorFor(schemaName);
    const valid = validate(fixture);
    expect(validate.errors ?? []).toEqual([]);
    expect(valid).toBe(true);
  });
});

describe('optimization history carries no itinerary content', () => {
  // The operation's own description limits it to "status, timestamps, target
  // trip, and run link only", and CLAUDE.md forbids duplicating itinerary
  // content for history. The schema is additionalProperties:false, so this
  // asserts the fixture does not quietly become the place a snapshot lives.
  const allowed = new Set([
    'runId',
    'tripId',
    'tripTitle',
    'scope',
    'status',
    'decision',
    'runLink',
    'queuedAt',
    'decidedAt',
  ]);

  it.each(optimizationFixtures.historyPage.items)('item %#', (item) => {
    expect(Object.keys(item).filter((key) => !allowed.has(key))).toEqual([]);
  });
});

describe('the validator actually rejects bad data', () => {
  // Without this, a broken validator would make every test above pass silently.
  const validateProblem = validatorFor('Problem');

  it('rejects an unknown Problem code', () => {
    expect(validateProblem({ ...problemFixtures.NOT_FOUND, code: 'MADE_UP_CODE' })).toBe(
      false,
    );
  });

  it('rejects a Problem missing a required field', () => {
    const withoutRequestId: Record<string, unknown> = { ...problemFixtures.NOT_FOUND };
    delete withoutRequestId.requestId;
    expect(validateProblem(withoutRequestId)).toBe(false);
  });

  it('rejects an undeclared property (additionalProperties is false)', () => {
    expect(validateProblem({ ...problemFixtures.NOT_FOUND, extra: true })).toBe(false);
  });
});
