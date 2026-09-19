// The mock's item-mutation replies have the shape of the approved examples (#16).
//
// WHY THIS EXISTS. BE added seven response examples — mutation-add.json and its
// six siblings — and FE's msw handlers answer the same seven operations by
// computing a reply from trip-detail-scheduled.json. Nothing compared the two.
// A handler that drifts from the example is a mock that teaches the screens a
// response shape the server never sends, and the screens' own tests cannot
// notice because they assert against that same mock.
//
// The risk is not hypothetical: `trip-detail-scheduled.json` — the input these
// handlers build from — was edited by BE in this very merge (866b134). A change
// there moves every reply below, and until now the only thing that would have
// caught a disagreement was someone reading both files.
//
// WHAT IT COMPARES, and why not just the keys. A key-set comparison is cheap
// and nearly worthless here: it passes when `position` is 0 instead of 1, when
// `version` fails to advance, when `constraints` empties. So this walks the
// whole reply and compares VALUES, with three narrow exemptions that are
// request-dependent rather than shape-dependent (see REQUEST_SHAPED).
//
// WHAT IT IS NOT. It does not claim the fixtures match the real server — that
// is BE's TripMutationFixtureIT — nor that the handlers are otherwise correct.
// It says one thing: what the mock returns for these seven operations has the
// same shape as what the contract says the server returns.
import { beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures, tripMutationFixtures } from '@nullnull/contracts';
import { API_BASE, resetMockState } from '../handlers.js';

type Json = Record<string, unknown>;

/**
 * Fields whose VALUES legitimately differ between a fixture and a live reply,
 * because they name the particular request rather than the response's shape.
 *
 * Three, and each one earns its place. Ids are minted per call, and
 * `changedItemIds` names whichever item the caller asked about — both are
 * properties of the request, not the shape.
 *
 * `sourceAttribution` is the awkward one and is exempted for a DIFFERENT
 * reason, stated plainly because it is the kind of entry that quietly turns a
 * parity test into a key-set test. It differs between fixture SETS rather than
 * between mock and server: BE's trip fixtures carry `null` on every place,
 * while the candidate and related fixtures — where the replacement places
 * 연희동 카페거리 and 서울숲 actually live — carry the full KTO credit. So the
 * mock is right to answer with the credit its own fixture holds, and the
 * example is right to show null for a place whose fixture has none; neither is
 * a defect this test can resolve. Whether BE's trip fixtures SHOULD carry
 * attribution is a question for #16, not something to hide by comparing
 * loosely everywhere.
 *
 * Everything else — versions, positions, dates, constraints, candidateCount,
 * the rest of the place object — is compared by value. Anything added here
 * needs a reason of one of those two kinds.
 */
const REQUEST_SHAPED = new Set(['id', 'changedItemIds']);

/**
 * Replaces request-shaped values with a marker for their TYPE.
 *
 * A marker rather than deletion: dropping the field would stop this test
 * noticing if a reply lost its `id` altogether, which is a real regression and
 * exactly the sort of thing an exemption list tends to hide.
 */
function normalise(value: unknown, key?: string): unknown {
  // Differs between fixture SETS, so neither the value nor its presence can be
  // compared — see the note on REQUEST_SHAPED.
  if (key === 'sourceAttribution') return '<fixture-set>';
  if (key !== undefined && REQUEST_SHAPED.has(key)) {
    // A marker for the KIND of value, so a field that vanished is still
    // caught. An earlier version returned objects unchanged, which meant an
    // exemption on an object-valued field did nothing at all — null and an
    // object are both "not a string, not an array" — and failed silently
    // rather than loudly.
    if (Array.isArray(value)) return `<${String(value.length)} item(s)>`;
    return value === null ? '<absent>' : '<present>';
  }
  if (Array.isArray(value)) return value.map((entry) => normalise(entry));
  if (value !== null && typeof value === 'object') {
    const out: Json = {};
    for (const [k, v] of Object.entries(value as Json)) out[k] = normalise(v, k);
    return out;
  }
  return value;
}

const TRIP = tripFixtures.detailScheduled;
const TRIP_ID = TRIP.id;
const BASE_VERSION = TRIP.version;

/**
 * The items and dates each fixture's scenario names.
 *
 * Read OFF the fixtures rather than guessed: a first attempt invented plausible
 * requests (move the last item to day 4, add at position 2) and six of seven
 * comparisons failed on the day and position, because each example encodes one
 * particular edit. Decoding them first is what made this test compare the thing
 * it claims to compare.
 */
const GYEONGBOKGUNG = TRIP.days[0]?.items[0];
const INSADONG = TRIP.days[0]?.items[1];
const MYEONGDONG = TRIP.days[1]?.items[0];

/** The ETag the handlers demand, in the form the server sends it. */
const IF_MATCH = `"${String(BASE_VERSION)}"`;

const JSON_HEADERS = { 'If-Match': IF_MATCH, 'Content-Type': 'application/json' };

interface Case {
  /** The fixture this reply must match. */
  readonly fixture: { trip: unknown; changedItemIds: unknown };
  readonly request: () => Promise<Response>;
  /**
   * `addItem` answers 201 because it creates a resource; the other six answer
   * 200. Stated per case rather than accepting any 2xx, so a handler that
   * silently changed its status would be caught here.
   */
  readonly status: number;
}

/**
 * One request per approved example, each reproducing that example's scenario.
 *
 * Table-driven rather than seven hand-written tests so that a fixture added
 * without a case here fails rather than being quietly uncovered — the failure
 * mode that let 14 fixtures arrive unchecked elsewhere in this package.
 */
