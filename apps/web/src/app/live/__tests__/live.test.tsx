// @vitest-environment happy-dom
import { readFileSync } from 'node:fs';
import type { components } from '@nullnull/api-client';
import {
  candidateFixtures,
  placeFixtures,
  sessionFixtures,
  tripFixtures,
} from '@nullnull/contracts';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

type LiveAreaResult = components['schemas']['LiveAreaResult'];
type LivePlace = components['schemas']['LivePlace'];
type LivePlaceDetail = components['schemas']['LivePlaceDetail'];

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.kakao;
});

function liveFixture<T>(name: string): T {
  return JSON.parse(
    readFileSync(`../../packages/contracts/fixtures/live/${name}.json`, 'utf8'),
  ) as T;
}

function renderLive(initialEntry = '/live') {
  const router = createMemoryRouter(routes, { initialEntries: [initialEntry] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('FE-401 Live area list', () => {
  it('renders the map-first shell, persistent data state and accessible sheet control', async () => {
    const result = liveFixture<LiveAreaResult>('area-result-live');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(result)));

    renderLive();

    expect(await screen.findByRole('region', { name: /Live map/i })).toBeVisible();
    expect(await screen.findByTestId('live-persistent-state')).toHaveTextContent(
      /Observed live/i,
    );
    expect(screen.getByRole('button', { name: /Lower place list/i })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByRole('tab', { name: /Current trip/i })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('requests the approved list-only query and renders every returned area', async () => {
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
  });

  it('keeps empty, unavailable and request failure as different states', async () => {
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

  it('FE-401-T2 labels stale observations without presenting them as live', async () => {
    const stale = liveFixture<LiveAreaResult>('area-result-stale');
    server.use(http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(stale)));

    renderLive();

    expect(await screen.findAllByText('Update delayed')).not.toHaveLength(0);
    expect(screen.queryByText('Observed live')).not.toBeInTheDocument();
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
    expect(screen.getByText('명동')).toBeVisible();
    expect(
      screen.getByRole('link', { name: /View Live information for 경복궁/i }),
    ).toHaveAttribute('href', `/live/places/${places[0]?.place.id}`);
    expect(areaPlaceRequests).toBe(1);
  });

  it('searches canonically and offers each result as a map selection', async () => {
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
    const result = await screen.findByRole('button', {
      name: /Show 경복궁 on the map/i,
    });
    expect(result).toBeVisible();
    expect(result).not.toHaveTextContent('›');
  });

  it('shows a selected search result on the map before opening its Live page', async () => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', 'test-key');
    const user = userEvent.setup();
    const areas = liveFixture<LiveAreaResult>('area-result-live');
    const detail = liveFixture<LivePlaceDetail>('place-detail-live');
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
          constructor(public container: HTMLElement) {}
        },
        load: (callback) => callback(),
      },
    };
    server.use(
      http.post(`${API_BASE}/live/areas`, () => HttpResponse.json(areas)),
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPage),
      ),
      http.get(`${API_BASE}/places/:placeId`, ({ params }) => {
        expect(params.placeId).toBe(detail.place.id);
        return HttpResponse.json(placeFixtures.detail);
      }),
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(detail)),
    );

    renderLive();
    await user.type(await screen.findByRole('searchbox'), '경복궁');
    await user.click(
      await screen.findByRole('button', { name: /Show 경복궁 on the map/i }),
    );

    expect(screen.getByRole('searchbox')).toHaveValue('');
    expect(screen.getByRole('button', { name: /Raise place list/i })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    const marker = await screen.findByRole('button', { name: '경복궁' });
    await user.click(marker);
    expect(
      await screen.findByRole('heading', { level: 1, name: '경복궁' }),
    ).toBeVisible();
  });

  it('saves the live place to the representative trip without changing its schedule', async () => {
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
    await user.click(
      await screen.findByRole('button', { name: /Save to representative trip/i }),
    );

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
  it('keeps the candidate action and its schedule note in the fixed bottom bar', async () => {
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

  it('FE-402-T1 distinguishes a verified absence of alternatives from a request error', async () => {
    const none = liveFixture<LivePlaceDetail>('place-detail-related-none');
    server.use(
      http.get(`${API_BASE}/live/places/:placeId`, () => HttpResponse.json(none)),
    );

    const view = renderLive(`/live/places/${none.place.id}`);
    expect(
      await screen.findByText('No valid alternative is available right now'),
    ).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

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
});
