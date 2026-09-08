// Retry policy proven by counting requests, not by reading the policy table
// back. A table that says "no retry" while the client retries anyway would
// pass a table-inspection test and fail this one.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useQuery, useMutation } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { problemFixtures } from '@nullnull/contracts';
import type { ReactNode } from 'react';
import { createQueryClient } from '../query-client.js';
import { toProblem, type ProblemCode } from '../problem.js';
import { server } from '../../testing/msw/server.js';
import { API_BASE } from '../../testing/msw/handlers.js';

let requestCount = 0;

/** Always fails with the given code, counting how many times it was called. */
function failingEndpoint(code: ProblemCode, headers: Record<string, string> = {}) {
  const fixture = problemFixtures[code];
  return http.get(`${API_BASE}/probe`, () => {
    requestCount += 1;
    return HttpResponse.json(fixture, {
      status: fixture.status,
      headers: { 'Content-Type': 'application/problem+json', ...headers },
    });
  });
}

async function fetchProbe() {
  const response = await fetch(`${API_BASE}/probe`);
  if (!response.ok) throw await response.json();
  return response.json();
}

function Probe() {
  const { isError } = useQuery({ queryKey: ['probe'], queryFn: fetchProbe });
  return <span>{isError ? 'failed' : 'pending'}</span>;
}

function Mutator() {
  const { mutate, isError } = useMutation({
    mutationFn: async () => {
      requestCount += 1;
      const fixture = problemFixtures.INTERNAL_ERROR;
      throw fixture;
    },
  });
  return (
    <button type="button" onClick={() => mutate()}>
      {isError ? 'failed' : 'go'}
    </button>
  );
}

function withClient(client: QueryClient, ui: ReactNode) {
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>;
}

beforeEach(() => {
  requestCount = 0;
});

describe('query retry follows the contract policy', () => {
  it('retries a safe GET exactly once for UNAUTHORIZED', async () => {
    server.use(failingEndpoint('UNAUTHORIZED'));
    render(withClient(createQueryClient(), <Probe />));
    // The backoff between attempts is a real 1s, so allow for it.
    await waitFor(() => expect(screen.getByText('failed')).toBeInTheDocument(), {
      timeout: 4_000,
    });
    // Original request plus one retry.
    expect(requestCount).toBe(2);
  });

  it('does not retry a code the contract marks 금지', async () => {
    server.use(failingEndpoint('NOT_FOUND'));
    render(withClient(createQueryClient(), <Probe />));
    await waitFor(() => expect(screen.getByText('failed')).toBeInTheDocument());
    expect(requestCount).toBe(1);
  });

  it('does not silently retry CSRF_INVALID', async () => {
    // Its policy is "reissue the token, then re-confirm with the user", not a
    // silent replay.
    server.use(failingEndpoint('CSRF_INVALID'));
    render(withClient(createQueryClient(), <Probe />));
    await waitFor(() => expect(screen.getByText('failed')).toBeInTheDocument());
    expect(requestCount).toBe(1);
  });

  it('does not retry a failure with no readable Problem body', async () => {
    server.use(
      http.get(`${API_BASE}/probe`, () => {
        requestCount += 1;
        return new HttpResponse('<html>502</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        });
      }),
    );
    render(withClient(createQueryClient(), <Probe />));
    await waitFor(() => expect(screen.getByText('failed')).toBeInTheDocument());
    expect(requestCount).toBe(1);
  });
});

describe('mutations never auto-retry', () => {
  it('runs a failing mutation exactly once', async () => {
    render(withClient(createQueryClient(), <Mutator />));
    screen.getByRole('button').click();
    await waitFor(() => expect(screen.getByText('failed')).toBeInTheDocument());
    expect(requestCount).toBe(1);
  });
});

describe('the probe endpoint really returns a Problem', () => {
  // Guards the tests above: if the fixture stopped parsing as a Problem they
  // would all "pass" by never retrying.
  it('parses as a Problem', async () => {
    server.use(failingEndpoint('UNAUTHORIZED'));
    const response = await fetch(`${API_BASE}/probe`);
    expect(toProblem(await response.json(), response)).not.toBeNull();
  });
});
