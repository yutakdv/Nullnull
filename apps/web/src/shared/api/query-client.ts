// QueryClient wired to the Problem retry policy.
//
// The policy table only matters if something consults it. TanStack Query
// retries queries three times by default, which would replay requests that
// docs/api/README.md marks "금지". This factory replaces that default with the
// contract's rules and leaves mutations at zero retries.
import { QueryClient } from '@tanstack/react-query';
import { toProblem } from './problem.js';
import { retryDelayMs, shouldRetry } from './problem-policy.js';

/**
 * Queries are GETs, so the safe-method check in `shouldRetry` is satisfied by
 * construction. A failure with no readable Problem is not retried: without a
 * code there is no policy saying it is safe to repeat.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry(failureCount, error) {
          const problem = toProblem(error);
          if (!problem) return false;
          // TanStack passes failureCount 0-based: it is 0 on the first
          // failure, so it already equals the number of retries made so far,
          // which is what RetryContext.attempt means.
          return shouldRetry(problem.code, { method: 'GET', attempt: failureCount });
        },
        retryDelay(_attemptIndex, error) {
          const problem = toProblem(error);
          return problem ? retryDelayMs(problem.code) : 0;
        },
      },
      // Mutations never auto-retry. Trip mutations are guarded by ETag and
      // Idempotency-Key (invariant 6); replaying one without the user asking
      // is exactly what those guards exist to prevent.
      mutations: { retry: 0 },
    },
  });
}
