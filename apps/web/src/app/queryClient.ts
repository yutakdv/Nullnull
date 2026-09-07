import { QueryClient } from '@tanstack/react-query';

// Server state lives in the query cache; edit/form buffers stay feature-local
// and shared selections go in the URL (.claude/rules/frontend.md).
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Screens must render explicit loading/error states rather than
        // retrying silently forever.
        retry: 1,
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: {
        // Retries need an Idempotency-Key decision per command, so no blanket
        // retry here.
        retry: 0,
      },
    },
  });
}
