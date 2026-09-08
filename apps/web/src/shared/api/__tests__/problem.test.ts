import { describe, expect, it } from 'vitest';
import { problemFixtures } from '@nullnull/contracts';
import { PROBLEM_CODES, isProblem, toProblem } from '../problem.js';
import { PROBLEM_POLICY, retryDelayMs, shouldRetry } from '../problem-policy.js';
import { problemPresentation, unknownFailurePresentation } from '../problem-message.js';
import { messages } from '../../../i18n/messages.js';

const t = (key: keyof (typeof messages)['ko-KR']) => messages['ko-KR'][key];

describe('the policy table covers the whole contract enum', () => {
  // The compiler already enforces this via Record<ProblemCode, …>, but the
  // assertion states the intent and catches a table filled with placeholders.
  it('has a policy for every code', () => {
    for (const code of PROBLEM_CODES) {
      expect(PROBLEM_POLICY[code]).toBeDefined();
    }
    expect(Object.keys(PROBLEM_POLICY).sort()).toEqual([...PROBLEM_CODES].sort());
  });

  it('has a fixture for every code', () => {
    expect(Object.keys(problemFixtures).sort()).toEqual([...PROBLEM_CODES].sort());
  });

  it('has a CTA label in both locales for every code', () => {
    for (const code of PROBLEM_CODES) {
      expect(messages['ko-KR'][`error.${code}.cta`]).toBeTruthy();
      expect(messages['en-US'][`error.${code}.cta`]).toBeTruthy();
    }
  });
});

describe('toProblem', () => {
  it('accepts a contract-shaped body', () => {
    expect(toProblem(problemFixtures.NOT_FOUND)).toEqual(problemFixtures.NOT_FOUND);
  });

  it('returns null for a network error', () => {
    expect(toProblem(new TypeError('Failed to fetch'))).toBeNull();
  });

  it('returns null for a proxy HTML page', () => {
    expect(toProblem('<html><body>502 Bad Gateway</body></html>')).toBeNull();
  });

  it('returns null rather than inventing a code for an unknown one', () => {
    // A server may add a code before the client knows it. Surfacing it would
    // mean guessing its retry and recovery behaviour.
    expect(toProblem({ ...problemFixtures.NOT_FOUND, code: 'FUTURE_CODE' })).toBeNull();
  });

  it('returns null when a required field is missing', () => {
    const withoutRequestId: Record<string, unknown> = { ...problemFixtures.NOT_FOUND };
    delete withoutRequestId.requestId;
    expect(toProblem(withoutRequestId)).toBeNull();
  });

  it('rejects a body whose content type is not problem+json', () => {
    const response = new Response(null, {
      headers: { 'Content-Type': 'application/json' },
    });
    expect(toProblem(problemFixtures.NOT_FOUND, response)).toBeNull();
  });

  it('accepts problem+json with parameters on the content type', () => {
    const response = new Response(null, {
      headers: { 'Content-Type': 'application/problem+json; charset=utf-8' },
    });
    expect(toProblem(problemFixtures.NOT_FOUND, response)).not.toBeNull();
  });

  it('rejects null and primitives', () => {
    expect(isProblem(null)).toBe(false);
    expect(isProblem('NOT_FOUND')).toBe(false);
    expect(isProblem(404)).toBe(false);
  });
});

describe('shouldRetry never replays a mutation', () => {
  it.each(PROBLEM_CODES)('%s is not auto-retried as a POST', (code) => {
    expect(shouldRetry(code, { method: 'POST', attempt: 0 })).toBe(false);
  });

  it.each(['PUT', 'PATCH', 'DELETE'])('%s is never auto-retried', (method) => {
    // INTERNAL_ERROR is one of the retryable codes, so it is the strongest case.
    expect(shouldRetry('INTERNAL_ERROR', { method, attempt: 0 })).toBe(false);
  });

  it('allows exactly one retry for a safe GET', () => {
    expect(shouldRetry('UNAUTHORIZED', { method: 'GET', attempt: 0 })).toBe(true);
    expect(shouldRetry('UNAUTHORIZED', { method: 'GET', attempt: 1 })).toBe(false);
  });

  it('refuses codes the contract marks 금지, even on a GET', () => {
    for (const code of ['NOT_FOUND', 'CURSOR_EXPIRED', 'LOCK_CONFLICT'] as const) {
      expect(shouldRetry(code, { method: 'GET', attempt: 0 })).toBe(false);
    }
  });

  it('does not auto-retry an apply', () => {
    // "일정은 바뀌지 않았어요" is only true if the client did not silently retry.
    expect(shouldRetry('APPLY_FAILED', { method: 'POST', attempt: 0 })).toBe(false);
  });
});

describe('retryDelayMs', () => {
  it('honours Retry-After in seconds for RATE_LIMITED', () => {
    expect(retryDelayMs('RATE_LIMITED', '30')).toBe(30_000);
  });

  it('falls back to backoff when Retry-After is missing or malformed', () => {
    expect(retryDelayMs('RATE_LIMITED', null)).toBe(1_000);
    expect(retryDelayMs('RATE_LIMITED', 'Wed, 21 Oct 2026 07:28:00 GMT')).toBe(1_000);
  });

  it('ignores Retry-After for codes whose policy is not retry-after', () => {
    expect(retryDelayMs('ROUTE_UNAVAILABLE', '30')).toBe(1_000);
  });
});

describe('presentation keeps message ownership straight', () => {
  it('uses the Figma copy for the six confirmed codes', () => {
    const { message } = problemPresentation(problemFixtures.TRIP_CHANGED, t);
    expect(message).toBe('다른 곳에서 일정이 바뀌었어요.');
  });

  it('uses the server detail for codes Figma has not fixed', () => {
    const { message } = problemPresentation(problemFixtures.CURSOR_EXPIRED, t);
    expect(message).toBe(problemFixtures.CURSOR_EXPIRED.detail);
  });

  it('returns detail as a plain string, never markup', () => {
    // docs/api/README.md:214 — FE는 detail을 HTML로 렌더링하지 않는다.
    const hostile = {
      ...problemFixtures.NOT_FOUND,
      detail: '<img src=x onerror="alert(1)">',
    };
    const { message } = problemPresentation(hostile, t);
    expect(message).toBe('<img src=x onerror="alert(1)">');
    expect(typeof message).toBe('string');
  });

  it('shows the requestId only where the policy says to', () => {
    expect(problemPresentation(problemFixtures.INTERNAL_ERROR, t).requestId).toBe(
      problemFixtures.INTERNAL_ERROR.requestId,
    );
    expect(problemPresentation(problemFixtures.NOT_FOUND, t).requestId).toBeUndefined();
  });

  it('describes an unreadable failure without giving it a code', () => {
    const { message, policy } = unknownFailurePresentation(t);
    expect(message).toBe(messages['ko-KR']['error.unknown.message']);
    expect(policy.retry).toBe('none');
  });
});
