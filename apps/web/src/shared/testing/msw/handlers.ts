// MSW handlers backed by @nullnull/contracts fixtures.
//
// Every response body here is ajv-validated against docs/api/openapi.yaml by
// packages/contracts (see its README). Handlers must not build bodies inline —
// an inline object is a hand-written model with nothing checking it.
import {
  candidateFixtures,
  feedFixtures,
  postFixtures,
  relatedFixtures,
  optimizationFixtures,
  placeFixtures,
  problemFixtures,
  sessionFixtures,
  tripFixtures,
} from '@nullnull/contracts';
import { http, HttpResponse } from 'msw';
import type { components } from '@nullnull/api-client';
import type { ProblemCode } from '../../api/index.js';

type PostDetail = components['schemas']['PostDetail'];
type Problem = components['schemas']['Problem'];

/**
 * The dev proxy and the deployed app both serve the API under /api/v1.
 *
 * Relative on purpose: MSW resolves a relative handler path against the
 * document origin at match time, which is the same origin the client resolves
 * its own base URL against (shared/api/session.ts).
 */
export const API_BASE = '/api/v1';

const PROBLEM_CONTENT_TYPE = 'application/problem+json';

/**
 * A Problem response with the fixture's own status and the RFC 9457 type.
 *
 * `overrides` carries BODY fields, `headers` carries headers — they are
 * separate because `problemFixtures` is `Record<ProblemCode, Problem>` and has
 * room for exactly one body per code. UNAUTHORIZED has two shapes the screen
 * must tell apart (`missingCredential` present or absent, #240), and a second
 * fixture for the same code has nowhere to live; overriding one field of the
 * one fixture is how the variant is expressed without a parallel fixture set
 * that could drift from it.
 *
 * Third parameter, not second: `headers` was already the second and 79 call
 * sites pass it that way.
 */
export function problemResponse(
  code: ProblemCode,
  headers: Record<string, string> = {},
  overrides: Partial<Problem> = {},
) {
  const fixture = problemFixtures[code];
  const body = { ...fixture, ...overrides };
  return HttpResponse.json(body, {
    status: fixture.status,
    headers: {
      'Content-Type': PROBLEM_CONTENT_TYPE,
      'X-Request-ID': fixture.requestId,
      ...headers,
    },
  });
}

/**
 * FE-106's trip state. A replace increments the version, so the next If-Match
 * has to use the ETag the server just returned.
 */
let tripState: (typeof tripFixtures)['detailWithInterests'] | null = null;

function currentTrip() {
  // The scheduled fixture is the default: it carries the interests FE-106
  // edits *and* the days/items FE-301 renders, so one trip serves both screens
  // and they cannot disagree about the same id.
  tripState ??= tripFixtures.detailScheduled;
  return tripState;
}

/**
 * FE-104's trip LIST state, separate from the detail state above.
 *
 * deleteTrip removes a row, so the list has to be able to lose one. Serving
 * the fixture flat would make "the deleted trip is gone" true before the
 * screen did anything.
 */
let tripPageState: (typeof tripFixtures)['page'] | null = null;

function currentTripPage() {
  tripPageState ??= tripFixtures.page;
  return tripPageState;
}

/**
 * The owner profile, which PATCH /me mutates and every bootstrap answers with.
 *
 * Stateful for the same reason the trip list is — see the PATCH handler.
 *
 * `activeTripId` starts at the list's first trip rather than at the fixture's
 * `null`. The fixture is right about what it models — the contract calls it "a
 * freshly bootstrapped owner", and null until a trip is created is exactly that
 * — but this mock ALSO serves a trip list with four trips in it, and an owner
 * who has four trips and no active one is a state the server cannot produce:
 * the pointer is only null before the first create or after the active trip is
 * deleted.
 *
 * Getting this wrong is what made the 내 여행 tab look broken in `npm run dev`:
 * the trips were right there on the profile and the tab kept falling back,
 * because the two fixtures disagreed about the same owner.
 *
 * The fixture is not edited: it is pinned to the contract's own example by
 * packages/contracts/scripts/check-examples.mjs, and that example is correct.
 * Composing the mock's starting state here is the same thing the trip and
 * import handlers already do.
 *
 * NO TEST COVERS THIS LINE, and that is measured rather than assumed: reverting
 * it to the bare fixture leaves all 1045 green. Every test seeds the session
 * cache itself, because a test that depended on the mock's opening state would
 * be asserting the mock rather than the screen. What this line fixes is `npm
 * run dev` — where the tab fell back while four trips sat on the profile — and
 * the browser is where it was verified.
 */
let ownerState: (typeof sessionFixtures)['owner'] | null = null;

function currentOwner() {
  ownerState ??= {
    ...sessionFixtures.owner,
    activeTripId: currentTripPage().items[0]?.id ?? null,
  };
  return ownerState;
}

let candidateState: (typeof candidateFixtures)['page'] | null = null;

function currentCandidates() {
  candidateState ??= candidateFixtures.page;
  return candidateState;
}

type ImportDraft = components['schemas']['ImportDraft'];

/**
 * FE-104's import draft.
 *
 * Stateful for the same reason the trip is: a remap advances the version, so a
 * stale If-Match has to come back as a conflict rather than pass silently. A
 * handler that answered every correction with the same body would let a
 * stale-ETag bug through, and would also make "the correction stuck" true
 * before the screen did anything.
 */
let importDraftState: ImportDraft | null = null;

/**
 * Builds a draft from the place fixtures rather than from an import fixture.
 *
 * There is no approved example for parseTripImport (BA-060) and no
 * packages/contracts fixture for it, so this models the contract the way the
 * FE-305 item handlers do. The places are real fixture rows, which is what
 * keeps the PlaceSummary shape honest; only the draft envelope is composed.
 *
 * The shape says what the screen has to handle: one item the parser placed,
 * one it placed without a date, and two unresolved tokens — a PLACE with
 * suggestions, and one with an EMPTY label, which is the free-memo line that
 * #223's `dismissed` exists for. A draft where every token had suggestions
 * would never exercise the dead end FCR-019 recorded.
 */
