// @vitest-environment happy-dom
//
// FE-301 acceptance (FR-TRP-01), S07-1 `410:1738`.
//
// FE-301-T1: day/item/candidate counts are distinct from the empty state and
//            no total is wrong.
// FE-301-T2: default/loading/empty/error/offline/stale each render.
// FE-301-T3: keyboard reach, focus, accessible names.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { crowdFixtures, tripFixtures } from '@nullnull/contracts';
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

  it('formats the trip date range for the selected locale', async () => {
    localStorage.setItem('nullnull.locale', 'en-US');
    renderTrip();
    await loaded();

    expect(screen.getByText('Oct 4, 2026 – Oct 7, 2026')).toBeInTheDocument();
    expect(screen.queryByText('2026.10.04 – 10.07')).toBeNull();
    localStorage.removeItem('nullnull.locale');
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

describe('FE-301 trip title editing from the detail header', () => {
  it('validates, saves, and keeps the renamed trip after reopening the detail', async () => {
    const user = userEvent.setup();
    const firstView = renderTrip();
    await loaded();

    await user.click(screen.getByRole('button', { name: 'Edit trip name' }));
    const title = screen.getByLabelText(copy['trip.field.title']);
    await user.clear(title);
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));
    expect(screen.getByRole('alert')).toHaveTextContent(copy['trip.error.title-empty']);

    await user.type(title, 'Autumn Seoul');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Autumn Seoul' }),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Edit trip name' })).toHaveFocus();
    });

    firstView.unmount();
    renderTrip();
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Autumn Seoul' }),
    ).toBeInTheDocument();
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
    // The contract dropped the UTC offset from wall-clock times (#145), so
    // the fixture carries "09:30:00" and an offset can no longer appear in it.
    // Asserting its absence checked nothing and read as though it did; what is
    // still worth checking is that the raw contract value — seconds and all —
    // does not reach the screen.
    expect(screen.queryByText(/09:30:00/)).not.toBeInTheDocument();
  });

  it('shows visit order when an item has no exact time', async () => {
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
    for (const day of untimed.days) {
      for (const item of day.items) {
        const card = screen
          .getByRole('heading', { level: 3, name: item.place.name })
          .closest('article');
        expect(card).not.toBeNull();
        expect(
          within(card as HTMLElement).getByText(
            copy['trip.visitOrder'].replace('{position}', String(item.position + 1)),
          ),
        ).toBeInTheDocument();
      }
    }
  });
});

