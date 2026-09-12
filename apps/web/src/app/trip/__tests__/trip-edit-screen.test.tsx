// @vitest-environment happy-dom
//
// FE-302 acceptance (FR-TRP-02, FR-TRP-03, FR-TRP-05), S07-2 `411:1837` and
// the discard dialog `413:2020`.
//
// FE-302-T1: the dirty-exit prompt and the save-conflict recovery never lose
//            the user's edit.
// FE-302-T2: default/loading/error/conflict/validation each render.
// FE-302-T3: keyboard reach, focus into and back out of the dialog, names.
//
// The conflict assertions read what went on the wire rather than the
// component's own state: a form that believes it sent If-Match while sending
// nothing would pass an inspection test and overwrite a concurrent edit.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { problemFixtures, tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { API_BASE, problemResponse } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

interface Sent {
  method: string;
  ifMatch: string | null;
  contentType: string | null;
  body: unknown;
}

let sent: Sent[] = [];

beforeEach(() => {
  sent = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method !== 'PATCH') return;
    const clone = request.clone();
    void clone.json().then(
      (body) => {
        sent.push({
          method: request.method,
          ifMatch: request.headers.get('If-Match'),
          contentType: request.headers.get('content-type'),
          body,
        });
      },
      () => undefined,
    );
  });
});

afterEach(() => {
  server.events.removeAllListeners();
});

