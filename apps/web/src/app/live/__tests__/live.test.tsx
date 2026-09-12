// @vitest-environment happy-dom
//
// happy-dom because the screen links into the router (#67).
//
// FE-401 is NOT built: this covers the 준비 중 screen that stands in for it,
// and the one thing that screen must never do.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { createQueryClient } from '../../../shared/api/index.js';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { routes } from '../../routes.js';

const copy = messages['en-US'];

function renderLive() {
  const router = createMemoryRouter(routes, { initialEntries: ['/live'] });
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <RouterProvider router={router} />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

describe('the live tab says what it is', () => {
  it('renders a real screen, not the debug placeholder', async () => {
    // 라이브 is one of four persistent tabs, reachable from the feed, a trip
    // and the profile. It used to render the app name above the untranslated
    // literal "live", which reads as a broken build rather than an unfinished
    // feature.
    renderLive();
    expect(
      await screen.findByRole('heading', { level: 1, name: copy['live.title'] }),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('placeholder-route')).toBeNull();
  });

  it('marks itself 준비 중 rather than looking empty', async () => {
    renderLive();
    expect(await screen.findByText(copy['live.comingSoon'])).toBeInTheDocument();
    expect(screen.getByText(copy['live.description'])).toBeInTheDocument();
  });

  it('shows no crowd reading, because there is no data behind it', async () => {
    // AGENTS.md rule 6 and invariant 8: a placeholder number would be a
    // crowd figure with no observation, no source and no time behind it.
    // BA-091 has not opened queryLiveAreas, so the honest count is zero.
    renderLive();
    await screen.findByRole('heading', { level: 1, name: copy['live.title'] });
    const body = document.body.textContent ?? '';
    expect(body).not.toMatch(/\d+\s*%/);
    expect(body).not.toMatch(/혼잡해요|여유|보통|Busy|Quiet|Moderate/);
  });

  it('sends no request, because the capability is off', async () => {
    // P1/unbuilt capabilities must not call the server
    // (.claude/rules/frontend.md). The screen has nothing to fetch.
    const seen: string[] = [];
    const { server } = await import('../../../shared/testing/msw/server.js');
    const record = ({ request }: { request: Request }) => {
      const path = new URL(request.url).pathname;
      if (!path.includes('/session') && !path.includes('/demo')) seen.push(path);
    };
    server.events.on('request:start', record);
    try {
      renderLive();
      await screen.findByRole('heading', { level: 1, name: copy['live.title'] });
      expect(seen).toEqual([]);
    } finally {
      // By reference: the server is shared and removeAllListeners would drop
      // other files' listeners.
      server.events.removeListener('request:start', record);
    }
  });

  it('offers the data guide, which explains the states without claiming any', async () => {
    const user = userEvent.setup();
    renderLive();
    await user.click(
      await screen.findByRole('link', { name: new RegExp(copy['live.dataGuide']) }),
    );
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveAttribute(
        'id',
        'data-guide-heading',
      );
    });
  });
});
