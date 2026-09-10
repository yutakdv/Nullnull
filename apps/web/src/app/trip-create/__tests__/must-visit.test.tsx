// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-103 acceptance (FR-TRC-04, FR-TRC-05, FR-TRC-08).
//
// Two assertions carry most of the weight:
//   - the query never reaches a URL, because searchPlaces is a read-only POST
//     specifically so free-form text stays out of CDN, proxy and history logs;
//   - the card shows no crowd figure, because the contract has no such field
//     and inventing one is the unsourced comparison invariant 8 forbids.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { placeFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const [first, second] = placeFixtures.searchPage.items;

let requests: { method: string; url: string }[] = [];

beforeEach(() => {
  requests = [];
  server.events.on('request:start', ({ request }) => {
    requests.push({ method: request.method, url: request.url });
  });
});
afterEach(() => {
  server.events.removeAllListeners();
});

function renderScreen() {
  const router = createMemoryRouter(routes, { initialEntries: ['/start/must-visit'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function searchFor(text: string) {
  const user = userEvent.setup();
  renderScreen();
  await user.type(await screen.findByRole('searchbox'), text);
  return user;
}

describe('searching for a place keeps the query out of every URL', () => {
  it('sends the query in a POST body, not a query string', async () => {
    let body: unknown = null;
    server.use(
      http.post(`${API_BASE}/places/search`, async ({ request }) => {
        body = await request.json();
        return HttpResponse.json(placeFixtures.searchPage);
      }),
    );
    await searchFor('경복궁');

    await waitFor(() => {
      expect(body).toEqual({ query: '경복궁' });
    });
    const search = requests.filter((r) => r.url.includes('/places/search'));
    expect(search.every((r) => r.method === 'POST')).toBe(true);
    // The text must not appear anywhere in a URL, encoded or not.
    for (const request of requests) {
      expect(decodeURIComponent(request.url)).not.toContain('경복궁');
    }
  });

  it('does not search on an empty field', async () => {
    renderScreen();
    await screen.findByRole('searchbox');
    expect(requests.filter((r) => r.url.includes('/places/search'))).toEqual([]);
  });
});

describe('results and the kept list', () => {
  it('lists what the search returned', async () => {
    await searchFor('서울');
    expect(await screen.findByText(first?.name ?? '')).toBeInTheDocument();
  });

  it('keeps a place and marks it as a must-visit', async () => {
    const user = await searchFor('서울');
    const add = await screen.findAllByRole('button', { name: copy['mustVisit.add'] });
    await user.click(add[0] as HTMLElement);

    const kept = await screen.findByRole('list', { name: copy['mustVisit.picked'] });
    expect(kept).toHaveTextContent(first?.name ?? '');
    expect(kept).toHaveTextContent('꼭 가요');
  });

  it('removes a kept place again', async () => {
    const user = await searchFor('서울');
    await user.click(
      (
        await screen.findAllByRole('button', { name: copy['mustVisit.add'] })
      )[0] as HTMLElement,
    );
    await user.click(
      await screen.findByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    );
    expect(await screen.findByText(copy['mustVisit.pickedEmpty'])).toBeInTheDocument();
  });

  it('will not keep the same place twice', async () => {
    const user = await searchFor('서울');
    const add = await screen.findAllByRole('button', { name: copy['mustVisit.add'] });
    await user.click(add[0] as HTMLElement);
    await waitFor(() => {
      expect(
        screen.getAllByRole('button', { name: copy['mustVisit.add'] })[0],
      ).toBeDisabled();
    });
  });

  it('says so when nothing matches', async () => {
    server.use(
      http.post(`${API_BASE}/places/search`, () =>
        HttpResponse.json(placeFixtures.searchPageEmpty),
      ),
    );
    await searchFor('없는장소');
    expect(await screen.findByText(copy['mustVisit.noResults'])).toBeInTheDocument();
  });

  it('reports a failed search instead of showing nothing', async () => {
    server.use(http.post(`${API_BASE}/places/search`, () => HttpResponse.error()));
    await searchFor('서울');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['mustVisit.searchError'],
    );
  });
});

describe('the card shows only what the contract supplies', () => {
  it('renders no crowd figure, because PlaceSummary has none', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');

    // FCR-029: Figma shows "4 · 혼잡" and a forecast badge here. Backend/AI
    // confirmed crowd is planned but not yet in the contract, so the card ships
    // without it; a number here would be invented. This test is what keeps that
    // true until PlaceSummary actually gains the field.
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/혼잡/);
    expect(body).not.toMatch(/공식 혼잡 예측/);
    expect(screen.queryByText(/ⓒ한국관광공사/)).not.toBeInTheDocument();
  });

  it('shows the fields the contract does supply', async () => {
    await searchFor('서울');
    expect(await screen.findByText(first?.name ?? '')).toBeInTheDocument();
    // Every result renders, not just the first. getAllByText because a name can
    // also occur inside an address ("명동" is both a place and a street).
    expect(screen.getAllByText(second?.name ?? '').length).toBeGreaterThan(0);
    // The address is the contract field that is already human text.
    expect(screen.getByText(first?.address ?? '')).toBeInTheDocument();
  });

  it('does not show categoryCode, which is machine text', async () => {
    await searchFor('서울');
    await screen.findByText(first?.name ?? '');
    // categoryCode is a free string in the contract with no enum and no display
    // name, so there is nothing to map "ATTRACTION" onto for the user. Asked BE
    // for the vocabulary and its labels on #34 (BA-022).
    expect(screen.queryByText(new RegExp(first?.categoryCode ?? 'x'))).toBeNull();
  });
});

describe('keyboard and continuation', () => {
  it('gives the remove control an accessible name that says which place', async () => {
    const user = await searchFor('서울');
    await user.click(
      (
        await screen.findAllByRole('button', { name: copy['mustVisit.add'] })
      )[0] as HTMLElement,
    );
    expect(
      await screen.findByRole('button', {
        name: `${first?.name ?? ''} ${copy['mustVisit.remove']}`,
      }),
    ).toBeInTheDocument();
  });

  it('continues without keeping anything', async () => {
    const user = userEvent.setup();
    renderScreen();
    await user.click(await screen.findByRole('button', { name: copy['mustVisit.skip'] }));
    await waitFor(() => {
      expect(screen.getByTestId('placeholder-route')).toHaveTextContent('feed');
    });
  });
});