function renderTrip() {
  const router = createMemoryRouter(routes, { initialEntries: [`/trip/${trip.id}`] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

/** Opens edit mode and returns the user-event instance. */
async function openEditor() {
  const user = userEvent.setup();
  renderTrip();
  await user.click(await screen.findByRole('button', { name: copy['trip.editStart'] }));
  await screen.findByLabelText(copy['trip.field.title']);
  return user;
}

describe('FE-302-T2 view and edit are separate modes', () => {
  it('shows no form until edit is chosen', async () => {
    renderTrip();
    await screen.findByRole('heading', { level: 1, name: trip.title });
    expect(screen.queryByLabelText(copy['trip.field.title'])).not.toBeInTheDocument();
  });

  it('opens the form seeded from the trip', async () => {
    await openEditor();
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(trip.title);
    expect(screen.getByLabelText(copy['trip.field.startDate'])).toHaveValue(
      trip.startDate,
    );
  });

  it('offers no save until something changes', async () => {
    await openEditor();
    // Nothing typed: the patch would be empty, which the contract rejects.
    const save = screen.getByRole('button', { name: copy['trip.editSave'] });
    expect(save).toBeEnabled();
    await userEvent.setup().click(save);
    await waitFor(() => {
      expect(screen.queryByLabelText(copy['trip.field.title'])).not.toBeInTheDocument();
    });
    // No request went out for a no-op save.
    expect(sent).toHaveLength(0);
  });

  it('blocks a save that breaks a contract limit', async () => {
    const user = await openEditor();
    await user.clear(screen.getByLabelText(copy['trip.field.title']));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['trip.error.title-empty'],
    );
    expect(screen.getByRole('button', { name: copy['trip.editSave'] })).toBeDisabled();
  });
});

describe('FE-302-T1 leaving with unsaved changes asks first', () => {
  it('prompts rather than discarding silently', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));

    expect(await screen.findByRole('dialog')).toHaveTextContent(
      copy['trip.discard.title'],
    );
  });

  it('keeps the edit when the user chooses to keep editing', async () => {
    const user = await openEditor();
    const title = screen.getByLabelText(copy['trip.field.title']);
    await user.type(title, ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.discard.keep'],
      }),
    );

    // Still editing, and the typing survived.
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });

  it('discards only when the user says to leave', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.discard.leave'],
      }),
    );

    await waitFor(() => {
      expect(screen.queryByLabelText(copy['trip.field.title'])).not.toBeInTheDocument();
    });
    // And nothing was saved on the way out.
    expect(sent).toHaveLength(0);
  });

  it('does not prompt when nothing was changed', async () => {
    const user = await openEditor();
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    // A prompt on every cancel trains the user to dismiss it, which is how a
    // real unsaved edit gets thrown away.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByLabelText(copy['trip.field.title'])).not.toBeInTheDocument();
    });
  });

  it('treats a reverted edit as clean', async () => {
    const user = await openEditor();
    const title = screen.getByLabelText(copy['trip.field.title']);
    await user.type(title, 'x');
    await user.clear(title);
    await user.type(title, trip.title);
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('FE-302-T1 saving uses optimistic concurrency', () => {
  it('sends merge-patch with the ETag the trip was read at', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.ifMatch).toBe(`"${String(trip.version)}"`);
    expect(sent[0]?.ifMatch).toMatch(/^"[1-9][0-9]*"$/);
    // BA-011's controller enforces consumes; application/json comes back 415.
    expect(sent[0]?.contentType).toBe('application/merge-patch+json');
  });

  it('patches only the field that changed', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // Resending untouched fields would overwrite a concurrent edit to them.
    expect(sent[0]?.body).toEqual({ title: `${trip.title} 수정` });
  });

  it('keeps what the user typed when the trip changed underneath', async () => {
    server.use(
      http.patch(`${API_BASE}/trips/:tripId`, () => problemResponse('TRIP_CHANGED')),
    );
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    expect(await screen.findByRole('alert')).toHaveTextContent(copy['trip.conflict']);
    // The edit is still on screen: a conflict must not discard it.
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });

  it('offers both ways out of a conflict', async () => {
    server.use(
      http.patch(`${API_BASE}/trips/:tripId`, () => problemResponse('TRIP_CHANGED')),
    );
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    const alert = await screen.findByRole('alert');
    expect(
      within(alert).getByRole('button', { name: copy['trip.conflict.reload'] }),
    ).toBeInTheDocument();
    expect(
      within(alert).getByRole('button', { name: copy['trip.conflict.discard'] }),
    ).toBeInTheDocument();
  });

  it('reloads the trip when the user keeps editing', async () => {
    // The test above proves both buttons exist; neither was ever pressed, so
    // nothing noticed that 계속 편집 only cleared the banner. The cached ETag
    // is stale the moment the server says TRIP_CHANGED, so the next save sent
    // the same If-Match and failed the same way — the form's own comment said
    // "the parent refetches", which no code did.
    let reads = 0;
    server.use(
      http.get(`${API_BASE}/trips/:tripId`, () => {
        reads += 1;
        return HttpResponse.json(trip, {
          headers: { ETag: `"${String(trip.version)}"` },
        });
      }),
      http.patch(`${API_BASE}/trips/:tripId`, () => problemResponse('TRIP_CHANGED')),
    );
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));
    const alert = await screen.findByRole('alert');
    const before = reads;
    await user.click(
      within(alert).getByRole('button', { name: copy['trip.conflict.reload'] }),
    );

    await waitFor(() => {
      expect(reads).toBeGreaterThan(before);
    });
    // And the draft survives: reloading the trip must not discard what the
    // user typed, which is the whole point of offering 계속 편집.
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });
});

describe('FE-302-T1 leaving by the tab bar asks first', () => {
  it('warns before a tab press discards the draft', async () => {
    // beforeunload covers closing the browser and requestClose covers this
    // form's own exit, but neither sees a react-router navigation. Pressing
    // 내 정보 while editing left the screen and discarded the draft with no
    // warning — reproduced before this test existed: the URL became /profile
    // and no dialog appeared.
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));

    expect(await screen.findByRole('dialog')).toHaveTextContent(
      copy['trip.discard.title'],
    );
    // Still here, with what was typed.
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });

  it('stays put when the user keeps editing', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.discard.keep'],
      }),
    );

    // The form is still open and the draft intact.
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );

    // And the blocked navigation was RELEASED, not merely hidden. Without
    // blocker.reset() the router stays blocked and a second tab press does
    // nothing at all — the dialog never returns and the user is stuck on a
    // screen they asked twice to leave. Checked by removing the reset.
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));
    expect(await screen.findByRole('dialog')).toHaveTextContent(
      copy['trip.discard.title'],
    );
  });

  it('lets the user leave when they choose to', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: copy['trip.discard.leave'],
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'profile-heading',
      );
    });
  });

  it('does not ask when nothing was typed', async () => {
    // A clean draft has nothing to lose; a prompt there is noise.
    const user = await openEditor();
    await user.click(screen.getByRole('button', { name: copy['nav.tab.profile'] }));
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'profile-heading',
      );
    });
  });
});