function buildImportDraft(): ImportDraft {
  const [first, second, third] = placeFixtures.searchPage.items;
  return {
    id: '018f4c30-2b55-7f22-ad13-6e8f4a2b3c01',
    version: 1,
    status: 'NEEDS_REVIEW',
    title: null,
    dates: { startDate: '2026-10-04', endDate: '2026-10-05' },
    items: [
      {
        clientKey: 'line-1',
        place: first ?? null,
        originalLabel: first?.name ?? '',
        date: '2026-10-04',
        startTime: '10:00:00',
        position: 0,
        confidence: 0.94,
        constraints: [],
      },
      {
        clientKey: 'line-2',
        place: second ?? null,
        originalLabel: second?.name ?? '',
        // No date: the parser read the place but not the day, so this is an
        // item the person still has to answer for before READY.
        date: null,
        startTime: null,
        position: 1,
        confidence: 0.61,
        constraints: [],
      },
    ],
    unresolved: [
      {
        clientKey: 'token-3',
        kind: 'PLACE',
        line: 4,
        label: '한옥마을',
        suggestions: third ? [third] : [],
      },
      {
        // An empty label with no suggestions: a free-memo line. The contract
        // says the label is allowlist-extracted and a memo yields an EMPTY
        // one, so `line` is the only way a person finds it. Nothing here can
        // be resolved, which is exactly why `dismissed` exists.
        clientKey: 'token-4',
        kind: 'PLACE',
        line: 7,
        label: '',
        suggestions: [],
      },
    ],
    expiresAt: '2026-09-16T04:00:00Z',
  };
}

function currentImportDraft(): ImportDraft {
  importDraftState ??= buildImportDraft();
  return importDraftState;
}

/** READY once nothing is unresolved and every item has a place and a date. */
function importDraftStatus(draft: ImportDraft): ImportDraft['status'] {
  const settled =
    draft.unresolved.length === 0 &&
    draft.items.every((item) => item.place != null && item.date != null);
  return settled ? 'READY' : 'NEEDS_REVIEW';
}

/**
 * Which posts are bookmarked. A SavedPost is its own resource with no trip in
 * its path, so this state is deliberately separate from the trip's — mixing
 * them is what invariant 1 forbids.
 */
const savedPosts = new Set<string>();

/**
 * A PostDetail for any post the feed lists.
 *
 * The single approved-shape fixture covers one of the five feed posts, so the
 * other four answered NOT_FOUND: in `npm run dev` four of the five cards
 * opened onto 없는 게시물이에요, which reads as a broken app rather than as
 * missing mock data. The detail is built from the feed's own entry — same id,
 * title, excerpt, cover and place — so the card and the screen it opens agree.
 *
 * An id the feed does not list still answers NOT_FOUND, which is what the
 * deep-link-to-a-missing-post case needs.
 */
function postDetailFor(postId: string): PostDetail | null {
  if (postId === postFixtures.detail.id) return postFixtures.detail;
  const entry = [...feedFixtures.page.items, ...feedFixtures.pageTwo.items].find(
    (item) => item.post.id === postId,
  );
  if (!entry) return null;
  return {
    ...postFixtures.detail,
    id: entry.post.id,
    title: entry.post.title,
    excerpt: entry.post.excerpt,
    coverUrl: entry.post.coverUrl,
    publishedAt: entry.post.publishedAt,
    places: [entry.primaryPlace],
  };
}

/**
 * How many times each run has been polled, so the mock can progress.
 *
 * A handler that answered the same status forever would let a screen that
 * never polls, or one that polls a settled run for ever, pass identically.
 * This one moves QUEUED → RUNNING → READY as it is asked.
 */
const runPolls = new Map<string, number>();

/**
 * Runs the mock should answer for. Seeded by createOptimization.
 *
 * Exported so a test can drive the DEFAULT handlers rather than copying the id:
 * two spellings of the same constant drift, and the one that drifts is the one
 * that makes a test quietly stop reaching the handler it meant to.
 */
export const MOCK_RUN_ID = '018f6a00-0000-7000-8000-000000000001';

/**
 * The decision recorded against a run, if any.
 *
 * Kept so `getOptimization` can answer APPLIED or KEPT after one is made. A
 * mock that always re-answered READY would leave the transition the decision
 * causes untestable: the bar's `applied` state and the run's own status line
 * would both pass against a run that never moved, which is the shape of a test
 * that renders the right thing for the wrong reason.
 */
const runDecisions = new Map<string, { decision: 'APPLY' | 'KEEP'; decidedAt: string }>();

/**
 * The fields that belong to one kind of decision and not the other.
 *
 * The contract splits these by discriminator: only APPLY carries a resulting
 * version, the two revision ids and a revert window — a KEEP changed nothing,
 * so there is no version to report and nothing to revert to. Returning them on
 * a KEEP would let a screen read `resultingTripVersion` off a decision that
 * never moved the trip, which is invariant 4 stated the wrong way round.
 *
 * `revertUntil` is `decidedAt` plus exactly 24 hours, per the contract.
 */
/**
 * Two proposals, derived from the one approved example.
 *
 * The contract allows up to three (`maxItems: 3`) and the example carries one,
 * so a mock that served the fixture flat left the whole selection path — the
 * radiogroup, `aria-checked`, the keyboard move — unreachable: the screen only
 * makes the cards selectable when there is more than one, so that code had
 * never executed anywhere, including in `npm run dev`.
 *
 * Derived here rather than by editing the fixture: that file is pinned to the
 * contract's example by check-examples.mjs and fixtures.test.ts, and it is
 * right about the SHAPE. What it does not carry is a COUNT the screen has to
 * handle.
 *
 * The clone keeps `metrics` and `dataProvenance` untouched, so both proposals
 * pass the same `crowdComparison` gate. Changing them here would make the two
 * cards differ for a reason unrelated to choosing between them, and a test
 * that then looked at the wrong card would still find something plausible.
 *
 * Rank 2 is listed FIRST so "the default is rank 1" cannot pass on array order.
 */
