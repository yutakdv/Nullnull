// Every fixture in @nullnull/contracts must satisfy docs/api/openapi.yaml.
//
// TEST_STRATEGY.md:113 forbids FE from hand-writing a parallel model of the
// contract. FE authored these fixtures anyway to unblock FE-003 (see
// packages/contracts/README.md), so this test is what keeps that honest: a
// fixture that drifts from the schema fails the build.
//
// This file IS that test. packages/contracts/README.md:31 names this path, and
// there is no packages/contracts/__tests__ — an earlier version of this comment
// pointed there, at a directory that has never existed in any branch. Checks
// that read cited test names (scripts/check_cited_tests.py) read Markdown and
// Java, so a wrong path in a TS comment is seen by nobody; it survived because
// of that, not because anyone confirmed it.
//
// The validator setup mirrors docs/contracts/review-2026-09-06/verify.cjs,
// which BE already runs against this same spec.
//
// THE LIST IS NOT WRITTEN BY HAND. It used to be: nine groups named in an
// import statement, and every fixture BE added after it was written went
// unchecked here — 14 arrived with #16 and this file validated none of them,
// across SEVEN whole groups it had never heard of. Nothing failed, because a
// list that does not mention a fixture cannot notice it.
//
// So the fixtures come from `import * as`: the set under test is whatever the
// package exports, today and next week. SCHEMA_OF below still has to say which
// schema each one is validated against — that cannot be derived from the export
// name — but it is checked for COMPLETENESS rather than trusted: an export with
// no entry fails, so a new fixture makes this file red until someone names its
// schema. That is the opposite of the old failure, where a new fixture was
// silently ignored.
//
// This is not the only net. packages/contracts/scripts/check-examples.mjs pins
// the contract's response examples to these same files and runs in
// docker-integration, and it caught nothing here because it was never blind in
// the same way. Two nets that fail differently are the point.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { load } from 'js-yaml';
import { describe, expect, it } from 'vitest';
import * as contracts from '@nullnull/contracts';

// Only the two groups whose own cases name them directly. Every other group
// reaches the validator through `everyFixture()`, so naming one here would be
// re-introducing the hand-maintained list this file just removed.
const { optimizationFixtures, problemFixtures } = contracts;

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

/**
 * Which schema each fixture is validated against, keyed `group.member`.
 *
 * Hand-written because the schema name cannot be read off the export — nothing
 * connects `tripFixtures.detailScheduled` to `TripDetail` but this line. What is
 * NOT hand-written is the set of keys: `everyFixture()` reads the package, and
 * the coverage test below fails on any fixture missing from here. So a stale
 * entry is loud and a missing one is louder; neither is silent, which is what
 * the old import list was.
 *
 * `problemFixtures` is absent on purpose: it is `Record<ProblemCode, Problem>`,
 * so every member is a Problem and its own test iterates the enum.
 */
const SCHEMA_OF: Record<string, string> = {
  'sessionFixtures.bootstrap': 'SessionBootstrap',
  'sessionFixtures.csrfToken': 'CsrfTokenResponse',
  'sessionFixtures.owner': 'OwnerProfile',
  'sessionFixtures.deletionReceipt': 'DeletionReceipt',
  'sessionFixtures.deletionStatus': 'DeletionRequestStatus',
  'sessionFixtures.deletionStatusCompleted': 'DeletionRequestStatus',
  'sessionFixtures.deletionStatusPartialFailed': 'DeletionRequestStatus',
  'sessionFixtures.deletionStatusFailed': 'DeletionRequestStatus',
  'tripFixtures.page': 'TripPage',
  'tripFixtures.pageEmpty': 'TripPage',
  'tripFixtures.detailCreated': 'TripDetail',
  'tripFixtures.detailWithInterests': 'TripDetail',
  'tripFixtures.detailScheduled': 'TripDetail',
  'tripFixtures.detailReservation': 'TripDetail',
  'tripMutationFixtures.add': 'TripMutationResult',
  'tripMutationFixtures.update': 'TripMutationResult',
  'tripMutationFixtures.reorder': 'TripMutationResult',
  'tripMutationFixtures.replace': 'TripMutationResult',
  'tripMutationFixtures.constraintSet': 'TripMutationResult',
  'tripMutationFixtures.constraintRemove': 'TripMutationResult',
  'tripMutationFixtures.remove': 'TripMutationResult',
  'tripDraftFixtures.ready': 'TripDraftPreview',
  'tripDraftFixtures.empty': 'TripDraftPreview',
  'importFixtures.draftNeedsReview': 'ImportDraft',
  'importFixtures.draftReady': 'ImportDraft',
  'importFixtures.confirmedTrip': 'TripDetail',
  'preferenceFixtures.ownerOnboarded': 'OwnerProfile',
  'analyticsFixtures.receiptAccepted': 'EventBatchReceipt',
  'analyticsFixtures.receiptResent': 'EventBatchReceipt',
  'crowdFixtures.seriesForecast': 'CrowdSeries',
  'crowdFixtures.seriesStale': 'CrowdSeries',
  'crowdFixtures.seriesUnavailable': 'CrowdSeries',
  'crowdFixtures.forecastQuery': 'PlaceCrowdForecastQueryResult',
  'postFixtures.detail': 'PostDetail',
  'postFixtures.detailSaved': 'PostDetail',
  'postFixtures.savedState': 'SavedPostState',
  'postFixtures.savedStateDuplicate': 'SavedPostState',
  'feedFixtures.page': 'FeedPage',
  'feedFixtures.pageTwo': 'FeedPage',
  'feedFixtures.pageNoTrip': 'FeedPage',
  'feedFixtures.pageEmpty': 'FeedPage',
  'optimizationFixtures.historyPage': 'OptimizationHistoryPage',
  'optimizationFixtures.historyPageEmpty': 'OptimizationHistoryPage',
  'optimizationFixtures.runReady': 'OptimizationRun',
  'optimizationFixtures.runApplied': 'OptimizationRun',
  'optimizationFixtures.runQueued': 'OptimizationRun',
  'optimizationFixtures.runFailed': 'OptimizationRun',
  'optimizationFixtures.decisionApply': 'InitialOptimizationDecision',
  'optimizationFixtures.decisionKeep': 'InitialOptimizationDecision',
  'optimizationFixtures.decisionRevert': 'RevertOptimizationDecision',
  'placeFixtures.searchPage': 'PlaceSearchPage',
  'placeFixtures.searchPageEmpty': 'PlaceSearchPage',
  'placeFixtures.detail': 'PlaceDetail',
  'relatedFixtures.page': 'RelatedPlaceResult',
  'relatedFixtures.none': 'RelatedPlaceResult',
  'relatedFixtures.checking': 'RelatedPlaceResult',
  'candidateFixtures.page': 'CandidatePage',
  'candidateFixtures.pageEmpty': 'CandidatePage',
  'candidateFixtures.matchExact': 'CandidateMatchResult',
  'candidateFixtures.matchSimilar': 'CandidateMatchResult',
  'candidateFixtures.matchNone': 'CandidateMatchResult',
  'candidateFixtures.matchChecking': 'CandidateMatchResult',
  'candidateFixtures.matchUnknown': 'CandidateMatchResult',
  'candidateFixtures.saveResultCreated': 'CandidateSaveResult',
  'candidateFixtures.saveResultDuplicate': 'CandidateSaveResult',
  'systemFixtures.demoReadinessNotReady': 'DemoReadiness',
  'systemFixtures.healthLive': 'HealthStatus',
  'systemFixtures.readinessReady': 'ReadinessStatus',
};

