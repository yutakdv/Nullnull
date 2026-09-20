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

let requests: {
  method: string;
  url: string;
  token: string | null;
  idempotencyKey: string | null;
}[] = [];

beforeEach(() => {
  requests = [];
  forgetDeletionToken();
  server.events.on('request:start', ({ request }) => {
    requests.push({
      method: request.method,
      url: request.url,
      token: request.headers.get('x-deletion-status-token'),
      idempotencyKey: request.headers.get('Idempotency-Key'),
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

// #254: the Idempotency-Key used to be minted inside useRequestDeletion's
// mutationFn, so every press — including a retry of a failed attempt — sent a
// fresh key. Deletion revokes the cookie in the same step it accepts the
// request, so a retry with a fresh key after a lost 202 arrives as "revoked
// cookie, unknown key": the server cannot tell it apart from an
// unauthenticated request, and the user never gets a statusToken for a
// deletion that already happened. The same key gets the original 202 replayed
// back instead — but only if the client actually sends the same one.
describe('the deletion request carries a stable Idempotency-Key (#254)', () => {
  it('sends a key on the request', async () => {
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);

    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(1);
    });
    const sent = requests.filter((r) => r.method === 'DELETE');
    expect(sent[0]?.idempotencyKey).not.toBeNull();
  });

  it('replays the same key when a failed attempt is retried', async () => {
    server.use(http.delete(`${API_BASE}/session`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);

    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(1);
    });
    expect(await screen.findByText(copy['deletion.failed'])).toBeInTheDocument();

    // The confirm row is still showing (requestId never got set), so the same
    // "yes" press retries the same attempt.
    await user.click(screen.getByRole('button', { name: copy['deletion.confirm.yes'] }));
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(2);
    });

    const [first, second] = requests.filter((r) => r.method === 'DELETE');
    expect(first?.idempotencyKey).not.toBeNull();
    expect(second?.idempotencyKey).toBe(first?.idempotencyKey);
  });

  it('mints a new key for a new attempt after the user backs out', async () => {
    // Backing out with 아니요 is a decision to stop, not a failed attempt — a
    // later 예 is a NEW attempt and must not replay whatever the abandoned one
    // sent, so it needs its own key.
    server.use(http.delete(`${API_BASE}/session`, () => HttpResponse.error()));
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(1);
    });
    await screen.findByText(copy['deletion.failed']);

    // Back out, then start a fresh confirm flow.
    await user.click(screen.getByRole('button', { name: copy['deletion.confirm.no'] }));
    await requestDeletion(user);
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(2);
    });

    const [first, second] = requests.filter((r) => r.method === 'DELETE');
    expect(second?.idempotencyKey).not.toBeNull();
    expect(second?.idempotencyKey).not.toBe(first?.idempotencyKey);
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

  it('clears an unfinished trip wizard when deletion revokes the session', async () => {
    sessionStorage.setItem('nullnull.wizard.v1', '{"step":3}');
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await screen.findByText(copy['deletion.requested']);

    expect(sessionStorage.getItem('nullnull.wizard.v1')).toBeNull();
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

  it('keeps polling a PARTIAL_FAILED deletion, which the server is still working on', async () => {
    // The contract is explicit in the schema: "COMPLETED and FAILED are the
    // states a client may stop polling on. PARTIAL_FAILED is NOT one of them:
    // it means an attempt failed while the server still has attempts left, so
    // the server retries on its own and the status changes again without any
    // client action."
    //
    // The screen treated it as terminal, so it stopped showing progress and
    // offered a retry for work already in hand. The test beside this one only
    // checked the label renders, which is true either way.
    let polls = 0;
    server.use(
      http.get(`${API_BASE}/deletion-requests/:id`, () => {
        polls += 1;
        return HttpResponse.json({
          ...sessionFixtures.deletionStatus,
          status: 'PARTIAL_FAILED',
          retryable: true,
        });
      }),
    );
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await screen.findByText(copy['deletion.status.PARTIAL_FAILED']);

    // It is progress, not a problem the user must act on.
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByRole('button', { name: copy['deletion.retry'] })).toBeNull();

    // And the poll continues: the interval is 3s, so this waits past one.
    const before = polls;
    await new Promise((resolve) => setTimeout(resolve, 3400));
    expect(polls).toBeGreaterThan(before);
  }, 10000);

  it('stops polling once the deletion is COMPLETED', async () => {
    // The other direction, so "keep polling" cannot become "poll for ever".
    let polls = 0;
    server.use(
      http.get(`${API_BASE}/deletion-requests/:id`, () => {
        polls += 1;
        return HttpResponse.json({
          ...sessionFixtures.deletionStatus,
          status: 'COMPLETED',
        });
      }),
    );
    const user = userEvent.setup();
    renderSection();
    await requestDeletion(user);
    await screen.findByText(copy['deletion.status.COMPLETED']);
    const settled = polls;
    await new Promise((resolve) => setTimeout(resolve, 3400));
    expect(polls).toBe(settled);
  }, 10000);

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
