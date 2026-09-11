import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { problemFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { messages } from '../../i18n/messages.js';
import { createQueryClient } from '../../shared/api/index.js';
import { RouteErrorBoundary } from '../RouteErrorBoundary.js';
import { routes } from '../routes.js';

// jsdom reports an English navigator, so I18nProvider resolves to en-US here.
// Asserting against the resolved locale keeps these tests about the boundary
// rather than about which language the environment happens to pick.
const copy = messages['en-US'];

// React logs caught render errors; silence that so a passing run stays readable.
beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

function Boom(): never {
  throw new Error('screen exploded');
}

function renderWithBoundary(element: React.ReactElement) {
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element,
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

describe('RouteErrorBoundary catches render failures', () => {
  it('renders recovery UI instead of a blank screen', async () => {
    renderWithBoundary(<Boom />);
    expect(await screen.findByTestId('route-error')).toBeInTheDocument();
    expect(screen.getByRole('heading')).toHaveTextContent(copy['app.error.title']);
  });

  it('offers a focusable route home', async () => {
    renderWithBoundary(<Boom />);
    const home = await screen.findByRole('link', { name: copy['app.error.home'] });
    expect(home).toHaveAttribute('href', '/');
    home.focus();
    expect(home).toHaveFocus();
  });

  it('offers a retry control', async () => {
    renderWithBoundary(<Boom />);
    expect(
      await screen.findByRole('button', { name: copy['app.error.retry'] }),
    ).toBeInTheDocument();
  });

  it('does not put the raw exception message on screen', async () => {
    renderWithBoundary(<Boom />);
    await screen.findByTestId('route-error');
    // In dev the message appears as a diagnostic; the guard is that it is never
    // the heading or body copy the user is asked to act on.
    expect(screen.getByRole('heading')).not.toHaveTextContent('screen exploded');
  });
});

// These throw from the rendered element. The loader path is covered separately
// in loader-error.test.tsx, which needs happy-dom to run at all (#67).
describe('RouteErrorBoundary shows contract copy when a route throws a Problem', () => {
  function ThrowProblem(): never {
    throw problemFixtures.TRIP_CHANGED;
  }

  it('uses the Figma-confirmed message, not the generic one', async () => {
    renderWithBoundary(<ThrowProblem />);
    expect(await screen.findByRole('heading')).toHaveTextContent(
      copy['error.TRIP_CHANGED.message'],
    );
  });

  it('labels the recovery with the code CTA', async () => {
    renderWithBoundary(<ThrowProblem />);
    expect(
      await screen.findByRole('link', { name: copy['error.TRIP_CHANGED.cta'] }),
    ).toBeInTheDocument();
  });

  it('shows the requestId only for codes whose policy asks for it', async () => {
    function ThrowInternal(): never {
      throw problemFixtures.INTERNAL_ERROR;
    }
    renderWithBoundary(<ThrowInternal />);
    expect(
      await screen.findByText(new RegExp(problemFixtures.INTERNAL_ERROR.requestId)),
    ).toBeInTheDocument();
  });

  it('renders a hostile detail as text, never as markup', async () => {
    function ThrowHostile(): never {
      throw { ...problemFixtures.NOT_FOUND, detail: '<img src=x onerror="alert(1)">' };
    }
    const { container } = renderWithBoundary(<ThrowHostile />);
    expect(await screen.findByRole('heading')).toHaveTextContent(
      '<img src=x onerror="alert(1)">',
    );
    expect(container.querySelector('img')).toBeNull();
  });
});

describe('the shell survives a crashed screen', () => {
  it('keeps the main landmark mounted', async () => {
    renderWithBoundary(<Boom />);
    await screen.findByTestId('route-error');
    expect(document.getElementById('main')).not.toBeNull();
  });

  it('leaves the real route table intact for working routes', async () => {
    // /live rather than /feed: this renders without a QueryClientProvider, so
    // it needs a route that makes no request. /feed became a real screen in
    // FE-201 and now fetches.
    const router = createMemoryRouter(routes, { initialEntries: ['/live'] });
    // AppShell is the root element of every route and asks for this tab's CSRF
    // token there (FR-SES-03), so the real table needs a query client even for
    // a route that fetches nothing of its own.
    render(
      <QueryClientProvider client={createQueryClient()}>
        <I18nProvider>
          <RouterProvider router={router} />
        </I18nProvider>
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId('placeholder-route')).toHaveTextContent('live');
  });
});
