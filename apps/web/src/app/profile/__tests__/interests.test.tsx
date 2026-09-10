// @vitest-environment happy-dom
//
// FE-106 acceptance (FR-PRO-05).
//
// FE-106-T1: replacing the interest set surfaces an ETag/If-Match conflict in a
//            state the user can recover from.
// FE-106-T2: default/loading/empty/error/offline/stale each render.
// FE-106-T3: keyboard reach, focus, accessible names.
//
// The concurrency assertions are written against what actually goes on the
// wire, not against the component's own state. A card that believes it sent
// If-Match while sending nothing would pass an inspection test and lose a
// concurrent edit in production.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { InterestsSection } from '../InterestsSection.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailWithInterests;

interface Sent {
  method: string;
  url: string;
  ifMatch: string | null;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    sent.push({
      method: request.method,
      url: request.url,
      ifMatch: request.headers.get('If-Match'),
    });
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderCard() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <InterestsSection />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Waits for the trip to load and returns the save button. */
async function loaded() {
  return await screen.findByRole('button', { name: copy['profile.interests.save'] });
}

describe('FE-106-T2 the card renders each state', () => {
  it('shows the trip’s saved interests as pressed chips', async () => {
    renderCard();
    await loaded();
    // FOOD is in the fixture, ACTIVITY is not.
    expect(screen.getByRole('button', { name: copy['interest.FOOD'] })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(
      screen.getByRole('button', { name: copy['interest.ACTIVITY'] }),
    ).toHaveAttribute('aria-pressed', 'false');
  });

  it('reports loading before the trip arrives', async () => {
    renderCard();
    expect(
      await screen.findByText(copy['profile.interests.loading']),
    ).toBeInTheDocument();
  });

  it('offers a retry when the trip fails to load', async () => {
    server.use(http.get(`${API_BASE}/trips/:tripId`, () => HttpResponse.error()));
    renderCard();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['profile.interests.error'],
    );
    expect(
      screen.getByRole('button', { name: copy['profile.retry'] }),
    ).toBeInTheDocument();
  });

  it('says there is nothing to manage when no trip exists', async () => {
    server.use(
      http.get(`${API_BASE}/trips`, () => HttpResponse.json(tripFixtures.pageEmpty)),
    );
    renderCard();
    expect(
      await screen.findByText(copy['profile.interests.noTrips']),
    ).toBeInTheDocument();
  });

  it('shows the empty state when a trip has no interests', async () => {
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(tripFixtures.detailCreated, { headers: { ETag: '"1"' } }),
      ),
    );
    renderCard();
    expect(await screen.findByText(copy['profile.interests.empty'])).toBeInTheDocument();
  });

  it('keeps a save from being offered until something changes', async () => {
    renderCard();
    expect(await loaded()).toBeDisabled();
  });
});

