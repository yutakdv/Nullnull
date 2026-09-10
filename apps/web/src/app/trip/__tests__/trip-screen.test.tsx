// @vitest-environment happy-dom
//
// FE-301 acceptance (FR-TRP-01), S07-1 `410:1738`.
//
// FE-301-T1: day/item/candidate counts are distinct from the empty state and
//            no total is wrong.
// FE-301-T2: default/loading/empty/error/offline/stale each render.
// FE-301-T3: keyboard reach, focus, accessible names.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

function renderTrip(id = trip.id) {
  const router = createMemoryRouter(routes, { initialEntries: [`/trip/${id}`] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function loaded() {
  return await screen.findByRole('heading', { level: 1, name: trip.title });
}

describe('FE-301-T1 the counts are right and distinct', () => {
  it('shows the candidate count from the contract field', async () => {
    renderTrip();
    await loaded();
    // 5 in the fixture, while `candidates` is an empty array: taking the
    // array length would show 0 and under-report the total.
    expect(trip.candidates).toHaveLength(0);
    expect(
      screen.getByText(
        copy['trip.candidates'].replace('{count}', String(trip.candidateCount)),
      ),
    ).toBeInTheDocument();
  });

  it('sums the scheduled items across every day', async () => {
    renderTrip();
    await loaded();
    const total = trip.days.reduce((n, d) => n + d.items.length, 0);
    expect(total).toBe(3);
    expect(
      screen.getByText(copy['trip.itemCount'].replace('{count}', String(total))),
    ).toBeInTheDocument();
  });

  it('states the trip length as nights and days', async () => {
    renderTrip();
    await loaded();
    // 10-04 to 10-07 is 3 nights, 4 days.
    expect(
      screen.getByText(
        copy['trip.length'].replace('{nights}', '3').replace('{days}', '4'),
      ),
    ).toBeInTheDocument();
  });

  it('distinguishes an empty day from an empty trip', async () => {
    renderTrip();
    await loaded();
    // The fixture has two days with nothing on them, so the per-day empty
    // state renders while the whole-trip empty state does not.
    expect(screen.getAllByText(copy['trip.dayEmpty']).length).toBeGreaterThan(0);
    expect(screen.queryByText(copy['trip.empty'])).not.toBeInTheDocument();
  });

  it('shows the whole-trip empty state when nothing is scheduled', async () => {
    const blank = {
      ...trip,
      days: trip.days.map((d) => ({ ...d, items: [] })),
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(blank, { headers: { ETag: '"3"' } }),
      ),
    );
    renderTrip();
    await loaded();
    expect(screen.getByText(copy['trip.empty'])).toBeInTheDocument();
    // And not a count of zero, which reads as a working screen with no data.
    expect(
      screen.queryByText(copy['trip.itemCount'].replace('{count}', '0')),
    ).not.toBeInTheDocument();
  });

  it('orders items by position rather than array order', async () => {
    const shuffled = {
      ...trip,
      days: trip.days.map((d) => ({ ...d, items: [...d.items].reverse() })),
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(shuffled, { headers: { ETag: '"3"' } }),
      ),
    );
    renderTrip();
    await loaded();
    const names = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    // Day 1 holds 경복궁 at position 0 and 인사동 at position 1.
    expect(names[0]).toBe('경복궁');
    expect(names[1]).toBe('인사동');
  });
});

describe('FE-301-T2 the screen renders each state', () => {
  it('reports loading before the trip arrives', async () => {
    // Held open so the pending state is observable: without the delay the mock
    // resolves in the same tick and the test would assert on a screen that has
    // already finished loading.
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, async () => {
        await delay('infinite');
        return HttpResponse.json(trip, { headers: { ETag: '"3"' } });
      }),
    );
    renderTrip();
    // Two nodes carry the copy (the heading and the status line), so this
    // asserts on the live region rather than on the string alone.
    expect(await screen.findByRole('status')).toHaveTextContent(copy['trip.loading']);
  });

  it('offers a retry when the request fails', async () => {
    server.use(http.get(`${API_BASE}/trips/:tripId`, () => HttpResponse.error()));
    renderTrip();
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['trip.error']);
    expect(screen.getByRole('button', { name: copy['trip.retry'] })).toBeInTheDocument();
  });

  it('says a missing trip is missing, and offers no retry for it', async () => {
    server.use(http.get(`${API_BASE}/trips/:tripId`, () => problemResponse('NOT_FOUND')));
    renderTrip();
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['trip.notFound']);
    // Retrying a 404 just repeats the 404.
    expect(
      screen.queryByRole('button', { name: copy['trip.retry'] }),
    ).not.toBeInTheDocument();
  });

  it('shows a time as a time, not the raw contract value', async () => {
    renderTrip();
    await loaded();
    // The contract's `time` format carries an offset ("09:30:00+09:00").
    expect(screen.queryByText(/\+09:00/)).not.toBeInTheDocument();
    expect(screen.queryByText(/09:30:00/)).not.toBeInTheDocument();
  });

  it('says when an item has no time rather than leaving it blank', async () => {
    const untimed = {
      ...trip,
      days: trip.days.map((d) => ({
        ...d,
        items: d.items.map((i) => ({ ...i, startTime: null })),
      })),
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(untimed, { headers: { ETag: '"3"' } }),
      ),
    );
    renderTrip();
    await loaded();
    expect(screen.getAllByText(copy['trip.timeUnset']).length).toBeGreaterThan(0);
  });
});

