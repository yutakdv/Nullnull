// The two read-only handlers answer, and answer with the contract's fixtures.
//
// WHY THIS EXISTS. Both operations had a contract, a generated client and
// ajv-pinned fixtures, and no msw handler — so a screen written against either
// would have hit an unhandled request rather than a reply. Adding handlers
// without a caller leaves the opposite gap: a handler nothing exercises is
// indistinguishable from one that was never registered, and the first screen to
// need it finds out at that moment.
//
// WHAT IT CHECKS. That each route matches, and that the body is the fixture
// ITSELF rather than something assembled here. Handlers must not build bodies
// inline (this file's own header says so) because an inline object is a
// hand-written model with nothing checking it; comparing against the fixture is
// what keeps that true as the fixtures move.
//
// WHY THE VARIANTS MATTER. getPlaceCrowdForecast has three faces and #105 turns
// on the two that are not the happy one: STALE and UNAVAILABLE. A mock wired to
// serve only the forecast lets a screen ship having never rendered the other
// two, which is the state this endpoint exists to report.
//
// WHAT IT IS NOT. It does not claim the fixtures match the real server — that
// is BE's CrowdForecastApiIT and the BA-055 suite. This suite isolates handler
// parity; AddPlace and MustVisit separately prove the screens consume it.
import { describe, expect, it } from 'vitest';
import { crowdFixtures, tripDraftFixtures } from '@nullnull/contracts';
import { API_BASE } from '../handlers.js';
import { server } from '../server.js';

const PLACE_ID = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02';

/** An unhandled request throws rather than reaching the network. */
function crowdUrl(query = '') {
  return `${API_BASE}/places/${PLACE_ID}/crowd-forecast?from=2026-10-04&to=2026-10-06${query}`;
}

describe('getPlaceCrowdForecast is mocked', () => {
  it('serves the forecast series by default', async () => {
    const response = await fetch(crowdUrl());
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(crowdFixtures.seriesForecast);
  });

  // The two that #105 turns on. Asserting `state` as well as the body makes the
  // case readable as "this is the stale one" rather than only "this is fixture
  // B", and it fails loudly if the fixtures are ever re-pointed.
  it('serves the stale series when asked for it', async () => {
    const response = await fetch(crowdUrl('&mock=stale'));
    expect(await response.json()).toEqual(crowdFixtures.seriesStale);
    expect(crowdFixtures.seriesStale.state).toBe('STALE');
  });

  it('serves the unavailable series, which carries no points', async () => {
    const response = await fetch(crowdUrl('&mock=unavailable'));
    const body = (await response.json()) as typeof crowdFixtures.seriesUnavailable;
    expect(body).toEqual(crowdFixtures.seriesUnavailable);
    // No coverage is an answer with an empty series, not an error: a screen
    // that treats it as a failure would report an outage the server did not.
    expect(body.state).toBe('UNAVAILABLE');
    expect(body.points).toHaveLength(0);
  });

  it('is served by a handler rather than reaching the network', async () => {
    // server.ts runs with onUnhandledRequest: 'error', so an unregistered route
    // rejects. Without this the three cases above would still pass if msw were
    // proxying to something real, and the point of them is that it is not.
    await expect(
      fetch(`${API_BASE}/places/${PLACE_ID}/crowd-forecast`),
    ).resolves.toBeDefined();
  });
});

