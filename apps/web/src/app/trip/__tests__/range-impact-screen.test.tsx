// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-306 acceptance (FR-TRP-05), S07-2 `411:1837`. No Figma node exists for
// this state — "기간 축소 실패" is listed in SCREEN_REVIEW as a required state
// with no frame, registered as FCR-032.
//
// FE-306-T1: the impact shows before saving, and 취소 restores the dates.
// FE-306-T2: default/absent/present states each render.
// FE-306-T3: keyboard reach, accessible names, live announcement.
//
// The assertion that carries the most weight is that the save button stays
// enabled. The contract's shrink rule is computable from data we hold, so it
// is tempting to block the save — but `trip` is a cached query and may be
// stale, and FR-TRP-05 puts the judgement on the server.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

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

async function openEditor() {
  const user = userEvent.setup();
  renderTrip();
  await user.click(await screen.findByRole('button', { name: copy['trip.editStart'] }));
  await screen.findByLabelText(copy['trip.field.title']);
  return user;
}

/** Types a new start date, which is what shrinks the range. */
async function shrinkTo(user: ReturnType<typeof userEvent.setup>, date: string) {
  const start = screen.getByLabelText(copy['trip.field.startDate']);
  await user.clear(start);
  await user.type(start, date);
}

describe('FE-306-T2 the impact appears only when the range actually narrows', () => {
  it('says nothing when the form opens', async () => {
    await openEditor();
    expect(screen.queryByText(copy['trip.range.impactTitle'])).not.toBeInTheDocument();
  });

  it('says nothing when the range grows', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-01');
    expect(screen.queryByText(copy['trip.range.impactTitle'])).not.toBeInTheDocument();
  });

  it('says nothing while a date field is mid-edit', async () => {
    // Clearing the field to retype it must not flash a list of every stop in
    // the trip: "" sorts before every real date.
    const user = await openEditor();
    await user.clear(screen.getByLabelText(copy['trip.field.endDate']));
    expect(screen.queryByText(copy['trip.range.impactTitle'])).not.toBeInTheDocument();
  });

  it('lists the stops left outside when the range narrows', async () => {
    const user = await openEditor();
    // The fixture schedules 경복궁 and 인사동 on the first day.
    await shrinkTo(user, '2026-10-05');
    // Scoped to the block: the itinerary behind the form names the same
    // places, so a document-wide query matches both and proves nothing.
    const block = await screen.findByRole('complementary', {
      name: copy['trip.range.impactTitle'],
    });
    expect(within(block).getByText('경복궁')).toBeInTheDocument();
    expect(within(block).getByText('인사동')).toBeInTheDocument();
    expect(within(block).queryByText('명동')).not.toBeInTheDocument();
  });

  it('names the locks the contract rule names, and no others', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    const block = await screen.findByRole('complementary', {
      name: copy['trip.range.impactTitle'],
    });
    // 경복궁 carries DATE (named by the rule) and MUST_VISIT (not named).
    expect(within(block).getByText(copy['trip.range.lockDate'])).toBeInTheDocument();
    expect(within(block).queryByText(/must.?visit/i)).not.toBeInTheDocument();
  });

  it('disappears again when the range is widened back', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);
    await shrinkTo(user, trip.startDate);
    await waitFor(() => {
      expect(screen.queryByText(copy['trip.range.impactTitle'])).not.toBeInTheDocument();
    });
  });
});

describe('FE-306-T1 the preview advises and never blocks', () => {
  it('leaves the save enabled while stops are outside the range', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);
    // The whole point: the server decides. A stale cached trip must not cost
    // the user the ability to try (FR-TRP-05).
    expect(screen.getByRole('button', { name: copy['trip.editSave'] })).toBeEnabled();
  });

  it('states that the server decides, rather than predicting refusal', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    expect(await screen.findByText(copy['trip.range.impactNote'])).toBeInTheDocument();
  });

  it('restores the dates when the undo is pressed', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);
    await user.click(screen.getByRole('button', { name: copy['trip.range.undo'] }));

    expect(screen.getByLabelText(copy['trip.field.startDate'])).toHaveValue(
      trip.startDate,
    );
    await waitFor(() => {
      expect(screen.queryByText(copy['trip.range.impactTitle'])).not.toBeInTheDocument();
    });
  });

  it('restores only the dates, leaving an edited title alone', async () => {
    // This is what separates FE-306's 취소 from the whole-form discard: a user
    // who renamed the trip and then mis-set a date keeps the rename.
    const user = await openEditor();
    const title = screen.getByLabelText(copy['trip.field.title']);
    await user.clear(title);
    await user.type(title, '고쳐진 이름');
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);

    await user.click(screen.getByRole('button', { name: copy['trip.range.undo'] }));
    expect(screen.getByLabelText(copy['trip.field.title'])).toHaveValue('고쳐진 이름');
    expect(screen.getByLabelText(copy['trip.field.startDate'])).toHaveValue(
      trip.startDate,
    );
  });
});

describe('FE-306-T3 keyboard and announcement', () => {
  it('gives the block a name a screen reader can find', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    expect(
      await screen.findByRole('complementary', { name: copy['trip.range.impactTitle'] }),
    ).toBeInTheDocument();
  });

  it('reaches the undo by keyboard and activates it with Enter', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);

    const undo = screen.getByRole('button', { name: copy['trip.range.undo'] });
    undo.focus();
    expect(undo).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByLabelText(copy['trip.field.startDate'])).toHaveValue(
      trip.startDate,
    );
  });

  it('announces the restore, outside the block that disappears', async () => {
    const user = await openEditor();
    await shrinkTo(user, '2026-10-05');
    await screen.findByText(copy['trip.range.impactTitle']);
    await user.click(screen.getByRole('button', { name: copy['trip.range.undo'] }));
    // The message has to outlive the block it describes, or it is removed from
    // the tree before a screen reader reads it.
    expect(await screen.findByText(copy['trip.range.undone'])).toBeInTheDocument();
  });
});
