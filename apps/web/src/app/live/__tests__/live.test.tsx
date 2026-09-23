// @vitest-environment happy-dom
import type { components } from '@nullnull/api-client';
import {
  candidateFixtures,
  liveFixtures,
  placeFixtures,
  relatedFixtures,
  sessionFixtures,
  tripFixtures,
} from '@nullnull/contracts';
import { QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';
import { formatReferenceTime } from '../../../shared/crowd/reference-time.js';

type LiveAreaResult = components['schemas']['LiveAreaResult'];
type LivePlace = components['schemas']['LivePlace'];
type LivePlaceDetail = components['schemas']['LivePlaceDetail'];

afterEach(() => {
  act(() => onlineManager.setOnline(true));
  vi.unstubAllEnvs();
  delete window.kakao;
});

const LIVE_FIXTURES = {
  'area-result-live': liveFixtures.areaResultLive,
  'area-result-replay': liveFixtures.areaResultReplay,
  'area-result-unavailable': liveFixtures.areaResultUnavailable,
  'area-result-stale': liveFixtures.areaResultStale,
  'area-places': liveFixtures.areaPlaces,
  'place-detail-live': liveFixtures.placeDetailLive,
  'place-detail-related-none': liveFixtures.placeDetailRelatedNone,
  'place-detail-related-checking': liveFixtures.placeDetailRelatedChecking,
} as const;

function liveFixture<T>(name: keyof typeof LIVE_FIXTURES): T {
  return structuredClone(LIVE_FIXTURES[name]) as T;
}

function renderLive(initialEntry = '/live', client = createQueryClient()) {
  const router = createMemoryRouter(routes, { initialEntries: [initialEntry] });
  return render(
    <QueryClientProvider client={client}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-401 Live area list', () => {
  it('keeps cached areas visible and distinguishes offline from missing data', async () => {
    renderLive();
    await screen.findByTestId('live-persistent-state');
    act(() => onlineManager.setOnline(false));
    expect(
      await screen.findByText(
        'You are offline. Previously loaded readings may be out of date.',
      ),
    ).toBeVisible();
    expect(screen.getByRole('list', { name: 'Crowding by area now' })).toBeVisible();
    act(() => onlineManager.setOnline(true));
    await waitFor(() =>
      expect(
        screen.queryByText(
          'You are offline. Previously loaded readings may be out of date.',
        ),
      ).not.toBeInTheDocument(),
    );
  });

  it('reports background refresh and retains readings when it fails', async () => {
    const client = createQueryClient();
    renderLive('/live', client);
    await screen.findByTestId('live-persistent-state');
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post(`${API_BASE}/live/areas`, async () => {
        await gate;
        return HttpResponse.error();
      }),
    );
    act(() => {
      void client.invalidateQueries({ queryKey: ['live', 'areas'] });
    });
    expect(await screen.findByText('Refreshing crowd readings.')).toBeVisible();
    await act(async () => {
      release?.();
    });
    expect(
      await screen.findByText('Could not refresh. Showing previously loaded readings.'),
    ).toBeVisible();
    expect(screen.getByRole('list', { name: 'Crowding by area now' })).toBeVisible();
    expect(screen.queryByText('Refreshing crowd readings.')).not.toBeInTheDocument();
  });

  it('FE-401-T1 FE-403-T2 keeps the area list available when the map key is unavailable', async () => {
    const result = liveFixture<LiveAreaResult>('area-result-live');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(result)));

    renderLive();

    expect(await screen.findByRole('heading', { name: 'Live' })).toBeVisible();
    expect(screen.getByRole('region', { name: /Live map/i })).toBeVisible();
    expect(screen.getByText('Could not load the map')).toBeVisible();
    expect(await screen.findByTestId('live-persistent-state')).toHaveTextContent(
      /Observed live/i,
    );
    expect(screen.getByTestId('live-persistent-state')).not.toHaveTextContent(
      result.generatedAt,
    );
    expect(screen.queryByRole('button', { name: /Lower place list/i })).toBeNull();
    expect(screen.getByRole('tab', { name: /Current trip/i })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('FE-403-T2 renders loading independently from empty and failure', async () => {
    const result = liveFixture<LiveAreaResult>('area-result-live');
    let release: (() => void) | undefined;
    server.use(
      http.post(`${API_BASE}/live/areas`, async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json(result);
      }),
    );

    const view = renderLive();
    expect(await screen.findByText('Loading live areas')).toBeVisible();
    release?.();
    view.unmount();
  });

  it('FE-401-T1 requests the approved list-only query and renders every returned area', async () => {
    const result = liveFixture<LiveAreaResult>('area-result-live');
    let body: unknown = null;
    server.use(
      http.post(`${API_BASE}/live/areas`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(result);
      }),
    );

    renderLive();

    expect(await screen.findByRole('button', { name: /광화문·덕수궁/ })).toBeVisible();
    expect(screen.getByRole('button', { name: /명동 관광특구/ })).toBeVisible();
    await waitFor(() => {
      expect(body).toEqual({ mode: 'AUTO', regionCode: '11', viewport: null });
    });
    expect(document.body.textContent).not.toMatch(/37\.\d+|126\.\d+/);
    expect(document.body.textContent).not.toMatch(
      /\b(?:walk|detour)\b|\+\d+\s*min|\d+(?:\.\d+)?\s*km/i,
    );
  });

  it('FE-403-T2 keeps empty, unavailable and request failure as different states', async () => {
    const unavailable = liveFixture<LiveAreaResult>('area-result-unavailable');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(unavailable)));

    let view = renderLive();
    expect(
      await screen.findByText(/Live observations are unavailable right now/i),
    ).toBeVisible();

    server.use(
      http.post(`${API_BASE}/live/areas`, () =>
        HttpResponse.json({ ...unavailable, mode: 'LIVE' }),
      ),
    );
    view.unmount();
    view = renderLive();
    expect(await screen.findByText(/No areas to show/i)).toBeVisible();

    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.error()));
    view.unmount();
    renderLive();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /couldn't check live areas/i,
    );
  });

  it('FE-401-T2 FE-403-T2 labels stale observations without presenting them as live', async () => {
    const stale = liveFixture<LiveAreaResult>('area-result-stale');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(stale)));

    renderLive();

    expect(await screen.findAllByText('Update delayed')).not.toHaveLength(0);
    expect(screen.queryByText('Observed live')).not.toBeInTheDocument();
  });

  it('FE-403-T1 shows replay observation time without using response generation time', async () => {
    const replay = liveFixture<LiveAreaResult>('area-result-replay');
    const observedAt = replay.areas[0]?.crowd?.provenance.observedAt;
    if (!observedAt) throw new Error('Live fixture must include observedAt');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(replay)));

    renderLive();

    const state = await screen.findByTestId('live-persistent-state');
    expect(state).toHaveTextContent(/replay/i);
    expect(state).toHaveTextContent(/not live/i);
    // The badge names the observation in Seoul time, not the raw UTC instant,
    // and never the response's generation time.
    expect(state).toHaveTextContent(
      `Observed ${formatReferenceTime(observedAt, 'en-US')}`,
    );
    expect(state).not.toHaveTextContent(observedAt);
    expect(state).not.toHaveTextContent(formatReferenceTime(replay.generatedAt, 'en-US'));
  });

  it('uses the reviewed Seoul four-stage wording instead of the generic five-stage copy', async () => {
    const result = liveFixture<LiveAreaResult>('area-result-live');
    const firstArea = result.areas[0];
    if (!firstArea?.crowd) throw new Error('Live fixture must include a crowd metric');
    const seoulLevel = {
      ...result,
      areas: [
        {
          ...firstArea,
          crowd: { ...firstArea.crowd, ordinalLevel: '3' },
        },
      ],
    };
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(seoulLevel)));

    renderLive();

    expect(await screen.findByText('3 · Slightly crowded')).toBeVisible();
    expect(screen.getByRole('img', { name: 'Seoul crowd level 3 of 4' })).toBeVisible();
    expect(screen.queryByText('3 · Moderate')).not.toBeInTheDocument();
  });

  it('loads mapped places only after an area is selected', async () => {
    const user = userEvent.setup();
    const result = liveFixture<LiveAreaResult>('area-result-live');
    const places = liveFixture<LivePlace[]>('area-places');
    let areaPlaceRequests = 0;
    server.use(
      http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(result)),
      http.get(`${API_BASE}/live/areas/:areaId/places`, ({ params }) => {
        areaPlaceRequests += 1;
        expect(params.areaId).toBe(result.areas[0]?.id);
        return HttpResponse.json(places);
      }),
    );

    renderLive();
    const area = await screen.findByRole('button', { name: /광화문·덕수궁/ });
    expect(areaPlaceRequests).toBe(0);

    await user.click(area);

    expect(await screen.findByText('경복궁')).toBeVisible();
    expect(screen.getByText('북촌한옥마을')).toBeVisible();
    expect(screen.queryByText('명동')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /View Live information for 경복궁/i }),
    ).toHaveAttribute('href', `/live/places/${places[0]?.place.id}`);
    expect(areaPlaceRequests).toBe(1);
  });

  it('shows a selected place at its detail coordinates without inventing area centroids', async () => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', 'test-key');
    const centers: unknown[] = [];
    window.kakao = {
      maps: {
        CustomOverlay: class {
          constructor(private options: { content: HTMLElement; position: unknown }) {}
          setMap(map: { container: HTMLElement } | null) {
            if (map) map.container.append(this.options.content);
            else this.options.content.remove();
          }
        },
        LatLng: class {
          constructor(
            public latitude: number,
            public longitude: number,
          ) {}
        },
        Map: class {
          constructor(
            public container: HTMLElement,
            options: { center: unknown },
          ) {
            centers.push(options.center);
          }
        },
        load: (callback) => callback(),
      },
    };
    const user = userEvent.setup();
    const result = liveFixture<LiveAreaResult>('area-result-live');
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    let detailRequests = 0;
    server.use(
      http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(result)),
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPage),
      ),
      http.get(`${API_BASE}/places/:placeId`, ({ params }) => {
        expect(params.placeId).toBe(detail.place.id);
        detailRequests += 1;
        return HttpResponse.json(detail.place);
      }),
    );

    renderLive();
    const map = await screen.findByRole('region', { name: 'Live map' });
    expect(within(map).queryByRole('button')).not.toBeInTheDocument();
    expect(detailRequests).toBe(0);
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    const showOnMap = await screen.findByRole('button', {
      name: 'Show 경복궁 on the map',
    });
    await user.click(showOnMap);

    const marker = await within(map).findByRole('button', { name: '경복궁' });
    expect(marker).toBeVisible();
    expect(showOnMap).toHaveAttribute('aria-pressed', 'true');
    expect(detailRequests).toBe(1);
    expect(centers.at(-1)).toEqual(detail.place.location);
    expect(within(map).queryByRole('button', { name: /광화문/ })).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /View Live information for 경복궁/i }),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: /한국관광공사/ })).toBeVisible();
    await user.click(marker);
    expect(await screen.findByRole('heading', { name: '경복궁' })).toBeVisible();
  });

  it('keeps the detail link usable when the selected place cannot be loaded for the map', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPage),
      ),
      http.get(`${API_BASE}/places/:placeId`, () => HttpResponse.error()),
    );
    renderLive();
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    await user.click(
      await screen.findByRole('button', { name: 'Show 경복궁 on the map' }),
    );
    expect(
      await screen.findByText('We couldn’t load this place on the map'),
    ).toBeVisible();
    expect(
      screen.getByRole('link', { name: /View Live information for 경복궁/i }),
    ).toBeVisible();
  });

  it('searches canonically and offers each result as a direct detail link', async () => {
    const user = userEvent.setup();
    const areas = liveFixture<LiveAreaResult>('area-result-live');
    server.use(
      http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(areas)),
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        expect(await request.json()).toEqual({ query: '경복궁' });
        return HttpResponse.json(placeFixtures.searchPage);
      }),
    );

    renderLive();
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    const result = await screen.findByRole('link', {
      name: /View Live information for 경복궁/i,
    });
    expect(result).toBeVisible();
    expect(result).toHaveAttribute(
      'href',
      `/live/places/${placeFixtures.searchPage.items[0]?.id}`,
    );
    expect(result).not.toHaveTextContent('›');
  });

  it('FE-401-T2 shows searching, then no results, as two different states', async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    server.use(
      http.post(`${API_BASE}/places/search`, async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json({ ...placeFixtures.searchPage, items: [] });
      }),
    );

    renderLive();
    await user.type(await screen.findByRole('searchbox'), '없는곳');
    expect(await screen.findByText('Searching places')).toBeVisible();
    expect(screen.queryByText('No matching places')).not.toBeInTheDocument();
    release?.();
    expect(await screen.findByText('No matching places')).toBeVisible();
    expect(screen.queryByText('Searching places')).not.toBeInTheDocument();
  });

  it('FE-401-T2 offers a retry on a failed search that keeps the typed words', async () => {
    const user = userEvent.setup();
    // Every keystroke searches, so only the full query fails: twice, which
    // covers the contract's one automatic retry and leaves the error on screen.
    let failures = 2;
    server.use(
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        const body = (await request.json()) as { query: string };
        if (body.query === '경복궁' && failures > 0) {
          failures -= 1;
          return problemResponse('INTERNAL_ERROR');
        }
        return HttpResponse.json(placeFixtures.searchPage);
      }),
    );

    renderLive();
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    const alert = await screen.findByRole('alert', {}, { timeout: 4000 });
    expect(alert).toHaveTextContent("We couldn't search places");
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(
      await screen.findByRole('link', { name: /View Live information for 경복궁/i }),
    ).toBeVisible();
    expect(screen.getByRole('searchbox')).toHaveValue('경복궁');
  });

  it('FE-401-T3 opens a named search result link with the keyboard', async () => {
    const user = userEvent.setup();
    const areas = liveFixture<LiveAreaResult>('area-result-live');
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    server.use(
      http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(areas)),
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPage),
      ),
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive();
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    const result = await screen.findByRole('link', {
      name: /View Live information for 경복궁/i,
    });
    result.focus();
    expect(result).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(
      await screen.findByRole('heading', { level: 1, name: '경복궁' }),
    ).toBeVisible();
  });

  it('FE-402-T3 saves without changing the schedule and restores action focus', async () => {
    const user = userEvent.setup();
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    const requests: Array<{
      tripId: string;
      body: unknown;
      idempotencyKey: string | null;
    }> = [];
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
      http.post(`${API_BASE}/trips/:tripId/candidates`, async ({ params, request }) => {
        requests.push({
          tripId: String(params.tripId),
          body: await request.json(),
          idempotencyKey: request.headers.get('Idempotency-Key'),
        });
        return HttpResponse.json(candidateFixtures.saveResultCreated, { status: 201 });
      }),
    );

    renderLive(`/live/places/${detail.place.id}`);
    const save = await screen.findByRole('button', {
      name: /Save to representative trip/i,
    });
    save.focus();
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(requests).toHaveLength(1);
    });
    expect(requests[0]).toEqual({
      tripId: tripFixtures.page.items[0]?.id,
      body: { placeId: detail.place.id, source: { type: 'LIVE' } },
      idempotencyKey: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      ),
    });
    expect(await screen.findByRole('status')).toHaveTextContent(
      /Saved to your representative trip\. Your itinerary is unchanged\./i,
    );
    expect(save).toHaveFocus();
  });

  it('does not guess a trip when the owner has no representative trip', async () => {
    const user = userEvent.setup();
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    let candidateRequests = 0;
    server.use(
      http.post(`${API_BASE}/demo/sessions`, () =>
        HttpResponse.json(
          {
            ...sessionFixtures.bootstrap,
            owner: { ...sessionFixtures.owner, activeTripId: null },
          },
          { status: 201 },
        ),
      ),
      http.get(`${API_BASE}/me`, () =>
        HttpResponse.json({ ...sessionFixtures.owner, activeTripId: null }),
      ),
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
      http.post(`${API_BASE}/trips/:tripId/candidates`, () => {
        candidateRequests += 1;
        return HttpResponse.json(candidateFixtures.saveResultCreated, { status: 201 });
      }),
    );

    renderLive(`/live/places/${detail.place.id}`);
    await user.click(
      await screen.findByRole('button', { name: /Choose a trip to save/i }),
    );

    expect(await screen.findByRole('heading', { name: /My trips/i })).toBeVisible();
    expect(candidateRequests).toBe(0);
  });
});

