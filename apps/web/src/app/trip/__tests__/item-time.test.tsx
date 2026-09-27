// @vitest-environment happy-dom
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import { tripFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { server } from '../../../shared/testing/msw/server.js';
import { routes } from '../../routes.js';

const trip = tripFixtures.detailScheduled;
const copy = messages['en-US'];

afterEach(() => server.events.removeAllListeners());

it('saves a user-entered exact time and can clear it without changing the date', async () => {
  const sent: unknown[] = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'PATCH' && request.url.includes('/items/')) {
      void request
        .clone()
        .json()
        .then((body) => sent.push(body));
    }
  });
  const user = userEvent.setup();
  const router = createMemoryRouter(routes, { initialEntries: [`/trip/${trip.id}`] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
  const name = await screen.findByRole('heading', { level: 3, name: '명동' });
  const card = name.closest('article') as HTMLElement;
  await user.click(
    within(card).getByRole('button', {
      name: copy['trip.item.actions'].replace('{name}', '명동'),
    }),
  );
  await user.click(within(card).getByRole('button', { name: copy['trip.time.edit'] }));
  const input = within(card).getByLabelText(copy['trip.time.label']);
  await user.clear(input);
  await user.type(input, '15:45');
  await user.click(within(card).getByRole('button', { name: copy['trip.time.save'] }));
  await waitFor(() => expect(sent).toContainEqual({ startTime: '15:45:00' }));
  expect(within(card).getByText('3:45 PM')).toBeInTheDocument();
  await user.click(within(card).getByRole('button', { name: copy['trip.time.edit'] }));
  await user.clear(within(card).getByLabelText(copy['trip.time.label']));
  await user.click(within(card).getByRole('button', { name: copy['trip.time.save'] }));
  await waitFor(() => expect(sent).toContainEqual({ startTime: null }));
  expect(within(card).getByText('Stop 1')).toBeInTheDocument();
});
