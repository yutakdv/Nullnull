---
aliases:
  - "FE 공개 이슈 1~8 실행 계획"
doc_type: plan
status: active
area: frontend
tags:
  - nullnull/plan
  - nullnull/frontend
---

# FE Open Issues Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the currently approved frontend work through the ordered #333, Live, traceability, post creation, candidate-state, and FCR evidence tasks without changing server-owned contracts.

**Architecture:** Work only on the `frontend` branch and preserve the current Live changes. Integrate the two existing dirty worktrees selectively into the current checkout, keeping server contracts and generated clients read-only. Complete one vertical slice at a time and verify its focused Vitest/Playwright coverage before moving on.

**Tech Stack:** React 19, TypeScript, Vite 7, React Router 7, TanStack Query 5, CSS Modules, Vitest, Testing Library, Playwright.

**Spec:** GitHub issues #333, #97, #99, #98, #329, #332, #331, #312, #328, #13; `docs/engineering/frontend-plan.json`; `docs/design/FIGMA_HANDOFF.md`; `docs/design/FIGMA_CHANGE_REQUESTS.md`; read-only `docs/api/openapi.yaml` and generated `packages/api-client` types.

## Global Constraints

- Frontend-only: edit `apps/web/**`, frontend-owned `docs/**`, and this plan/ledger only.
- Never inspect or edit `apps/api/**`, `apps/ai/**`, `infra/**`, DB/Flyway, backend plans, or backend workflows.
- Keep `docs/api/**`, `docs/contracts/**`, `packages/contracts/**`, and `packages/api-client/**` read-only.
- Preserve all existing modifications and untracked files; never reset, clean, stage, commit, push, open a PR, or change branches.
- Use generated API types. Do not invent fields, enum values, media constraints, or `any` escape hatches.
- Keep Korean and English copy together and keep JA/ZH disabled.
- For route, search, sheet/dialog, trip mutation, and Live changes, cover keyboard/focus, 360 px, 200% zoom, and reduced motion.
- Do not run `verify:ci` if `apps/web/src/design/tokens.css` becomes modified; otherwise run it before final completion.

## Review Focus

- A REPLAY payload must never be presented as current live data, even after navigation or background refresh.
- An EMPTY trip draft must not create a trip or masquerade as a successful empty itinerary.
- A retryable `SOURCE_UNAVAILABLE` draft failure must keep the wizard draft and expose retry; non-retryable malformed output must not.
- The post upload must send exactly the ticket method and headers without session cookies or CSRF, and must not add a client-only 2048 px restriction absent from the contract.
- Plan/test evidence must be non-vacuous: every added test ID must appear in an executed testcase title and each acceptance clause must have its own guard.

---

### Task 1: Stabilize the current Live baseline

**Files:** Existing modified Live/AppShell files only.

**Interfaces:** Produces the FE-401 map-off, list-first route behavior that later Live work consumes.

- [x] Run `npm run test -- app-shell live kakao-live-map live-bottom-sheet` and inspect all results.
- [x] Run `npx playwright test e2e/live-map-sheet.mock.spec.ts` and inspect all results.
- [x] Keep the current diff intact and record any unresolved semantic gap for Task 3.

### Task 2: Implement #333 deterministic trip draft preview

**Files:**

- Modify: `apps/web/src/app/trip-create/**`
- Modify: `apps/web/src/shared/api/index.ts`
- Modify: `apps/web/src/shared/api/session.ts`
- Modify: `apps/web/src/i18n/messages.ts`
- Modify: `apps/web/e2e/trip-create.spec.ts`
- Modify: `apps/web/e2e/keyboard-flow.spec.ts`
- Modify: `docs/engineering/frontend-plan.json`

**Interfaces:** Consumes generated `previewTripDraft` request/response types. Produces a wizard preview step whose explicit confirmation alone maps stops to `createTrip.seedItems`.

- [x] Add/retain failing tests for READY mapping, EMPTY no-create behavior, retryable 503, non-retryable malformed response, Pick→MUST_VISIT, keyboard focus, 360 px, and 200% zoom.
- [x] Confirm each test fails against the current checkout because the preview consumer is absent.
- [x] Selectively integrate the existing #333 worktree without whole-file JSON formatting churn.
- [x] Add `previewTripDraft` and FE-102-T4/T5 to the plan without altering unrelated cards.
- [x] Run focused trip-create Vitest and Playwright suites, then the full Vitest suite.

### Task 3: Resolve #97 Live vocabulary and finish FE-401

**Files:** `apps/web/src/app/live/**`, `apps/web/src/shared/crowd/**`, `apps/web/src/i18n/messages.ts`, focused tests.

