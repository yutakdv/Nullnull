// @vitest-environment happy-dom
//
// happy-dom because the screen navigates (#67).
//
// FE-306 (FR-TRP-05): the date-range edit is reached from the trip itself.
// TripEditForm lives at /trip/:id/settings, and since 93de5679 moved 일정 편집
// to the S07-2 schedule editor nothing in the app linked there: changing a
// trip's dates, a P0 capability, took typing the URL.
//
// FE-306-T4: the trip view names one control that opens the form.
// FE-306-T5: opening it puts focus on the form's first field.
// FE-306-T6: closing the form puts focus back on that control.
//
// The last two are the ones a keyboard user notices. The control unmounts
// when the form opens (the form's own screen has no need of it), so without
// T5 focus would fall to the document, and without T6 closing would do the
// same the other way.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { routes } from '../../routes.js';

const copy = messages['en-US'];
const trip = tripFixtures.detailScheduled;

function renderTrip(entry = `/trip/${trip.id}`) {
  const router = createMemoryRouter(routes, { initialEntries: [entry] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
  return router;
}

/** Opens the trip and presses its settings control by keyboard. */
async function openSettingsByKeyboard() {
  const user = userEvent.setup();
  const router = renderTrip();
  const entry = await screen.findByRole('link', { name: copy['trip.settingsOpen'] });
  entry.focus();
  await user.keyboard('{Enter}');
  return { user, router };
}

describe('FE-306-T4 the trip view opens the dates form', () => {
  it('FE-306-T4 names one control that leads to the date fields', async () => {
    const { router } = await openSettingsByKeyboard();

    expect(router.state.location.pathname).toBe(`/trip/${trip.id}/settings`);
    expect(await screen.findByLabelText(copy['trip.field.startDate'])).toHaveValue(
      trip.startDate,
    );
    expect(screen.getByLabelText(copy['trip.field.endDate'])).toHaveValue(trip.endDate);
  });

  it('keeps the form out of the S07-2 schedule editor', async () => {
    // routes.tsx: metadata editing must not be mixed into schedule edit, so
    // the control belongs to the view only.
    renderTrip(`/trip/${trip.id}/edit`);
    await screen.findByText(copy['trip.editMode']);
    expect(
      screen.queryByRole('link', { name: copy['trip.settingsOpen'] }),
    ).not.toBeInTheDocument();
  });
});

describe('FE-306-T5 opening the dates form moves focus into it', () => {
  it('FE-306-T5 lands on the first field', async () => {
    await openSettingsByKeyboard();
    const first = await screen.findByLabelText(copy['trip.field.title']);
    await waitFor(() => {
      expect(first).toHaveFocus();
    });
  });
});

describe('FE-306-T6 closing the dates form returns focus to its control', () => {
  it('FE-306-T6 after a cancel with nothing changed', async () => {
    const { user } = await openSettingsByKeyboard();
    await user.click(
      await screen.findByRole('button', { name: copy['trip.editCancel'] }),
    );

    const entry = await screen.findByRole('link', { name: copy['trip.settingsOpen'] });
    await waitFor(() => {
      expect(entry).toHaveFocus();
    });
  });

  it('FE-306-T6 after leaving a changed form through the discard confirm', async () => {
    const { user } = await openSettingsByKeyboard();
    await user.type(await screen.findByLabelText(copy['trip.field.title']), '!');
    await user.click(screen.getByRole('button', { name: copy['trip.editCancel'] }));
    await user.click(
      await screen.findByRole('button', { name: copy['trip.discard.leave'] }),
    );

    const entry = await screen.findByRole('link', { name: copy['trip.settingsOpen'] });
    await waitFor(() => {
      expect(entry).toHaveFocus();
    });
  });

  it('FE-306-T6 after a save', async () => {
    const { user } = await openSettingsByKeyboard();
    const title = await screen.findByLabelText(copy['trip.field.title']);
    await user.type(title, '!');
    await user.click(screen.getByRole('button', { name: copy['trip.editSave'] }));

    const entry = await screen.findByRole('link', { name: copy['trip.settingsOpen'] });
    await waitFor(() => {
      expect(entry).toHaveFocus();
    });
  });
});