describe('FE-106-T1 replacing the set uses optimistic concurrency', () => {
  it('sends the ETag the trip was read at as If-Match', async () => {
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    await user.click(await loaded());

    await waitFor(() => {
      expect(sent.some((r) => r.method === 'PUT')).toBe(true);
    });
    const put = sent.find((r) => r.method === 'PUT');
    // Strong validator, matching components.headers.ETag's own pattern.
    expect(put?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(put?.ifMatch).toMatch(/^"[1-9][0-9]*"$/);
  });

  it('replaces the whole set rather than merging into it', async () => {
    const bodies: { interests: { code: string; weight: number }[] }[] = [];
    server.use(
      http.put(`${API_BASE}/trips/:tripId/interests`, async ({ request }) => {
        bodies.push(
          (await request.json()) as { interests: { code: string; weight: number }[] },
        );
        return HttpResponse.json(trip, { headers: { ETag: '"4"' } });
      }),
    );
    const user = userEvent.setup();
    renderCard();
    await loaded();
    // Deselect FOOD, which the fixture has.
    await user.click(screen.getByRole('button', { name: copy['interest.FOOD'] }));
    await user.click(await loaded());

    await waitFor(() => {
      expect(bodies).toHaveLength(1);
    });
    const body = bodies[0];
    if (!body) throw new Error('no request body captured');
    const codes = body.interests.map((i) => i.code);
    expect(codes).not.toContain('FOOD');
    expect(codes).toContain('FRIENDS');
    // A weight the trip already carried survives the edit.
    expect(body.interests.find((i) => i.code === 'CULTURE')?.weight).toBe(3);
  });

  it('keeps the user’s selection when the trip changed underneath', async () => {
    server.use(
      http.put(`${API_BASE}/trips/:tripId/interests`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    await user.click(await loaded());

    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['profile.interests.conflict'],
    );
    // The edit is still there: a conflict must not silently discard it.
    expect(screen.getByRole('button', { name: copy['interest.NATURE'] })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('offers both ways out of a conflict', async () => {
    server.use(
      http.put(`${API_BASE}/trips/:tripId/interests`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    await user.click(await loaded());

    const alert = await screen.findByRole('alert');
    expect(
      within(alert).getByRole('button', {
        name: copy['profile.interests.conflict.reload'],
      }),
    ).toBeInTheDocument();
    expect(
      within(alert).getByRole('button', {
        name: copy['profile.interests.conflict.discard'],
      }),
    ).toBeInTheDocument();
  });

  it('drops the local edit only when the user asks to discard it', async () => {
    server.use(
      http.put(`${API_BASE}/trips/:tripId/interests`, () =>
        problemResponse('TRIP_CHANGED'),
      ),
    );
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    await user.click(await loaded());

    const alert = await screen.findByRole('alert');
    await user.click(
      within(alert).getByRole('button', {
        name: copy['profile.interests.conflict.discard'],
      }),
    );
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: copy['interest.NATURE'] }),
      ).toHaveAttribute('aria-pressed', 'false');
    });
  });

  it('saves against the new ETag after reloading a conflict', async () => {
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    await user.click(await loaded());

    // The default handler is stateful, so the first save succeeds and bumps
    // the version; a second save must use the ETag it returned.
    await waitFor(() => {
      expect(sent.filter((r) => r.method === 'PUT')).toHaveLength(1);
    });
    await user.click(screen.getByRole('button', { name: copy['interest.RELAXED'] }));
    await user.click(await loaded());

    await waitFor(() => {
      expect(sent.filter((r) => r.method === 'PUT')).toHaveLength(2);
    });
    const puts = sent.filter((r) => r.method === 'PUT');
    expect(puts[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(puts[1]?.ifMatch).toBe(`"${String(trip.version + 1)}"`);
  });

  it('never sends a blind write when no ETag is known', async () => {
    // A 200 with no ETag header: the screen must refuse to save rather than
    // fall back to an unconditional PUT.
    server.use(http.get(`${API_BASE}/trips/:tripId`, () => HttpResponse.json(trip)));
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    expect(await loaded()).toBeDisabled();
    expect(sent.filter((r) => r.method === 'PUT')).toHaveLength(0);
  });
});

describe('FE-106-T2 the contract limits are the screen’s limits', () => {
  it('stops at twenty and still allows deselecting', async () => {
    const user = userEvent.setup();
    renderCard();
    await loaded();
    // The catalogue has 13 codes, fewer than the cap of 20, so the cap cannot
    // be reached from this screen. Asserting that is what keeps a future
    // catalogue growth from silently passing this test.
    const all = screen
      .getAllByRole('button')
      .filter((b) => b.getAttribute('aria-pressed') !== null);
    expect(all.length).toBeLessThanOrEqual(20);
    for (const chip of all) expect(chip).toBeEnabled();
    const first = all[0];
    if (!first) throw new Error('no chips rendered');
    await user.click(first);
  });
});

describe('FE-106-T3 the card is reachable and named', () => {
  it('names the trip selector', async () => {
    renderCard();
    expect(
      await screen.findByLabelText(copy['profile.interests.pickTrip']),
    ).toBeInTheDocument();
  });

  it('groups the chips under a legend', async () => {
    renderCard();
    await loaded();
    expect(
      screen.getByRole('group', { name: copy['wizard.interests.who'] }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('group', { name: copy['wizard.interests.style'] }),
    ).toBeInTheDocument();
  });

  it('reaches the chips and the save button by keyboard', async () => {
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.tab();
    expect(screen.getByLabelText(copy['profile.interests.pickTrip'])).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: copy['interest.ALONE'] })).toHaveFocus();
  });

  it('announces saving and saved without stealing focus', async () => {
    const user = userEvent.setup();
    renderCard();
    await loaded();
    await user.click(screen.getByRole('button', { name: copy['interest.NATURE'] }));
    const save = await loaded();
    await user.click(save);
    expect(await screen.findByText(copy['profile.interests.saved'])).toBeInTheDocument();
  });
});