**Interfaces:** Produces source-aware crowd labels consumed by Live list and detail screens.

- [x] Add a failing test that would catch rendering the generic five-step vocabulary for the four-step Seoul source.
- [x] Use only source/contract metadata already present in generated types; if no discriminator exists, retain safe qualitative copy and record a contract handoff instead of guessing.
- [x] Run Live unit tests and the Live Playwright sheet suite.

### Task 4: Implement #99 FE-403 replay/degraded UI

**Files:** `apps/web/src/app/live/**`, `apps/web/src/i18n/messages.ts`, Live unit/E2E tests, `docs/engineering/frontend-plan.json`.

**Interfaces:** Consumes existing Live result source state/REPLAY metadata. Produces a persistent, accessible non-live state across Live list, map, and detail navigation.

- [x] Add failing FE-403-T1/T2/T3 tests for REPLAY labeling, degraded/empty/error distinctions, focus, keyboard, 360 px, 200% zoom, and reduced motion.
- [x] Implement the minimal persistent state and copy without adding an API operation.
- [x] Change FE-403 to `integration-ready` only after focused tests and full Vitest pass.

### Task 5: Finish #98 FE-402 evidence

**Files:** `apps/web/src/app/live/LivePlaceScreen*`, Live tests/E2E, `docs/engineering/frontend-plan.json` only if evidence fields are supported.

**Interfaces:** Consumes Task 3 vocabulary and Task 4 degraded state. Produces verified detail/alternative/no-alternative behavior.

- [x] Add only missing tests for alternative absence versus error, ineligible comparisons, candidate-save focus restoration, responsive layout, and reduced motion.
- [x] Run the focused Live detail tests/E2E and full Vitest suite.

### Task 6: Correct #329/#332/#331 traceability

**Files:** `apps/web/e2e/location-off.spec.ts`, `docs/design/FIGMA_CHANGE_REQUESTS.md`, `docs/engineering/frontend-plan.json`, existing focus/reduced-motion E2E.

**Interfaces:** Produces truthful FE/BE acceptance-ID aggregation without changing runtime behavior.

- [x] Confirm #329's testcase already names both FE-603-T1 and BA-073-T5; do not duplicate the edit.
- [x] Correct FCR-024 to distinguish server-accepted, contract-allowed, and UI-emitted feedback sets.
- [x] Split FE-104 and FE-203 focus-return/reduced-motion clauses into independently named acceptance IDs and align testcase titles.
- [x] Run docs validation and the affected Playwright specs.

### Task 7: Implement #312 post creation

**Files:** `apps/web/src/app/post-create/**`, routes, feed entry, i18n, generated-client wrappers, focused unit/E2E, `docs/engineering/frontend-plan.json`.

**Interfaces:** Consumes generated `createPostImageUpload`, `UploadTicket`, `createPost`, and `searchPlaces`. Produces `/posts/new`, direct signed PUT, and synchronous publish.

- [ ] Add/retain failing tests for file type/4 MiB contract bounds, ticket→PUT→create order, exact ticket headers, stable separate idempotency keys, retry preservation, accessibility, keyboard, 360 px, 200% zoom, and location-off.
- [ ] Drop the worktree's contract-absent 2048 px restriction and unrelated FE-001 status downgrade.
- [ ] Selectively integrate the remaining worktree implementation.
- [ ] Mark FE-P1-104 integration-ready only after focused tests and full Vitest pass.

### Task 8: Handle #328 candidate-state follow-up

**Files:** Generated-client consumers and candidate UI/tests only if the approved enum is already present.

**Interfaces:** Consumes an approved regenerated closed enum. Produces KO/EN unavailable copy, suppressed schedule CTA, and immediate-refetch coverage.

- [x] Inspect generated types for the approved new state.
- [x] If absent, make no response-shape change and record a Backend/AI contract blocker.
- [ ] If present, add a failing UI test and implement the new state exhaustively.

### Task 9: Close #13 FCR evidence locally

**Files:** `docs/design/FIGMA_CHANGE_REQUESTS.md`, `docs/design/FIGMA_HANDOFF.md`, frontend plan/test references, existing tests only where a non-vacuous guard is missing.

**Interfaces:** Consumes completed Live and traceability evidence. Produces truthful local FCR/test linkage; does not claim PM or direct-Figma approval that was not observed.

- [ ] Link reusable tests to the completed FCR clauses.
- [ ] Add non-vacuous absence tests only where the documented control must remain absent.
- [ ] Keep PM/direct-Figma approval open if not externally observed.
- [ ] Run docs validation, Markdown lint, affected frontend tests, and final frontend verification.