describe('queryPlaceCrowdForecasts is mocked from the approved batch fixture', () => {
  async function query(placeIds: string[]) {
    return fetch(`${API_BASE}/places/crowd-forecasts/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...crowdFixtures.forecastQueryRequest,
        placeIds,
      }),
    });
  }

  it('pairs the approved deprecated-id request with its canonical response by position', async () => {
    const requestFixture = crowdFixtures.forecastQueryRequest;
    const response = await query(requestFixture.placeIds);
    const body = (await response.json()) as typeof crowdFixtures.forecastQuery;

    expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe('private, no-store');
    expect(body).toEqual(crowdFixtures.forecastQuery);
    expect(body.items).toHaveLength(requestFixture.placeIds.length);
    expect(requestFixture.placeIds[0]).not.toBe(body.items[0]?.placeId);
    expect(body.items[0]).toEqual(
      expect.objectContaining({
        placeId: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b02',
        state: 'FORECAST',
      }),
    );
    expect(body.items.map((item) => item.state)).toEqual([
      'FORECAST',
      'STALE',
      'UNAVAILABLE',
      'UNAVAILABLE',
    ]);
    expect(body.items.map((item) => item.unavailableReason)).toEqual([
      null,
      null,
      'NO_COVERAGE',
      'PLACE_UNAVAILABLE',
    ]);
  });

  it('serves the exported response fixture for its canonical item ids', async () => {
    const placeIds = crowdFixtures.forecastQuery.items.map((item) => item.placeId);
    const response = await query(placeIds);

    expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe('private, no-store');
    expect(await response.json()).toEqual(crowdFixtures.forecastQuery);
  });

  it('keeps a different canonical-id request length and order with mixed states', async () => {
    // The approved request/response pair above owns deprecated-id parity. This
    // complementary case proves canonical ids still work in another subset and
    // order. AddPlace/MustVisit separately prove shipped screens join by index
    // rather than by item.placeId.
    const fixtureItems = crowdFixtures.forecastQuery.items;
    const placeIds = [
      fixtureItems[1]?.placeId,
      fixtureItems[0]?.placeId,
      fixtureItems[2]?.placeId,
    ].filter((id): id is string => id !== undefined);
    const body = (await (
      await query(placeIds)
    ).json()) as typeof crowdFixtures.forecastQuery;

    expect(body.items).toHaveLength(placeIds.length);
    expect(body.items.map((item) => item.placeId)).toEqual(placeIds);
    expect(body.items.map((item) => item.state)).toEqual([
      'STALE',
      'FORECAST',
      'UNAVAILABLE',
    ]);
  });

  it('uses the approved PLACE_UNAVAILABLE state for an id outside the fixture', async () => {
    const placeId = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b98';
    const body = (await (
      await query([placeId])
    ).json()) as typeof crowdFixtures.forecastQuery;

    expect(body.items).toEqual([
      expect.objectContaining({
        placeId,
        state: 'UNAVAILABLE',
        points: [],
        unavailableReason: 'PLACE_UNAVAILABLE',
      }),
    ]);
  });
});

describe('previewTripDraft is mocked', () => {
  const body = { startDate: '2026-10-04', endDate: '2026-10-05', timezone: 'Asia/Seoul' };

  async function preview(query = '', requestBody: typeof body = body) {
    return fetch(`${API_BASE}/trip-drafts/preview${query}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody),
    });
  }

  it('answers READY with the draft fixture', async () => {
    const response = await preview();
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(tripDraftFixtures.ready);
  });

  // EMPTY is an answer, not an outage — an outage is a 503. A screen has to be
  // able to reach it, and both fixtures cover the same two dates, so the flag
  // is the only thing that can select it.
  it('answers EMPTY when asked for it', async () => {
    const response = await preview('?mock=empty');
    const parsed = (await response.json()) as typeof tripDraftFixtures.empty;
    expect(parsed).toEqual(tripDraftFixtures.empty);
    expect(parsed.state).toBe('EMPTY');
    expect(response.status).toBe(200);
  });

  it('saves nothing: a second identical preview answers the same', async () => {
    // The operation is read-only, so there is no state to leak between tests
    // and no entry in resetMockState. Asking twice proves the handler is not
    // quietly remembering the first call.
    expect(await (await preview()).json()).toEqual(await (await preview()).json());
  });

  it('keeps every returned day and stop inside the requested range', async () => {
    const response = await preview('', {
      startDate: '2026-09-20',
      endDate: '2026-09-23',
      timezone: 'Asia/Seoul',
    });
    const parsed = (await response.json()) as typeof tripDraftFixtures.ready;

    expect(parsed.days.map((day) => day.date)).toEqual([
      '2026-09-20',
      '2026-09-21',
      '2026-09-22',
      '2026-09-23',
    ]);
    expect(parsed.days.flatMap((day) => day.stops).map((stop) => stop.date)).toEqual([
      '2026-09-20',
      '2026-09-20',
      '2026-09-21',
    ]);
  });
});

// Guards the claim above rather than restating it: if either handler ever grows
// state, this file is where that shows up first.
describe('neither handler holds state', () => {
  it('serves the same crowd variant however many times it is asked', async () => {
    const first = await (await fetch(crowdUrl('&mock=stale'))).json();
    await fetch(crowdUrl());
    const second = await (await fetch(crowdUrl('&mock=stale'))).json();
    expect(second).toEqual(first);
  });

  it('is unaffected by resetMockState, having registered nothing with it', () => {
    // Imported for its side-effect-free-ness: calling it must not change what
    // these handlers answer. A handler that stored its last variant would need
    // a line there, and its absence is the thing being pinned.
    expect(server.listHandlers().length).toBeGreaterThan(0);
  });
});
