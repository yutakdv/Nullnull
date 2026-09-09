// @vitest-environment happy-dom
//
// This file runs on happy-dom rather than the suite's jsdom. Vitest's jsdom
// environment installs jsdom's AbortController/AbortSignal but leaves Request as
// Node's undici, and undici brand-checks the signal against its own realm. Any
// route loader trips that: react-router builds `new Request(url, { signal })` to
// call it, and the router dies during initialization (#67). happy-dom keeps the
// pair consistent, so loaders can actually run.
//
// Everything here is about the loader path specifically. Render-time throws stay
// in error-boundary.test.tsx on jsdom, which the rest of the suite uses.
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { problemFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { RouteErrorBoundary } from '../RouteErrorBoundary.js';

const copy = messages['en-US'];

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

function renderWithLoader(loader: () => unknown) {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        loader,
        element: <p>loaded</p>,
        errorElement: (
          <main id="main">
            <RouteErrorBoundary />
          </main>
        ),
      },
    ],
    { initialEntries: ['/'] },
  );
  return render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
}

describe('a route loader can run at all', () => {
  it('renders the route element once the loader resolves', async () => {
    renderWithLoader(() => ({ ok: true }));
    expect(await screen.findByText('loaded')).toBeInTheDocument();
  });
});

describe('RouteErrorBoundary catches what a loader throws', () => {
  it('shows the contract copy for a Problem, not the generic message', async () => {
    renderWithLoader(() => {
      throw problemFixtures.TRIP_CHANGED;
    });
    expect(await screen.findByRole('heading')).toHaveTextContent(
      copy['error.TRIP_CHANGED.message'],
    );
  });

  it('labels the recovery with the code CTA', async () => {
    renderWithLoader(() => {
      throw problemFixtures.TRIP_CHANGED;
    });
    expect(
      await screen.findByRole('link', { name: copy['error.TRIP_CHANGED.cta'] }),
    ).toBeInTheDocument();
  });

  it('falls back to the generic message for a plain error', async () => {
    renderWithLoader(() => {
      throw new Error('loader exploded');
    });
    expect(await screen.findByTestId('route-error')).toBeInTheDocument();
    expect(screen.getByRole('heading')).toHaveTextContent(copy['app.error.title']);
    expect(screen.getByRole('heading')).not.toHaveTextContent('loader exploded');
  });
});