function mockProposals() {
  const [first] = optimizationFixtures.runReady.proposals;
  if (!first) return optimizationFixtures.runReady.proposals;
  const second = {
    ...first,
    id: '018f6d00-0000-7000-8000-000000000002',
    rank: 2,
    summary: '경복궁 방문을 10월 5일 오전으로 옮기면 상대 집중률이 조금 낮아져요.',
  };
  return [second, first];
}

function decisionOutcome(decision: 'APPLY' | 'KEEP', decidedAt: string, version: number) {
  if (decision === 'KEEP') return {};
  const REVERT_WINDOW_MS = 24 * 60 * 60 * 1000;
  return {
    resultingTripVersion: version,
    beforeRevisionId: '018f6c00-0000-7000-8000-000000000001',
    afterRevisionId: '018f6c00-0000-7000-8000-000000000002',
    revertUntil: new Date(new Date(decidedAt).getTime() + REVERT_WINDOW_MS).toISOString(),
  };
}

/** Drops mutations between tests, so ordering cannot leak state. */
export function resetMockState(): void {
  tripState = null;
  tripPageState = null;
  ownerState = null;
  candidateState = null;
  importDraftState = null;
  savedPosts.clear();
  runPolls.clear();
  runDecisions.clear();
}

/**
 * Default happy-path handlers: only the session bootstrap trio, which is all
 * FE-003 needs. Screen slices add their own as their fixtures arrive from BE.
 */
