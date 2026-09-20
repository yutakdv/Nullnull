---
name: nullnull-frontend-slice
description: Implement, diagnose, review, or verify a Nullnull frontend slice while enforcing the repository's Figma, generated-client, accessibility, test, and strict frontend-only boundaries. Use for work in apps/web and related FE handoff artifacts; never use it to change backend, AI, infrastructure, or server-owned contracts.
---

# Nullnull Frontend Slice

Work only as the Frontend owner. Keep Backend/AI code and infrastructure out of scope even when a UI issue appears to originate there.

## Hard boundaries

- Never inspect, edit, format, generate into, test through, or otherwise operate on `apps/api/**`, `apps/ai/**`, `infra/**`, DB/Flyway files, backend plans, or backend-owned workflows.
- This frontend-only mode intentionally delegates ordinary Backend/AI and infrastructure read-only review duties back to the Backend/AI owner; return a handoff instead of opening those implementation paths.
- Treat `docs/api/**` and `docs/contracts/**` as read-only contract inputs. If the UI needs a missing field, state, operation, or error, stop that part of the implementation and return a contract blocker for Backend/AI. Do not invent a handwritten response type, field, or `any` escape hatch.
- Never hand-edit `packages/api-client/**`. Regenerate it only after an approved server-owned contract has landed on the task's base branch and only when the user explicitly includes regeneration in scope.
- Treat `packages/contracts/**` as read-only Backend/AI-owned fixture input. Return a handoff request when a fixture needs to change.
- Preserve every pre-existing modification and untracked file. Do not clean, reset, move, stage, commit, push, open a PR, or change branches unless the user explicitly asks.
- Do not edit unless the current branch is `frontend`. On another branch, stay read-only and report the branch mismatch.

## Establish the slice

Before editing, record:

1. Current branch, exact base SHA, and `git status --short`.
2. Feature/work ID, Figma node and state, route or overlay, `operationId` and schema, approved example or fixture, expected domain transition or non-transition, and test IDs.
3. Exact editable paths and forbidden paths for this task.
4. Contract, privacy, provenance, capability, or accessibility blockers.

Also snapshot `git status --short -- apps/api apps/ai infra` before the task. Compare the same output after every edit batch and at handoff. Any new backend/AI/infra change is a hard failure: stop and report it without reverting pre-existing user work.

Use the current Figma design for visuals and copy, and the generated client plus approved examples for request and response behavior. Do not infer server truth from UI copy.

## Multi-agent shape

Use the project agents as follows:

- `fe_explorer`: read-only mapping before implementation. It returns entry points, state flow, contract dependencies, candidate tests, and collision files.
- `fe_planner`: read-only implementation planner for one bounded vertical slice and an explicit path allowlist.
- `fe_verifier`: read-only test, browser/accessibility, and Figma-parity auditor.
- The root agent is the only writer, coordinator, and integrator. It owns implementation, shared-file decisions, final diff review, and final verification.

Parallelize only read-heavy exploration, Figma comparison, test-gap analysis, and review. Subagents must not edit tracked or untracked files. The root agent applies changes sequentially after reconciling their evidence.

Reserve these collision-prone files for the coordinator or one explicitly named owner: routes, `AppShell`, i18n messages, shared API session code, MSW handlers, shared UI barrels/tokens, E2E helpers, manifests, workspace package files and lockfiles, and frontend plan/status documents.

Every delegated prompt must include the goal and acceptance criteria, exact base SHA, editable paths, forbidden paths, generated-file rules, whether writes are allowed, required checks, and a return format covering evidence, changed files, checks run, checks not run, and blockers.

## Implementation and verification

- Implement one vertical slice at a time in `apps/web/**`; keep component, CSS Module, focused tests, KO/EN copy, loading/empty/error/offline/stale states, and accessibility behavior together.
- Use semantic HTML and generated types. Server state belongs in TanStack Query, edit buffers stay feature-local, and shareable navigation state belongs in the URL.
- For route, search, sheet/dialog, trip mutation, or optimization changes, include the affected Playwright keyboard/focus flow. Verify focus entry and restoration, Escape, 360 px layout, 200% zoom, KO/EN text, and reduced motion where applicable.
- For Figma implementation, load the applicable Figma skill first, compare actual node values, and verify computed styles in the browser rather than relying on visual memory.
- For a change or build task, the root agent runs the narrowest relevant Vitest or Playwright check first. Before completion, it runs `npm run verify:ci` from `apps/web` and affected E2E separately when not covered. Check `git status --short` before and after because token generation may update tracked output. If `apps/web/src/design/tokens.css` already has a user change, do not run `verify:ci` in that checkout; report the conflict and use an isolated clean worktree only with user authorization. Do not overwrite or restore a pre-existing user change. Read-only diagnosis or review reports the checks that remain to be run instead. Do not claim a check passed unless its fresh output was inspected.
- Never weaken a test merely to make it pass. For a new guard or regression test, confirm it fails under the intended mutation when practical, then restore and verify the real implementation.

## Handoff

Return the user-visible result, feature/Figma/operation/test trace, changed files, exact verification commands and outcomes, checks not run, contract or backend blockers, and confirmation that backend/AI/infra files were untouched.