const CASES: Record<string, Case> = {
  // 서울숲 appended to the empty third day.
  add: {
    fixture: tripMutationFixtures.add,
    status: 201,
    request: () =>
      fetch(`${API_BASE}/trips/${TRIP_ID}/items`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({
          placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b12',
          date: TRIP.days[2]?.date,
          position: 0,
        }),
      }),
  },
  // 명동's start time moved an hour later.
  update: {
    fixture: tripMutationFixtures.update,
    status: 200,
    request: () =>
      fetch(`${API_BASE}/trips/${TRIP_ID}/items/${String(MYEONGDONG?.id)}`, {
        method: 'PATCH',
        headers: {
          'If-Match': IF_MATCH,
          'Content-Type': 'application/merge-patch+json',
        },
        body: JSON.stringify({ startTime: '15:00:00' }),
      }),
  },
  // 명동 moved from the second day to the fourth.
  reorder: {
    fixture: tripMutationFixtures.reorder,
    status: 200,
    request: () =>
      fetch(`${API_BASE}/trips/${TRIP_ID}/items/reorder`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({
          items: [{ itemId: MYEONGDONG?.id, date: TRIP.days[3]?.date, position: 0 }],
        }),
      }),
  },
  // 경복궁 swapped for 연희동 카페거리, keeping its slot.
  replace: {
    fixture: tripMutationFixtures.replace,
    status: 200,
    request: () =>
      fetch(`${API_BASE}/trips/${TRIP_ID}/items/${String(GYEONGBOKGUNG?.id)}/replace`, {
        method: 'POST',
        headers: JSON_HEADERS,
        body: JSON.stringify({
          replacementPlaceId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b11',
          // 경복궁 carries MUST_VISIT, and a replacement changes the PLACE, so
          // the lock has to be named or the edit is refused (invariant 7). The
          // fixture shows it released: its 경복궁 slot comes back as 연희동
          // 카페거리 with DATE alone.
          releaseConstraints: ['MUST_VISIT'],
        }),
      }),
  },
  // 명동 given a TIME lock it did not have.
  constraintSet: {
    fixture: tripMutationFixtures.constraintSet,
    status: 200,
    request: () =>
      fetch(
        `${API_BASE}/trips/${TRIP_ID}/items/${String(MYEONGDONG?.id)}/constraints/TIME`,
        {
          method: 'PUT',
          headers: JSON_HEADERS,
          body: JSON.stringify({
            type: 'TIME',
            locked: true,
            source: 'USER',
            startTime: '14:00:00',
            toleranceMinutes: 30,
          }),
        },
      ),
  },
  // 인사동's TIME lock released.
  constraintRemove: {
    fixture: tripMutationFixtures.constraintRemove,
    status: 200,
    request: () =>
      fetch(
        `${API_BASE}/trips/${TRIP_ID}/items/${String(INSADONG?.id)}/constraints/TIME`,
        { method: 'DELETE', headers: { 'If-Match': IF_MATCH } },
      ),
  },
  // 명동 taken off the itinerary, emptying the second day.
  remove: {
    fixture: tripMutationFixtures.remove,
    status: 200,
    request: () =>
      fetch(
        `${API_BASE}/trips/${TRIP_ID}/items/${String(MYEONGDONG?.id)}?disposition=REMOVE`,
        {
          method: 'DELETE',
          headers: { 'If-Match': IF_MATCH },
        },
      ),
  },
};

describe('the mock answers item mutations with the approved shape (#16)', () => {
  // Each case starts from the unmutated trip. The handlers are stateful — a
  // reply advances the version the next If-Match must carry — so without this
  // the first call would leave every later one answering 409 TRIP_CHANGED.
  // Measured exactly that: one 201 followed by five conflicts.
  beforeEach(() => {
    resetMockState();
  });

  it('covers every approved mutation example, with none left untested', () => {
    // The zero-target guard, in both directions. A table that silently stopped
    // matching the fixture set would leave this file green while covering
    // nothing — the failure this whole file exists to prevent, turned on
    // itself. Naming the keys rather than counting them means a renamed
    // fixture fails here rather than sliding past a length check.
    expect(Object.keys(CASES).sort()).toEqual(Object.keys(tripMutationFixtures).sort());
    expect(Object.keys(CASES).length).toBe(7);
  });

  for (const [name, testCase] of Object.entries(CASES)) {
    it(`${name}: the reply matches mutation-${name}.json`, async () => {
      const response = await testCase.request();

      // A 4xx would make the comparison below vacuous in the most convincing
      // way: an error body has none of the fields, so a lenient comparison
      // would "pass" against it. The status is checked first and named.
      expect(response.status, `${name} was refused: ${String(response.status)}`).toBe(
        testCase.status,
      );

      const body = (await response.json()) as Json;

      // The trip, field for field.
      expect(normalise(body.trip)).toEqual(normalise(testCase.fixture.trip));

      // `changedItemIds` compared by arity rather than value — the ids are
      // whichever items this request named — but it must be present and
      // non-empty, because "the server tells you what changed" is the point of
      // the field.
      expect(Array.isArray(body.changedItemIds)).toBe(true);
      expect((body.changedItemIds as unknown[]).length).toBeGreaterThan(0);
      expect((body.changedItemIds as unknown[]).length).toBe(
        (testCase.fixture.changedItemIds as unknown[]).length,
      );
    });
  }

  it('advances the trip version exactly once, as the ETag promises', async () => {
    // Separate clause, separate id: a reply can have the right shape and still
    // fail to move the version, and the fixture comparison above would not see
    // it because every fixture is at version 4 by construction.
    const response = await CASES.update?.request();
    expect(response?.status).toBe(200);
    const body = (await response?.json()) as { trip: { version: number } };
    expect(body.trip.version).toBe(BASE_VERSION + 1);
    expect(response?.headers.get('ETag')).toBe(`"${String(BASE_VERSION + 1)}"`);
  });
});