describe('FE-301-T1 locks are shown as status, not as controls', () => {
  it('names each lock the item carries', async () => {
    renderTrip();
    await loaded();
    expect(screen.getByText(copy['trip.lock.MUST_VISIT'])).toBeInTheDocument();
    expect(screen.getByText(copy['trip.lock.DATE'])).toBeInTheDocument();
    expect(screen.getByText(copy['trip.lock.TIME'])).toBeInTheDocument();
  });

  it('offers no lock button, because unlocking is FE-304', async () => {
    renderTrip();
    await loaded();
    // A pressable lock that does nothing would be worse than a status label.
    for (const label of ['MUST_VISIT', 'DATE', 'TIME'] as const) {
      expect(
        screen.queryByRole('button', { name: copy[`trip.lock.${label}`] }),
      ).not.toBeInTheDocument();
    }
  });
});

describe('FE-301 renders no value the contract does not carry', () => {
  it('shows no crowd level, because every fixture crowd is null', async () => {
    renderTrip();
    await loaded();
    // CrowdMetric requires a full DataProvenance; nothing invents one.
    for (const day of trip.days) {
      for (const item of day.items) expect(item.crowd).toBeNull();
    }
    expect(screen.queryByText(/혼잡/)).not.toBeInTheDocument();
  });

  it('shows no weather, which exists nowhere in the contract (FCR-030)', async () => {
    renderTrip();
    await loaded();
    expect(screen.queryByText(/☀|☂|🌤|맑음|흐림/)).not.toBeInTheDocument();
  });

  it('shows no raw categoryCode, which is machine text (BA-022)', async () => {
    renderTrip();
    await loaded();
    // The contract types categoryCode as a free string with no enum and no
    // display name, so there is nothing to translate it against.
    expect(screen.queryByText('ATTRACTION')).not.toBeInTheDocument();
    expect(screen.queryByText('STREET')).not.toBeInTheDocument();
  });

  it('shows no route distance or travel time (FCR-005)', async () => {
    renderTrip();
    await loaded();
    expect(screen.queryByText(/km|도보|徒歩/)).not.toBeInTheDocument();
  });
});

describe('FE-301-T3 the screen is reachable and named', () => {
  it('names the trip as the page heading', async () => {
    renderTrip();
    expect(await loaded()).toBeInTheDocument();
  });

  it('gives each day section its own accessible name', async () => {
    renderTrip();
    await loaded();
    const sections = screen.getAllByRole('region');
    expect(sections.length).toBeGreaterThanOrEqual(trip.days.length);
  });

  it('filters to one day by chip and back to all', async () => {
    const user = userEvent.setup();
    renderTrip();
    await loaded();
    await user.click(
      screen.getByRole('button', { name: copy['trip.day'].replace('{n}', '2') }),
    );
    // Day 2 holds 명동 only.
    expect(screen.getByRole('heading', { level: 3, name: '명동' })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { level: 3, name: '경복궁' }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: copy['trip.allDays'] }));
    expect(screen.getByRole('heading', { level: 3, name: '경복궁' })).toBeInTheDocument();
  });

  it('marks the selected chip for a screen reader, not by colour alone', async () => {
    const user = userEvent.setup();
    renderTrip();
    await loaded();
    const all = screen.getByRole('button', { name: copy['trip.allDays'] });
    expect(all).toHaveAttribute('aria-pressed', 'true');
    const day1 = screen.getByRole('button', {
      name: copy['trip.day'].replace('{n}', '1'),
    });
    await user.click(day1);
    expect(day1).toHaveAttribute('aria-pressed', 'true');
    expect(all).toHaveAttribute('aria-pressed', 'false');
  });

  it('does not offer P1 actions as pressable buttons', async () => {
    renderTrip();
    await loaded();
    // `준비 중`: inert text, so there is nothing to press and no request.
    expect(
      screen.queryByRole('button', { name: new RegExp(copy['trip.optimize']) }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(copy['trip.optimize'])).toBeInTheDocument();
  });

  it('reaches the day chips by keyboard', async () => {
    const user = userEvent.setup();
    renderTrip();
    await loaded();
    await user.tab();
    expect(screen.getByRole('button', { name: copy['trip.allDays'] })).toHaveFocus();
  });
});