describe('FE-301-T1 locks are shown as status, not as controls', () => {
  it('names each lock the item carries', async () => {
    renderTrip();
    await loaded();
    const mustVisit = screen.getByText(copy['trip.lock.MUST_VISIT']);
    const placeName = screen.getByRole('heading', { level: 3, name: '경복궁' });
    expect(mustVisit).toBeInTheDocument();
    expect(mustVisit.parentElement?.previousElementSibling).toBe(placeName);
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

describe('FE-301-T1 renders no value the contract does not carry', () => {
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

  it('renders the approved place labels and five-step crowd reading when present', async () => {
    const firstDay = trip.days[0];
    const firstItem = firstDay?.items[0];
    if (!firstDay || !firstItem) throw new Error('fixture shape changed');
    const detailed = {
      ...trip,
      days: [
        {
          ...firstDay,
          items: [
            {
              ...firstItem,
              place: {
                ...firstItem.place,
                categoryName: '관광지',
                regionName: '종로구',
              },
              crowd: {
                ...crowdFixtures.seriesForecast.points[0],
                ordinalLevel: '4',
              },
            },
            ...firstDay.items.slice(1),
          ],
        },
        ...trip.days.slice(1),
      ],
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(detailed, { headers: { ETag: '"3"' } }),
      ),
    );

    renderTrip();
    await loaded();

    expect(screen.getByText('관광지')).toBeInTheDocument();
    expect(screen.getByText('종로구')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Level 4 of 5' })).toBeInTheDocument();
    expect(screen.getByText('4 · 혼잡')).toBeInTheDocument();
  });

  it('shows real stops without route distance or travel-time claims (FCR-005 trace)', async () => {
    renderTrip();
    await loaded();
    const firstItem = trip.days.flatMap((day) => day.items)[0];
    expect(firstItem).toBeDefined();
    expect(screen.getAllByText(firstItem?.place.name ?? '').length).toBeGreaterThan(0);
    expect(document.body).not.toHaveTextContent(
      /↓\s*\d+(?:\.\d+)?\s*(?:km|mi|miles?)|(?:walking|walk|travel time|route time|도보|이동 시간|徒歩)[^\n·]{0,24}\d+\s*(?:min|minutes?|분)|\b\d+(?:\.\d+)?\s*(?:km|mi|miles?)\b/i,
    );
  });

  it('keeps the supported optimization entry without a quieter-date banner (FCR-013 trace)', async () => {
    renderTrip();
    await loaded();
    expect(screen.getByRole('link', { name: copy['trip.optimize'] })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /compare|비교/i })).toBeNull();
    expect(screen.queryByRole('link', { name: /compare|비교/i })).toBeNull();
    expect(screen.queryByText(/더 여유로운 날짜|quieter date/i)).toBeNull();
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

  it('keeps the view header free of the edit-only add-place action', async () => {
    localStorage.setItem('nullnull.locale', 'ko-KR');
    renderTrip();
    await loaded();

    expect(
      within(screen.getByRole('banner')).queryByRole('link', { name: '장소 추가' }),
    ).not.toBeInTheDocument();
    localStorage.removeItem('nullnull.locale');
  });

  it('switches to the Figma edit state instead of appending the metadata form', async () => {
    const user = userEvent.setup();
    localStorage.setItem('nullnull.locale', 'ko-KR');
    renderTrip();
    await loaded();

    await user.click(screen.getByRole('button', { name: '일정 편집' }));

    expect(await screen.findByText('편집 중')).toBeInTheDocument();
    expect(
      within(screen.getByRole('banner')).getByRole('link', { name: '장소 추가' }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('banner')).getByRole('link', { name: /담아둔 장소/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: 'AI로 일정 최적화' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByLabelText('여행 이름')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('navigation', { name: '주요 메뉴' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '변경사항 저장' })).toBeInTheDocument();

    localStorage.removeItem('nullnull.locale');
  });

  it('uses Korean day labels in the Korean itinerary', async () => {
    localStorage.setItem('nullnull.locale', 'ko-KR');
    renderTrip();
    await loaded();

    expect(screen.getByRole('button', { name: '1일차' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /1일차/ })).toBeInTheDocument();
    localStorage.removeItem('nullnull.locale');
  });

  it('restores the existing item-card layout in edit mode', async () => {
    const user = userEvent.setup();
    renderTrip();
    await loaded();

    const move = copy['trip.move.open'].replace('{name}', '경복궁');
    const remove = copy['trip.remove.open'].replace('{name}', '경복궁');
    expect(screen.queryByRole('button', { name: move })).toBeNull();
    expect(screen.queryByRole('button', { name: remove })).toBeNull();

    await user.click(screen.getByRole('button', { name: copy['trip.editStart'] }));

    const actions = screen.getByRole('button', { name: '경복궁 item actions' });
    expect(actions).toBeInTheDocument();
    await user.click(actions);
    expect(screen.getByRole('button', { name: move })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: remove })).toBeInTheDocument();
    expect(screen.getByText('9:30 AM')).toBeInTheDocument();
  });

  it('does not mount the retired optimization undo block', async () => {
    let historyReads = 0;
    server.use(
      http.get(`${API_BASE}/optimizations`, () => {
        historyReads += 1;
        return HttpResponse.json({ items: [], nextCursor: null });
      }),
    );

    renderTrip();
    await loaded();
    await delay(30);

    expect(historyReads).toBe(0);
    expect(screen.queryByText(copy['trip.applied.badge.available'])).toBeNull();
  });

  it('does not offer the optimization entry as a pressable button', async () => {
    renderTrip();
    await loaded();
    // Optimization is FE-501, so it stays `준비 중`: inert text, nothing to
    // press and no request. Editing is real as of FE-302.
    expect(
      screen.queryByRole('button', { name: new RegExp(copy['trip.optimize']) }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(copy['trip.optimize'])).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: copy['trip.editStart'] }),
    ).toBeInTheDocument();
  });

  it('reaches the header controls and the day chips by keyboard', async () => {
    const user = userEvent.setup();
    renderTrip();
    await loaded();
    // Asserted as "reachable in order", not as an exact tab index: the header
    // gains controls as slices land (FE-302's edit, FE-303's saved-places
    // link), and pinning a position makes every later slice edit this test
    // without telling us anything about accessibility.
    const wanted = [
      screen.getByRole('link', {
        name: new RegExp(copy['trip.candidates'].replace('{count}', '')),
      }),
      screen.getByRole('button', { name: copy['trip.editStart'] }),
      screen.getByRole('button', { name: copy['trip.allDays'] }),
    ];
    const seen: HTMLElement[] = [];
    for (let i = 0; i < 8 && seen.length < wanted.length; i += 1) {
      await user.tab();
      const active = document.activeElement;
      if (active instanceof HTMLElement && wanted.includes(active)) seen.push(active);
    }
    expect(seen).toEqual(wanted);
  });
});

// CMP-ATT-001 on the trip screen, which is submission screenshot #2.
//
// The screen already renders the credit. What was missing is any test that
// enters that branch: no trip fixture carried sourceAttribution, so
// `item.place.sourceAttribution ? ... : null` short-circuited on the first
// operand in every run and the render could be deleted with the suite green.
//
// #281 changed the input, not this test. Of the six trip-*.json fixtures, the
// two that hold items (scheduled, reservation) now carry the KTO credit on
// every place; the other four (created, interests, page, page-empty) have no
// items at all, so they hold no place to attribute and are not evidence either
// way. The override below stays because it pins the exact credit this case
// asserts rather than inheriting whatever the shared fixture happens to say.
// That is the same shape as the thumbnailUrl:null trap — the guard's own
// comment says the real server populates the field, which is exactly why the
// fixture's silence is not evidence of anything.
//
// The fixtures are the shared BE/FE contract and are not edited here; the
// state they cannot express is supplied by an override.
describe('FE-301 the trip screen credits the places it shows', () => {
  const CREDIT = '출처: ⓒ한국관광공사 (여행 화면 검증용)';

  function tripWithCredit() {
    const [firstDay, ...restDays] = trip.days;
    if (!firstDay) throw new Error('fixture has no days');
    const [firstItem, ...restItems] = firstDay.items;
    if (!firstItem) throw new Error('fixture day has no items');
    return {
      ...trip,
      days: [
        {
          ...firstDay,
          items: [
            {
              ...firstItem,
              place: {
                ...firstItem.place,
                sourceAttribution: {
                  ...(firstItem.place.sourceAttribution ?? {}),
                  source: 'KTO_KOR_SERVICE_2',
                  sourceDisplayName: '한국관광공사 국문 관광정보',
                  sourceRegistryVersion: 4,
                  attribution: CREDIT,
                  officialUrl: 'https://www.data.go.kr/data/15101578/openapi.do',
                  licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
                  license: '이용허락범위 제한 없음',
                },
              },
            },
            ...restItems,
          ],
        },
        ...restDays,
      ],
    };
  }

  it('shows the server credit verbatim on the sourced stop itself', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(tripWithCredit(), { headers: { ETag: '"3"' } }),
      ),
    );
    renderTrip();
    await loaded();

    // Scoped to the item row rather than the document: the credit also appears
    // in the day's own summary, so a document-wide query passes even when the
    // per-item render is deleted. That ambiguity is what made three earlier
    // attempts at this assertion prove nothing.
    const name = trip.days[0]?.items[0]?.place.name ?? '';
    const row = (await screen.findByRole('heading', { level: 3, name })).closest(
      'article',
    );
    expect(row).not.toBeNull();
    // Verbatim, never composed by the client (CMP-ATT-003).
    // The credit renders inside its officialUrl link, so the link role names
    // it exactly once; getByText matches both the wrapper span and the anchor.
    expect(
      within(row as HTMLElement).getByRole('link', { name: CREDIT }),
    ).toBeInTheDocument();
  });

  it('does not reserve empty rows when optional place details are absent', async () => {
    const credited = tripWithCredit();
    const [firstDay, ...restDays] = credited.days;
    if (!firstDay) throw new Error('fixture has no days');
    const [firstItem, ...restItems] = firstDay.items;
    if (!firstItem) throw new Error('fixture day has no items');
    const sparse = {
      ...credited,
      days: [
        {
          ...firstDay,
          items: [
            {
              ...firstItem,
              durationMinutes: null,
              constraints: firstItem.constraints.filter(
                (constraint) => constraint.type === 'MUST_VISIT',
              ),
              place: {
                ...firstItem.place,
                categoryName: null,
                regionName: null,
              },
            },
            ...restItems,
          ],
        },
        ...restDays,
      ],
    };
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(sparse, { headers: { ETag: '"3"' } }),
      ),
    );
    renderTrip();
    await loaded();

    const row = screen
      .getByRole('heading', { level: 3, name: firstItem.place.name })
      .closest('article');
    expect(row).not.toBeNull();
    expect(row?.querySelector('p')).toBeNull();
    expect(
      within(row as HTMLElement).queryByRole('list', { name: copy['trip.locks'] }),
    ).not.toBeInTheDocument();
  });
});
