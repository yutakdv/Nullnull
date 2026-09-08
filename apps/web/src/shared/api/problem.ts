// RFC 9457 Problem Details parsing. docs/api/openapi.yaml `Problem`.
import type { components } from '@nullnull/api-client/types';

export type Problem = components['schemas']['Problem'];
export type ProblemCode = Problem['code'];
export type FieldError = components['schemas']['FieldError'];

/** Every code in the contract enum, in the order docs/api/README.md lists them. */
export const PROBLEM_CODES = [
  'INVALID_REQUEST',
  'UNAUTHORIZED',
  'FORBIDDEN',
  'NOT_FOUND',
  'VALIDATION_FAILED',
  'CSRF_INVALID',
  'CURSOR_INVALID',
  'CURSOR_EXPIRED',
  'TRIP_CHANGED',
  'DATA_CHANGED',
  'LOCK_CONFLICT',
  'ROUTE_UNAVAILABLE',
  'NO_IMPROVEMENT',
  'APPLY_FAILED',
  'IDEMPOTENCY_KEY_REUSED',
  'IMPORT_DRAFT_EXPIRED',
  'IMPORT_DRAFT_CHANGED',
  'PREVIEW_EXPIRED',
  'REVERT_WINDOW_EXPIRED',
  'DELETION_STATUS_EXPIRED',
  'SOURCE_UNAVAILABLE',
  'RATE_LIMITED',
  'INTERNAL_ERROR',
] as const satisfies readonly ProblemCode[];

const KNOWN_CODES = new Set<string>(PROBLEM_CODES);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * True only for a body that carries every field the contract requires.
 *
 * A body with an unrecognised `code` is rejected. The server may add codes
 * ahead of the client, but surfacing one the policy table has never seen would
 * mean guessing its retry and recovery behaviour — the caller handles that as
 * an unrecognised failure instead.
 */
export function isProblem(value: unknown): value is Problem {
  if (!isRecord(value)) return false;
  return (
    typeof value.type === 'string' &&
    typeof value.title === 'string' &&
    typeof value.status === 'number' &&
    typeof value.code === 'string' &&
    KNOWN_CODES.has(value.code) &&
    typeof value.detail === 'string' &&
    typeof value.instance === 'string' &&
    typeof value.requestId === 'string' &&
    typeof value.retryable === 'boolean'
  );
}

/**
 * Narrow a failed request to a Problem, or null when it is not one.
 *
 * Returns null for network failures, HTML error pages from a proxy, and any
 * body that is not shaped like a Problem. Those are real failures but they are
 * a different kind: inventing a code for them would put a value on screen that
 * the contract never defined (docs/api/README.md forbids exposing anything as a
 * new code). Callers must handle null as "something failed, cause unknown".
 *
 * `response` is optional so this also works on an openapi-fetch `error` value
 * alone; when given, a non-problem+json content type is rejected outright.
 */
export function toProblem(error: unknown, response?: Response): Problem | null {
  if (response) {
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('application/problem+json')) return null;
  }
  return isProblem(error) ? error : null;
}
