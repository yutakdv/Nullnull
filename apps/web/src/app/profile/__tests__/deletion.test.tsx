// @vitest-environment happy-dom
//
// FE-105 deletion flow (FR-SES-04).
//
// The assertions that matter are the privacy ones. The contract says the status
// token is "store in memory only and never log it", and deletion revokes the
// session, so a token that outlives the page would be a credential left behind
// for data the user asked to erase.
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import {
  createQueryClient,
  currentDeletionToken,
  forgetDeletionToken,
} from '../../../shared/api/index.js';
import { API_BASE } from '../../../shared/testing/msw/handlers.js';
import { server } from '../../../shared/testing/msw/server.js';
import { DeletionSection } from '../DeletionSection.js';

const copy = messages['en-US'];

let requests: { method: string; url: string; token: string | null }[] = [];

beforeEach(() => {
  requests = [];
  forgetDeletionToken();
  server.events.on('request:start', ({ request }) => {
    requests.push({
      method: request.method,
      url: request.url,
      token: request.headers.get('x-deletion-status-token'),
    });
  });
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  server.events.removeAllListeners();
  forgetDeletionToken();
  localStorage.clear();
  sessionStorage.clear();
});

function renderSection() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <I18nProvider>
        <DeletionSection />
      </I18nProvider>
    </QueryClientProvider>,
  );
}

async function requestDeletion(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: copy['deletion.request'] }));
  await user.click(
    await screen.findByRole('button', { name: copy['deletion.confirm.yes'] }),
  );
}

describe('deletion takes two deliberate actions', () => {
  it('does not delete on the first press', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole('button', { name: copy['deletion.request'] }));

    expect(requests.filter((r) => r.method === 'DELETE')).toEqual([]);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      copy['deletion.confirm.body'],
    );
  });

  it('can be cancelled without sending anything', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByRole('button', { name: copy['deletion.request'] }));
    await user.click(screen.getByRole('button', { name: copy['deletion.confirm.no'] }));

    expect(requests.filter((r) => r.method === 'DELETE')).toEqual([]);
    expect(
      screen.getByRole('button', { name: copy['deletion.request'] }),
    ).toBeInTheDocument();
  });
});

describe('the receipt token never leaves memory', () => {
  it('is not written to any browser storage', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await screen.findByText(copy['deletion.requested']);

    const token = sessionFixtures.deletionReceipt.statusToken;
    // The contract: "store in memory only and never log it". Deletion revokes
    // the session, so a persisted token outlives what it belonged to.
    expect(JSON.stringify(localStorage)).not.toContain(token);
    expect(JSON.stringify(sessionStorage)).not.toContain(token);
    expect(document.body.innerHTML).not.toContain(token);
  });

  it('never appears in a URL', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'GET')).toBe(true);
    });

    const token = sessionFixtures.deletionReceipt.statusToken;
    for (const request of requests) {
      expect(decodeURIComponent(request.url)).not.toContain(token);
    }
  });

  it('is sent as the header the contract names', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);

    await waitFor(() => {
      const status = requests.find((r) => r.url.includes('/deletion-requests/'));
      expect(status?.token).toBe(sessionFixtures.deletionReceipt.statusToken);
    });
  });

  it('is dropped when asked', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await waitFor(() => {
      expect(currentDeletionToken()).not.toBeNull();
    });
    forgetDeletionToken();
    expect(currentDeletionToken()).toBeNull();
  });
});

describe('the screen shows the job, not the request', () => {
  it('reports accepted as in progress rather than done', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);

    // 202 means queued. Declaring success here would claim the data is gone.
    expect(await screen.findByText(copy['deletion.requested'])).toBeInTheDocument();
    expect(screen.queryByText(copy['deletion.status.COMPLETED'])).toBeNull();
  });

  it('says the receipt cannot be reopened', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    expect(await screen.findByText(copy['deletion.receiptNote'])).toBeInTheDocument();
  });

  it.each([
    ['COMPLETED', copy['deletion.status.COMPLETED']],
    ['PARTIAL_FAILED', copy['deletion.status.PARTIAL_FAILED']],
    ['FAILED', copy['deletion.status.FAILED']],
  ])('renders the %s state', async (status, label) => {
    server.use(
      http.get(`${API_BASE}/deletion-requests/:id`, () =>
        HttpResponse.json({ ...sessionFixtures.deletionStatus, status }),
      ),
    );
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    expect(await screen.findByText(label)).toBeInTheDocument();
  });

  it('offers a retry only when the server says it is retryable', async () => {
    server.use(
      http.get(`${API_BASE}/deletion-requests/:id`, () =>
        HttpResponse.json({
          ...sessionFixtures.deletionStatus,
          status: 'FAILED',
          retryable: false,
        }),
      ),
    );
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await screen.findByText(copy['deletion.status.FAILED']);

    // retryable is the server's judgement; the client does not guess.
    expect(screen.queryByRole('button', { name: copy['deletion.retry'] })).toBeNull();
    expect(screen.getByText(copy['deletion.contact'])).toBeInTheDocument();
  });

  it('reports an expired receipt instead of retrying forever', async () => {
    server.use(
      http.get(`${API_BASE}/deletion-requests/:id`, () =>
        HttpResponse.json({ code: 'DELETION_STATUS_EXPIRED' }, { status: 410 }),
      ),
    );
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    expect(await screen.findByText(copy['deletion.expired'])).toBeInTheDocument();
  });
});