describe('FE-402 Live place detail', () => {
  it('renders the Seoul four-stage reading in English on place detail', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    if (!detail.crowd) throw new Error('Missing crowd fixture');
    detail.crowd.ordinalLevel = '3';
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );
    renderLive(`/live/places/${detail.place.id}`);
    const bar = await screen.findByRole('img', { name: 'Seoul crowd level 3 of 4' });
    expect(bar.children).toHaveLength(4);
    expect(screen.getByText('3 · Slightly crowded')).toBeVisible();
  });

  it('FE-402-T2 renders loading before detail data arrives', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    let release: (() => void) | undefined;
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json(detail);
      }),
    );

    const view = renderLive(`/live/places/${detail.place.id}`);
    expect(await screen.findByRole('status')).toHaveTextContent(
      /Loading place information/i,
    );
    release?.();
    view.unmount();
  });

  it('FE-402-T2 says the detail may be out of date while offline, and not afterwards', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive(`/live/places/${detail.place.id}`);
    await screen.findByRole('heading', { name: detail.place.name });
    act(() => onlineManager.setOnline(false));
    expect(
      await screen.findByText(
        'You are offline. Previously loaded readings may be out of date.',
      ),
    ).toBeVisible();
    // The cached reading stays on screen; it is labelled, not removed.
    expect(screen.getByRole('heading', { name: detail.place.name })).toBeVisible();
    act(() => onlineManager.setOnline(true));
    await waitFor(() =>
      expect(
        screen.queryByText(
          'You are offline. Previously loaded readings may be out of date.',
        ),
      ).not.toBeInTheDocument(),
    );
  });

  it('FE-402-T2 labels stale detail data without presenting it as live', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    if (!detail.crowd) throw new Error('Live detail fixture must include crowd data');
    const stale: LivePlaceDetail = {
      ...detail,
      dataState: 'STALE',
      crowd: {
        ...detail.crowd,
        state: 'STALE',
        provenance: {
          ...detail.crowd.provenance,
          sourceState: 'STALE',
          freshness: 'STALE',
          comparisonEligible: false,
        },
      },
    };
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(stale)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    expect(await screen.findAllByText('Update delayed')).not.toHaveLength(0);
    expect(screen.queryByText('Observed live')).not.toBeInTheDocument();
  });

  it('FCR-011 trace keeps KTO place credit separate from Seoul crowd credit', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    expect(await screen.findByRole('link', { name: /한국관광공사/ })).toBeVisible();
    expect(screen.getByRole('link', { name: /서울특별시/ })).toBeVisible();
  });

  it('FE-402-T2 keeps the default candidate action and schedule note visible', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    const save = await screen.findByRole('button', {
      name: /Save to representative trip/i,
    });
    const fixedBar = save.closest('[data-fixed="true"]');
    expect(fixedBar).not.toBeNull();
    expect(
      within(fixedBar as HTMLElement).getByText(
        /Saves this as a candidate\. Your itinerary stays unchanged\./i,
      ),
    ).toBeVisible();
  });

  it('uses the emphasized app-bar title treatment on place details', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    expect(await screen.findByText('Place crowd information')).toHaveAttribute(
      'data-size',
      'large',
    );
  });

  it('FE-402-T1 FE-402-T2 distinguishes a verified absence from request failure', async () => {
    const none = liveFixture<LivePlaceDetail>('place-detail-related-none');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(none)),
    );

    const view = renderLive(`/live/places/${none.place.id}`);
    expect(
      await screen.findByText('No valid alternative is available right now'),
    ).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse other areas' })).toHaveAttribute(
      'href',
      '/live',
    );

    server.use(http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.error()));
    view.unmount();
    renderLive(`/live/places/${none.place.id}`);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /couldn't load this place/i,
    );
  });

  it('FE-402-T2 keeps a checking relation visibly distinct from no alternatives', async () => {
    const checking = liveFixture<LivePlaceDetail>('place-detail-related-checking');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(checking)),
    );

    renderLive(`/live/places/${checking.place.id}`);

    expect(await screen.findByText('Checking alternatives')).toBeVisible();
    expect(
      screen.queryByText('No valid alternative is available right now'),
    ).not.toBeInTheDocument();
  });

  it('FE-402-T1 suppresses comparison-ineligible crowd values in alternatives', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    const crowd = liveFixture<LivePlace[]>('area-places')[0]?.crowd;
    const alternative = relatedFixtures.page.items[0];
    if (!crowd || !alternative) throw new Error('Fixtures must include an alternative');
    const ineligible: LivePlaceDetail = {
      ...detail,
      related: {
        ...detail.related,
        state: 'SIMILAR',
        items: [
          {
            ...alternative,
            crowd: {
              ...crowd,
              provenance: { ...crowd.provenance, comparisonEligible: false },
            },
          },
        ],
      },
    };
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(ineligible)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    const link = await screen.findByRole('link', { name: alternative.place.name });
    const row = link.closest('li');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).queryByRole('img')).toBeNull();
    expect(within(row as HTMLElement).getByText(/different basis/i)).toBeVisible();
  });

  it('FE-402-T1 does not compare TEMPORAL metrics across different places', async () => {
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
    const crowd = liveFixture<LivePlace[]>('area-places')[0]?.crowd;
    const alternative = relatedFixtures.page.items[0];
    if (!crowd || !alternative) throw new Error('Fixtures must include an alternative');
    const temporalProvenance = {
      ...crowd.provenance,
      comparisonAxis: 'TEMPORAL' as const,
      comparisonEligible: true,
    };
    const temporal: LivePlaceDetail = {
      ...detail,
      crowd: { ...crowd, provenance: temporalProvenance },
      related: {
        ...detail.related,
        state: 'SIMILAR',
        items: [
          {
            ...alternative,
            crowd: { ...crowd, provenance: temporalProvenance },
          },
        ],
      },
    };
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(temporal)),
    );

    renderLive(`/live/places/${detail.place.id}`);

    const link = await screen.findByRole('link', { name: alternative.place.name });
    const row = link.closest('li');
    expect(row).not.toBeNull();
    expect(within(row as HTMLElement).queryByRole('img')).toBeNull();
    expect(within(row as HTMLElement).getByText(/different basis/i)).toBeVisible();
  });
});
