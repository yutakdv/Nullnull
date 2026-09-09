// Provisional contract fixtures. TEST_STRATEGY.md:113 and OWNERSHIP_MATRIX.md:108
// assign packages/contracts/** to BE/AI with FE as consumer; FE authored these to
// unblock FE-003 and every one is ajv-validated against docs/api/openapi.yaml by
// packages/contracts/__tests__/fixtures.test.ts. See README.md for the swap plan.
import type { components } from "@nullnull/api-client/types";

import invalidRequest from "../fixtures/problems/invalid-request.json" with { type: "json" };
import unauthorized from "../fixtures/problems/unauthorized.json" with { type: "json" };
import forbidden from "../fixtures/problems/forbidden.json" with { type: "json" };
import notFound from "../fixtures/problems/not-found.json" with { type: "json" };
import validationFailed from "../fixtures/problems/validation-failed.json" with { type: "json" };
import csrfInvalid from "../fixtures/problems/csrf-invalid.json" with { type: "json" };
import cursorInvalid from "../fixtures/problems/cursor-invalid.json" with { type: "json" };
import cursorExpired from "../fixtures/problems/cursor-expired.json" with { type: "json" };
import tripChanged from "../fixtures/problems/trip-changed.json" with { type: "json" };
import dataChanged from "../fixtures/problems/data-changed.json" with { type: "json" };
import lockConflict from "../fixtures/problems/lock-conflict.json" with { type: "json" };
import routeUnavailable from "../fixtures/problems/route-unavailable.json" with { type: "json" };
import noImprovement from "../fixtures/problems/no-improvement.json" with { type: "json" };
import applyFailed from "../fixtures/problems/apply-failed.json" with { type: "json" };
import idempotencyKeyReused from "../fixtures/problems/idempotency-key-reused.json" with { type: "json" };
import importDraftExpired from "../fixtures/problems/import-draft-expired.json" with { type: "json" };
import importDraftChanged from "../fixtures/problems/import-draft-changed.json" with { type: "json" };
import previewExpired from "../fixtures/problems/preview-expired.json" with { type: "json" };
import revertWindowExpired from "../fixtures/problems/revert-window-expired.json" with { type: "json" };
import deletionStatusExpired from "../fixtures/problems/deletion-status-expired.json" with { type: "json" };
import sourceUnavailable from "../fixtures/problems/source-unavailable.json" with { type: "json" };
import rateLimited from "../fixtures/problems/rate-limited.json" with { type: "json" };
import internalError from "../fixtures/problems/internal-error.json" with { type: "json" };

import sessionBootstrap from "../fixtures/session/session-bootstrap.json" with { type: "json" };
import csrfToken from "../fixtures/session/csrf-token.json" with { type: "json" };
import ownerProfileAnonymous from "../fixtures/session/owner-profile-anonymous.json" with { type: "json" };
import tripPage from "../fixtures/trips/trip-page.json" with { type: "json" };
import tripPageEmpty from "../fixtures/trips/trip-page-empty.json" with { type: "json" };
import historyPage from "../fixtures/optimizations/history-page.json" with { type: "json" };
import historyPageEmpty from "../fixtures/optimizations/history-page-empty.json" with { type: "json" };

type Problem = components["schemas"]["Problem"];
export type ProblemCode = Problem["code"];

/** One fixture per Problem.code enum value. Keyed so tests can iterate the enum. */
export const problemFixtures: Record<ProblemCode, Problem> = {
  INVALID_REQUEST: invalidRequest as Problem,
  UNAUTHORIZED: unauthorized as Problem,
  FORBIDDEN: forbidden as Problem,
  NOT_FOUND: notFound as Problem,
  VALIDATION_FAILED: validationFailed as Problem,
  CSRF_INVALID: csrfInvalid as Problem,
  CURSOR_INVALID: cursorInvalid as Problem,
  CURSOR_EXPIRED: cursorExpired as Problem,
  TRIP_CHANGED: tripChanged as Problem,
  DATA_CHANGED: dataChanged as Problem,
  LOCK_CONFLICT: lockConflict as Problem,
  ROUTE_UNAVAILABLE: routeUnavailable as Problem,
  NO_IMPROVEMENT: noImprovement as Problem,
  APPLY_FAILED: applyFailed as Problem,
  IDEMPOTENCY_KEY_REUSED: idempotencyKeyReused as Problem,
  IMPORT_DRAFT_EXPIRED: importDraftExpired as Problem,
  IMPORT_DRAFT_CHANGED: importDraftChanged as Problem,
  PREVIEW_EXPIRED: previewExpired as Problem,
  REVERT_WINDOW_EXPIRED: revertWindowExpired as Problem,
  DELETION_STATUS_EXPIRED: deletionStatusExpired as Problem,
  SOURCE_UNAVAILABLE: sourceUnavailable as Problem,
  RATE_LIMITED: rateLimited as Problem,
  INTERNAL_ERROR: internalError as Problem,
};

export const sessionFixtures = {
  bootstrap: sessionBootstrap as components["schemas"]["SessionBootstrap"],
  csrfToken: csrfToken as components["schemas"]["CsrfTokenResponse"],
  owner: ownerProfileAnonymous as components["schemas"]["OwnerProfile"],
};

// PROVISIONAL MOCK DATA — replace when BA-011/BA-030 serve these for real.
//
// listTrips and listOptimizationHistory have no example in docs/api/openapi.yaml,
// so unlike the Problem and session sets these were not derived from an approved
// one: the values below are invented to match the Figma frame (S14 `422:2925`)
// while satisfying the schema. They are schema-valid, not server-verified —
// exactly the gap PR #17 found in four of the FE-003 fixtures, where every
// fixture passed ajv and still disagreed with the real response.
//
// To swap them for the real thing: replace these JSON files with BE/AI's
// responses, delete this notice, and drop the msw handlers in
// apps/web/src/shared/testing/msw/handlers.ts that serve them.
export const tripFixtures = {
  page: tripPage as components["schemas"]["TripPage"],
  pageEmpty: tripPageEmpty as components["schemas"]["TripPage"],
};

export const optimizationFixtures = {
  historyPage: historyPage as components["schemas"]["OptimizationHistoryPage"],
  historyPageEmpty:
    historyPageEmpty as components["schemas"]["OptimizationHistoryPage"],
};