/** `problemFixtures` has its own enum-driven test, so it is not walked here. */
const SELF_COVERED = new Set(['problemFixtures']);

/**
 * Every fixture the package exports, as `[key, value]`.
 *
 * Reads the module namespace rather than a list, which is the whole point: a
 * group added to packages/contracts appears here without anyone editing this
 * file. Only plain objects are walked — a future string or function export is
 * not a fixture and must not be validated as one.
 */
function everyFixture(): [string, unknown][] {
  const found: [string, unknown][] = [];
  for (const [group, value] of Object.entries(contracts)) {
    if (SELF_COVERED.has(group)) continue;
    if (value === null || typeof value !== 'object') continue;
    for (const [member, fixture] of Object.entries(value)) {
      found.push([`${group}.${member}`, fixture]);
    }
  }
  return found;
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

  it.each(everyFixture())('%s', (key, fixture) => {
    const schemaName = SCHEMA_OF[key];
    // Not `?? skip`: an unnamed fixture must fail, not quietly pass. The
    // coverage test below says the same thing about the whole set at once, but
    // this keeps the failure attached to the fixture that caused it.
    expect(schemaName, `${key} has no SCHEMA_OF entry`).toBeDefined();
    const validate = validatorFor(schemaName as string);
    const valid = validate(fixture);
    expect(validate.errors ?? []).toEqual([]);
    expect(valid).toBe(true);
  });
});

describe('the fixture list maintains itself', () => {
  // These two guards are why the walk can be trusted. Without them
  // `everyFixture()` returning [] would make every test above vacuously pass —
  // "0 fixtures drifted" reads exactly like "nothing drifted", and that is the
  // shape attribution-coverage.test.ts already had to defend against.

  it('finds fixtures at all, so an empty walk cannot pass vacuously', () => {
    // A floor, not the exact count: pinning the number would mean editing this
    // file for every fixture BE adds, which is the hand-maintenance being
    // removed. It only has to be high enough that a broken walk cannot clear it.
    expect(everyFixture().length).toBeGreaterThan(50);
  });

  it('walks more than one group, so a broken namespace read cannot pass', () => {
    // `import * as` returning a partial namespace would still clear the floor
    // above if one big group survived. Counting distinct groups catches that.
    const groups = new Set(everyFixture().map(([key]) => key.split('.')[0]));
    expect(groups.size).toBeGreaterThan(5);
  });

  it('names a schema for every exported fixture', () => {
    // The one that closes the original hole: 14 fixtures across 7 groups were
    // exported and unvalidated because nothing compared the two sets.
    const missing = everyFixture()
      .map(([key]) => key)
      .filter((key) => !(key in SCHEMA_OF));
    expect(missing, 'exported fixtures with no SCHEMA_OF entry').toEqual([]);
  });

  it('has no SCHEMA_OF entry for a fixture that no longer exists', () => {
    // The other direction: a renamed or deleted fixture leaves a line here that
    // validates nothing, and a map full of those is how the list rots again.
    const live = new Set(everyFixture().map(([key]) => key));
    const stale = Object.keys(SCHEMA_OF).filter((key) => !live.has(key));
    expect(stale, 'SCHEMA_OF entries with no matching export').toEqual([]);
  });

  it('names schemas that the contract actually defines', () => {
    // A typo'd schema name would otherwise surface as an ajv compile error deep
    // in an unrelated case. `problemFixtures` covers Problem, which is why it is
    // not in the map and not expected here.
    const undefined_ = [...new Set(Object.values(SCHEMA_OF))].filter(
      (name) => !(name in api.components.schemas),
    );
    expect(undefined_, 'SCHEMA_OF names absent from openapi.yaml').toEqual([]);
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