export const handlers = [
  // The bootstrap carries the owner, so it has to serve the SAME one PATCH /me
  // writes. Serving the flat fixture here would undo every preference on the
  // next load while the PATCH handler still reported success.
  http.post(`${API_BASE}/demo/sessions`, () =>
    HttpResponse.json(
      { ...sessionFixtures.bootstrap, owner: currentOwner() },
      { status: 201 },
    ),
  ),
  http.post(`${API_BASE}/session/csrf`, () =>
    HttpResponse.json(sessionFixtures.csrfToken),
  ),
  http.get(`${API_BASE}/me`, () => HttpResponse.json(currentOwner())),
  // Merge-patch, and STATEFUL for the reason the trip list is: the owner
  // profile is what the next bootstrap answers with, so a handler that echoed
  // the patch and forgot it would make "the preference stuck" true for exactly
  // one render and false after any reload.
  //
  // `activeTripId` is the case that showed it. The 내 여행 tab resolves through
  // this field, the wizard PATCHes it on create, and a reload re-bootstraps —
  // against a flat fixture the tab went back to the fallback every time, which
  // reads as "the tab is broken" and is really "the mock forgot".
  http.patch(`${API_BASE}/me`, async ({ request }) => {
    const patch = (await request.json()) as Partial<typeof sessionFixtures.owner>;
    // Merge-patch semantics: omitted fields keep their value, an explicit null
    // clears. Spreading the patch over the current owner is exactly that,
    // because `undefined` never appears in parsed JSON.
    ownerState = { ...currentOwner(), ...patch };
    return HttpResponse.json(ownerState);
  }),

  // MOCK DATA (FE-105) — these two operations have no approved example, so the
  // fixtures behind them are schema-valid guesses rather than real responses
  // (packages/contracts/src/index.ts). Delete these two handlers once BA-030
  // and BA-053 serve the real thing; the screens already call the real client.
  http.get(`${API_BASE}/trips`, () => HttpResponse.json(currentTripPage())),
  // MOCK DATA (FE-104). deleteTrip has no approved example either.
  //
  // Stateful for the same reason the item handlers are: if this answered 204
  // and left the list alone, "the deleted trip is gone from the list" would
  // pass whether or not the screen ever removed it. The row has to actually
  // leave, or the assertion measures nothing.
  //
  // If-Match is checked against THAT ROW's version, because the contract's
  // ETag is the quoted trip version and the profile builds it from
  // TripSummary.version. A stale validator has to be visible as a conflict.
  http.delete(`${API_BASE}/trips/:tripId`, ({ request, params }) => {
    const page = currentTripPage();
    const tripId = String(params.tripId);
    const target = page.items.find((trip) => trip.id === tripId);
    if (!target) return problemResponse('NOT_FOUND');
    if (request.headers.get('If-Match') !== `"${String(target.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    tripPageState = {
      ...page,
      items: page.items.filter((trip) => trip.id !== tripId),
    };
    return new HttpResponse(null, {
      status: 204,
      headers: { 'Cache-Control': 'private, no-store' },
    });
  }),
  http.get(`${API_BASE}/optimizations`, () =>
    HttpResponse.json(optimizationFixtures.historyPage),
  ),
  // MOCK DATA (FE-501). createOptimization has an approved example, so the
  // request shape is the contract's own; the run it answers with is invented
  // until BA-050 lands. 202 with a Location header, per the contract.
  http.post(`${API_BASE}/trips/:tripId/optimizations`, async ({ request, params }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const body = (await request.json()) as { scope: string; targetItemId?: string };
    // The mock refuses anything but ITEM. P0 ships one scope (FCR-010), and
    // a handler that accepted DAY would let a regression through silently.
    if (body.scope !== 'ITEM') return problemResponse('VALIDATION_FAILED');
    const runId = '018f6a00-0000-7000-8000-000000000001';
    const tripId = String(params.tripId);
    return HttpResponse.json(
      {
        id: runId,
        tripId,
        scope: 'ITEM',
        status: 'QUEUED',
        inputTripVersion: trip.version,
        includeCandidates: false,
        queuedAt: '2026-09-11T06:00:00Z',
        proposals: [],
        snapshotSetIds: [],
        decisions: [],
      },
      {
        status: 202,
        headers: { Location: `/trip/${tripId}/optimizations/${runId}` },
      },
    );
  }),

  // MOCK DATA (FE-502). getOptimization has no approved example (BA-050), so
  // the QUEUED/RUNNING shape below is a schema-valid invention. It
  // PROGRESSES: the first poll answers QUEUED, the second RUNNING, the third
  // READY, and Retry-After carries the interval the contract says to send
  // "for QUEUED/RUNNING responses". A handler pinned to one status would let
  // a screen that never polls and a screen that polls a settled run for ever
  // both pass.
  //
  // READY no longer invents proposals: BA-051 computes them now, and
  // optimizationFixtures.runReady is that computed shape, pinned to the
  // contract's response example by check-examples.mjs and to the schema by
  // fixtures.test.ts. Only id, tripId and inputTripVersion are overridden —
  // they have to match this mock's run and trip, everything else must stay
  // as the contract fixed it. inputTripVersion in particular: the fixture's
  // value is whatever BE captured when the example was written, not this
  // mock's live trip.version, and trip.version moves (a replace increments
  // it) — leaving the fixture's number in would make the run compare as
  // stale against a trip it was never actually run against, and no APPLY
  // path could ever be exercised against a fresh run.
  http.get(`${API_BASE}/optimizations/:runId`, ({ params }) => {
    const runId = String(params.runId);
    if (runId !== MOCK_RUN_ID) return problemResponse('NOT_FOUND');
    const seen = (runPolls.get(runId) ?? 0) + 1;
    runPolls.set(runId, seen);
    const decided = runDecisions.get(runId);
    // A decided run has settled. The poll counter no longer drives it —
    // otherwise an APPLIED run would answer READY again on the next read and
    // the screen would offer to apply something it already applied.
    const status = decided
      ? decided.decision === 'APPLY'
        ? 'APPLIED'
        : 'KEPT'
      : seen === 1
        ? 'QUEUED'
        : seen === 2
          ? 'RUNNING'
          : 'READY';
    const trip = currentTrip();

    if (status === 'READY' || decided) {
      return HttpResponse.json({
        ...optimizationFixtures.runReady,
        id: runId,
        tripId: trip.id,
        inputTripVersion: trip.version,
        status,
        proposals: mockProposals(),
        // The decision the run now carries. The contract keeps proposals on a
        // decided run — the user can still see what was applied — so only the
        // status and this array change.
        decisions: decided
          ? [
              {
                id: '018f6b00-0000-7000-8000-000000000001',
                runId,
                proposalId: optimizationFixtures.runReady.proposals[0]?.id,
                decision: decided.decision,
                decidedAt: decided.decidedAt,
                ...decisionOutcome(decided.decision, decided.decidedAt, trip.version),
              },
            ]
          : [],
      });
    }

    return HttpResponse.json(
      {
        id: runId,
        tripId: trip.id,
        scope: 'ITEM',
        status,
        inputTripVersion: trip.version,
        includeCandidates: false,
        queuedAt: '2026-09-11T06:00:00Z',
        completedAt: null,
        proposals: [],
        snapshotSetIds: [],
        decisions: [],
      },
      // Seconds, per the contract's integer schema. Only while working.
      { headers: { 'Retry-After': '1' } },
    );
  }),

  // MOCK DATA (FE-505). decideOptimization has no approved example, so the
  // response is built from the contract's required fields rather than copied
  // from a fixture.
  //
  // The decision is READ FROM THE BODY and echoed back. Answering a fixed
  // 'APPLY' would make the client's own branch — invalidate the trip cache on
  // APPLY, leave it alone on KEEP — take the wrong path with nothing on screen
  // to show for it, and the test asserting that KEEP does not reload the
  // itinerary would pass against a mock that called it an APPLY.
  //
  // Stateful for the same reason the GET above is: recording it lets the next
  // poll answer APPLIED or KEPT, so the transition a decision causes is
  // observable instead of being asserted against a run that never moved.
  http.post(`${API_BASE}/optimizations/:runId/decisions`, async ({ params, request }) => {
    const runId = String(params.runId);
    if (runId !== MOCK_RUN_ID) return problemResponse('NOT_FOUND');
    const body = (await request.json()) as {
      proposalId: string;
      decision: 'APPLY' | 'KEEP';
    };
    const decidedAt = '2026-09-11T06:10:00Z';
    runDecisions.set(runId, { decision: body.decision, decidedAt });
    const trip = currentTrip();
    return HttpResponse.json(
      {
        id: '018f6b00-0000-7000-8000-000000000001',
        runId,
        proposalId: body.proposalId,
        decision: body.decision,
        decidedAt,
        ...decisionOutcome(body.decision, decidedAt, trip.version),
      },
      // The contract declares ETag on this 200: the trip moved, and the next
      // mutation needs the new one.
      { headers: { ETag: `W/"trip-${String(trip.version)}"` } },
    );
  }),

  // MOCK DATA (FE-202). getPost, savePost and unsavePost have no approved
  // example (BA-032). Stateful so a save actually round-trips: a handler that
  // always answered `saved: false` would let a broken toggle pass.
  http.get(`${API_BASE}/posts/:postId`, ({ params }) => {
    const postId = String(params.postId);
    const detail = postDetailFor(postId);
    if (!detail) return problemResponse('NOT_FOUND');
    return HttpResponse.json({ ...detail, saved: savedPosts.has(postId) });
  }),
  http.put(`${API_BASE}/posts/:postId/saved`, ({ params }) => {
    const postId = String(params.postId);
    // The contract makes this idempotent in the resource: 201 when the save is
    // new, 200 when it already existed, and `duplicate` says which.
    const duplicate = savedPosts.has(postId);
    savedPosts.add(postId);
    return HttpResponse.json(
      {
        postId,
        saved: true,
        duplicate,
        savedAt: postFixtures.savedState.savedAt,
      },
      { status: duplicate ? 200 : 201 },
    );
  }),
  http.delete(`${API_BASE}/posts/:postId/saved`, ({ params }) => {
    // 204 "removed or already absent", so this is safe to repeat.
    savedPosts.delete(String(params.postId));
    return new HttpResponse(null, { status: 204 });
  }),

  // MOCK DATA (FE-201). listFeed has no approved example either. This handler
  // reads the cursor rather than always answering page one, so pagination is
  // exercised for real: a handler that ignored it would let a broken "load
  // more" pass by returning the same page forever.
  http.get(`${API_BASE}/feed`, ({ request }) => {
    const url = new URL(request.url);
    const cursor = url.searchParams.get('cursor');
    // `candidateState` describes the REQUEST, not the card: without a tripId
    // every card is NO_TRIP_SELECTED, and with one none of them is. A page
    // mixing the two is a document the server cannot produce (#156), so the
    // mock answers from the fixture that matches the request it was given.
    if (url.searchParams.get('tripId') === null) {
      return HttpResponse.json(feedFixtures.pageNoTrip);
    }
    if (cursor === null) return HttpResponse.json(feedFixtures.page);
    if (cursor === feedFixtures.page.page.nextCursor) {
      return HttpResponse.json(feedFixtures.pageTwo);
    }
    // Any other cursor is one this mock never issued. The contract answers 410
    // CURSOR_EXPIRED for a cursor past its 15 minutes, and the screen has to
    // recover from the first page rather than retry (problem-policy.ts).
    return problemResponse('CURSOR_EXPIRED');
  }),
  // MOCK DATA (FE-102). Without this the wizard's final submit is an unhandled
  // request: the tests each stood up their own handler and passed, while the
  // running app answered 500 and showed its failure state. Delete with BA-030.
  http.post(`${API_BASE}/trips`, () =>
    HttpResponse.json(tripFixtures.detailCreated, {
      status: 201,
      headers: {
        // Strong, not W/"1": components.headers.ETag is `^"[1-9][0-9]*"$` and
        // If-Match repeats that pattern, so a weak validator is one the real
        // server would reject. Mirrors the body's own `version`.
        ETag: `"${String(tripFixtures.detailCreated.version)}"`,
        Location: `/trips/${tripFixtures.detailCreated.id}`,
      },
    }),
  ),
  // MOCK DATA (FE-105). Deletion is 202 with a receipt, then a status the
  // screen polls with the receipt token. Delete with BA-012.
  http.delete(`${API_BASE}/session`, () =>
    HttpResponse.json(sessionFixtures.deletionReceipt, {
      status: 202,
      headers: { Location: sessionFixtures.deletionReceipt.statusUrl },
    }),
  ),
  http.get(`${API_BASE}/deletion-requests/:id`, () =>
    HttpResponse.json(sessionFixtures.deletionStatus, {
      headers: { 'Cache-Control': 'private, no-store' },
    }),
  ),
  // MOCK DATA (FE-103). searchPlaces is a read-only POST so the query never
  // reaches a URL log; the handler matches that shape. Delete with BA-022.
  http.post(`${API_BASE}/places/search`, () =>
    HttpResponse.json(placeFixtures.searchPage, {
      headers: { 'Cache-Control': 'private, no-store' },
    }),
  ),

  // MOCK DATA (FE-303). listTripCandidates, getCandidateTripMatches and
  // addTripItem have no approved example (BA-034, BA-042).
  http.get(`${API_BASE}/trips/:tripId/candidates`, () =>
    HttpResponse.json(currentCandidates()),
  ),
  http.get(`${API_BASE}/trips/:tripId/candidates/:candidateId/matches`, ({ params }) => {
    // Keyed off the candidate so each match state is reachable from the running
    // app, not only from a test that forces a handler.
    const id = String(params.candidateId);
    if (id.endsWith('0001')) return HttpResponse.json(candidateFixtures.matchSimilar);
    return HttpResponse.json(candidateFixtures.matchExact);
  }),
  http.delete(`${API_BASE}/trips/:tripId/candidates/:candidateId`, ({ params }) => {
    const candidates = currentCandidates();
    const target = candidates.items.find((c) => c.id === String(params.candidateId));
    // Only an ACTIVE candidate can be dismissed. BA-034's CandidateService
    // throws LOCK_CONFLICT for a SCHEDULED one — "A scheduled candidate is
    // removed through its trip item, not dismissed" — and a mock that
    // accepted every id let the screen offer a button the server always
    // refuses.
    if (target && target.status !== 'ACTIVE') {
      return problemResponse('LOCK_CONFLICT');
    }
    // DISMISSED rather than deleted: the contract keeps the row so the place is
    // not re-suggested. The panel filters it out.
    candidateState = {
      ...candidates,
      items: candidates.items.map((c) =>
        c.id === String(params.candidateId) ? { ...c, status: 'DISMISSED' as const } : c,
      ),
    };
    return new HttpResponse(null, { status: 204 });
  }),
  // MOCK DATA (FE-305). listRelatedPlaces has no approved example (BA-042).
  // Every row's crowd is null: CrowdMetric needs a 29-field DataProvenance and
  // the replace rules read comparisonEligible off it, so a synthesised one
  // would be the fabricated evidence invariant 8 exists to stop.
  http.get(`${API_BASE}/places/:placeId/related`, () =>
    HttpResponse.json(relatedFixtures.page, {
      headers: { 'Cache-Control': 'private, no-store' },
    }),
  ),

  // MOCK DATA (FE-305). addTripCandidate has no approved example (BA-034).
  // The response asserts invariant 2 in its own shape: saving a candidate sets
  // tripScheduleChanged false and leaves the trip untouched here.
  http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ request }) => {
    const body = (await request.json()) as { placeId: string };
    const candidates = currentCandidates();
    const existing = candidates.items.find((c) => c.place.id === body.placeId);
    if (existing) {
      return HttpResponse.json({
        candidate: existing,
        duplicate: true,
        tripScheduleChanged: false,
      });
    }
    const place =
      placeFixtures.searchPage.items.find((p) => p.id === body.placeId) ??
      placeFixtures.searchPage.items[0];
    const template = candidates.items[0];
    if (!place || !template) return problemResponse('VALIDATION_FAILED');
    const candidate = {
      ...template,
      id: crypto.randomUUID(),
      place,
      status: 'ACTIVE' as const,
      scheduledTripItemId: null,
    };
    candidateState = { ...candidates, items: [...candidates.items, candidate] };
    return HttpResponse.json(
      { candidate, duplicate: false, tripScheduleChanged: false },
      { status: 201 },
    );
  }),

  // MOCK DATA (FE-305). updateTripItem, reorderTripItems, replaceTripItem and
  // removeTripItem have no approved example (BA-040). Each models the contract
  // rather than echoing a fixture, so a client that sends the wrong shape fails
  // here instead of in staging.
  http.patch(`${API_BASE}/trips/:tripId/items/:itemId`, async ({ request, params }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    // The contract declares application/merge-patch+json on this operation and
    // BA-011's controller enforces consumes, so a JSON body is a 415.
    if (!(request.headers.get('content-type') ?? '').includes('merge-patch+json')) {
      return problemResponse('INVALID_REQUEST');
    }
    const patch = (await request.json()) as Record<string, unknown>;
    if (Object.keys(patch).length === 0) return problemResponse('VALIDATION_FAILED');
    const itemId = String(params.itemId);
    const next = {
      ...trip,
      version: trip.version + 1,
      days: trip.days.map((day) => ({
        ...day,
        items: day.items.map((item) =>
          item.id === itemId ? { ...item, ...patch } : item,
        ),
      })),
    } as typeof trip;
    tripState = next;
    return HttpResponse.json(
      { trip: next, changedItemIds: [itemId] },
      { headers: { ETag: `"${String(next.version)}"` } },
    );
  }),
  http.post(`${API_BASE}/trips/:tripId/items/reorder`, async ({ request }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const body = (await request.json()) as {
      items: { itemId: string; date: string; position: number }[];
    };
    const moves = new Map(body.items.map((entry) => [entry.itemId, entry]));
    // Applied as one unit: every item named moves to its stated day and
    // position together, which is what "reorder or move atomically" means.
    const pool = trip.days.flatMap((day) => day.items);
    const next = {
      ...trip,
      version: trip.version + 1,
      days: trip.days.map((day) => ({
        ...day,
        items: pool
          .map((item) => {
            const move = moves.get(item.id);
            return move ? { ...item, date: move.date, position: move.position } : item;
          })
          .filter((item) => item.date === day.date)
          .sort((a, b) => a.position - b.position),
      })),
    } as typeof trip;
    tripState = next;
    return HttpResponse.json(
      { trip: next, changedItemIds: body.items.map((entry) => entry.itemId) },
      { headers: { ETag: `"${String(next.version)}"` } },
    );
  }),
  http.post(
    `${API_BASE}/trips/:tripId/items/:itemId/replace`,
    async ({ request, params }) => {
      const trip = currentTrip();
      if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
        return problemResponse('TRIP_CHANGED');
      }
      const body = (await request.json()) as {
        replacementPlaceId: string;
        preserveDateTime?: boolean;
        releaseConstraints?: string[];
      };
      const itemId = String(params.itemId);
      // `false` is refused with 422 rather than honoured or ignored: the field
      // was published with no description and no implementation, so what it
      // would mean was never decided (#203). The mock refuses it too, because a
      // mock that accepts what the server rejects teaches the screen a
      // behaviour it cannot have.
      if (body.preserveDateTime === false) {
        return problemResponse('VALIDATION_FAILED');
      }
      const target = currentTrip()
        .days.flatMap((day) => day.items)
        .find((item) => item.id === itemId);
      // Naming a lock is the only way it is released, and one this request does
      // not name still refuses the edit (invariant 7). A replacement changes
      // the PLACE, so MUST_VISIT and RESERVATION are the locks it can violate;
      // DATE and TIME pin the schedule, which a replacement keeps.
      const named = body.releaseConstraints ?? [];
      const violated = (target?.constraints ?? [])
        .map((constraint) => constraint.type)
        .filter((type) => type === 'MUST_VISIT' || type === 'RESERVATION')
        .filter((type) => !named.includes(type));
      if (violated.length > 0) {
        return problemResponse('LOCK_CONFLICT');
      }
      const replacement = placeFixtures.searchPage.items.find(
        (place) => place.id === body.replacementPlaceId,
      );
      const next = {
        ...trip,
        version: trip.version + 1,
        days: trip.days.map((day) => ({
          ...day,
          items: day.items.map((item) =>
            item.id === itemId && replacement
              ? {
                  ...item,
                  place: replacement,
                  // Releasing a lock deletes its row, so a named lock is gone
                  // afterwards rather than merely bypassed for this one edit.
                  constraints: item.constraints.filter(
                    (constraint) => !named.includes(constraint.type),
                  ),
                }
              : item,
          ),
        })),
      } as typeof trip;
      tripState = next;
      return HttpResponse.json(
        { trip: next, changedItemIds: [itemId] },
        { headers: { ETag: `"${String(next.version)}"` } },
      );
    },
  ),
  http.delete(`${API_BASE}/trips/:tripId/items/:itemId`, ({ request, params }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    // Required by the contract, with no default: the caller must say whether
    // the saved place survives the removal.
    const disposition = new URL(request.url).searchParams.get('disposition');
    if (disposition !== 'RESTORE_CANDIDATE' && disposition !== 'REMOVE') {
      return problemResponse('VALIDATION_FAILED');
    }
    const itemId = String(params.itemId);
    const removed = trip.days
      .flatMap((day) => day.items)
      .find((item) => item.id === itemId);
    const next = {
      ...trip,
      version: trip.version + 1,
      days: trip.days.map((day) => ({
        ...day,
        items: day.items.filter((item) => item.id !== itemId),
      })),
    } as typeof trip;
    tripState = next;
    if (disposition === 'RESTORE_CANDIDATE' && removed) {
      const candidates = currentCandidates();
      candidateState = {
        ...candidates,
        items: candidates.items.map((candidate) =>
          candidate.scheduledTripItemId === itemId
            ? { ...candidate, status: 'ACTIVE' as const, scheduledTripItemId: null }
            : candidate,
        ),
      };
    }
    return HttpResponse.json(
      { trip: next, changedItemIds: [itemId] },
      { headers: { ETag: `"${String(next.version)}"` } },
    );
  }),

  // MOCK DATA (FE-304). removeTripItemConstraint has no approved example
  // (BA-041). Stateful so a released lock stays released and the version
  // advances, which is what makes a stale-ETag bug visible.
  http.delete(
    `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
    ({ request, params }) => {
      const trip = currentTrip();
      if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
        return problemResponse('TRIP_CHANGED');
      }
      const itemId = String(params.itemId);
      const type = String(params.constraintType);
      const next = {
        ...trip,
        version: trip.version + 1,
        days: trip.days.map((day) => ({
          ...day,
          items: day.items.map((item) =>
            item.id === itemId
              ? {
                  ...item,
                  // Only the named constraint goes. The others are copied
                  // through untouched, which is invariant 7 modelled rather
                  // than assumed.
                  constraints: item.constraints.filter((c) => c.type !== type),
                }
              : item,
          ),
        })),
      } as typeof trip;
      tripState = next;
      return HttpResponse.json(
        { trip: next, changedItemIds: [itemId] },
        { headers: { ETag: `"${String(next.version)}"` } },
      );
    },
  ),
  // MOCK DATA (FE-307). setTripItemConstraint has no approved example either
  // (BA-041). Stateful for the same reason as the release above: a lock that
  // is set has to stay set, and the version has to advance so a stale ETag
  // shows up as a conflict rather than passing silently.
  http.put(
    `${API_BASE}/trips/:tripId/items/:itemId/constraints/:constraintType`,
    async ({ request, params }) => {
      const trip = currentTrip();
      if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
        return problemResponse('TRIP_CHANGED');
      }
      const itemId = String(params.itemId);
      const type = String(params.constraintType);
      const body = (await request.json()) as { type: string };
      // The contract requires the body's type to equal the path's, so a mock
      // that ignored the mismatch would let a real bug through.
      if (body.type !== type) return problemResponse('VALIDATION_FAILED');
      const next = {
        ...trip,
        version: trip.version + 1,
        days: trip.days.map((day) => ({
          ...day,
          items: day.items.map((item) =>
            item.id === itemId
              ? {
                  ...item,
                  // Replaces this one type and copies the rest through, which
                  // is invariant 7 modelled rather than assumed.
                  constraints: [
                    ...item.constraints.filter((c) => c.type !== type),
                    body as (typeof item.constraints)[number],
                  ],
                }
              : item,
          ),
        })),
      } as typeof trip;
      tripState = next;
      return HttpResponse.json(
        { trip: next, changedItemIds: [itemId] },
        { headers: { ETag: `"${String(next.version)}"` } },
      );
    },
  ),
  http.post(`${API_BASE}/trips/:tripId/items`, async ({ request }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const body = (await request.json()) as {
      placeId: string;
      candidateId?: string | null;
      date: string;
      position: number;
      startTime?: string | null;
    };
    const candidates = currentCandidates();
    const candidate = candidates.items.find((c) => c.id === body.candidateId);
    // The contract's 201 is "item added and candidate marked scheduled": one
    // transaction, so the mock applies both or neither (invariant 5).
    const itemId = crypto.randomUUID();
    const nextTrip = {
      ...trip,
      version: trip.version + 1,
      days: trip.days.map((day) =>
        day.date === body.date
          ? {
              ...day,
              items: [
                ...day.items,
                {
                  id: itemId,
                  place: candidate?.place ?? trip.days[0]?.items[0]?.place,
                  date: body.date,
                  position: body.position,
                  startTime: body.startTime ?? null,
                  durationMinutes: null,
                  note: null,
                  constraints: [],
                  crowd: null,
                },
              ],
            }
          : day,
      ),
    } as typeof trip;
    tripState = nextTrip;
    candidateState = {
      ...candidates,
      items: candidates.items.map((c) =>
        c.id === body.candidateId
          ? { ...c, status: 'SCHEDULED' as const, scheduledTripItemId: itemId }
          : c,
      ),
    };
    return HttpResponse.json(
      { trip: nextTrip, changedItemIds: [itemId] },
      { status: 201, headers: { ETag: `"${String(nextTrip.version)}"` } },
    );
  }),

  // MOCK DATA (FE-106). getTrip and replaceTripInterests have no approved
  // example, so these are schema-valid guesses. Delete with BA-031.
  //
  // Stateful on purpose: the interest card reads an ETag, sends it back as
  // If-Match and expects a new one. A handler that returned a fixed version
  // would let a stale-ETag bug pass, because every save would look fresh.
  http.get(`${API_BASE}/trips/:tripId`, () => {
    const trip = currentTrip();
    return HttpResponse.json(trip, { headers: { ETag: `"${String(trip.version)}"` } });
  }),
  http.patch(`${API_BASE}/trips/:tripId`, async ({ request }) => {
    const trip = currentTrip();
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const patch = (await request.json()) as Partial<typeof trip>;
    // The contract refuses a date-range shrink while an item lies outside the
    // new range, and says so with 422 rather than deleting anything. Modelled
    // because FR-TRP-05's whole point is that no item is implicitly removed.
    const endDate = patch.endDate ?? trip.endDate;
    const startDate = patch.startDate ?? trip.startDate;
    const orphaned = trip.days.some(
      (day) => day.items.length > 0 && (day.date < startDate || day.date > endDate),
    );
    if (orphaned) return problemResponse('VALIDATION_FAILED');

    tripState = { ...trip, ...patch, version: trip.version + 1 };
    return HttpResponse.json(tripState, {
      headers: { ETag: `"${String(tripState.version)}"` },
    });
  }),
  http.put(`${API_BASE}/trips/:tripId/interests`, async ({ request }) => {
    const trip = currentTrip();
    // The contract requires If-Match; a mismatch is the 409 the screen recovers
    // from. Modelled here so the conflict path is exercised for real rather
    // than only by a test that forces it.
    if (request.headers.get('If-Match') !== `"${String(trip.version)}"`) {
      return problemResponse('TRIP_CHANGED');
    }
    const body = (await request.json()) as {
      interests: (typeof trip)['interests'];
    };
    tripState = { ...trip, interests: body.interests, version: trip.version + 1 };
    return HttpResponse.json(tripState, {
      headers: { ETag: `"${String(tripState.version)}"` },
    });
  }),

  // MOCK DATA (FE-104). parseTripImport, remapTripImport and confirmTripImport
  // have no approved example (BA-060), so these model the contract rather than
  // echo a fixture.
  //
  // The raw text is read to decide nothing is empty and is then DROPPED. It is
  // never stored in module state, never put in a response, never logged — the
  // handler is the one place a mock could quietly start retaining it, and
  // invariant 10 is the whole reason this operation has the shape it has.
  http.post(`${API_BASE}/trip-imports/parse`, async ({ request }) => {
    const body = (await request.json()) as { rawText?: string };
    if (!body.rawText) return problemResponse('VALIDATION_FAILED');
    importDraftState = buildImportDraft();
    return HttpResponse.json(importDraftState, {
      headers: {
        ETag: `"${String(importDraftState.version)}"`,
        'Cache-Control': 'no-store',
      },
    });
  }),

  http.patch(`${API_BASE}/trip-imports/:draftId`, async ({ request }) => {
    const draft = currentImportDraft();
    if (request.headers.get('If-Match') !== `"${String(draft.version)}"`) {
      return problemResponse('IMPORT_DRAFT_CHANGED');
    }
    const body = (await request.json()) as {
      updates: {
        clientKey: string;
        placeId?: string | null;
        date?: string | null;
        startTime?: string | null;
        dismissed?: boolean;
      }[];
    };

    let items = draft.items;
    let unresolved = draft.unresolved;

    for (const update of body.updates) {
      // `dismissed` withdraws rather than resolves (#223). It is the same
      // field for a token and an item because they are the same dead end: the
      // draft holds something the person cannot act on and cannot remove.
      if (update.dismissed === true) {
        items = items.filter((item) => item.clientKey !== update.clientKey);
        unresolved = unresolved.filter((token) => token.clientKey !== update.clientKey);
        continue;
      }
      const token = unresolved.find((t) => t.clientKey === update.clientKey);
      if (token && update.placeId != null) {
        // Resolving a token turns it into an item, which is what makes the
        // unresolved list shrink and READY reachable.
        const place =
          token.suggestions.find((p) => p.id === update.placeId) ??
          placeFixtures.searchPage.items.find((p) => p.id === update.placeId);
        if (!place) return problemResponse('VALIDATION_FAILED');
        unresolved = unresolved.filter((t) => t.clientKey !== update.clientKey);
        items = [
          ...items,
          {
            clientKey: token.clientKey,
            place,
            originalLabel: token.label,
            date: update.date ?? null,
            startTime: update.startTime ?? null,
            position: items.length,
            confidence: 1,
            constraints: [],
          },
        ];
        continue;
      }
      // Absent means "leave alone", so only the fields actually sent move.
      items = items.map((item) =>
        item.clientKey === update.clientKey
          ? {
              ...item,
              date: update.date === undefined ? item.date : update.date,
              startTime:
                update.startTime === undefined ? item.startTime : update.startTime,
            }
          : item,
      );
    }

    const next: ImportDraft = {
      ...draft,
      items,
      unresolved,
      version: draft.version + 1,
    };
    importDraftState = { ...next, status: importDraftStatus(next) };
    return HttpResponse.json(importDraftState, {
      headers: {
        ETag: `"${String(importDraftState.version)}"`,
        'Cache-Control': 'private, no-store',
      },
    });
  }),

  http.post(`${API_BASE}/trip-imports/:draftId/confirm`, async ({ request }) => {
    const draft = currentImportDraft();
    if (request.headers.get('If-Match') !== `"${String(draft.version)}"`) {
      return problemResponse('IMPORT_DRAFT_CHANGED');
    }
    // The server refuses a draft that still holds something unanswered. Modelled
    // here so the screen's gate is checked against a server that also gates,
    // rather than against one that accepts anything the screen happens to send.
    if (importDraftStatus(draft) !== 'READY') {
      return problemResponse('VALIDATION_FAILED');
    }
    const body = (await request.json()) as { title: string };
    const created = {
      ...tripFixtures.detailCreated,
      id: crypto.randomUUID(),
      title: body.title,
    };
    tripState = null;
    tripPageState = null;
    importDraftState = { ...draft, status: 'CONFIRMED' };
    return HttpResponse.json(created, {
      status: 201,
      headers: {
        ETag: `"${String(created.version)}"`,
        'Cache-Control': 'private, no-store',
      },
    });
  }),
];
