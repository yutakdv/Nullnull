---
name: nullnull-slice
description: Implement and validate one Nullnull vertical slice from a feature ID or Figma state through contracts, frontend/backend work, tests, and handoff. Use when the user explicitly invokes /nullnull-slice for a concrete feature.
argument-hint: "[feature ID, issue, Figma node, or feature description]"
disable-model-invocation: true
---

# Nullnull vertical slice

Execute this requested slice: `$ARGUMENTS`.

1. Inspect `git status`; preserve unrelated and untracked user work.
2. Resolve the feature ID in `docs/product/FUNCTIONAL_INVENTORY.md`, then read only its linked Figma, product, OpenAPI, ERD, test, privacy, and operations sections.
3. State the active role (`Frontend`, `Backend/AI`, or both), user outcome, explicit non-scope, Figma node/state, operationId/schema, entity transition, and acceptance tests.
4. Confirm the active branch is `frontend` for Frontend work or `backend` for Backend/AI work, with `main` as the only PR base. If it is not, report the mismatch before editing.
5. If any link is missing or contracts conflict, stop implementation at that boundary and propose the smallest synchronized contract change. Do not invent an API from the screen alone.
6. Update OpenAPI/event examples and ERD/transition before dependent implementation. Keep changes additive unless an approved expand-and-contract plan exists.
7. Build FE from the approved example and generated client; build BE contract tests against that same example. Preserve all invariants in `CLAUDE.md`.
8. For a contest-release slice, connect the applicable compliance requirement, actual KTO call/attribution evidence, anonymous external journey, and location-OFF test. Mock/replay is not actual-use evidence.
9. Test happy path plus relevant empty, duplicate, offline, stale, version conflict, idempotent retry, authorization, provider failure, keyboard/focus, and 360px cases.
10. Run every validation required by the touched paths, including `bash scripts/integration-test.sh`. Never weaken a gate or report an unrun check as passing.
11. Produce a handoff packet: feature ID, Figma node/state, operationId/schema, files changed, user behavior, evidence, compatibility/deploy impact, unresolved decision, and next owner/reviewer.

Do not commit, push, open a PR, change production, or run destructive migration/git operations unless the user explicitly requests that separate action.