describe('FE-302-T2 a rejected save explains itself', () => {
  it('shows the server message against the field it names', async () => {
    server.use(
      http.patch(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(
          {
            ...problemFixtures.VALIDATION_FAILED,
            fieldErrors: [
              {
                field: 'endDate',
                code: 'ITEMS_OUTSIDE_RANGE',
                message: '범위 밖 일정이 있어요',
              },
            ],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    expect(await screen.findByText('범위 밖 일정이 있어요')).toBeInTheDocument();
    // And the draft is untouched, so the user sees what they typed beside the
    // reason it was refused.
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });

  it('still shows an error for a field this form does not render', async () => {
    server.use(
      http.patch(`${API_BASE}/trips/:tripId`, () =>
        HttpResponse.json(
          {
            ...problemFixtures.VALIDATION_FAILED,
            fieldErrors: [
              {
                field: 'days[0].items[2]',
                code: 'LOCKED',
                message: '고정된 일정이 있어요',
              },
            ],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    // Dropping it would leave a failed save with no stated reason.
    expect(await screen.findByText('고정된 일정이 있어요')).toBeInTheDocument();
  });

  it('surfaces the server refusal for a shrink rather than predicting it', async () => {
    // The default handler models the contract: shrinking past a scheduled item
    // is 422, and nothing is deleted.
    const user = await openEditor();
    const end = screen.getByLabelText(copy['trip.field.endDate']);
    await user.clear(end);
    await user.type(end, '2026-10-04');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    // Day 2 holds an item, so the server refuses.
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByLabelText(copy['trip.field.endDate'])).toHaveValue('2026-10-04');
  });

  it('reports a plain failure without inventing a field', async () => {
    server.use(http.patch(`${API_BASE}/trips/:tripId`, () => HttpResponse.error()));
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));
    expect(await screen.findByRole('alert')).toHaveTextContent(copy['trip.saveFailed']);
  });
});

describe('FE-302-T3 the dialog behaves like a dialog', () => {
  it('puts focus on the safe choice, not the destructive one', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));

    const dialog = await screen.findByRole('dialog');
    // Enter on an unread dialog must not discard the user's work.
    expect(
      within(dialog).getByRole('button', { name: copy['trip.discard.keep'] }),
    ).toHaveFocus();
  });

  it('closes on Escape and keeps the edit', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    await screen.findByRole('dialog');

    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue(
      `${trip.title} 수정`,
    );
  });

  it('has an accessible name', async () => {
    const user = await openEditor();
    await user.type(screen.getByLabelText(copy['trip.field.title']), ' 수정');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    expect(
      await screen.findByRole('dialog', { name: copy['trip.discard.title'] }),
    ).toBeInTheDocument();
  });
});

describe('FE-302-T3 focus returns where it came from', () => {
  it('puts focus back on the edit button after closing', async () => {
    const user = await openEditor();
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: copy['trip.editStart'] })).toHaveFocus();
    });
  });

  it('names every field', async () => {
    await openEditor();
    for (const key of ['title', 'startDate', 'endDate', 'planningLevel'] as const) {
      expect(screen.getByLabelText(copy[`trip.field.${key}`])).toBeInTheDocument();
    }
  });
});
